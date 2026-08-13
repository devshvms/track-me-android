package `in`.shvms.trackme.domain.group

/*
 * A rider's self-declared status — SCOPE_1.7.2 §4.2, amendment **A25**.
 *
 * A status is a compact structured code, not an enum name and not a sentence:
 *
 * ```
 *   1  M  E  H  :  T15
 *   │  │  └──┘     └─┘
 *   │  │    │       └── optional extension, 1–8 chars [A-Za-z0-9]
 *   │  │    └────────── message, exactly 2 chars [A-Z]
 *   │  └─────────────── persona, 1 char [A-Z]
 *   └────────────────── severity, 1 char [0-9]
 * ```
 *
 * **Why this shape earns its complexity.** Severity is readable from character 1 *without knowing
 * the message*, so a client that has never heard of a code still renders it at the correct colour
 * and priority instead of dropping it. Forward compatibility falls out of the encoding rather than
 * being bolted on — compare an opaque enum name, where the only safe fallback is to render nothing
 * and lose the information entirely. Same instinct as §8 of 1.7.0's decrypt-failure rule: degrade
 * the one member, never the whole picture.
 *
 * Pure by construction. The rules that decide whether a rider's "Need help" is understood must be
 * testable without a device, a relay, or a group.
 */

/**
 * How urgent a status is. The digit is the wire representation and it **sorts ascending in severity
 * order**, so roster pinning is a plain string comparison.
 */
enum class StatusSeverity(val digit: Char) {
    /** Stopped and needs the group. The only tier that interrupts anyone (§5.2). */
    ALERT('1'),

    /** Something is wrong but handled. */
    CAUTION('2'),

    /** Normal riding life. */
    INFO('3');

    companion object {
        fun fromDigit(digit: Char): StatusSeverity? = entries.firstOrNull { it.digit == digit }
    }
}

/**
 * Which vocabulary a message belongs to.
 *
 * [GENERIC] is the shared core: "tired" is one code and one label for every rider rather than six.
 * The other letters carry vocabulary that only makes sense for that activity — `2MEH` engine heat,
 * `2BPU` puncture.
 *
 * A19 note: the letters are a **fixed wire alphabet** (O11). A future persona means a new letter,
 * and older clients fall back to generic-with-correct-severity. That is the designed behaviour, not
 * a gap — do not "fix" it later by broadening the parser.
 */
enum class StatusPersona(val letter: Char) {
    /** No ride started, or a message meaningful to everyone. */
    GENERIC('G'),
    BIKE_DRIVE('M'),
    CAR_DRIVE('C'),
    CYCLING('B'),
    WALK('W'),
    RUN('R'),
    AUTO('A');

    companion object {
        fun fromLetter(letter: Char): StatusPersona? = entries.firstOrNull { it.letter == letter }

        /**
         * Maps a `RidePersona` name onto the wire alphabet.
         *
         * Takes the name rather than the enum so the domain layer does not depend on the ride model
         * — and returns null for "no ride started", which the catalogue reads as the generic set
         * (§3.3), never as an empty picker.
         */
        fun forRideName(name: String?): StatusPersona? = when (name) {
            "BIKE_DRIVE" -> BIKE_DRIVE
            "CAR_DRIVE" -> CAR_DRIVE
            "CYCLING" -> CYCLING
            "WALK" -> WALK
            "RUN" -> RUN
            "AUTO" -> AUTO
            else -> null
        }
    }
}

/**
 * A parsed status.
 *
 * [severity] is the **effective** severity used for rendering, which is not always the raw digit —
 * see [RiderStatusCodec.parse] for why an unrecognised tier is deliberately demoted rather than
 * promoted.
 */
data class RiderStatus(
    /** The canonical 4-character code, without the extension. */
    val code: String,
    val severity: StatusSeverity,
    /** Null when the persona letter is one this client does not know. */
    val persona: StatusPersona?,
    /** The two-character message id. Meaningful only together with [severity] and [persona]. */
    val message: String,
    /**
     * Parsed and preserved, with **no consumers in 1.7.2**.
     *
     * D7's "structure now, display later", for the same reason: proving the grammar costs nothing
     * now, and a breaking wire change later costs a release.
     */
    val extension: String?,
    /** Exactly what arrived, including any extension. This is what gets re-sent on a retry. */
    val raw: String,
) {
    val isAlert: Boolean get() = severity == StatusSeverity.ALERT

    /**
     * The `AppStrings` key for this exact code, e.g. `groupStatus2MEH`.
     *
     * The wire carries the **code**, never the label — otherwise a Hindi rider's status would render
     * as Hindi text on a German rider's phone. The failure only shows up in a mixed-locale group,
     * which is exactly why it is enforced here rather than left to call sites.
     */
    val labelKey: String get() = "groupStatus$code"

    /**
     * True when this client recognises the code well enough to have a specific label for it.
     * When false, the UI renders the severity-and-persona fallback (§4.2) rather than nothing.
     */
    val isKnown: Boolean get() = code in RiderStatusCatalog.KNOWN_CODES
}

object RiderStatusCodec {

    private val GRAMMAR = Regex("^[0-9][A-Z][A-Z]{2}(:[A-Za-z0-9]{1,8})?$")

