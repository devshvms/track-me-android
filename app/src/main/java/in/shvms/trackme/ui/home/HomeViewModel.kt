package `in`.shvms.trackme.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.service.TrackingManager
import `in`.shvms.trackme.service.TrackingService
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.service.EmergencyManager
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.data.local.HomeDashboardRepository
import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.data.remote.FirestoreSyncManager
import `in`.shvms.trackme.data.remote.SyncResult
import `in`.shvms.trackme.domain.home.HomeDashboardSummary
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Shared live-HUD formatting so the foreground HUD and PiP can never drift. */
internal object LiveRideMetricFormatter {
    fun distance(distanceMeters: Float, imperial: Boolean): String =
        `in`.shvms.trackme.domain.UnitFormatter.distance(distanceMeters.toDouble(), imperial)

    fun speed(speedMps: Float, imperial: Boolean): String =
        `in`.shvms.trackme.domain.UnitFormatter.speed(speedMps.toDouble(), imperial)

    /** The shipped active HUD is kilometre-pace in both unit modes; PiP deliberately matches it. */
    fun pace(speedMps: Float): String = `in`.shvms.trackme.domain.UnitFormatter.pace(
        mps = speedMps.toDouble(),
        imperial = false,
    )
}

data class HomeUiState(
    val trackingState: TrackingState = TrackingState.IDLE,
    val pathPoints: List<LatLng> = emptyList(),
    val distanceText: String = "0.00 km",
    val durationText: String = "00:00:00",
    /** Total wall-clock time since start, including paused segments — see [formatElapsedDuration]. */
    /** Bare elapsed figure. The "Total" label is added by the HUD, which has the strings. */
    val elapsedDurationText: String = "00:00:00",
    val elapsedDurationMillis: Long = 0L,
    val distanceMeters: Float = 0f,
    val durationMillis: Long = 0L,
    val speedText: String = "0.0 km/h",
    /** Meaningful only for personas where `usesPace` holds — walk and run. See [formatPace]. */
    val paceText: String = "--:-- /km",
    val isEmergencyActive: Boolean = false,
    val timeSinceLastGps: Long = 0L,
    val liveShareState: LiveShareState = LiveShareState(),
    val isAutoPaused: Boolean = false,
    val inferredActivityType: `in`.shvms.trackme.domain.processor.InferredActivityType = `in`.shvms.trackme.domain.processor.InferredActivityType.RUN_OR_TREK,
    val selectedPersona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO,
    val selectedDashboardPersona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO,
    val dashboardSummary: HomeDashboardSummary = HomeDashboardSummary.empty(0L),
    /** False until Room has emitted at least one authoritative dashboard projection. */
    val dashboardSummaryResolved: Boolean = false,
    val isDashboardReconciling: Boolean = true,
    val dashboardSyncNeedsAction: Boolean = false,
    val gamificationLevel: `in`.shvms.trackme.domain.gamification.GamificationLevel = `in`.shvms.trackme.domain.gamification.GamificationDefinitions.LEVELS.first(),
    val gamificationTotalActiveMinutes: Long = 0L,
    val gamificationUnlockedAchievements: List<String> = emptyList(),
    val gamificationNewLevel: `in`.shvms.trackme.domain.gamification.GamificationLevel? = null,
    val gamificationNewAchievements: List<String> = emptyList(),
    val isAuthenticated: Boolean = false,
    val userName: String? = null
)

