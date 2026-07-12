package `in`.shvms.trackme.service

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackingManager {
    private val _trackingState = MutableStateFlow(TrackingState.IDLE)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _pathPoints = MutableStateFlow<List<LatLng>>(emptyList())
    val pathPoints: StateFlow<List<LatLng>> = _pathPoints.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _totalDistance = MutableStateFlow(0f)
    val totalDistance: StateFlow<Float> = _totalDistance.asStateFlow()

    private val _rideDurationInMillis = MutableStateFlow(0L)
    val rideDurationInMillis: StateFlow<Long> = _rideDurationInMillis.asStateFlow()

    private val _elapsedDurationInMillis = MutableStateFlow(0L)
    val elapsedDurationInMillis: StateFlow<Long> = _elapsedDurationInMillis.asStateFlow()

    private val _isAutoPaused = MutableStateFlow(false)
    val isAutoPaused: StateFlow<Boolean> = _isAutoPaused.asStateFlow()

    private val _inferredActivityType = MutableStateFlow(`in`.shvms.trackme.domain.processor.InferredActivityType.RUN_OR_TREK)
    val inferredActivityType: StateFlow<`in`.shvms.trackme.domain.processor.InferredActivityType> = _inferredActivityType.asStateFlow()

    private val _timeSinceLastGps = MutableStateFlow(0L)
    val timeSinceLastGps: StateFlow<Long> = _timeSinceLastGps.asStateFlow()

    fun updateState(state: TrackingState) {
        _trackingState.value = state
    }

    fun addPathPoint(point: LatLng) {
        _pathPoints.value = _pathPoints.value + point
    }

    fun updateSpeed(speed: Float) {
        _currentSpeed.value = speed
    }

    fun addDistance(distance: Float) {
        _totalDistance.value = _totalDistance.value + distance
    }

    fun updateDuration(duration: Long) {
        _rideDurationInMillis.value = duration
    }

    fun updateElapsedDuration(duration: Long) {
        _elapsedDurationInMillis.value = duration
    }

    fun setAutoPaused(paused: Boolean) {
        _isAutoPaused.value = paused
    }

    fun setInferredActivityType(activityType: `in`.shvms.trackme.domain.processor.InferredActivityType) {
        _inferredActivityType.value = activityType
    }

    private val _selectedPersona = MutableStateFlow(`in`.shvms.trackme.domain.model.RidePersona.AUTO)
    val selectedPersona: StateFlow<`in`.shvms.trackme.domain.model.RidePersona> = _selectedPersona.asStateFlow()

    fun setSelectedPersona(persona: `in`.shvms.trackme.domain.model.RidePersona) {
        _selectedPersona.value = persona
    }

    fun updateTimeSinceLastGps(time: Long) {
        _timeSinceLastGps.value = time
    }
    
    fun reset() {
        _trackingState.value = TrackingState.IDLE
        _pathPoints.value = emptyList()
        _currentSpeed.value = 0f
        _totalDistance.value = 0f
        _rideDurationInMillis.value = 0L
        _elapsedDurationInMillis.value = 0L
        _isAutoPaused.value = false
        _inferredActivityType.value = `in`.shvms.trackme.domain.processor.InferredActivityType.RUN_OR_TREK
        _timeSinceLastGps.value = 0L
        _selectedPersona.value = `in`.shvms.trackme.domain.model.RidePersona.AUTO
    }
}
