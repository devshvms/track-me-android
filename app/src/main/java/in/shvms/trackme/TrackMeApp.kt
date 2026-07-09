package `in`.shvms.trackme

import android.app.Application
import androidx.room.Room
import `in`.shvms.trackme.data.local.AppDatabase
import `in`.shvms.trackme.service.TrackingManager

import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.data.remote.FirestoreSyncManager
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.service.EmergencyManager
import `in`.shvms.trackme.service.EmergencyBroadcastWorker

import `in`.shvms.trackme.utils.logger.ErrorLogger
import `in`.shvms.trackme.utils.logger.CrashlyticsErrorLogger
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackMeApp : Application() {
    lateinit var database: AppDatabase
    lateinit var trackingManager: TrackingManager
    lateinit var emergencyManager: EmergencyManager
    lateinit var emergencyBroadcastWorker: EmergencyBroadcastWorker
    lateinit var firestoreSyncManager: FirestoreSyncManager
        private set

    lateinit var liveShareManager: LiveShareManager
        private set

    lateinit var authManager: AuthManager
        private set

    lateinit var errorLogger: ErrorLogger
        private set

    lateinit var preferencesManager: AppPreferencesManager
        private set

    lateinit var appUpdateChecker: `in`.shvms.trackme.ui.update.AppUpdateChecker
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        errorLogger = CrashlyticsErrorLogger()
        errorLogger.init()

        preferencesManager = AppPreferencesManager(this)

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
        liveShareManager = LiveShareManager()

        // Wire up AuthManager state changes to ErrorLogger
        authManager.currentUser.onEach { user ->
            errorLogger.setUserId(user?.uid)
        }.launchIn(applicationScope)

        firestoreSyncManager = FirestoreSyncManager(database.rideDao(), database.emergencyDao(), authManager, errorLogger)
        appUpdateChecker = `in`.shvms.trackme.ui.update.AppUpdateChecker(this)
        `in`.shvms.trackme.data.remote.SyncWorker.schedulePeriodicSync(this)
        emergencyBroadcastWorker = EmergencyBroadcastWorker(this, database.emergencyDao(), trackingManager, emergencyManager, firestoreSyncManager, errorLogger)
        emergencyBroadcastWorker.start()

        applicationScope.launch(Dispatchers.IO) {
            appUpdateChecker.checkForUpdate()
        }
    }
}
