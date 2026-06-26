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
import com.google.android.gms.maps.model.LatLng
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
    val timeSinceLastGps: Long = 0L
)

class HomeViewModel(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val emergencyDao: EmergencyDao
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

    private val trackingStats = combine(trackingStatsGroup1, trackingStatsGroup2) { g1, g2 ->
        HomeUiState(
            trackingState = g1.first,
            pathPoints = g1.second,
            distanceText = formatDistance(g1.third),
            durationText = formatDuration(g2.first),
            speedText = formatSpeed(g2.second),
            timeSinceLastGps = g2.third
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
        isEmergencyReadyFlow
    ) { stats, isEmergency, isReady ->
        stats.copy(isEmergencyActive = isEmergency, isEmergencyReady = isReady)
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

    fun startTracking(context: Context) {
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

    fun triggerEmergency() {
        emergencyManager.triggerEmergency()
    }

    fun stopEmergency() {
        emergencyManager.stopEmergency()
    }
}

class HomeViewModelFactory(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val emergencyDao: EmergencyDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(trackingManager, emergencyManager, authManager, emergencyDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
