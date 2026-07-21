package `in`.shvms.trackme.domain.stats

/**
 * Pure B4 eligibility for the in-app review prompt. No Android, no Play API — just the decision,
 * so it is unit-testable and identical to iOS.
 *
 * We self-gate hard because the OS also throttles silently (Apple ≈3/yr, Google quota): the one
 * prompt the OS may actually show should land at a genuine peak. Eligibility =
 *   - at least [MIN_GOOD_RIDES] good rides (a happy, invested user), AND
 *   - not already asked on this app version (version-dedupe), AND
 *   - at least [COOLDOWN_DAYS] since the last request (on top of the OS throttle).
 *
 * The caller invokes this ONLY at a positive moment (after a good ride is saved / its reveal is
 * dismissed) and NEVER after an error / SOS / storage-low / discard — those are excluded upstream.
 */
object ReviewPromptPolicy {

    const val MIN_GOOD_RIDES = 3
    const val COOLDOWN_DAYS = 90L
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun isEligible(
        goodRideCount: Int,
        lastPromptedAtMillis: Long,
        lastPromptedVersion: String?,
        currentVersion: String,
        nowMillis: Long
    ): Boolean {
        if (goodRideCount < MIN_GOOD_RIDES) return false
        if (lastPromptedVersion == currentVersion) return false
        if (lastPromptedAtMillis != 0L && nowMillis - lastPromptedAtMillis < COOLDOWN_DAYS * DAY_MILLIS) return false
        return true
    }
}
