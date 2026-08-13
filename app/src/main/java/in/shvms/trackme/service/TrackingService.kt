package `in`.shvms.trackme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.utils.RideUtils
import `in`.shvms.trackme.utils.StorageHealthMonitor
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.getAppStrings
import `in`.shvms.trackme.ui.community.statusLabelForCode
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

enum class TrackingState {
    IDLE, TRACKING, PAUSED, GPS_LOST, GPS_DISABLED, STORAGE_LOW
}

/** Pure transition predicates keep GPS telemetry edge-triggered and unit-testable. */
internal fun shouldEmitGpsPauseTelemetry(state: TrackingState): Boolean = state == TrackingState.TRACKING

internal fun shouldEmitGpsResumeTelemetry(state: TrackingState): Boolean =
    state == TrackingState.GPS_LOST || state == TrackingState.GPS_DISABLED

class TrackingService : Service() {

    private lateinit var locationHelper: LocationHelper
    private lateinit var rideDao: RideDao
    private lateinit var trackingManager: TrackingManager
    private lateinit var liveShareManager: LiveShareManager
    private lateinit var groupSessionManager: `in`.shvms.trackme.data.remote.GroupSessionManager

    /**
     * §4.6: presence is an **orthogonal flag**, not a new [TrackingState] value. It composes with
     * whatever the recorder is doing — a member can be in a group while idle, riding, or paused,
     * and each combination is valid.
     */
    private var presenceMode = false

    /** Only registered when no ride stream is open. See [PresenceStreamPolicy]. */
    private var presenceStreamActive = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentState = TrackingState.IDLE
    private var currentRideId: Long? = null
    private var lastLocation: Location? = null

    private var isTimerEnabled = false
    private var timeStarted = 0L
    private var rideDuration = 0L
    private var elapsedWallClockDuration = 0L
    private var currentPointCount = 0
    private var storageWarningShown = false
    private var lastGpsTimeMs = 0L
    private var lastLiveShareTimeMs = 0L
    private var lastNotifyElapsedMs = Long.MIN_VALUE
    private var lastNotifyDistanceMeters = 0f
    private var lastNotifyState: TrackingState? = null

    private val adaptiveAutoPauseEngine = `in`.shvms.trackme.domain.processor.AdaptiveAutoPauseEngine()
    private lateinit var motionSensorManager: MotionSensorManager

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            val location = result.lastLocation ?: return

            // §6.1 B1: the group push happens HERE, above every ride-state gate below.
            //
            // The whole blocker is that the existing push sits inside `if (currentState ==
            // TRACKING)`, so a member who has joined but not set off broadcasts nothing and is
            // invisible to the people they are trying to meet. Presence is orthogonal to recording,
            // so it runs before the recorder's states are consulted at all — paused at a café,
            // searching for GPS, out of storage, or not riding yet.
            pushGroupPresence(location)

            // 1. Strict GPS Accuracy Filter: discard indoor/multipath bounce (> 22 meters inaccuracy)
            //    Applies to the RIDE only — presence uses a looser threshold above, because
            //    BALANCED_POWER_ACCURACY routinely returns 20-100m and this gate would discard
            //    nearly every presence fix.
            if (location.hasAccuracy() && location.accuracy > PresenceStreamPolicy.RIDE_MAX_ACCURACY_METERS) {
                return
            }

            if (shouldEmitGpsResumeTelemetry(currentState)) {
                updateState(TrackingState.TRACKING)
                AnalyticsManager.trackLocationUpdatesResumed()
            }

            if (currentState == TrackingState.STORAGE_LOW) {
                return
            }

