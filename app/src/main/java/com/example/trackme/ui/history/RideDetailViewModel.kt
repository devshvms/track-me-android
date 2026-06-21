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
import kotlinx.coroutines.sync.withLock

class RideDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as com.example.trackme.TrackMeApp
    private val db = app.database
    private val rideDao = db.rideDao()
    private val errorLogger = app.errorLogger

    private val _rideWithPoints = MutableStateFlow<RideWithPoints?>(null)
    val rideWithPoints: StateFlow<RideWithPoints?> = _rideWithPoints.asStateFlow()

    fun loadRide(rideId: Long) {
        viewModelScope.launch {
            _rideWithPoints.value = rideDao.getRideWithPointsById(rideId)
        }
    }

    private val actionMutex = kotlinx.coroutines.sync.Mutex()

    fun updateTitle(rideId: Long, newTitle: String) {
        viewModelScope.launch {
            actionMutex.withLock {
                try {
                    val rideWithPoints = rideDao.getRideWithPointsById(rideId)
                    if (rideWithPoints != null) {
                        val updatedRide = rideWithPoints.ride.copy(title = newTitle, isSynced = false)
                        rideDao.updateRide(updatedRide)
                        _rideWithPoints.value = rideWithPoints.copy(ride = updatedRide)
                        
                        // Trigger sync
                        app.firestoreSyncManager.uploadRide(rideId)
                    }
                } catch (e: Exception) {
                    errorLogger.log("Failed to update title for ride $rideId")
                    errorLogger.recordException(e)
                    _uiEvent.emit(UiEvent.ShowError("Failed to update title: ${e.localizedMessage}"))
                }
            }
        }
    }

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    fun deleteRide(rideId: Long) {
        viewModelScope.launch {
            actionMutex.withLock {
                try {
                    // Always try to delete from Firestore if user is logged in, 
                    // to avoid orphaned data if delete happens right after an edit
                    if (app.authManager.currentUser.value != null) {
                        val ride = rideDao.getRideWithPointsById(rideId)?.ride
                        val firestoreId = ride?.firestoreId ?: rideId.toString()
                        app.firestoreSyncManager.deleteRide(firestoreId)
                    }
                    
                    rideDao.deleteRide(rideId)
                    _uiEvent.emit(UiEvent.NavigateBack)
                } catch (e: Exception) {
                    errorLogger.log("Failed to delete ride $rideId")
                    errorLogger.recordException(e)
                    _uiEvent.emit(UiEvent.ShowError("Failed to delete: ${e.localizedMessage}"))
                }
            }
        }
    }
    
    sealed class UiEvent {
        object NavigateBack : UiEvent()
        data class ShowError(val message: String) : UiEvent()
    }
}
