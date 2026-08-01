package `in`.shvms.trackme.analytics

import android.app.Application
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import `in`.shvms.trackme.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.posthog.PostHog

object AnalyticsManager {
    private const val TAG = "AnalyticsManager"
    private const val PREFS_NAME = "trackme_prefs"
    private const val PREF_KEY_LOCAL_CONSENT = "telemetry_enabled"
    private var isInitialized = false
    private var configListener: ListenerRegistration? = null

    // Local consent is read synchronously from the same store Settings writes. The remote flag
    // remains an emergency kill switch, but it can never grant consent that the user did not give.
    private val _localConsent = MutableStateFlow(false)
    private val _remoteAllowed = MutableStateFlow(true)
    private val _isTelemetryEnabled = MutableStateFlow(false)
    val isTelemetryEnabled: StateFlow<Boolean> = _isTelemetryEnabled.asStateFlow()

    fun init(application: Application) {
        if (isInitialized) return

        val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        _localConsent.value = prefs.getBoolean(PREF_KEY_LOCAL_CONSENT, false)
        recomputeEffective()

        // 1. Setup Firestore Config Listener
        val firestore = FirebaseFirestore.getInstance()
        configListener = firestore.collection("config").document("telemetry_settings")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Listen failed for telemetry_settings.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _remoteAllowed.value = snapshot.getBoolean("isTelemetryEnabled") ?: true
                    recomputeEffective()
                    Log.d(TAG, "Remote telemetry allow-state updated: ${_remoteAllowed.value}")
                } else {
                    Log.d(TAG, "telemetry_settings document does not exist, remote allow-state defaults to true")
                    _remoteAllowed.value = true
                    recomputeEffective()
                }
            }

        // 2. Initialize PostHog SDK
        val config = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_API_KEY,
            host = "https://eu.i.posthog.com"
        ).apply {
            captureApplicationLifecycleEvents = true
            captureScreenViews = false // We handle this manually in Compose
        }
        
        PostHogAndroid.setup(application, config)
        isInitialized = true
        applyOptState()
    }

    /** Update local consent immediately after the Settings toggle changes. */
    fun updateLocalConsent(enabled: Boolean) {
        _localConsent.value = enabled
        recomputeEffective()
    }

    private fun recomputeEffective() {
        _isTelemetryEnabled.value = TelemetryConsentState(
            localConsent = _localConsent.value,
            remoteAllowed = _remoteAllowed.value
        ).isEnabled
        applyOptState()
    }

    private fun applyOptState() {
        if (!isInitialized) return
        if (_isTelemetryEnabled.value) PostHog.optIn() else PostHog.optOut()
    }

    // Authentication
    fun identifyUser(userId: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.identify(userId)
    }
    
    fun trackUserLoggedIn() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("user_logged_in")
    }

    fun trackUserSignedUp() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("user_signed_up")
    }

    // Account Management
    fun trackAccountDeletionRequested(reason: String? = null) {
        if (!_isTelemetryEnabled.value) return
        val props = mutableMapOf<String, Any>()
        reason?.let { props["reason"] = it }
        PostHog.capture("account_deletion_requested", properties = props)
    }

    fun trackDataDownloadRequested() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("data_download_requested")
    }

    // App Performance & Errors — taxonomy parity with iOS. These remain unwired until a
    // crash handler/ANR watchdog is deliberately introduced on both platforms.
    fun trackAppCrashDetected(errorMessage: String, errorStack: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "app_crash_detected",
            properties = mapOf(
                "error_message" to errorMessage,
                "error_stack" to errorStack
            )
        )
    }

    fun trackScreenStuckDetected(screenName: String, stuckDurationSeconds: Long) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "screen_stuck_detected",
            properties = mapOf(
                "screen_name" to screenName,
                "stuck_duration_seconds" to stuckDurationSeconds
            )
        )
    }

    // Background Tracking Reliability — Android's GPS-staleness watchdog is the equivalent
    // of iOS's CLLocationManager pause/resume callbacks. These have no event properties.
    fun trackLocationUpdatesPaused() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("location_updates_paused")
    }

    fun trackLocationUpdatesResumed() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("location_updates_resumed")
    }

    // Core Rides
    fun trackRideStarted(rideId: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "ride_started",
            properties = mapOf(
                "ride_id" to rideId
            )
        )
    }

    // distance_km is Double for cross-platform type parity (decision_log 2026-07-20 — no
    // Float/Double fragmentation in PostHog).
    fun trackRideCompleted(rideId: String, durationSeconds: Long, distanceKm: Double) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "ride_completed",
            properties = mapOf(
                "ride_id" to rideId,
                "duration_seconds" to durationSeconds,
                "distance_km" to distanceKm
            )
        )
    }

    // Live Sharing
    fun trackLiveShareStarted(shareId: String, recipientCount: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "live_share_started",
            properties = mapOf(
                "share_id" to shareId,
                "recipient_count" to recipientCount
            )
        )
    }

    fun trackLiveShareEnded(shareId: String, durationSeconds: Long) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "live_share_ended",
            properties = mapOf(
                "share_id" to shareId,
                "duration_seconds" to durationSeconds
            )
        )
    }

    // SOS Usage
    fun trackSosTriggered(triggerMethod: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "sos_triggered",
            properties = mapOf(
                "trigger_method" to triggerMethod
            )
        )
    }

    fun trackSosResolved(resolutionTimeSeconds: Long, falseAlarm: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "sos_resolved",
            properties = mapOf(
                "resolution_time_seconds" to resolutionTimeSeconds,
                "false_alarm" to falseAlarm
            )
        )
    }

    // Screen tracking
    fun trackScreenViewed(screenName: String, durationSeconds: Long) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "screen_viewed",
            properties = mapOf(
                "screen_name" to screenName,
                "duration_seconds" to durationSeconds
            )
        )
    }

    // ------------------------------------------------------------------------------------
    // v1.6.0 retention taxonomy (A1). snake_case names + typed props, NO PII (no lat/lng,
    // no names/emails/titles). iOS MUST emit identical event names + property keys/types.
    // These are emitted by the feature layer (B1–B4), only when the surface is actually
    // shown/acted on — never speculatively.
    // ------------------------------------------------------------------------------------

    /** B1 — reveal_type in {"pr","first_ride","milestone","default"}. */
    fun trackPostRideRevealShown(revealType: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "post_ride_reveal_shown",
            properties = mapOf(
                "reveal_type" to revealType
            )
        )
    }

    /** B2 — weekly gain-framed recap surfaced. distance_km is Double (parity). */
    fun trackWeeklyRecapShown(weekKey: String, rideCount: Int, distanceKm: Double) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "weekly_recap_shown",
            properties = mapOf(
                "week_key" to weekKey,
                "ride_count" to rideCount,
                "distance_km" to distanceKm
            )
        )
    }

    /** B3 — active-week streak advanced. `froze` reserved for the freeze/tolerance path. */
    fun trackWeeklyStreakUpdated(streakWeeks: Int, froze: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "weekly_streak_updated",
            properties = mapOf(
                "streak_weeks" to streakWeeks,
                "froze" to froze
            )
        )
    }

    /** B4 — in-app review prompt requested (system may or may not show it). */
    fun trackReviewPromptRequested(platform: String = "android") {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "review_prompt_requested",
            properties = mapOf(
                "platform" to platform
            )
        )
    }
}

/** Pure consent contract used by [AnalyticsManager] and its JVM tests. */
internal data class TelemetryConsentState(
    val localConsent: Boolean,
    val remoteAllowed: Boolean
) {
    val isEnabled: Boolean get() = localConsent && remoteAllowed
}