            if (currentState == TrackingState.TRACKING) {
                lastGpsTimeMs = System.currentTimeMillis()

                // 2. Compute true displacement and time delta
                var distance = 0f
                var timeDeltaMs = 0L
                lastLocation?.let { prevLocation ->
                    distance = prevLocation.distanceTo(location)
                    timeDeltaMs = location.time - prevLocation.time
                }

                // 3. Hardware IMU Sensor Fusion: Check linear accelerometer stillness
                val isHardwareStill = motionSensorManager.isDeviceStationary()
                val currentlyPaused = trackingManager.isAutoPaused.value

                val prefs = getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
                val autoPauseEnabled = prefs.getBoolean("intelligent_auto_pause", true)
                val currentPersona = trackingManager.selectedPersona.value
                val thresholds = adaptiveAutoPauseEngine.getThresholdsForPersona(currentPersona)

                val rawSpeed = if (location.hasSpeed()) location.speed else 0f
                val isStationaryDrift = rawSpeed < 0.6f && distance < 2.5f

                val isPointPaused: Boolean
                val effectiveSpeed: Float

                if (autoPauseEnabled) {
                    effectiveSpeed = if (isHardwareStill || isStationaryDrift) 0f else rawSpeed
                    isPointPaused = if (isHardwareStill || isStationaryDrift) {
                        true
                    } else {
                        adaptiveAutoPauseEngine.evaluateAutoPause(effectiveSpeed, currentlyPaused, location.time, currentPersona)
                    }
                } else {
                    effectiveSpeed = if (isStationaryDrift) 0f else rawSpeed
                    isPointPaused = false
                }

                trackingManager.updateSpeed(effectiveSpeed)
                trackingManager.setAutoPaused(isPointPaused)
                if (autoPauseEnabled) {
                    trackingManager.setInferredActivityType(adaptiveAutoPauseEngine.updateActivityProfile(effectiveSpeed))
                }

                val latLng = LatLng(location.latitude, location.longitude)
                trackingManager.addPathPoint(latLng)

                if (!isPointPaused && distance >= 1.5f && effectiveSpeed > 0.3f) {
                    trackingManager.addDistance(distance)
                }
                lastLocation = location

                currentRideId?.let { rideId ->
                    if (StorageHealthMonitor.isLowStorage(this@TrackingService)) {
                        enterStorageLowState()
                        return@let
                    }
                    serviceScope.launch {
                        try {
                            rideDao.insertGPSPoint(
                                GPSPointEntity(
                                    rideId = rideId,
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    altitude = location.altitude,
                                    accuracy = location.accuracy,
                                    speed = effectiveSpeed,
                                    timestamp = location.time,
                                    isPaused = isPointPaused
                                )
                            )
                            currentPointCount++
                            if (currentPointCount == 8000) {
                                showPointLimitWarning()
                            } else if (currentPointCount >= 9000) {
                                splitRide()
                            }
                        } catch (_: SQLiteException) {
                            withContext(Dispatchers.Main.immediate) {
                                enterStorageLowState()
                            }
                        }
                    }
                }
                
                val liveShareState = liveShareManager.state.value
                if (liveShareState.status == LiveShareStatus.ACTIVE) {
                    val prefs = getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
                    val freqSec = prefs.getInt("live_share_frequency_sec", 5)
                    val now = System.currentTimeMillis()
                    
                    if (now - lastLiveShareTimeMs >= freqSec * 1000L) {
                        lastLiveShareTimeMs = now
                        
                        val manager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                        val batteryLevel = manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val heading = if (location.hasBearing()) location.bearing else null
                        
                        serviceScope.launch {
                            liveShareManager.pushLocation(
                                lat = location.latitude,
                                lon = location.longitude,
                                batteryLevel = batteryLevel,
                                speed = if (location.hasSpeed()) location.speed else null,
                                heading = heading
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locationHelper = LocationHelper(this)
        motionSensorManager = MotionSensorManager(this)
        val app = application as TrackMeApp
        rideDao = app.database.rideDao()
        trackingManager = app.trackingManager
        liveShareManager = app.liveShareManager
        groupSessionManager = app.groupSessionManager
        
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_RESUME_SERVICE -> {
                if (currentState == TrackingState.IDLE) {
                    startForegroundService()
                } else {
                    resumeTracking()
                }
            }
            ACTION_START_GROUP_PRESENCE -> startGroupPresence()
            ACTION_STOP_GROUP_PRESENCE -> stopGroupPresence()
            ACTION_PAUSE_SERVICE -> pauseTracking()
            ACTION_STOP_SERVICE -> stopTracking()
            ACTION_DISCARD_NEAR_EMPTY_RIDE -> stopTracking(discardNearEmptyRide = true)
            null -> {
                // START_STICKY recreates the service with a null intent after process death.
                // Only restore a session that was explicitly marked active by the service.
                if (hasPersistedActiveSession() && currentState == TrackingState.IDLE) {
                    startForegroundService()
                } else if (groupSessionManager.state.value.isActive && !presenceMode) {
                    // B6: a group session restored by TrackMeApp after process death needs its
                    // presence back, and there is no ride to restore.
                    startGroupPresence()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        try {
            createNotificationChannels()
        wireGroupAlerts()
            startForeground(
                NOTIFICATION_ID,
                getNotification(
                    durationMillis = rideDuration,
                    distanceMeters = trackingManager.totalDistance.value,
                    speedMps = trackingManager.currentSpeed.value,
                    state = currentState
                )
            )
        } catch (e: Exception) {
            handleForegroundStartFailure(e)
            return
        }

        if (StorageHealthMonitor.isLowStorage(this)) {
            enterStorageLowState()
            return
        }

        updateState(TrackingState.TRACKING)
        currentPointCount = 0
        lastGpsTimeMs = System.currentTimeMillis()
        motionSensorManager.startListening()
        // The ride is about to open its own high-accuracy stream; drop the presence one so the two
        // are never registered at once (§4.6, "no second location subscription").
        presenceStreamActive = false
        
        serviceScope.launch {
            try {
                if (!restorePersistedRide()) {
                    (application as TrackMeApp).emergencyManager.beginRideSession()
                    val startTime = System.currentTimeMillis()
                    val rideId = rideDao.insertRide(
                        RideEntity(
                            startTime = startTime,
                            title = RideUtils.getDefaultTitle(startTime, trackingManager.selectedPersona.value),
                            persona = trackingManager.selectedPersona.value.name
                        )
                    )
                    currentRideId = rideId
                    activeRideId = rideId
                    setPersistedActiveSession(true)
                    setPersistedPausedSession(false)

                    `in`.shvms.trackme.analytics.AnalyticsManager.trackRideStarted(
                        rideId = rideId.toString()
                    )
                }

                if (hasPersistedPausedSession()) {
                    updateState(TrackingState.PAUSED)
                    isTimerEnabled = false
                    motionSensorManager.stopListening()
                } else {
                    if (!locationHelper.startLocationTracking(locationCallback)) {
                        handleLocationStartFailure()
                        return@launch
                    }
                }
            } catch (_: SQLiteException) {
                withContext(Dispatchers.Main.immediate) {
                    enterStorageLowState()
                }
            }
        }
        startTimer()
    }

    // --- Group presence (§4.6, B1) ------------------------------------------------------------

    /**
     * Turns on presence. Starts the foreground service if no ride is running, because a member who
     * has joined but not set off still has to be visible — that is the entire point of B1.
     */
    /**
     * Promotes to the foreground, whatever else this command is going to do.
     *
     * **The Android contract, and the crash it caused.** A service reached via
     * `startForegroundService()` must call `startForeground()` within ~5 seconds on *every* path,
     * or the system kills the process with `ForegroundServiceDidNotStartInTimeException`.
     *
     * `startGroupPresence()` used to skip it whenever `presenceMode` was already true — and
     * `onCreate` set exactly that from a restored session. So on every launch with a stored group:
     * TrackMeApp saw an active session, called `startForegroundService`, `onCreate` set
     * `presenceMode = true`, the handler early-returned, and five seconds later the process died.
     * The app then relaunched, restored the same session, and did it again — a crash loop that
     * only appeared with a group already on disk, which is why nothing before this saw it.
     *
     * Promoting first, unconditionally, removes the whole class: no early return anywhere in a
     * presence handler can violate the contract, because the contract is satisfied before the
     * handler makes any decision.
     *
     * @return false when promotion failed and the failure has already been handled.
     */
    private fun ensureForegroundForPresence(): Boolean = try {
        createNotificationChannels()
        // The ride's own notification when a ride is running, the presence one otherwise. Calling
        // this again when already foreground is a harmless notification update.
        startForeground(
            NOTIFICATION_ID,
            if (currentState == TrackingState.IDLE) {
                buildPresenceNotification()
            } else {
                getNotification(
                    durationMillis = rideDuration,
                    distanceMeters = trackingManager.totalDistance.value,
                    speedMps = trackingManager.currentSpeed.value,
                    state = currentState,
                )
            },
        )
        true
    } catch (e: Exception) {
        presenceMode = false
        handleForegroundStartFailure(e)
        false
    }

    private fun startGroupPresence() {
        // §16.4 rules out ACCESS_BACKGROUND_LOCATION, so a foreground service started while the
        // app is visible is the ONLY thing keeping location flowing once the user switches away.
        if (!ensureForegroundForPresence()) return

        if (presenceMode) {
            reconcileLocationStreams()
            return
        }
        presenceMode = true
        reconcileLocationStreams()
        postTrackingNotification(force = true)
    }

    /**
     * Turns off presence. Stops the service **only** if no ride is running — a member leaving a
     * group mid-ride must not have their recording torn down with it (§8: a group failure must never
     * affect the user's own ride).
     */
    private fun stopGroupPresence() {
        // Same contract. TrackMeApp uses startService() for stop, but a stray
        // startForegroundService() from anywhere must not be able to kill the app.
        if (!ensureForegroundForPresence()) return

        if (!presenceMode) {
            if (PresenceStreamPolicy.canStopService(currentState, presenceMode = false)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        presenceMode = false
        reconcileLocationStreams()

        if (PresenceStreamPolicy.canStopService(currentState, presenceMode)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            postTrackingNotification(force = true)
        }
    }

    /**
     * Opens exactly the one location subscription [PresenceStreamPolicy] says we should have.
     *
     * §4.6: *"No second location subscription, no doubled GPS cost."* When a ride is running,
     * presence rides on the stream the ride already opened; the presence stream only exists when
     * there is no ride at all.
     */
    private fun reconcileLocationStreams() {
        when (PresenceStreamPolicy.streamFor(currentState, presenceMode)) {
            LocationStreamMode.PRESENCE_BALANCED -> {
                if (!presenceStreamActive) {
                    presenceStreamActive = locationHelper.startPresenceTracking(locationCallback)
                }
            }
            LocationStreamMode.RIDE_HIGH_ACCURACY -> {
                // The ride's own stream is (or is about to be) open on the same callback. Drop the
                // presence request so the two cannot both be registered.
                if (presenceStreamActive) {
                    presenceStreamActive = false
                    if (currentState == TrackingState.IDLE) {
                        locationHelper.stopLocationTracking(locationCallback)
                    }
                }
            }
            LocationStreamMode.NONE -> {
                if (presenceStreamActive) {
                    presenceStreamActive = false
                    locationHelper.stopLocationTracking(locationCallback)
                }
            }
        }
    }

    /**
     * Hands one fix to the group, sealed on the way (§4.6).
     *
     * `riding` is the honest answer to "has this member set off yet", which the Community roster
     * shows for everyone (amendment A18). It is `currentRideId != null` rather than
     * `state == TRACKING`, so a member paused at a junction still reads as riding.
     */
    private fun pushGroupPresence(location: Location) {
        if (!PresenceStreamPolicy.shouldPushPresence(currentState, presenceMode)) return
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (!PresenceStreamPolicy.isAccurateEnoughForPresence(accuracy)) return

        val battery = runCatching {
            (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()

        // The estimator needs the persona for its arrival radius (§2.9) — 40m on foot is right,
        // 40m in a car park is not — and this service is the only thing that knows it.
        groupSessionManager.currentPersona = trackingManager.selectedPersona.value.name
        groupSessionManager.updatePosition(
            lat = location.latitude,
            lng = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed else null,
            headingDeg = if (location.hasBearing()) location.bearing else null,
            batteryPercent = battery,
            moving = !motionSensorManager.isDeviceStationary(),
            riding = currentRideId != null,
        )
    }

    private fun pauseTracking() {
        updateState(TrackingState.PAUSED)
        isTimerEnabled = false
        motionSensorManager.stopListening()
        setPersistedPausedSession(true)
        lastLocation = null // prevent distance jumping when resumed
    }

    private fun resumeTracking() {
        if (StorageHealthMonitor.isLowStorage(this)) {
            enterStorageLowState()
            return
        }
        storageWarningShown = false
        updateState(TrackingState.TRACKING)
        motionSensorManager.startListening()
        setPersistedPausedSession(false)
        currentRideId?.let { rideId ->
            serviceScope.launch {
                currentPointCount = rideDao.getPointsForRide(rideId).firstOrNull()?.size ?: 0
            }
        }
        lastGpsTimeMs = System.currentTimeMillis()
        if (!locationHelper.startLocationTracking(locationCallback)) {
            handleLocationStartFailure()
            return
        }
        if (!isTimerEnabled) {
            startTimer()
        }
    }

    private fun handleForegroundStartFailure(error: Exception) {
        val app = application as TrackMeApp
        app.errorLogger.recordException(error)
        val outcome = ForegroundStartPolicy.classify(error, Build.VERSION.SDK_INT)
        app.abandonPersistedTrackingSession(outcome)
        updateState(TrackingState.IDLE)
        isTimerEnabled = false
        stopSelf()
    }

    private fun handleLocationStartFailure() {
        val error = SecurityException("Location permission was revoked while starting ride tracking")
        val app = application as TrackMeApp
        app.errorLogger.recordException(error)
        val outcome = ForegroundStartPolicy.classify(error, Build.VERSION.SDK_INT)
        app.abandonPersistedTrackingSession(outcome)
        // Keep the unfinished ride row and its points for OrphanedRideRecoveryManager. The
        // normal stop path finalizes/deletes rides, which is unsafe after a permission failure.
        stopTracking(preserveRideForRecovery = true)
    }

    private fun stopTracking(
        discardNearEmptyRide: Boolean = false,
        preserveRideForRecovery: Boolean = false
    ) {
        updateState(TrackingState.IDLE)
        isTimerEnabled = false
        motionSensorManager.stopListening()
        locationHelper.stopLocationTracking(locationCallback)
        
        val finalDistance = trackingManager.totalDistance.value.toDouble()
        val finalDuration = rideDuration
        val rideToProcess = currentRideId
        
        trackingManager.reset()
        rideDuration = 0L
        lastLocation = null
        currentRideId = null
        activeRideId = null
        setPersistedActiveSession(false)
        setPersistedPausedSession(false)
        
        // §2.6: "Stopping a ride does not leave the group — the member keeps seeing others and
        // keeps sharing presence until they explicitly leave or the group ends." So ending a ride
        // hands the foreground service over to presence rather than tearing it down. Ride cleanup
        // below is unchanged either way: a group must never affect the user's own ride (§8).
        val keepAliveForPresence = !PresenceStreamPolicy.canStopService(TrackingState.IDLE, presenceMode)
        if (keepAliveForPresence) {
            reconcileLocationStreams()
            postTrackingNotification(force = true)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        serviceScope.launch {
            if (liveShareManager.state.value.status == LiveShareStatus.ACTIVE || liveShareManager.state.value.stopOnRideEnd) {
                liveShareManager.stopSession("Ride ended by user.")
            }
            if (!preserveRideForRecovery) {
                rideToProcess?.let { rideId ->
                    finalizeRide(rideId, finalDistance, finalDuration, discardNearEmptyRide)
                }
            }
            if (!keepAliveForPresence) {
                stopSelf()
            }
        }
    }

    private fun updateState(newState: TrackingState) {
        val stateChanged = newState != currentState
        currentState = newState
        trackingManager.updateState(newState)
        if (stateChanged && newState != TrackingState.IDLE && newState != TrackingState.STORAGE_LOW) {
            postTrackingNotification(force = true)
        }
    }

    private fun hasPersistedActiveSession(): Boolean =
        getSharedPreferences(TRACKING_PREFS, Context.MODE_PRIVATE)
            .getBoolean(ACTIVE_TRACKING_SESSION_KEY, false)

    private fun setPersistedActiveSession(active: Boolean) {
        getSharedPreferences(TRACKING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ACTIVE_TRACKING_SESSION_KEY, active)
            .apply()
    }

    private fun hasPersistedPausedSession(): Boolean =
        getSharedPreferences(TRACKING_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PAUSED_TRACKING_SESSION_KEY, false)

    private fun setPersistedPausedSession(paused: Boolean) {
        getSharedPreferences(TRACKING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PAUSED_TRACKING_SESSION_KEY, paused)
            .apply()
    }

    /**
     * Reattaches the sticky service to the newest unfinished ride after process death.
     * GPS points are the source of truth for the restored path and HUD metrics; the next
     * live fix starts a fresh distance segment so downtime cannot create a jump.
     */
    private suspend fun restorePersistedRide(): Boolean {
        if (!hasPersistedActiveSession()) return false

        val ride = rideDao.getUncompletedRides().maxByOrNull { it.startTime }
        if (ride == null) {
            setPersistedActiveSession(false)
            return false
        }

        val points = rideDao.getPointsForRideSync(ride.id)
        trackingManager.reset()
        updateState(TrackingState.TRACKING)
        currentRideId = ride.id
        activeRideId = ride.id
        currentPointCount = points.size

        val persona = runCatching {
            `in`.shvms.trackme.domain.model.RidePersona.valueOf(ride.persona)
        }.getOrDefault(`in`.shvms.trackme.domain.model.RidePersona.AUTO)
        trackingManager.setSelectedPersona(persona)

        points.forEach { point ->
            trackingManager.addPathPoint(LatLng(point.latitude, point.longitude))
        }

        val now = System.currentTimeMillis()
        val restoredMetrics = TrackingSessionRestorer.calculate(ride.startTime, points, now)
        rideDuration = restoredMetrics.activeDurationMillis
        elapsedWallClockDuration = restoredMetrics.elapsedDurationMillis
        trackingManager.addDistance(restoredMetrics.distanceMeters)
        trackingManager.updateDuration(restoredMetrics.activeDurationMillis)
        trackingManager.updateElapsedDuration(restoredMetrics.elapsedDurationMillis)
        trackingManager.updateSpeed(restoredMetrics.latestSpeedMetersPerSecond)
        trackingManager.setAutoPaused(restoredMetrics.isPaused)
        lastGpsTimeMs = now
        lastLocation = null
        return true
    }

    private fun enterStorageLowState() {
        if (currentState == TrackingState.STORAGE_LOW && storageWarningShown) return
        storageWarningShown = true
        updateState(TrackingState.STORAGE_LOW)
        isTimerEnabled = false
        motionSensorManager.stopListening()
        locationHelper.stopLocationTracking(locationCallback)
        showStorageLowNotification()
    }

    private fun startTimer() {
        isTimerEnabled = true
        timeStarted = android.os.SystemClock.elapsedRealtime()
        serviceScope.launch {
            while (isTimerEnabled) {
                val currentTime = android.os.SystemClock.elapsedRealtime()
                val lapTime = currentTime - timeStarted
                elapsedWallClockDuration += lapTime
                if (!trackingManager.isAutoPaused.value) {
                    rideDuration += lapTime
                }
                timeStarted = currentTime
                trackingManager.updateDuration(rideDuration)
                trackingManager.updateElapsedDuration(elapsedWallClockDuration)
                
                if ((currentState == TrackingState.TRACKING || currentState == TrackingState.GPS_LOST || currentState == TrackingState.GPS_DISABLED) && lastGpsTimeMs > 0) {
                    val timeSinceLastGps = System.currentTimeMillis() - lastGpsTimeMs
                    trackingManager.updateTimeSinceLastGps(timeSinceLastGps)
                    if (timeSinceLastGps >= GPS_LOSS_TIMEOUT_MS) {
                        val wasAlreadyStale = !shouldEmitGpsPauseTelemetry(currentState)
                        updateState(
                            if (isLocationServiceEnabled()) TrackingState.GPS_LOST else TrackingState.GPS_DISABLED
                        )
                        if (!wasAlreadyStale) {
                            AnalyticsManager.trackLocationUpdatesPaused()
                        }
                    }
                } else {
                    trackingManager.updateTimeSinceLastGps(0L)
                }

                postTrackingNotification()
                
                delay(1000L)
            }
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            val gpsEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            val networkEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
            !areLocationProvidersUnavailable(gpsEnabled, networkEnabled)
        }
    }

    private fun getNotification(
        durationMillis: Long,
        distanceMeters: Float,
        speedMps: Float,
        state: TrackingState
    ): Notification {
        val strings = appStrings()
        val intent = android.content.Intent(this, `in`.shvms.trackme.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when (state) {
            TrackingState.PAUSED -> String.format(
                java.util.Locale.getDefault(),
                strings.notifTrackingPaused,
                formatTrackingNotificationDuration(durationMillis)
            )
            TrackingState.GPS_LOST, TrackingState.GPS_DISABLED -> strings.notifTrackingGpsSearching
            TrackingState.TRACKING -> {
                val imperial = (application as? TrackMeApp)?.preferencesManager?.unitSystem?.value == "imperial"
                String.format(
                    java.util.Locale.getDefault(),
                    strings.notifTrackingMetrics,
                    formatTrackingNotificationDuration(durationMillis),
                    `in`.shvms.trackme.domain.UnitFormatter.distance(
                        distanceMeters.toDouble().coerceAtLeast(0.0),
                        imperial,
                        decimals = 1
                    ),
                    `in`.shvms.trackme.domain.UnitFormatter.speed(
                        speedMps.toDouble().coerceAtLeast(0.0),
                        imperial
                    )
                )
            }
            else -> strings.notifTrackingText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(strings.notifTrackingTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * The presence-only notification — §4.6 and §16.2.
     *
     * *"The foreground notification text becomes honest about why the service is running."* When no
     * ride is recording, "TrackMe is recording your ride" would be plainly false, and this is the
     * exact surface the March-2026 foreground-service policy scrutinises. It also does the product
     * job §5.1.7 asks for: the user can see, without going looking, that they are visible and for
     * how long.
     *
     * No group name here. The notification is readable on a lock screen by anyone holding the
     * phone, and a group name is one of the things §5.3 encrypts precisely so it does not leak.
     */
    private fun buildPresenceNotification(): Notification {
        val strings = appStrings()
        val intent = android.content.Intent(this, `in`.shvms.trackme.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val remaining = groupSessionManager.state.value.expiresAtMillis - System.currentTimeMillis()
        val text = if (remaining > 0) {
            String.format(
                java.util.Locale.getDefault(),
                strings.notifGroupPresenceText,
                formatTrackingNotificationDuration(remaining),
            )
        } else {
            strings.notifGroupPresenceNoLimit
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(strings.notifGroupPresenceTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun postTrackingNotification(force: Boolean = false) {
        val state = currentState
        // Presence with no ride still owns the foreground notification — otherwise the service
        // would be running with stale "recording your ride" text, or none at all.
        val presenceOnly = presenceMode && state == TrackingState.IDLE
        if (!presenceOnly && (state == TrackingState.IDLE || state == TrackingState.STORAGE_LOW)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Foreground-service startup remains the system-owned path; avoid a rejected
            // incremental notify call when the user has denied optional notification access.
            return
        }

        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        val distanceMeters = trackingManager.totalDistance.value
        val previous = TrackingNotificationThrottle(
            lastNotifyElapsedMs = lastNotifyElapsedMs,
            lastNotifyDistanceMeters = lastNotifyDistanceMeters,
            lastNotifyState = lastNotifyState
        )
        if (!force && !shouldUpdateTrackingNotification(nowElapsedMs, distanceMeters, state, previous)) return

        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            if (presenceOnly) {
                buildPresenceNotification()
            } else {
                getNotification(
                    durationMillis = rideDuration,
                    distanceMeters = distanceMeters,
                    speedMps = trackingManager.currentSpeed.value,
                    state = state,
                )
            },
        )
        lastNotifyElapsedMs = nowElapsedMs
        lastNotifyDistanceMeters = distanceMeters
        lastNotifyState = state
    }

    /** Resolves user-facing notification content using the in-app language picker. */
    private fun appStrings(): AppStrings {
        val language = getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
            .getString("app_language", "en") ?: "en"
        return getAppStrings(language)
    }

    /**
     * Connects the sync loop's alert signals to a notification and a haptic.
     *
     * The wiring lives here because the tracking service is what stays alive with the screen off —
     * which is when a rider most needs to be told, and the whole reason E3 chose a notification over
     * an in-app banner.
     */
    private fun wireGroupAlerts() {
        val notifier = GroupAlertNotifier(this)
        notifier.ensureChannel(appStrings())
        groupSessionManager.onAlertSignal = { signal, memberName, code ->
            val s = appStrings()
            notifier.post(
                signal = signal,
                memberUid = memberName,
                memberName = memberName,
                statusLabel = s.statusLabelForCode(code) ?: code,
                groupName = groupSessionManager.state.value.groupName,
                strings = s,
            )
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackingChannel = NotificationChannel(
                CHANNEL_ID,
                getString(`in`.shvms.trackme.R.string.notification_channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(`in`.shvms.trackme.R.string.notification_channel_tracking_description)
            }
            val syncChannel = NotificationChannel(
                SYNC_CHANNEL_ID,
                getString(`in`.shvms.trackme.R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(`in`.shvms.trackme.R.string.notification_channel_sync_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(listOf(trackingChannel, syncChannel))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun finalizeRide(
        rideId: Long,
        finalDistance: Double,
        finalDuration: Long,
        discardNearEmptyRide: Boolean = false
    ) {
        // Consume the single per-ride SOS bit before any early return. History still records a
        // valid ride, while the transition prevents B1 from creating a reveal (and therefore B4
        // from chaining a review request) after an emergency flow.
        val suppressPostRideCelebrations = (application as TrackMeApp).emergencyManager
            .consumeRideSuppression()
        val rideWithPoints = rideDao.getRideWithPointsById(rideId)
        if (rideWithPoints != null) {
            val ride = rideWithPoints.ride
            val points = rideWithPoints.points
            
            if (points.isEmpty()) {
                rideDao.deletePointsForRide(rideId)
                rideDao.deleteRide(rideId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(applicationContext, "Ride was too short to save (no GPS data).", android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }

            if (discardNearEmptyRide && finalDistance < JUNK_RIDE_DISTANCE_METERS && finalDuration < JUNK_RIDE_DURATION_MILLIS) {
                rideDao.deletePointsForRide(rideId)
                rideDao.deleteRide(rideId)
                return
            }
            
            var activeTimeMs = 0L
            var maxSpeed = 0f
            
            if (points.size >= 2) {
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val curr = points[i]

                    if (curr.speed > maxSpeed) {
                        maxSpeed = curr.speed
                    }

                    val timestampDeltaMs = curr.timestamp - prev.timestamp
                    if (!curr.isPaused && !prev.isPaused && timestampDeltaMs > 0) {
                        activeTimeMs += timestampDeltaMs
                    }
                }
            } else if (points.size == 1) {
                maxSpeed = points[0].speed
            }

            // Keep the distance filtered by TrackingManager; recomputing raw point-to-point
            // distance here would count GPS drift and movement recorded during pauses.
            val avgSpeed = if (activeTimeMs > 0) (finalDistance / (activeTimeMs / 1000f)).toFloat() else 0f

            val persona = RideUtils.personaFromStoredName(ride.persona)
            val newTitle = if (RideUtils.isGeneratedTitle(ride.title, ride.startTime, persona)) {
                RideUtils.getDefaultTitle(ride.startTime, persona, maxSpeed * 3.6f)
            } else ride.title

            val calc = `in`.shvms.trackme.data.local.entity.PostRideCalculation(
                distance = finalDistance,
                maxSpeed = maxSpeed,
                avgSpeed = avgSpeed,
                pauseDuration = 0L
            )
            
            val finishedRide = ride.copy(
                endTime = System.currentTimeMillis(), 
                title = newTitle,
                postRideCalculation = calc
            )
            rideDao.updateRide(finishedRide)
            
            `in`.shvms.trackme.analytics.AnalyticsManager.trackRideCompleted(
                rideId = rideId.toString(),
                durationSeconds = activeTimeMs / 1000L,
                distanceKm = finalDistance / 1000.0
            )

            // A1: shared good-ride hook. Fold the just-saved ride into the aggregate store.
            // Best-effort and idempotent (keyed by ride ID) — must NEVER fail ride saving.
            // The returned RideStatsTransition is what B1 (reveal) / B2 (recap) / B3 (streak)
            // will consume.
            // Decision (decision_log 2026-07-20): NEVER credit sub-threshold "junk" rides to
            // retention stats, even when the user chose to keep them (discardNearEmptyRide=false).
            // Mirror iOS's guard so the eligibility rule is identical on both platforms.
            val isJunkRide = finalDistance < JUNK_RIDE_DISTANCE_METERS &&
                finalDuration < JUNK_RIDE_DURATION_MILLIS
            if (!isJunkRide) {
                try {
                    val app = application as? TrackMeApp
                    val transition = app?.rideStatsStore?.recordGoodRide(
                        `in`.shvms.trackme.domain.stats.GoodRideSummary(
                            rideId = rideId,
                            finishedAtMillis = finishedRide.endTime ?: System.currentTimeMillis(),
                            durationMillis = activeTimeMs,
                            distanceMeters = finalDistance,
                            suppressPostRideCelebrations = suppressPostRideCelebrations
                        )
                    )
                    // B1: pick the bounded reveal from the transition and persist it as a durable
                    // one-shot. Home consumes it once when foreground; presentation + telemetry
                    // happen there, never here. Skipped for idempotent replays (select -> null).
                    if (transition != null) {
                        `in`.shvms.trackme.domain.stats.RevealSelector.select(transition)?.let { reveal ->
                            app.pendingRevealStore.put(reveal)
                        }
                        // B3: the streak state machine transitions only on the first ride of a
                        // week — emit weekly_streak_updated then (an attempt-accurate state
                        // event; `froze` = a single missed week was auto-forgiven this rollover).
                        if (transition.isFirstRideOfWeek) {
                            `in`.shvms.trackme.analytics.AnalyticsManager.trackWeeklyStreakUpdated(
                                streakWeeks = transition.streakWeeks,
                                froze = transition.streakFroze
                            )
                        }
                    }
                } catch (t: Throwable) {
                    (application as? TrackMeApp)?.errorLogger?.recordException(t)
                }
            }

            val prefs = getSharedPreferences("trackme_prefs", android.content.Context.MODE_PRIVATE)
            val disablePostProcessing = prefs.getBoolean("disable_gps_post_processing", false)
            
            val gpsProcessor = `in`.shvms.trackme.domain.processor.DefaultGPSProcessor()
            gpsProcessor.processRide(rideId, rideDao, !disablePostProcessing)

            val app = application as TrackMeApp
            app.firestoreSyncManager.uploadRide(rideId)
            
            val bcastIntent = Intent("in.shvms.trackme.RIDE_SAVED").setPackage(packageName)
            sendBroadcast(bcastIntent)
        }
    }

    private fun splitRide() {
        val oldRideId = currentRideId
        val finalDistance = trackingManager.totalDistance.value.toDouble()
        val finalDuration = rideDuration
        
        currentPointCount = 0
        rideDuration = 0L
        elapsedWallClockDuration = 0L
        adaptiveAutoPauseEngine.reset()
        timeStarted = android.os.SystemClock.elapsedRealtime()
        trackingManager.reset()
        
        serviceScope.launch {
            oldRideId?.let { rideId ->
                finalizeRide(rideId, finalDistance, finalDuration)
            }

            (application as TrackMeApp).emergencyManager.beginRideSession()
            
            val startTime = System.currentTimeMillis()
            val rideId = rideDao.insertRide(
                RideEntity(
                    startTime = startTime,
                    title = RideUtils.getDefaultTitle(startTime, trackingManager.selectedPersona.value) + " (Part 2)",
                    persona = trackingManager.selectedPersona.value.name
                )
            )
            currentRideId = rideId
            activeRideId = rideId
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val strings = appStrings()
            val splitNotification = NotificationCompat.Builder(this@TrackingService, SYNC_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(strings.notifAutoSplitTitle)
                .setContentText(strings.notifAutoSplitText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notificationManager.notify(3, splitNotification)
        }
    }
    
    private fun showPointLimitWarning() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val strings = appStrings()
        val warningNotification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(strings.notifLongRideTitle)
            .setContentText(strings.notifLongRideText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(2, warningNotification)
    }

    private fun showStorageLowNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val strings = appStrings()
        val warningNotification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(strings.notifStorageLowTitle)
            .setContentText(strings.notifStorageLowText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(STORAGE_WARNING_NOTIFICATION_ID, warningNotification)
    }

    private fun formatTrackingNotificationDuration(durationMillis: Long): String =
        formatTrackingNotificationDurationValue(durationMillis)

    companion object {
        const val ACTION_START_OR_RESUME_SERVICE = "ACTION_START_OR_RESUME_SERVICE"
        const val ACTION_PAUSE_SERVICE = "ACTION_PAUSE_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_DISCARD_NEAR_EMPTY_RIDE = "ACTION_DISCARD_NEAR_EMPTY_RIDE"

        /** §4.6: presence composes with the recorder rather than replacing it. */
        const val ACTION_START_GROUP_PRESENCE = "ACTION_START_GROUP_PRESENCE"
        const val ACTION_STOP_GROUP_PRESENCE = "ACTION_STOP_GROUP_PRESENCE"
        const val JUNK_RIDE_DISTANCE_METERS = 10.0
        const val JUNK_RIDE_DURATION_MILLIS = 2 * 60 * 1000L
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
        const val SYNC_CHANNEL_ID = "sync_channel"
        const val STORAGE_WARNING_NOTIFICATION_ID = 4
        const val GPS_LOSS_TIMEOUT_MS = 15_000L
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 15_000L
        const val NOTIFICATION_DISTANCE_DELTA_METERS = 25f
        const val TRACKING_PREFS = "trackme_prefs"
        const val ACTIVE_TRACKING_SESSION_KEY = "active_tracking_session"
        const val PAUSED_TRACKING_SESSION_KEY = "paused_tracking_session"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeRideId: Long? = null
            private set
    }
}
