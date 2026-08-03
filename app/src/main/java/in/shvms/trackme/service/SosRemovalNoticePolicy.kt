package `in`.shvms.trackme.service

import android.content.SharedPreferences

/**
 * TG-A06 (1.6.4): decides once whether this install needs the SOS-removal notice, and
 * remembers the verdict.
 *
 * Eligibility is frozen at the first 1.6.4 launch: only users who had completed SOS setup
 * before the upgrade see it. Users who complete contact setup *after* 1.6.4 never had an SOS
 * button, so re-deciding on every launch would show them a notice about a removal they never
 * experienced.
 *
 * Lives beside [SosStateCleanup] rather than inside `TrackMeApp` so the decision — including
 * its failure handling — is reachable from a unit test.
 */
internal object SosRemovalNoticePolicy {
    const val EVALUATED_KEY = "sos_removal_notice_evaluated_v164"
    const val PENDING_KEY = "sos_removal_notice_pending"

    /**
     * @param readSetupComplete reads the stored emergency settings. It may throw; it may also
     *   legitimately report `false` for a user who never configured SOS.
     * @param onReadFailure receives the exception when the read throws.
     * @return whether to show the notice this launch, or `null` when the answer is not yet
     *   known and no verdict was recorded.
     */
    suspend fun evaluateOnce(
        prefs: SharedPreferences,
        onReadFailure: (Exception) -> Unit,
        readSetupComplete: suspend () -> Boolean,
    ): Boolean? {
        if (!prefs.getBoolean(EVALUATED_KEY, false)) {
            val needsNotice = try {
                readSetupComplete()
            } catch (e: Exception) {
                // A read that THREW is unknown, not "no". Recording it as a verdict would set
                // EVALUATED permanently, and a user who had contacts configured in 1.6.3 would
                // never learn why the SOS button vanished — which is the whole point of the
                // notice. So: record nothing, show nothing, and let the next launch retry.
                // A read that SUCCEEDED is a real answer either way, including a null row,
                // which correctly means "never configured, no notice needed".
                onReadFailure(e)
                return null
            }
            prefs.edit()
                .putBoolean(PENDING_KEY, needsNotice)
                .putBoolean(EVALUATED_KEY, true)
                .apply()
        }
        return prefs.getBoolean(PENDING_KEY, false)
    }

    /** The notice is must-acknowledge; only an explicit tap clears it, permanently. */
    fun acknowledge(prefs: SharedPreferences) {
        prefs.edit().putBoolean(PENDING_KEY, false).apply()
    }
}
