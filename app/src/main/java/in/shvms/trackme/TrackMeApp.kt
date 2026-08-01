package `in`.shvms.trackme

import android.app.Application
import android.os.StrictMode
import androidx.room.Room
import `in`.shvms.trackme.data.local.AppDatabase
import `in`.shvms.trackme.service.TrackingManager
import `in`.shvms.trackme.service.ForegroundStartOutcome
import `in`.shvms.trackme.service.ForegroundStartPolicy

import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.data.remote.FirestoreSyncManager
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.service.EmergencyManager
import `in`.shvms.trackme.service.EmergencyBroadcastWorker

import `in`.shvms.trackme.utils.logger.ErrorLogger
import `in`.shvms.trackme.utils.logger.CrashlyticsErrorLogger
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.asStateFlow
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

    /** Shared v1.6.0 ride-stats aggregate (A1). Feeds B1 reveal / B2 recap / B3 streak. */
    lateinit var rideStatsStore: `in`.shvms.trackme.data.local.RideStatsStore
        private set

    /** B1: durable one-shot post-ride reveal, produced at finalize, consumed once by Home. */
    lateinit var pendingRevealStore: `in`.shvms.trackme.data.local.PendingRevealStore
        private set

    lateinit var appUpdateChecker: `in`.shvms.trackme.ui.update.AppUpdateChecker
        private set

    private val _recoveryNotice = MutableStateFlow<`in`.shvms.trackme.domain.recovery.OrphanedRideRecoveryManager.RecoverySummary?>(null)
    val recoveryNotice = _recoveryNotice.asStateFlow()

    private val _smsPermissionRevokedNotice = MutableStateFlow(false)
    val smsPermissionRevokedNotice = _smsPermissionRevokedNotice.asStateFlow()

    private val _locationPermissionRevokedNotice = MutableStateFlow(false)
    val locationPermissionRevokedNotice = _locationPermissionRevokedNotice.asStateFlow()

    /** B2: pending weekly recap for a completed week, surfaced once on foreground. */
    private val _weeklyRecap = MutableStateFlow<`in`.shvms.trackme.domain.stats.WeeklyRecap?>(null)
    val weeklyRecap = _weeklyRecap.asStateFlow()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.STRICT_MODE) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        _smsPermissionRevokedNotice.value = getSharedPreferences("trackme_prefs", MODE_PRIVATE)
            .getBoolean("sos_permission_revoked_notice", false)
        _locationPermissionRevokedNotice.value = getSharedPreferences("trackme_prefs", MODE_PRIVATE)
            .getBoolean("location_permission_revoked_notice", false)
        
        errorLogger = CrashlyticsErrorLogger()
        errorLogger.init()
        `in`.shvms.trackme.analytics.AnalyticsManager.init(this)

        preferencesManager = AppPreferencesManager(this)
        rideStatsStore = `in`.shvms.trackme.data.local.RideStatsStore(this)
        pendingRevealStore = `in`.shvms.trackme.data.local.PendingRevealStore(this)

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "trackme_db"
        )
        .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
        .fallbackToDestructiveMigration()
        .build()
        
        trackingManager = TrackingManager()
        emergencyManager = EmergencyManager(
            getSharedPreferences(
                `in`.shvms.trackme.service.TrackingService.TRACKING_PREFS,
                MODE_PRIVATE
            )
        )
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
            try {
                val activeSessionPending = getSharedPreferences(
                    `in`.shvms.trackme.service.TrackingService.TRACKING_PREFS,
                    MODE_PRIVATE
                ).getBoolean(
                    `in`.shvms.trackme.service.TrackingService.ACTIVE_TRACKING_SESSION_KEY,
                    false
                )
                if (!activeSessionPending) {
                    val summary = `in`.shvms.trackme.domain.recovery.OrphanedRideRecoveryManager.recoverOrphanedRides(
                        database.rideDao(),
                        `in`.shvms.trackme.service.TrackingService.activeRideId
                    )
                    if (summary.hasChanges) {
                        _recoveryNotice.value = summary
                    }
                }
            } catch (e: Exception) {
                errorLogger.recordException(e)
            }
            appUpdateChecker.checkForUpdate()
        }
    }

    fun consumeRecoveryNotice() {
        _recoveryNotice.value = null
    }

    /**
     * TASK-119: the live "is now a calm moment to celebrate" snapshot, built from the app-scoped
     * sources of truth. This is the one place the `TrackingState.IDLE` mapping is made outside the
     * UI layer; `HomeScreen` builds the equivalent moment from its already-collected UI state so
     * the dialog also disappears if the app leaves idle while a recap is queued.
     */
    fun currentCalmMoment(): `in`.shvms.trackme.domain.stats.CalmMomentGate.AppMoment =
        `in`.shvms.trackme.domain.stats.CalmMomentGate.AppMoment(
            isTrackingIdle = trackingManager.trackingState.value ==
                `in`.shvms.trackme.service.TrackingState.IDLE,
            isEmergencyActive = emergencyManager.isEmergencyActive.value,
            hasPendingReveal = pendingRevealStore.pending.value != null
        )

    /**
     * B2: shared foreground trigger (called from [MainActivity.onResume], mirroring the
     * recovery-notice pattern). Asks the store whether a completed week is worth recapping;
     * the store computes weeks, this just surfaces the result. Idempotent while one is pending.
     *
     * TASK-119: prompt 09 requires this to fire only when the app is calmly idle. Foregrounding
     * mid-ride, mid-SOS, or into a GPS-lost/storage-low state must skip the cycle. Skipping does
     * NOT consume the recap — nothing is acknowledged here — so it stays eligible for the rest of
     * its week and surfaces on the next calm foreground.
     */
    fun checkWeeklyRecap() {
        if (_weeklyRecap.value != null) return
        if (!`in`.shvms.trackme.domain.stats.CalmMomentGate.isCalm(currentCalmMoment())) return
        _weeklyRecap.value = rideStatsStore.pendingWeeklyRecap()
    }

    /** B2: acknowledge the recap after it has actually been presented (dedupe by week). */
    fun consumeWeeklyRecap() {
        val recap = _weeklyRecap.value ?: return
        _weeklyRecap.value = null
        applicationScope.launch { rideStatsStore.acknowledgeWeeklyRecap(recap.weekStartEpochDay) }
    }

    fun resumePersistedTrackingIfNeeded() {
        val hasActiveSession = getSharedPreferences(
            `in`.shvms.trackme.service.TrackingService.TRACKING_PREFS,
            MODE_PRIVATE
        ).getBoolean(
            `in`.shvms.trackme.service.TrackingService.ACTIVE_TRACKING_SESSION_KEY,
            false
        )
        if (hasActiveSession && !`in`.shvms.trackme.service.TrackingService.isRunning) {
            val intent = android.content.Intent(this, `in`.shvms.trackme.service.TrackingService::class.java).apply {
                action = `in`.shvms.trackme.service.TrackingService.ACTION_START_OR_RESUME_SERVICE
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                errorLogger.recordException(e)
                val outcome = ForegroundStartPolicy.classify(e, android.os.Build.VERSION.SDK_INT)
                abandonPersistedTrackingSession(outcome)
            }
        }
    }

    /**
     * Clears only the persisted service flags after a failed start. The unfinished ride itself is
     * intentionally retained so OrphanedRideRecoveryManager can recover it on the next launch.
     */
    fun abandonPersistedTrackingSession(outcome: ForegroundStartOutcome) {
        getSharedPreferences(
            `in`.shvms.trackme.service.TrackingService.TRACKING_PREFS,
            MODE_PRIVATE
        ).edit()
            .putBoolean(`in`.shvms.trackme.service.TrackingService.ACTIVE_TRACKING_SESSION_KEY, false)
            .putBoolean(`in`.shvms.trackme.service.TrackingService.PAUSED_TRACKING_SESSION_KEY, false)
            .apply()

        if (outcome.shouldShowLocationPermissionRevokedNotice) {
            setLocationPermissionRevokedNotice(true)
        }
    }

    fun setLocationPermissionRevokedNotice(isRevoked: Boolean) {
        _locationPermissionRevokedNotice.value = isRevoked
        getSharedPreferences("trackme_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("location_permission_revoked_notice", isRevoked)
            .apply()
    }

    /**
     * Hides the location-revoked banner for this app session only. The persisted flag is left
     * untouched so the notice returns on the next launch while location is still denied; the
     * permanent clear happens in MainActivity.onResume once the permission is actually granted.
     */
    fun dismissLocationPermissionRevokedNoticeForSession() {
        _locationPermissionRevokedNotice.value = false
    }

    fun setSmsPermissionRevokedNotice(isRevoked: Boolean) {
        _smsPermissionRevokedNotice.value = isRevoked
        getSharedPreferences("trackme_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("sos_permission_revoked_notice", isRevoked)
            .apply()
    }
}
