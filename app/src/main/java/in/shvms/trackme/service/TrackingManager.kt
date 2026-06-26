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

    fun updateTimeSinceLastGps(time: Long) {
        _timeSinceLastGps.value = time
    }
    
    fun reset() {
        _trackingState.value = TrackingState.IDLE
        _pathPoints.value = emptyList()
        _currentSpeed.value = 0f
        _totalDistance.value = 0f
        _rideDurationInMillis.value = 0L
        _timeSinceLastGps.value = 0L
    }
}
