package `in`.shvms.trackme.ui.review

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    suspend fun maybeRequest(activity: Activity, goodRideCount: Int) {
        // The Play In-App Review API only works for a build installed BY the Play Store. On a
        // sideloaded/dev-installed build (Android Studio run, adb install, APK share — exactly
        // how this app is tested pre-launch) requestReview()/launchReview() can't succeed, and
        // the Play Store app itself then surfaces its own native "Something went wrong" toast —
        // not something our try/catch below can suppress, since it isn't our exception. Skip the
        // whole flow up front instead. This is an ENVIRONMENT gate, not a policy decision, so it
        // must run before touching prefs/eligibility: a tester repeatedly running a dev build
        // must never burn the one real attempt a Play-installed user gets. Once the app is
        // actually installed via Play (including internal testing), this check passes through
        // and the normal flow below is unaffected.
        if (!isInstalledFromPlayStore(activity)) return

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

    private fun isInstalledFromPlayStore(activity: Activity): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.packageManager.getInstallSourceInfo(activity.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getInstallerPackageName(activity.packageName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return installer == PLAY_STORE_PACKAGE
    }
}
