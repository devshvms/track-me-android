package `in`.shvms.trackme.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.service.TrackingManager
import `in`.shvms.trackme.service.TrackingService
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.service.EmergencyManager
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.dao.EmergencyDao
import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /** Total wall-clock time since start, including paused segments — see [formatElapsedDuration]. */
    val elapsedDurationText: String = "Total 00:00:00",
    val distanceMeters: Float = 0f,
    val durationMillis: Long = 0L,
    val speedText: String = "0.0 km/h",
    /** Only meaningful/shown for [in.shvms.trackme.domain.model.RidePersona.WALK] — see [formatPace]. */
    val paceText: String = "--:-- /km",
    val isEmergencyActive: Boolean = false,
    val isEmergencyReady: Boolean = false,
    val timeSinceLastGps: Long = 0L,
    val liveShareState: LiveShareState = LiveShareState(),
    val isAutoPaused: Boolean = false,
    val inferredActivityType: `in`.shvms.trackme.domain.processor.InferredActivityType = `in`.shvms.trackme.domain.processor.InferredActivityType.RUN_OR_TREK,
    val selectedPersona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO,
    val isAuthenticated: Boolean = false,
    val userName: String? = null
)

class HomeViewModel(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val emergencyDao: EmergencyDao,
    private val liveShareManager: LiveShareManager,
    private val preferencesManager: AppPreferencesManager
) : ViewModel() {

    // One-shot, Context-free UI events (service commands + toast-worthy outcomes). The
    // ViewModel must not hold a Context, so it emits data describing WHAT happened; HomeScreen
    // (which has a real Context/LocalAppStrings) decides how to present it.
    sealed class UiEvent {
        data class SendServiceCommand(val action: String) : UiEvent()
        object LiveShareAuthRequired : UiEvent()
        data class LiveShareStarted(val isTrackingActive: Boolean) : UiEvent()
        object LiveShareAuthExpired : UiEvent()
        data class LiveShareGracefulError(val message: String) : UiEvent()
        object LiveShareStopped : UiEvent()
    }

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    private val trackingStatsGroup1 = combine(
        trackingManager.trackingState,
        trackingManager.pathPoints,
        trackingManager.totalDistance
    ) { state, points, distance ->
        Triple(state, points, distance)
    }

    /** [Triple] can't hold 4 values, so duration/speed/GPS-age/elapsed share one small tuple. */
    private data class DurationSpeedTuple(
        val durationMillis: Long,
        val speedMps: Float,
        val timeSinceLastGps: Long,
        val elapsedMillis: Long
    )

    private val trackingStatsGroup2 = combine(
        trackingManager.rideDurationInMillis,
        trackingManager.currentSpeed,
        trackingManager.timeSinceLastGps,
        trackingManager.elapsedDurationInMillis
    ) { duration, speed, timeSinceLastGps, elapsed ->
        DurationSpeedTuple(duration, speed, timeSinceLastGps, elapsed)
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
        trackingStatsGroup3,
        preferencesManager.unitSystem
    ) { g1, g2, g3, unitSystem ->
        val imperial = unitSystem == "imperial"
        HomeUiState(
            trackingState = g1.first,
            pathPoints = g1.second,
            distanceText = formatDistance(g1.third, imperial),
            durationText = formatDuration(g2.durationMillis),
            elapsedDurationText = formatElapsedDuration(g2.elapsedMillis),
            distanceMeters = g1.third,
            durationMillis = g2.durationMillis,
            speedText = formatSpeed(g2.speedMps, imperial),
            paceText = formatPace(g2.speedMps),
            timeSinceLastGps = g2.timeSinceLastGps,
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
            isAuthenticated = user != null,
            userName = user?.displayName
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private fun formatDistance(distanceMeters: Float, imperial: Boolean): String {
        return `in`.shvms.trackme.domain.UnitFormatter.distance(distanceMeters.toDouble(), imperial)
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

    private fun formatSpeed(speedMps: Float, imperial: Boolean): String {
        return `in`.shvms.trackme.domain.UnitFormatter.speed(speedMps.toDouble(), imperial)
    }

    /**
     * Total wall-clock time since the ride started, including paused segments — shown as a
     * smaller caption under the (active, paused-excluded) headline DURATION stat, for every
     * persona. Reuses [formatDuration]'s HH:MM:SS formatting so the two stay visually aligned.
     */
    private fun formatElapsedDuration(millis: Long): String {
        return "Total ${formatDuration(millis)}"
    }

    /**
     * Walking pace (minutes:seconds per km) computed from the same live GPS speed that feeds
     * [formatSpeed] — a real-time pace, matching how fitness apps show "current pace" (not an
     * average over the whole ride). Shown instead of [formatSpeed] only for
     * [in.shvms.trackme.domain.model.RidePersona.WALK]; cycling/motorbike/car keep live speed,
     * where km/h is the natural unit.
     */
    private fun formatPace(speedMps: Float): String {
        // Below ~0.1 m/s (stopped/near-stopped) pace would blow up toward infinity — show a
        // placeholder instead of a meaningless huge number.
        if (speedMps < 0.1f) return "--:-- /km"
        val paceSecondsPerKm = 1000f / speedMps
        val minutes = (paceSecondsPerKm / 60).toInt()
        val seconds = (paceSecondsPerKm % 60).toInt()
        // Guard the same way at the top end (very slow shuffling) so the stat never shows an
        // absurd triple-digit minute value.
        if (minutes >= 60) return "--:-- /km"
        return String.format(Locale.getDefault(), "%d:%02d /km", minutes, seconds)
    }

    fun startTracking(persona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO) {
        trackingManager.setSelectedPersona(persona)
        sendCommandToService(TrackingService.ACTION_START_OR_RESUME_SERVICE)
    }

    fun pauseTracking() {
        sendCommandToService(TrackingService.ACTION_PAUSE_SERVICE)
    }

    fun stopTracking(discardNearEmptyRide: Boolean = false) {
        sendCommandToService(
            if (discardNearEmptyRide) TrackingService.ACTION_DISCARD_NEAR_EMPTY_RIDE
            else TrackingService.ACTION_STOP_SERVICE
        )
    }

    private fun sendCommandToService(action: String) {
        viewModelScope.launch { _uiEvent.emit(UiEvent.SendServiceCommand(action)) }
    }

    fun triggerEmergency() {
        `in`.shvms.trackme.analytics.AnalyticsManager.trackSosTriggered(
            triggerMethod = "in_app_button"
        )
        emergencyManager.triggerEmergency()
    }

    fun stopEmergency(falseAlarm: Boolean = false) {
        val startedAt = emergencyManager.emergencyStartedAtMillis.value
        val duration = if (startedAt != null) {
            ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        `in`.shvms.trackme.analytics.AnalyticsManager.trackSosResolved(
            resolutionTimeSeconds = duration,
            falseAlarm = falseAlarm
        )
        emergencyManager.stopEmergency()
    }

    fun startLiveShare(durationMinutes: Int, stopOnRideEnd: Boolean) {
        viewModelScope.launch {
            if (authManager.currentUser.value == null) {
                _uiEvent.emit(UiEvent.LiveShareAuthRequired)
                return@launch
            }
            val username = authManager.currentUser.value?.displayName
            val result = liveShareManager.startSession(durationMinutes, username, stopOnRideEnd)
            if (result.isSuccess) {
                val isTracking = uiState.value.trackingState == TrackingState.TRACKING || uiState.value.trackingState == TrackingState.PAUSED
                _uiEvent.emit(UiEvent.LiveShareStarted(isTracking))
            } else {
                emitLiveShareError(result.exceptionOrNull())
            }
        }
    }

    fun stopLiveShare(reason: String = "Live sharing stopped manually by user.") {
        viewModelScope.launch {
            val result = liveShareManager.stopSession(reason = reason)
            if (result.isSuccess) {
                _uiEvent.emit(UiEvent.LiveShareStopped)
            } else {
                emitLiveShareError(result.exceptionOrNull())
            }
        }
    }

    private suspend fun emitLiveShareError(error: Throwable?) {
        if (LiveShareManager.isAuthenticationError(error)) {
            _uiEvent.emit(UiEvent.LiveShareAuthExpired)
        } else {
            _uiEvent.emit(UiEvent.LiveShareGracefulError(LiveShareManager.formatGracefulError(error)))
        }
    }
}

class HomeViewModelFactory(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val emergencyDao: EmergencyDao,
    private val liveShareManager: LiveShareManager,
    private val preferencesManager: AppPreferencesManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(trackingManager, emergencyManager, authManager, emergencyDao, liveShareManager, preferencesManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
