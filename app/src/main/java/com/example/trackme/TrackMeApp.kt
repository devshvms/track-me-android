package com.example.trackme

import android.app.Application
import androidx.room.Room
import com.example.trackme.data.local.AppDatabase
import com.example.trackme.service.TrackingManager

import com.example.trackme.auth.AuthManager
import com.example.trackme.data.remote.FirestoreSyncManager
import com.example.trackme.service.EmergencyManager
import com.example.trackme.service.EmergencyBroadcastWorker

import com.example.trackme.utils.logger.ErrorLogger
import com.example.trackme.utils.logger.CrashlyticsErrorLogger
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TrackMeApp : Application() {
    lateinit var database: AppDatabase
    lateinit var trackingManager: TrackingManager
    lateinit var emergencyManager: EmergencyManager
    lateinit var emergencyBroadcastWorker: EmergencyBroadcastWorker
    lateinit var firestoreSyncManager: FirestoreSyncManager
        private set

    lateinit var authManager: AuthManager
        private set

    lateinit var errorLogger: ErrorLogger
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        errorLogger = CrashlyticsErrorLogger()
        errorLogger.init()

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "trackme_db"
        )
        .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
        .fallbackToDestructiveMigration()
        .build()
        
        trackingManager = TrackingManager()
        emergencyManager = EmergencyManager()
        authManager = AuthManager()

        // Wire up AuthManager state changes to ErrorLogger
        authManager.currentUser.onEach { user ->
            errorLogger.setUserId(user?.uid)
        }.launchIn(applicationScope)

        firestoreSyncManager = FirestoreSyncManager(database.rideDao(), database.emergencyDao(), authManager, errorLogger)
        emergencyBroadcastWorker = EmergencyBroadcastWorker(this, database.emergencyDao(), trackingManager, emergencyManager, firestoreSyncManager, errorLogger)
        emergencyBroadcastWorker.start()
    }
}
