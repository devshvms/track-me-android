package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.1.1 scenario 1 — deciding whether, and how, to tell someone their ride was saved
 * for them.
 *
 * The PRD lists this as a **currently failing** acceptance criterion: today an interrupted ride is
 * recovered in complete silence. Someone whose phone died mid-ride opens the app expecting to have
 * lost it, and either finds it by accident or never looks. §6.0 ranks it the highest-value item in
 * the release for that reason — it is not engagement, it is the app telling you it did not lose
 * your data.
 *
 * Pure so the decision and the shape of the message can be tested without a device, and so both
 * platforms can hold the same rules.
 */
object RecoveryNotice {

    /**
     * What the notification should say, or null when it should not be posted.
     *
     * @param recoveredCount rides auto-finalized on this launch.
     * @param discardedCount empty rides cleaned up. Deliberately never announced — see below.
     * @param endedAtLabel the recovered ride's end time, already formatted by the caller in the
     *   user's locale and clock preference. The renderer must not format times itself (the same
     *   rule as `ReplayOverlay`, for the same reason).
     * @param distanceLabel the recovered distance, already formatted with the user's units.
     */
    fun decide(
        recoveredCount: Int,
        discardedCount: Int,
        endedAtLabel: String?,
        distanceLabel: String?,
    ): Notice? {
        // A discarded ride had no GPS points at all: nothing was recorded, so nothing was lost and
        // there is nothing to tell anyone. Announcing cleanup would be the app talking about its
        // own housekeeping, which is exactly the noise §4.2 N1 rules out — and worse, it would
        // read as "we deleted something of yours".
        if (recoveredCount <= 0) return null

        if (recoveredCount > 1) {
            // More than one is rare enough that naming a single end time would be misleading, and
            // listing several is a notification nobody reads. The count is the honest fact.
            return Notice.Many(recoveredCount)
        }

        // Both labels or neither. A half-formed sentence — "Your ride was saved. Recording stopped
        // at ." — is worse than the plain version, and the caller can legitimately fail to produce
        // either when a recovered ride has one point and no measurable distance.
        return if (endedAtLabel.isNullOrBlank() || distanceLabel.isNullOrBlank()) {
            Notice.One(endedAtLabel = null, distanceLabel = null)
        } else {
            Notice.One(endedAtLabel = endedAtLabel, distanceLabel = distanceLabel)
        }
    }

    sealed interface Notice {
        /** A single recovered ride, with its facts when they are available. */
        data class One(val endedAtLabel: String?, val distanceLabel: String?) : Notice

        /** Several at once — after a crash loop, or a long spell without opening the app. */
        data class Many(val count: Int) : Notice
    }
}
