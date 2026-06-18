package com.example.trackme.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackme.data.local.AppDatabase
import com.example.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

class RideDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as com.example.trackme.TrackMeApp).database
    private val rideDao = db.rideDao()

    private val _rideWithPoints = MutableStateFlow<RideWithPoints?>(null)
    val rideWithPoints: StateFlow<RideWithPoints?> = _rideWithPoints.asStateFlow()

    fun loadRide(rideId: Long) {
        viewModelScope.launch {
            _rideWithPoints.value = rideDao.getRideWithPointsById(rideId)
        }
    }

    fun updateTitle(rideId: Long, newTitle: String) {
        viewModelScope.launch {
            try {
                val rideWithPoints = rideDao.getRideWithPointsById(rideId)
                if (rideWithPoints != null) {
                    val updatedRide = rideWithPoints.ride.copy(title = newTitle, isSynced = false)
                    rideDao.updateRide(updatedRide)
                    _rideWithPoints.value = rideWithPoints.copy(ride = updatedRide)
                    
                    // Trigger sync
                    val app = getApplication<com.example.trackme.TrackMeApp>()
                    app.firestoreSyncManager.uploadRide(rideId)
                }
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowError("Failed to update title: ${e.localizedMessage}"))
            }
        }
    }

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    fun deleteRide(rideId: Long) {
        viewModelScope.launch {
            try {
                val app = getApplication<com.example.trackme.TrackMeApp>()
                val ride = rideDao.getRideWithPointsById(rideId)?.ride
                
                if (ride?.isSynced == true) {
                    app.firestoreSyncManager.deleteRide(rideId)
                }
                
                rideDao.deleteRide(rideId)
                _uiEvent.emit(UiEvent.NavigateBack)
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowError("Failed to delete: ${e.localizedMessage}"))
            }
        }
    }
    
    sealed class UiEvent {
        object NavigateBack : UiEvent()
        data class ShowError(val message: String) : UiEvent()
    }
}
