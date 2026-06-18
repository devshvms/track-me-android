package com.example.trackme.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.trackme.service.TrackingManager
import com.example.trackme.service.TrackingService
import com.example.trackme.service.TrackingState
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
    val speedText: String = "0.0 km/h"
)

class HomeViewModel(private val trackingManager: TrackingManager) : ViewModel() {

    val uiState = combine(
        trackingManager.trackingState,
        trackingManager.pathPoints,
        trackingManager.totalDistance,
        trackingManager.rideDurationInMillis,
        trackingManager.currentSpeed
    ) { state, points, distance, duration, speed ->
        HomeUiState(
            trackingState = state,
            pathPoints = points,
            distanceText = formatDistance(distance),
            durationText = formatDuration(duration),
            speedText = formatSpeed(speed)
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
}

class HomeViewModelFactory(private val trackingManager: TrackingManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(trackingManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
