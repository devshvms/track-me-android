package `in`.shvms.trackme.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.service.TrackingManager
import `in`.shvms.trackme.service.TrackingService
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.service.EmergencyManager
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.dao.EmergencyDao
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import java.util.Locale

data class HomeUiState(
    val trackingState: TrackingState = TrackingState.IDLE,
    val pathPoints: List<LatLng> = emptyList(),
    val distanceText: String = "0.00 km",
    val durationText: String = "00:00:00",
    val speedText: String = "0.0 km/h",
    val isEmergencyActive: Boolean = false,
    val isEmergencyReady: Boolean = false,
    val timeSinceLastGps: Long = 0L,
    val liveShareState: LiveShareState = LiveShareState(),
    val isAutoPaused: Boolean = false,
    val inferredActivityType: `in`.shvms.trackme.domain.processor.InferredActivityType = `in`.shvms.trackme.domain.processor.InferredActivityType.RUN_OR_TREK,
    val selectedPersona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO,
    val userName: String? = null
)

class HomeViewModel(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val emergencyDao: EmergencyDao,
    private val liveShareManager: LiveShareManager
) : ViewModel() {

    private val trackingStatsGroup1 = combine(
        trackingManager.trackingState,
        trackingManager.pathPoints,
        trackingManager.totalDistance
    ) { state, points, distance ->
        Triple(state, points, distance)
    }

    private val trackingStatsGroup2 = combine(
        trackingManager.rideDurationInMillis,
        trackingManager.currentSpeed,
        trackingManager.timeSinceLastGps
    ) { duration, speed, timeSinceLastGps ->
        Triple(duration, speed, timeSinceLastGps)
    }

    private val trackingStatsGroup3 = combine(
        trackingManager.isAutoPaused,
        trackingManager.inferredActivityType,
        trackingManager.selectedPersona
    ) { isAutoPaused, inferredActivityType, selectedPersona ->
        Triple(isAutoPaused, inferredActivityType, selectedPersona)
    }

    private val trackingStats = combine(
        trackingStatsGroup1,
        trackingStatsGroup2,
        trackingStatsGroup3
    ) { g1, g2, g3 ->
        HomeUiState(
            trackingState = g1.first,
            pathPoints = g1.second,
            distanceText = formatDistance(g1.third),
            durationText = formatDuration(g2.first),
            speedText = formatSpeed(g2.second),
            timeSinceLastGps = g2.third,
            isAutoPaused = g3.first,
            inferredActivityType = g3.second,
            selectedPersona = g3.third
        )
    }

    private val isEmergencyReadyFlow = combine(
        authManager.currentUser,
        emergencyDao.getSettingsFlow()
    ) { user, settings ->
        user != null && settings?.isSetupComplete == true
    }

    val uiState = combine(
        trackingStats,
        emergencyManager.isEmergencyActive,
        isEmergencyReadyFlow,
        liveShareManager.state,
        authManager.currentUser
    ) { stats, isEmergency, isReady, liveShare, user ->
        stats.copy(
            isEmergencyActive = isEmergency,
            isEmergencyReady = isReady,
            liveShareState = liveShare,
            userName = user?.displayName
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private fun formatDistance(distanceMeters: Float): String {
        return String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000f)
    }

    private fun formatDuration(millis: Long): String {
        var milliseconds = millis
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
        milliseconds -= TimeUnit.HOURS.toMillis(hours)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
        milliseconds -= TimeUnit.MINUTES.toMillis(minutes)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds)
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSpeed(speedMps: Float): String {
        return String.format(Locale.getDefault(), "%.1f km/h", speedMps * 3.6f)
    }

    fun startTracking(context: Context, persona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO) {
        trackingManager.setSelectedPersona(persona)
        sendCommandToService(context, TrackingService.ACTION_START_OR_RESUME_SERVICE)
    }

    fun pauseTracking(context: Context) {
        sendCommandToService(context, TrackingService.ACTION_PAUSE_SERVICE)
    }

    fun stopTracking(context: Context) {
        sendCommandToService(context, TrackingService.ACTION_STOP_SERVICE)
    }

    private fun sendCommandToService(context: Context, action: String) {
        val intent = Intent(context, TrackingService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    private var sosStartTimeMs: Long = 0L

    fun triggerEmergency() {
        sosStartTimeMs = System.currentTimeMillis()
        val loc = trackingManager.pathPoints.value.lastOrNull()
        `in`.shvms.trackme.analytics.AnalyticsManager.trackSosTriggered(
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            triggerMethod = "in_app_button"
        )
        emergencyManager.triggerEmergency()
    }

    fun stopEmergency(falseAlarm: Boolean = false) {
        val duration = if (sosStartTimeMs > 0) (System.currentTimeMillis() - sosStartTimeMs) / 1000L else 0L
        `in`.shvms.trackme.analytics.AnalyticsManager.trackSosResolved(
            resolutionTimeSeconds = duration,
            falseAlarm = falseAlarm
        )
        sosStartTimeMs = 0L
        emergencyManager.stopEmergency()
    }

    private fun getStrings(context: Context): `in`.shvms.trackme.ui.localization.AppStrings {
        val prefs = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "en") ?: "en"
        return `in`.shvms.trackme.ui.localization.getAppStrings(lang)
    }

    fun startLiveShare(context: Context, durationMinutes: Int, stopOnRideEnd: Boolean) {
        viewModelScope.launch {
            val username = authManager.currentUser.value?.displayName
            val result = liveShareManager.startSession(durationMinutes, username, stopOnRideEnd)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val strings = getStrings(context)
                if (result.isSuccess) {
                    val isTracking = uiState.value.trackingState == TrackingState.TRACKING || uiState.value.trackingState == TrackingState.PAUSED
                    val msg = if (isTracking) strings.liveShareReadyActive else strings.liveShareReadyIdle
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                } else {
                    val msg = LiveShareManager.formatGracefulError(result.exceptionOrNull())
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun stopLiveShare(context: Context, reason: String = "Live sharing stopped manually by user.") {
        viewModelScope.launch {
            val result = liveShareManager.stopSession(reason = reason)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val strings = getStrings(context)
                if (result.isSuccess) {
                    android.widget.Toast.makeText(context, strings.liveShareStoppedToast, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val msg = LiveShareManager.formatGracefulError(result.exceptionOrNull())
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class HomeViewModelFactory(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val emergencyDao: EmergencyDao,
    private val liveShareManager: LiveShareManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(trackingManager, emergencyManager, authManager, emergencyDao, liveShareManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
