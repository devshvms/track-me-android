package `in`.shvms.trackme.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.TrackMeApp
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: TrackMeApp) : ViewModel() {
    val currentUser = app.authManager.currentUser
    val syncResult = app.firestoreSyncManager.syncResult

    private val prefs = app.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
    private val _lastSyncTime = MutableStateFlow(prefs.getLong("last_sync_time", 0L))
    val lastSyncTime = _lastSyncTime.asStateFlow()

    val syncedRidesCount = app.database.rideDao().getAllRidesWithPoints().map { rides ->
        rides.count { it.ride.isSynced }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        viewModelScope.launch {
            syncResult.collect { result ->
                if (result is `in`.shvms.trackme.data.remote.SyncResult.Success) {
                    val time = System.currentTimeMillis()
                    prefs.edit().putLong("last_sync_time", time).apply()
                    _lastSyncTime.value = time
                }
            }
        }
    }

    suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
        val result = app.authManager.signInWithGoogle(context)
        if (result.isSuccess) {
            app.firestoreSyncManager.syncRecent(10)
        }
        return result
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                app.database.rideDao().deleteSyncedPoints()
                app.database.rideDao().deleteSyncedRides()
                
                // Clear emergency settings and stop active broadcast
                app.database.emergencyDao().deleteSettings()
                app.database.emergencyDao().deleteAllContacts()
                app.emergencyManager.stopEmergency()
            } catch (e: Exception) {
                // Ignore DB error and ensure we still sign out
            }
            app.authManager.signOut()
        }
    }

    fun syncData() {
        app.firestoreSyncManager.syncAll()
    }

    suspend fun deleteCloudData(): Result<Unit> {
        val result = app.firestoreSyncManager.deleteAllCloudData()
        if (result.isSuccess) {
            // Mark all local rides as unsynced
            app.database.rideDao().markAllAsUnsynced()
        }
        return result
    }

    suspend fun deleteAccountAndData(feedbackText: String): Result<Unit> {
        // 1. Submit feedback
        app.firestoreSyncManager.submitFeedback(feedbackText, "account_deletion")
        
        // 2. Delete cloud data
        app.firestoreSyncManager.deleteAllCloudData()
        
        // 3. Delete Firebase Auth Account
        val deleteAuthResult = app.authManager.deleteAccount()
        if (deleteAuthResult.isSuccess) {
            // 4. Wipe local data
            app.database.rideDao().deleteAllPoints()
            app.database.rideDao().deleteAllRides()
            app.database.emergencyDao().deleteSettings()
            app.database.emergencyDao().deleteAllContacts()
            app.emergencyManager.stopEmergency()
            app.authManager.signOut()
            return Result.success(Unit)
        }
        
        return deleteAuthResult
    }
}

class SettingsViewModelFactory(private val app: TrackMeApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