class HomeViewModel(
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val authManager: AuthManager,
    private val liveShareManager: LiveShareManager,
    private val preferencesManager: AppPreferencesManager,
    private val dashboardRepository: HomeDashboardRepository,
    private val firestoreSyncManager: FirestoreSyncManager,
    private val gamificationRepository: `in`.shvms.trackme.domain.gamification.GamificationRepository,
) : ViewModel() {

    private val selectedDashboardPersona = MutableStateFlow(preferencesManager.lastStartedPersona.value)
    private val _dashboardRoute = MutableStateFlow<List<HomeDashboardRoutePoint>>(emptyList())
    val dashboardRoute: StateFlow<List<HomeDashboardRoutePoint>> = _dashboardRoute.asStateFlow()
    private var loadedDashboardRouteId: Long? = null

    private data class DashboardState(
        val summary: HomeDashboardSummary,
        val persona: `in`.shvms.trackme.domain.model.RidePersona,
        val resolved: Boolean,
        val reconciling: Boolean,
        val syncNeedsAction: Boolean,
        val gamificationLevel: `in`.shvms.trackme.domain.gamification.GamificationLevel,
        val gamificationTotalActiveMinutes: Long,
        val gamificationUnlockedAchievements: List<String>,
        val gamificationNewLevel: `in`.shvms.trackme.domain.gamification.GamificationLevel?,
        val gamificationNewAchievements: List<String>
    )

    private val dashboardSummary = dashboardRepository.summary
        .map<HomeDashboardSummary, HomeDashboardSummary?> { it }
        .onStart { emit(null) }

    private val dashboardState = combine(
        dashboardSummary,
        selectedDashboardPersona,
        dashboardRepository.isReconciling,
        firestoreSyncManager.syncResult,
        gamificationRepository.currentLevel,
        gamificationRepository.totalActiveMinutes,
        gamificationRepository.unlockedAchievements,
        gamificationRepository.newLevelReveal,
        gamificationRepository.newAchievementsReveal
    ) { params ->
        val summary = params[0] as HomeDashboardSummary?
        DashboardState(
            summary = summary ?: HomeDashboardSummary.empty(0L),
            persona = params[1] as `in`.shvms.trackme.domain.model.RidePersona,
            resolved = summary != null,
            reconciling = params[2] as Boolean,
            syncNeedsAction = params[3] is SyncResult.Error,
            gamificationLevel = params[4] as `in`.shvms.trackme.domain.gamification.GamificationLevel,
            gamificationTotalActiveMinutes = params[5] as Long,
            gamificationUnlockedAchievements = params[6] as List<String>,
            gamificationNewLevel = params[7] as `in`.shvms.trackme.domain.gamification.GamificationLevel?,
            gamificationNewAchievements = params[8] as List<String>
        )
    }

    init {
        viewModelScope.launch {
            preferencesManager.lastStartedPersona.collect { committed ->
                selectedDashboardPersona.value = committed
            }
        }
    }

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
            elapsedDurationMillis = g2.elapsedMillis,
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

    // isEmergencyActive is retained solely for CalmMomentGate: a stranded pre-1.6.4 SOS
    // state (cleared by SosStateCleanup, but belt-and-braces) must never be covered by a
    // celebration surface. Nothing in the UI can set or render it any more.
    val uiState = combine(
        trackingStats,
        emergencyManager.isEmergencyActive,
        liveShareManager.state,
        authManager.currentUser,
        dashboardState,
    ) { stats, isEmergency, liveShare, user, dashboard ->
        stats.copy(
            isEmergencyActive = isEmergency,
            liveShareState = liveShare,
            isAuthenticated = user != null,
            userName = user?.displayName,
            selectedDashboardPersona = dashboard.persona,
            dashboardSummary = dashboard.summary,
            dashboardSummaryResolved = dashboard.resolved,
            isDashboardReconciling = dashboard.reconciling,
            dashboardSyncNeedsAction = dashboard.syncNeedsAction,
            gamificationLevel = dashboard.gamificationLevel,
            gamificationTotalActiveMinutes = dashboard.gamificationTotalActiveMinutes,
            gamificationUnlockedAchievements = dashboard.gamificationUnlockedAchievements,
            gamificationNewLevel = dashboard.gamificationNewLevel,
            gamificationNewAchievements = dashboard.gamificationNewAchievements
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun acknowledgeGamificationReveals(level: `in`.shvms.trackme.domain.gamification.GamificationLevel?, achievements: List<String>) {
        viewModelScope.launch {
            if (level != null) gamificationRepository.acknowledgeNewLevel(level)
            if (achievements.isNotEmpty()) gamificationRepository.acknowledgeAchievements(achievements)
        }
    }

    private fun formatDistance(distanceMeters: Float, imperial: Boolean): String {
        return LiveRideMetricFormatter.distance(distanceMeters, imperial)
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
        return LiveRideMetricFormatter.speed(speedMps, imperial)
    }

    /**
     * Total wall-clock time since the ride started, including paused segments — shown as a
     * smaller caption under the (active, paused-excluded) headline DURATION stat, for every
     * persona. Reuses [formatDuration]'s HH:MM:SS formatting so the two stay visually aligned.
     */
    private fun formatElapsedDuration(millis: Long): String {
        return formatDuration(millis)
    }

    /**
     * Walking pace (minutes:seconds per km) computed from the same live GPS speed that feeds
     * [formatSpeed] — a real-time pace, matching how fitness apps show "current pace" (not an
     * average over the whole ride). Shown instead of [formatSpeed] only for
     * [in.shvms.trackme.domain.model.RidePersona.WALK]; cycling/motorbike/car keep live speed,
     * where km/h is the natural unit.
     */
    private fun formatPace(speedMps: Float): String {
        return LiveRideMetricFormatter.pace(speedMps)
    }

    fun startTracking(persona: `in`.shvms.trackme.domain.model.RidePersona = `in`.shvms.trackme.domain.model.RidePersona.AUTO) {
        trackingManager.setSelectedPersona(persona)
        sendCommandToService(TrackingService.ACTION_START_OR_RESUME_SERVICE)
    }

    fun selectDashboardPersona(persona: `in`.shvms.trackme.domain.model.RidePersona) {
        selectedDashboardPersona.value = persona
    }

    fun loadDashboardRoute(localId: Long) {
        if (loadedDashboardRouteId == localId) return
        loadedDashboardRouteId = localId
        _dashboardRoute.value = emptyList()
        viewModelScope.launch {
            _dashboardRoute.value = dashboardRepository.routePreview(localId)
        }
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
    private val liveShareManager: LiveShareManager,
    private val preferencesManager: AppPreferencesManager,
    private val dashboardRepository: HomeDashboardRepository,
    private val firestoreSyncManager: FirestoreSyncManager,
    private val gamificationRepository: `in`.shvms.trackme.domain.gamification.GamificationRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(
                trackingManager,
                emergencyManager,
                authManager,
                liveShareManager,
                preferencesManager,
                dashboardRepository,
                firestoreSyncManager,
                gamificationRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
