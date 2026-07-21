package `in`.shvms.trackme.ui.review

import android.app.Activity
import android.content.Context
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import `in`.shvms.trackme.BuildConfig
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.domain.stats.ReviewPromptPolicy

/**
 * B4 Android in-app review requester. The Play API needs a foreground [Activity], so this is the
 * UI boundary: it applies the pure [ReviewPromptPolicy], records the attempt, then asks the OS.
 *
 * Must only be called at a positive moment (a good ride's reveal was just dismissed) — never
 * after an error / SOS / storage-low / discard.
 *
 * The OS silently throttles and its completion never means a prompt was shown, so we record an
 * *attempt* and emit telemetry describing a request, not a display or a conversion.
 */
object ReviewPrompter {
    private const val PREFS = "trackme_review"
    private const val KEY_LAST_AT = "last_prompted_at"
    private const val KEY_LAST_VERSION = "last_prompted_version"

    suspend fun maybeRequest(activity: Activity, goodRideCount: Int) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val eligible = ReviewPromptPolicy.isEligible(
            goodRideCount = goodRideCount,
            lastPromptedAtMillis = prefs.getLong(KEY_LAST_AT, 0L),
            lastPromptedVersion = prefs.getString(KEY_LAST_VERSION, null),
            currentVersion = BuildConfig.VERSION_NAME,
            nowMillis = System.currentTimeMillis()
        )
        if (!eligible) return

        // Record the attempt BEFORE launching so a recreation or Play API failure can never
        // re-trigger it — one attempt per (version, 90-day) window regardless of OS behaviour.
        prefs.edit()
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .putString(KEY_LAST_VERSION, BuildConfig.VERSION_NAME)
            .apply()
        AnalyticsManager.trackReviewPromptRequested("android")

        try {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
        } catch (t: Throwable) {
            // OS throttle / no Play Store / transient failure — the attempt is already recorded
            // and telemetry sent; never let a review prompt crash the app.
        }
    }
}
