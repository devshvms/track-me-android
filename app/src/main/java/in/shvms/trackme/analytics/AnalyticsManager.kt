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
    private var isInitialized = false
    private var configListener: ListenerRegistration? = null
    
    private val _isTelemetryEnabled = MutableStateFlow(true)
    val isTelemetryEnabled: StateFlow<Boolean> = _isTelemetryEnabled.asStateFlow()

    fun init(application: Application) {
        if (isInitialized) return

        // 1. Setup Firestore Config Listener
        val firestore = FirebaseFirestore.getInstance()
        configListener = firestore.collection("config").document("telemetry_settings")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Listen failed for telemetry_settings.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val enabled = snapshot.getBoolean("isTelemetryEnabled") ?: true
                    _isTelemetryEnabled.value = enabled
                    
                    if (!enabled) {
                        PostHog.optOut()
                    } else {
                        PostHog.optIn()
                    }
                    Log.d(TAG, "Telemetry enabled state updated: $enabled")
                } else {
                    Log.d(TAG, "telemetry_settings document does not exist, defaulting to true")
                    _isTelemetryEnabled.value = true
                    PostHog.optIn()
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
        
        if (!_isTelemetryEnabled.value) {
            PostHog.optOut()
        }

        isInitialized = true
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

    fun trackRideCompleted(rideId: String, durationSeconds: Long, distanceKm: Float) {
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
}