    /**
     * Parses a status code, or returns null when it should be **ignored entirely**.
     *
     * §6.2 H9 is the reason for strictness: this runs on a path that executes every ~10s for hours,
     * and a guessed status is worse than no status. A malformed code renders no chip at all rather
     * than a wrong one.
     *
     * The fallbacks are deliberately asymmetric:
     *
     * - **Unknown message**, known severity and persona → parses fine. The UI shows a generic label
     *   at the correct severity. This is the whole point of the encoding.
     * - **Unknown persona** → parses fine with [RiderStatus.persona] null. A future persona must
     *   never blank out a valid alert.
     * - **Unknown severity** (`0`, `4`–`9`) → parses as [StatusSeverity.INFO], never ALERT. An
     *   unrecognised tier must not be able to make an old client scream, so this **fails quiet,
     *   never loud** — including for digit `0`, which is reserved for a tier *above* ALERT.
     */
    fun parse(raw: String?): RiderStatus? {
        val value = raw.orEmpty()
        // Deliberately NOT trimmed. We produce this field ourselves at the other end of an envelope
        // we sealed, so whitespace has no legitimate source — accepting it would only hide the bug
        // that produced it, and leniency is how a format we own starts drifting.
        if (!GRAMMAR.matches(value)) return null

        val code = value.substringBefore(':')
        val extension = value.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

        return RiderStatus(
            code = code,
            severity = StatusSeverity.fromDigit(code[0]) ?: StatusSeverity.INFO,
            persona = StatusPersona.fromLetter(code[1]),
            message = code.substring(2, 4),
            extension = extension,
            raw = value,
        )
    }

    /** Builds a wire value. Only used when producing a genuinely new status (§4.3). */
    fun encode(code: String, extension: String? = null): String =
        if (extension.isNullOrBlank()) code else "$code:$extension"
}

/**
 * The status vocabulary — which codes exist, and which are offered to whom.
 *
 * §3.3: **4–6 options for the current persona, always visible without scrolling**, at most two at
 * severity 1, and the alert rows **last**. That last part is a deliberate asymmetry with everywhere
 * else: severity 1 sorts *first* when displayed (the attention section) because it is the most
 * urgent thing to read, and *last* when offered because it is the most expensive thing to mis-tap.
 * Urgency orders reading; mis-tap cost orders tapping.
 *
 * The final wording of each label is a content pass (§3.10); what is fixed here is the code set and
 * the per-persona offer, because those are on the wire and in the tests.
 */
object RiderStatusCatalog {

    // Shared core, offered to every persona. `G` keeps "tired" as one code and one label for
    // everyone rather than six near-duplicates.
    const val SHORT_BREAK = "3GBR"
    const val TIRED = "3GTI"
    const val VEHICLE_ISSUE = "2GVI"
    const val NEED_HELP = "1GNH"

    /**
     * **O16: reserved and decodable, but never offered.**
     *
     * `Need help` carries the actionable fact without implying crash detection, medical certainty,
     * or emergency-service delivery. A self-reported `Crashed` creates the strongest expectation in
     * the catalogue while resting on polling, local notifications, and no acknowledgement channel
     * from another human — a mismatch a second Alert label does not justify.
     *
     * The code stays parsed, localized and rendered as Alert, because removing a value from the
     * picker must never make an older or newer peer's valid status disappear or fail quiet to the
     * wrong tier. Both clients test **decode yes, offer no**.
     */
    const val CRASHED = "1GCR"

    // Persona-specific vocabulary.
    const val FUEL_STOP_BIKE = "3MFS"
    const val ENGINE_HEAT = "2MEH"
    const val FUEL_STOP_CAR = "3CFS"
    const val ON_A_CALL = "3CIC"
    const val WATER_BREAK_CYCLE = "3BWA"
    const val PUNCTURE = "2BPU"
    const val WATER_BREAK_WALK = "3WWA"
    const val WATER_BREAK_RUN = "3RWA"

    /**
     * What the picker offers, in display order: info, then caution, then alert last.
     *
     * [StatusPersona.GENERIC] is what a member who has not started a ride sees — the core set only,
     * with no empty persona section.
     */
    fun optionsFor(persona: StatusPersona?): List<String> = when (persona) {
        // CRASHED is deliberately absent from every list (O16) — reserved on the wire, never offered.
        StatusPersona.BIKE_DRIVE ->
            listOf(FUEL_STOP_BIKE, SHORT_BREAK, ENGINE_HEAT, VEHICLE_ISSUE, NEED_HELP)
        StatusPersona.CAR_DRIVE ->
            listOf(FUEL_STOP_CAR, SHORT_BREAK, ON_A_CALL, VEHICLE_ISSUE, NEED_HELP)
        StatusPersona.CYCLING ->
            listOf(WATER_BREAK_CYCLE, SHORT_BREAK, PUNCTURE, VEHICLE_ISSUE, NEED_HELP)
        StatusPersona.WALK ->
            listOf(WATER_BREAK_WALK, SHORT_BREAK, TIRED, NEED_HELP)
        StatusPersona.RUN ->
            listOf(WATER_BREAK_RUN, SHORT_BREAK, TIRED, NEED_HELP)
        StatusPersona.AUTO, StatusPersona.GENERIC, null ->
            listOf(SHORT_BREAK, TIRED, VEHICLE_ISSUE, NEED_HELP)
    }

    /** Every code this client has a specific label for. Anything else renders the §4.2 fallback. */
    val KNOWN_CODES: Set<String> = setOf(
        SHORT_BREAK, TIRED, VEHICLE_ISSUE, NEED_HELP, CRASHED,
        FUEL_STOP_BIKE, ENGINE_HEAT, FUEL_STOP_CAR, ON_A_CALL,
        WATER_BREAK_CYCLE, PUNCTURE, WATER_BREAK_WALK, WATER_BREAK_RUN,
    )

    /**
     * Roster ordering for the attention section (**A31**): severity ascending, so ALERT pins first.
     *
     * A18's sort is deliberately stable *"so a roster that updates every few seconds does not
     * reshuffle under the reader's finger"* — this comparator is applied only to the pinned section,
     * never to the main roster, so that promise holds.
     */
    val BY_SEVERITY: Comparator<RiderStatus> = compareBy { it.severity.digit }
}
