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
import `in`.shvms.trackme.service.SosRemovalNoticePolicy
import `in`.shvms.trackme.service.SosStateCleanup

import `in`.shvms.trackme.utils.logger.ErrorLogger
import `in`.shvms.trackme.utils.logger.CrashlyticsErrorLogger
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackMeApp : Application() {
    lateinit var database: AppDatabase
    lateinit var trackingManager: TrackingManager
    lateinit var emergencyManager: EmergencyManager
    lateinit var firestoreSyncManager: FirestoreSyncManager
        private set

    lateinit var liveShareManager: LiveShareManager

    /**
     * Group Ride session state and the sync loop (§4.6). Created here rather than injected because
     * the app has no DI (§6.2 H5) — every dependency is a lateinit on this class plus a hand-written
     * factory.
     */
    lateinit var groupSessionStore: `in`.shvms.trackme.data.local.GroupSessionStore

    lateinit var groupSessionManager: `in`.shvms.trackme.data.remote.GroupSessionManager

        private set

    lateinit var authManager: AuthManager
        private set

    lateinit var errorLogger: ErrorLogger
        private set

    /** Resolved as the first statement of [onCreate]; see the comment there before moving it. */
    var onboardingState: `in`.shvms.trackme.ui.onboarding.OnboardingState =
        `in`.shvms.trackme.ui.onboarding.OnboardingState.LEGACY
        private set

    /**
     * Records the walkthrough's outcome.
     *
     * The analytics value is written explicitly rather than left to the stored default, so what
     * lands in preferences is a decision the user made on a screen they saw — not an assumption.
     */
    fun completeOnboarding(outcome: `in`.shvms.trackme.ui.onboarding.OnboardingOutcome) {
        // Consent first, capture second. AnalyticsManager drops every event while the flag is off,
        // so emitting before this line would silently discard the one event describing the very
        // screen the user just answered — and it would deserve to, because at that instant they
        // had not yet agreed to anything.
        preferencesManager.setTelemetryEnabled(outcome.analyticsEnabled)
        `in`.shvms.trackme.analytics.AnalyticsManager.updateLocalConsent(outcome.analyticsEnabled)

        `in`.shvms.trackme.analytics.AnalyticsManager.trackOnboardingCompleted(
            attempts = outcome.attempts,
            furthestPage = outcome.furthestPage,
            usedSkip = outcome.usedSkip,
            seconds = outcome.seconds,
            analyticsOptIn = outcome.analyticsEnabled,
            locationGranted = outcome.locationGranted,
            notificationsGranted = outcome.notificationsGranted,
        )

        `in`.shvms.trackme.ui.onboarding.OnboardingGate.markDone(this)
        onboardingState = `in`.shvms.trackme.ui.onboarding.OnboardingState.DONE
    }

    lateinit var preferencesManager: AppPreferencesManager
        private set

    lateinit var ageSignalManager: `in`.shvms.trackme.data.AgeSignalManager
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

    /**
     * TG-A06 (1.6.4): true while an upgrading user who had completed SOS setup has not yet
     * acknowledged the removal notice. Evaluated exactly once (per install) in [onCreate];
     * users who never completed setup are grandfathered out so they never see it.
     */
    private val _sosRemovalNotice = MutableStateFlow(false)
    val sosRemovalNotice = _sosRemovalNotice.asStateFlow()

    private val _locationPermissionRevokedNotice = MutableStateFlow(false)
    val locationPermissionRevokedNotice = _locationPermissionRevokedNotice.asStateFlow()

    /** B2: pending weekly recap for a completed week, surfaced once on foreground. */
    private val _weeklyRecap = MutableStateFlow<`in`.shvms.trackme.domain.stats.WeeklyRecap?>(null)
    val weeklyRecap = _weeklyRecap.asStateFlow()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // FIRST. Not "early" — first. SosStateCleanup below commits a flag into trackme_prefs, so
        // after it runs a brand-new install is indistinguishable from an upgrade by preference
        // contents alone, and the walkthrough would never show for anyone. See OnboardingGate.
        onboardingState = `in`.shvms.trackme.ui.onboarding.OnboardingGate.resolve(this)

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

        // TG-A05 / HAZARD-1: must run before EmergencyManager is constructed and before any
        // UI reads the persisted SOS state. Synchronous by design — see SosStateCleanup.
        SosStateCleanup.clearOnce(
            getSharedPreferences(
                `in`.shvms.trackme.service.TrackingService.TRACKING_PREFS,
                MODE_PRIVATE
            )
        )
        // The SOS dispatch machinery is gone; drop its stale notification channel so
        // "Emergency alerts" stops appearing in the system notification settings of
        // upgraded installs. Deleting a nonexistent channel is a documented no-op.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getSystemService(android.app.NotificationManager::class.java)
                ?.deleteNotificationChannel("sos_channel")
        }

        _locationPermissionRevokedNotice.value = getSharedPreferences("trackme_prefs", MODE_PRIVATE)
            .getBoolean("location_permission_revoked_notice", false)

        errorLogger = CrashlyticsErrorLogger()
        errorLogger.init()
        `in`.shvms.trackme.analytics.AnalyticsManager.init(this)

        // Install the Maps SDK's static delegates before any screen can reach for them.
        //
        // `CameraUpdateFactory` is a façade over a delegate the SDK installs when it loads, and
        // until then every factory call throws `NullPointerException: CameraUpdateFactory is not
        // initialized` — fatally. Four screens animate a camera from a `LaunchedEffect` or a tap,
        // and those race map initialisation: a quick first location fix wins on a slow device.
        //
        // After errorLogger.init() on purpose, so a failure here is reportable rather than silent.
        // Best-effort: `animateSafely`/`moveSafely` guard the call sites for the cases this cannot
        // cover — no Play Services, or a renderer the device refuses to load.
        try {
            com.google.android.gms.maps.MapsInitializer.initialize(this)
        } catch (e: Exception) {
            errorLogger.recordException(e)
        }

        preferencesManager = AppPreferencesManager(this)
        ageSignalManager = `in`.shvms.trackme.data.AgeSignalManager(this)
        rideStatsStore = `in`.shvms.trackme.data.local.RideStatsStore(this)
        pendingRevealStore = `in`.shvms.trackme.data.local.PendingRevealStore(this)

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "trackme_db"
        )
        .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
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
        groupSessionStore = `in`.shvms.trackme.data.local.GroupSessionStore(this)
        groupSessionManager = `in`.shvms.trackme.data.remote.GroupSessionManager(groupSessionStore)
        // §6.1 B6: a session orphaned by an OS kill has to come back on its own, before any UI
        // exists to ask for it. restore() is a no-op when there is nothing to restore.
        groupSessionManager.restore()
        observeGroupPresence()

        // Wire up AuthManager state changes to ErrorLogger
        authManager.currentUser.onEach { user ->
            errorLogger.setUserId(user?.uid)
        }.launchIn(applicationScope)

        firestoreSyncManager = FirestoreSyncManager(database.rideDao(), authManager, errorLogger)
        appUpdateChecker = `in`.shvms.trackme.ui.update.AppUpdateChecker(this)
        `in`.shvms.trackme.data.remote.SyncWorker.schedulePeriodicSync(this)

        applicationScope.launch(Dispatchers.IO) {
            evaluateSosRemovalNotice()
            `in`.shvms.trackme.service.EmergencyDataPurge.purgeOnce(
                prefs = getSharedPreferences("trackme_prefs", MODE_PRIVATE),
                authManager = authManager,
                errorLogger = errorLogger,
            )
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

    /**
     * TG-A06: decide once whether this install needs the SOS-removal notice — see
     * [SosRemovalNoticePolicy] for the eligibility rule and the read-failure handling.
     *
     * A `null` result means the answer is not known yet, so the notice state is left as-is for
     * this launch: showing nothing is correct when the verdict is unknown, showing a wrong
     * verdict is not.
     *
     * TG-A15–A21 (1.6.5): emergency tables are dropped by MIGRATION_9_10, so the setup-complete
     * check can no longer query the database. Users already evaluated on 1.6.4 keep their
     * stored verdict; unevaluated users who skip 1.6.4 never had SOS setup, so `false` is the
     * correct answer.
     */
    private suspend fun evaluateSosRemovalNotice() {
        val prefs = getSharedPreferences("trackme_prefs", MODE_PRIVATE)
        SosRemovalNoticePolicy.evaluateOnce(
            prefs = prefs,
            onReadFailure = { errorLogger.recordException(it) },
        ) {
            // The emergency_settings table was dropped in MIGRATION_9_10. Users who were
            // already evaluated on 1.6.4 will not reach this lambda. Users upgrading
            // directly from pre-1.6.4 to 1.6.5+ never had SOS setup complete, so `false`
            // is the correct answer — they should not see the notice.
            false
        }?.let { shouldShow ->
            _sosRemovalNotice.value = shouldShow
        }
    }

    /** TG-A06: the notice is must-acknowledge; only an explicit tap clears it, permanently. */
    fun acknowledgeSosRemovalNotice() {
        _sosRemovalNotice.value = false
        SosRemovalNoticePolicy.acknowledge(getSharedPreferences("trackme_prefs", MODE_PRIVATE))
    }

    /**
     * Turns the tracking service's presence mode on and off as group membership changes.
     *
     * **This is what makes §6.1 B1's fix actually run.** `TrackingService` grew
     * `ACTION_START_GROUP_PRESENCE` and the whole orthogonal presence path, but nothing was
     * sending the intent — so a member could join a group, see the roster, and never push a single
     * position. Everyone would have appeared permanently absent, with no error anywhere: exactly
     * the silent-failure shape this feature keeps producing.
     *
     * One observer rather than a call at each call site, because "in a group" is reached six ways
     * — create, join, restore-after-process-death, leave, end, and TTL expiry — and five of them
     * would eventually be missed.
     */
    private fun observeGroupPresence() {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        scope.launch {
            var presenceRequested = false
            groupSessionManager.state.collect { session ->
                if (session.isActive && !presenceRequested) {
                    presenceRequested = true
                    sendTrackingServiceCommand(
                        `in`.shvms.trackme.service.TrackingService.ACTION_START_GROUP_PRESENCE,
                    )
                } else if (!session.isActive && presenceRequested) {
                    presenceRequested = false
                    sendTrackingServiceCommand(
                        `in`.shvms.trackme.service.TrackingService.ACTION_STOP_GROUP_PRESENCE,
                    )
                }
            }
        }
    }

    /**
     * Whether an Activity is currently resumed.
     *
     * Android 12+ only permits `startForegroundService()` from the foreground, and presence has no
     * background-start exemption — §16.4 keeps `ACCESS_BACKGROUND_LOCATION` undeclared on purpose.
     */
    @Volatile var isAppInForeground: Boolean = false

    /**
     * Re-asserts presence when the app returns to the foreground.
     *
     * Idempotent: `startGroupPresence()` promotes and then no-ops when presence is already on.
     */
    fun resumeGroupPresenceIfNeeded() {
        if (groupSessionManager.state.value.isActive) {
            sendTrackingServiceCommand(`in`.shvms.trackme.service.TrackingService.ACTION_START_GROUP_PRESENCE)
        }
    }

    private fun sendTrackingServiceCommand(action: String) {
        val stopping = action == `in`.shvms.trackme.service.TrackingService.ACTION_STOP_GROUP_PRESENCE

        // Never START a service just to tell it to stop. Beyond being pointless,
        // startForegroundService() would then oblige that brand-new service to promote itself to
        // the foreground within five seconds or have the process killed.
        if (stopping && !`in`.shvms.trackme.service.TrackingService.isRunning) return

        try {
            val intent = android.content.Intent(this, `in`.shvms.trackme.service.TrackingService::class.java)
                .apply { this.action = action }
            when {
                // Already running: a plain startService carries no promotion obligation and is
                // allowed from the background, so it is right for both stop AND a start that only
                // needs to reach a service that already exists.
                `in`.shvms.trackme.service.TrackingService.isRunning -> startService(intent)

                // Not running, and we are in the background. Android 12+ refuses a background
                // startForegroundService() with ForegroundServiceStartNotAllowedException. This is
                // reachable in normal use: a group can end remotely, or its TTL can fire, while the
                // phone is in a pocket. Attempting it would throw, be swallowed, and leave the
                // member believing they were sharing.
                //
                // §16.4 rules out ACCESS_BACKGROUND_LOCATION, so presence genuinely cannot start
                // from the background — the honest answer is not to pretend. MainActivity.onResume
                // re-evaluates the session, so presence resumes the moment the app is opened.
                stopping || !isAppInForeground -> Unit

                else -> androidx.core.content.ContextCompat.startForegroundService(this, intent)
            }
        } catch (e: Exception) {
            // A foreground-service start can be refused (background start restrictions, or the
            // user revoking notification access). The group session itself is unaffected — the
            // member simply is not sharing, which §8 already has an honest banner for.
            errorLogger.recordException(e)
        }
    }


    private val _pendingGroupInvite =
        MutableStateFlow<`in`.shvms.trackme.domain.group.GroupInviteLink.Invite?>(null)

    /**
     * An invite that arrived from outside the app and has not been acted on yet.
     *
     * Held on the application rather than passed through the activity because the deep link can
     * land before any UI exists — a cold start from a browser tap creates the activity, the
     * navigation graph and the Community screen in that order, and the intent is already gone by
     * the time the screen that needs it is composed.
     */
    val pendingGroupInvite: StateFlow<`in`.shvms.trackme.domain.group.GroupInviteLink.Invite?> =
        _pendingGroupInvite.asStateFlow()

    fun setPendingGroupInvite(invite: `in`.shvms.trackme.domain.group.GroupInviteLink.Invite?) {
        _pendingGroupInvite.value = invite
    }

    fun consumePendingGroupInvite() {
        _pendingGroupInvite.value = null
    }

}
