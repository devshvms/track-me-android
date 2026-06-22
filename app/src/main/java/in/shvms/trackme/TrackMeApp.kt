package `in`.shvms.trackme

import android.app.Application
import androidx.room.Room
import `in`.shvms.trackme.data.local.AppDatabase
import `in`.shvms.trackme.service.TrackingManager

import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.remote.FirestoreSyncManager
import `in`.shvms.trackme.service.EmergencyManager
import `in`.shvms.trackme.service.EmergencyBroadcastWorker

import `in`.shvms.trackme.utils.logger.ErrorLogger
import `in`.shvms.trackme.utils.logger.CrashlyticsErrorLogger
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
