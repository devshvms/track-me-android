package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a and §6.1.2 scenario 10b — what the app is allowed to infer from
 * someone's own history, and when it must admit it does not know.
 *
 * ### This is the file where #12 became #12a
 *
 * Scenario 12 — inferring a routine from movement history and *acting* on it unasked — was cut.
 * §4.2 N3 is the reason: it is the surveillance read, and it is the one thing a privacy-first
 * tracker cannot be caught doing. 12a keeps the same inference and changes who decides. The app
 * says "Saturday, 8:00 — is that right?" and the user answers. Nothing is scheduled until they do.
 *
 * That difference only survives if the suggestion is *visibly* a guess and is withheld when it
 * would be a bad one. A confidently wrong "you usually ride Tuesday 7am" is worse than no
 * suggestion at all: it is the app demonstrating both that it watches and that it misreads. So
 * every derivation here fails closed — no pattern, no suggestion, and the settings screen simply
 * shows empty fields the user fills in themselves.
 *
 * ### Why the caller supplies day and hour
 *
 * [Sample] carries the weekday and hour rather than a timestamp. Deriving those needs a calendar
 * and a timezone, and a policy that reaches for the device clock cannot be tested at the boundaries
 * that matter — a rider who moved timezones, or whose rides straddle midnight. The platform layer
 * owns the conversion; this file owns the judgement.
 */
object RideHistoryProfile {

    /**
     * Below this there is no history to speak of, only a coincidence.
     *
     * Five rides is roughly a month of a once-a-week rider. Two rides on the same weekday is a
     * pattern to a computer and an accident to a person, and this file exists to keep the app on
     * the person's side of that distinction.
     */
    const val MIN_RIDES_FOR_SUGGESTION = 5

    /**
     * The modal weekday must carry at least this share of the rides.
     *
     * Someone who rides seven days a week has no "usual day", and picking their most frequent one
     * is picking noise. At 30% a suggestion needs roughly double the share a uniform week would
     * give any single day, which is enough to mean something without demanding a metronome.
     */
    const val MIN_DOMINANT_DAY_SHARE = 0.30

    /** A weekday needs at least two rides before an hour drawn from it means anything. */
    const val MIN_RIDES_ON_DOMINANT_DAY = 2

    /**
     * One recorded ride.
     *
     * @param dayOfWeek ISO-8601: 1 = Monday … 7 = Sunday. Chosen over the platform constants
     *   because `java.util.Calendar` makes Sunday 1 and `Foundation` agrees with it, so any shared
     *   vector written against either would be a trap for the other platform.
     * @param hour local hour of the ride's start, 0–23.
     * @param activeMinutes moving time, not elapsed. A ride that spent an hour at a café is not an
     *   hour-long ride, and using elapsed time here would tell scenario 10b that every ride crosses
     *   every threshold.
     */
    data class Sample(
        val dayOfWeek: Int,
        val hour: Int,
        val activeMinutes: Long,
        val persona: String,
    )

    /**
     * A slot worth offering, or null when the history does not support one.
     *
     * @param rideCount how many rides the suggestion rests on. Surfaced so the UI can say what it
     *   is based on: "suggested from your last 12 rides" is a courtesy, "suggested for you" is a
     *   claim the app has not earned.
     */
    data class SuggestedSlot(
        val dayOfWeek: Int,
        val hour: Int,
        val persona: String,
        val rideCount: Int,
    )

    /**
     * Scenario 12a's suggested slot, derived from the rider's own history.
     *
     * Returns null — meaning "offer an empty form" — whenever the evidence is thin, the week is
     * flat, or the chosen day is carried by a single ride.
     */
    fun suggestSlot(samples: List<Sample>): SuggestedSlot? {
        if (samples.size < MIN_RIDES_FOR_SUGGESTION) return null

        val byDay = samples.groupBy { it.dayOfWeek }
        // Ties break toward the earlier weekday rather than by iteration order: a map's ordering is
        // not a fact about the rider, and a suggestion that changes between two runs over identical
        // history is a suggestion nobody can trust.
        val dominantDay = byDay.entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<Sample>>> { it.value.size }
                .thenBy { it.key })
            .first()

        val onDay = dominantDay.value
        if (onDay.size < MIN_RIDES_ON_DOMINANT_DAY) return null
        if (onDay.size.toDouble() / samples.size < MIN_DOMINANT_DAY_SHARE) return null

        // The hour comes from that day's rides only. A rider whose Saturday ride is at 08:00 and
        // whose midweek rides are at 19:00 gets "Saturday, 8:00" — averaging across the week would
        // produce an hour they have never once ridden at.
        val hour = onDay.groupBy { it.hour }.entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<Sample>>> { it.value.size }
                .thenBy { it.key })
            .first().key

        val persona = dominantPersona(onDay) ?: return null

        return SuggestedSlot(
            dayOfWeek = dominantDay.key,
            hour = hour,
            persona = persona,
            rideCount = samples.size,
        )
    }

    /** The most-ridden persona, ties broken alphabetically so the answer is stable. */
    fun dominantPersona(samples: List<Sample>): String? {
        if (samples.isEmpty()) return null
        return samples.groupBy { it.persona }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Sample>>> { it.value.size }
                .thenBy { it.key })
            .first().key
    }

    /**
     * Median active minutes for [persona], or across all rides when that persona has too little
     * history of its own.
     *
     * Median rather than mean: one forgotten ride that recorded for six hours would drag a mean
     * far enough to make scenario 10b promise a level the rider will not reach today. Scenario 4
     * exists precisely because such rides happen, so the statistic that feeds another feature has
     * to be the one that shrugs them off.
     *
     * Returns null when there is nothing to go on.
     */
    fun typicalActiveMinutes(samples: List<Sample>, persona: String?): Long? {
        if (samples.isEmpty()) return null
        val forPersona = if (persona == null) emptyList() else samples.filter { it.persona == persona }
        // Falling back to all personas is deliberate. A rider with forty runs and one first-ever
        // cycle should still get a sane estimate on that first cycle; refusing to answer would
        // silently disable 10b for exactly the ride where a new level is most likely.
        val pool = if (forPersona.size >= MIN_RIDES_ON_DOMINANT_DAY) forPersona else samples
        val sorted = pool.map { it.activeMinutes }.sorted()
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            // Rounds down on the even case. Under-promising is the correct direction of error for
            // every consumer of this number.
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
