package com.example.trackme.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackme.data.local.AppDatabase
import com.example.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.InputStream
import com.example.trackme.domain.import.GPXParser

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as com.example.trackme.TrackMeApp
    private val db = app.database
    private val rideDao = db.rideDao()
    private val errorLogger = app.errorLogger

    val rides: StateFlow<List<RideWithPoints>> = rideDao.getAllRidesWithPoints()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    fun deleteRide(rideId: Long) {
        viewModelScope.launch {
            if (app.authManager.currentUser.value != null) {
                val ride = rideDao.getRideWithPointsById(rideId)?.ride
                val firestoreId = ride?.firestoreId ?: rideId.toString()
                app.firestoreSyncManager.deleteRide(firestoreId)
            }
            rideDao.deleteRide(rideId)
        }
    }

    fun importGPX(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                val parser = GPXParser()
                val parsed = parser.parse(inputStream)
                
                // Check duplicate by TrackMeID
                if (parsed.originalTrackMeId != null) {
                    val existingRides = rideDao.getAllRidesWithPoints().first()
                    val isDuplicate = existingRides.any { 
                        it.ride.id.toString() == parsed.originalTrackMeId || 
                        it.ride.firestoreId == parsed.originalTrackMeId 
                    }
                    if (isDuplicate) {
                        _uiEvent.emit(UiEvent.ShowError("Identical ride already exists"))
                        return@launch
                    }
                }
                
                val newRideId = rideDao.insertRide(parsed.rideWithPoints.ride)
                val newPoints = parsed.rideWithPoints.points.map { it.copy(rideId = newRideId) }
                rideDao.insertGPSPoints(newPoints)
                
                app.firestoreSyncManager.uploadRide(newRideId)
                
                _uiEvent.emit(UiEvent.Success("GPX Imported Successfully"))
            } catch (e: Exception) {
                errorLogger.log("Failed to parse GPX")
                errorLogger.recordException(e)
                _uiEvent.emit(UiEvent.ShowError("Failed to parse GPX: ${e.localizedMessage}"))
            }
        }
    }

    sealed class UiEvent {
        data class ShowError(val message: String) : UiEvent()
        data class Success(val message: String) : UiEvent()
    }
}
