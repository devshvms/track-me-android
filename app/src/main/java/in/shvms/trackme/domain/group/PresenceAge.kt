package `in`.shvms.trackme.domain.group

/**
 * How old anything in a group is — a position, a status — computed so that **no device's wall
 * clock ever enters the answer** —
 * SCOPE_1.7.2 §4.3, amendments **A26** and **A32**.
 *
 * Three clocks exist and each has exactly one job:
 *
 * | Clock | Job |
 * |---|---|
 * | Relay wall clock | Stamps every slot write, and answers "what time is it" via `serverNowMs` |
 * | Sender monotonic | Measures how long the sender has held a status (`stAge`) |
 * | Receiver monotonic | Advances an age between syncs |
 *
 * **A32 fixes a defect that predates this release.** `MemberMarkerPolicy.freshnessFor` takes a
 * `nowMillis`, and its only caller passes `System.currentTimeMillis()` — so while the *timestamp*
 * was relay-stamped, the *comparison base* was the device clock. A phone five minutes behind showed
 * the whole group as fresher than it was; five minutes ahead greyed everybody out. §8 of 1.7.0
 * claims "correct freshness regardless of device clock"; that was only half true. Anchoring to a
 * relay-supplied `serverNowMs` and advancing on the receiver's monotonic clock closes it.
 *
 * Pure: no Android, no network, no clock reads. Callers pass elapsed values in, which is what makes
 * skew testable at all.
 */
object PresenceAge {

    /**
     * An age frozen at the moment a sync was received, plus the monotonic reading taken alongside
     * it. [currentAgeMillis] advances it from there.
     */
    data class Anchor(
        /** Age at the instant the sync response was parsed. Never negative. */
        val ageAtReceiptMillis: Long,
        /** `SystemClock.elapsedRealtime()` at that same instant. */
        val receivedAtElapsed: Long,
        /**
         * False when the sender could not tell us how long they had held the status — the reboot
         * case (§4.3). The status is still shown; its age is not.
         */
        val isKnown: Boolean = true,
    ) {
        companion object {
            /** The sender rebooted, so the age is unrecoverable. Keep the status, drop the age. */
            fun unknown(receivedAtElapsed: Long) = Anchor(0L, receivedAtElapsed, isKnown = false)
        }
    }

    /**
     * Anchors a **position** age.
     *
     * `serverNowMs` and `serverTsMillis` come from the same sync response and are stamped by the
     * same server clock, so their difference is exact regardless of what either device believes the
     * time is.
     *
     * A negative difference means the relay stamped the position marginally after it read its own
     * clock; that is a fresh fix, not a future one, so it clamps to zero.
     */
    fun anchorPosition(
        serverNowMillis: Long,
        serverTsMillis: Long,
        receivedAtElapsed: Long,
    ): Anchor = Anchor(
        ageAtReceiptMillis = (serverNowMillis - serverTsMillis).coerceAtLeast(0L),
        receivedAtElapsed = receivedAtElapsed,
    )

    /**
     * Anchors a **status** age.
     *
     * `stAgeSeconds` is how long the sender had held the status when they sealed the envelope,
     * measured on *their* monotonic clock. Adding it to the relay-measured transit age gives the
     * total, with sender skew removed by construction — a wall-clock `stAt` would have been poisoned
     * by skew in both directions.
     *
     * A null [stAgeSeconds] is the reboot case: the sender kept the status but lost its age.
     */
    fun anchorStatus(
        serverNowMillis: Long,
        serverTsMillis: Long,
        stAgeSeconds: Long?,
        receivedAtElapsed: Long,
    ): Anchor {
        if (stAgeSeconds == null) return Anchor.unknown(receivedAtElapsed)
        val transit = (serverNowMillis - serverTsMillis).coerceAtLeast(0L)
        return Anchor(
            ageAtReceiptMillis = transit + stAgeSeconds.coerceAtLeast(0L) * 1000L,
            receivedAtElapsed = receivedAtElapsed,
        )
    }

    /**
     * The **rollout fallback**, for a client talking to a relay that does not yet send
     * `serverNowMs`.
     *
     * This reintroduces receiver-side skew and is therefore the old, defective behaviour — it exists
     * only so that a 1.7.2 client against a not-yet-deployed relay degrades to what 1.7.1 already
     * did rather than showing nothing. Delete it once the relay floor is guaranteed.
     */
    fun anchorPositionWithoutServerNow(
        deviceNowMillis: Long,
        serverTsMillis: Long,
        receivedAtElapsed: Long,
    ): Anchor = anchorPosition(deviceNowMillis, serverTsMillis, receivedAtElapsed)

    /**
     * Advances an anchored age to now. Both arguments are monotonic, so a wall-clock change mid-ride
     * cannot move the answer.
     */
    fun currentAgeMillis(anchor: Anchor, nowElapsed: Long): Long =
        anchor.ageAtReceiptMillis + (nowElapsed - anchor.receivedAtElapsed).coerceAtLeast(0L)

    /**
     * How an age should read — §2.2: *"Buckets, not a stopwatch."*
     *
     * A per-second number on eight roster rows is noise that costs recomposition on a screen a rider
     * glances at, so anything younger than one sync interval collapses to [Bucket.Now]. Returning a
     * structure rather than a string keeps the 7-locale formatting in `AppStrings` where it belongs.
     */
    sealed interface Bucket {
        /** Fresher than one sync interval — "Now", not a number. */
        data object Now : Bucket
        data class Seconds(val value: Int) : Bucket
        data class Minutes(val value: Int) : Bucket
        data class Hours(val value: Int) : Bucket

        /** The sender rebooted (§4.3). Render the status with no age chip — never a guessed number. */
        data object Unknown : Bucket
    }

    fun bucket(anchor: Anchor, nowElapsed: Long, syncIntervalSec: Int): Bucket {
        if (!anchor.isKnown) return Bucket.Unknown
        return bucketOf(currentAgeMillis(anchor, nowElapsed), syncIntervalSec)
    }

    fun bucketOf(ageMillis: Long, syncIntervalSec: Int): Bucket {
        val age = ageMillis.coerceAtLeast(0L)
        // "Fresh enough not to think about" tracks the relay's *current* cadence, not a constant:
        // the relay slows everyone down under load (§7.2 of 1.7.0), and a fixed threshold would make
        // the whole fleet start counting seconds during a legitimate slowdown.
        val nowThreshold = syncIntervalSec.coerceAtLeast(1) * 1000L
        return when {
            age < nowThreshold -> Bucket.Now
            age < 60_000L -> Bucket.Seconds((age / 1000L).toInt())
            age < 3_600_000L -> Bucket.Minutes((age / 60_000L).toInt())
            else -> Bucket.Hours((age / 3_600_000L).toInt())
        }
    }
}
