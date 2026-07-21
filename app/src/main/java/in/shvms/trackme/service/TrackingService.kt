package `in`.shvms.trackme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.utils.RideUtils
import `in`.shvms.trackme.utils.StorageHealthMonitor
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

enum class TrackingState {
    IDLE, TRACKING, PAUSED, GPS_LOST, GPS_DISABLED, STORAGE_LOW
}

class TrackingService : Service() {

    private lateinit var locationHelper: LocationHelper
    private lateinit var rideDao: RideDao
    private lateinit var trackingManager: TrackingManager
    private lateinit var liveShareManager: LiveShareManager
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

    private val adaptiveAutoPauseEngine = `in`.shvms.trackme.domain.processor.AdaptiveAutoPauseEngine()
    private lateinit var motionSensorManager: MotionSensorManager

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            val location = result.lastLocation ?: return

            // 1. Strict GPS Accuracy Filter: discard indoor/multipath bounce (> 22 meters inaccuracy)
            if (location.hasAccuracy() && location.accuracy > 22.0f) {
                return
            }

            if (currentState == TrackingState.GPS_LOST || currentState == TrackingState.GPS_DISABLED) {
                updateState(TrackingState.TRACKING)
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
            ACTION_PAUSE_SERVICE -> pauseTracking()
            ACTION_STOP_SERVICE -> stopTracking()
            ACTION_DISCARD_NEAR_EMPTY_RIDE -> stopTracking(discardNearEmptyRide = true)
            null -> {
                // START_STICKY recreates the service with a null intent after process death.
                // Only restore a session that was explicitly marked active by the service.
                if (hasPersistedActiveSession() && currentState == TrackingState.IDLE) {
                    startForegroundService()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, getNotification())

        if (StorageHealthMonitor.isLowStorage(this)) {
            enterStorageLowState()
            return
        }

        updateState(TrackingState.TRACKING)
        currentPointCount = 0
        lastGpsTimeMs = System.currentTimeMillis()
        motionSensorManager.startListening()
        
        serviceScope.launch {
            try {
                if (!restorePersistedRide()) {
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
                    locationHelper.startLocationTracking(locationCallback)
                }
            } catch (_: SQLiteException) {
                withContext(Dispatchers.Main.immediate) {
                    enterStorageLowState()
                }
            }
        }
        startTimer()
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
        locationHelper.startLocationTracking(locationCallback)
        if (!isTimerEnabled) {
            startTimer()
        }
    }

    private fun stopTracking(discardNearEmptyRide: Boolean = false) {
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
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        serviceScope.launch {
            if (liveShareManager.state.value.status == LiveShareStatus.ACTIVE || liveShareManager.state.value.stopOnRideEnd) {
                liveShareManager.stopSession("Ride ended by user.")
            }
            rideToProcess?.let { rideId ->
                finalizeRide(rideId, finalDistance, finalDuration, discardNearEmptyRide)
            }
            stopSelf()
        }
    }

    private fun updateState(newState: TrackingState) {
        currentState = newState
        trackingManager.updateState(newState)
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
                        updateState(
                            if (isLocationServiceEnabled()) TrackingState.GPS_LOST else TrackingState.GPS_DISABLED
                        )
                    }
                } else {
                    trackingManager.updateTimeSinceLastGps(0L)
                }
                
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

    private fun getNotification(): Notification {
        val intent = android.content.Intent(this, `in`.shvms.trackme.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TrackMe is recording your ride")
            .setContentText("Ongoing Ride")
            .setContentIntent(pendingIntent)
            .build()
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
            val sosChannel = NotificationChannel(
                SOS_CHANNEL_ID,
                getString(`in`.shvms.trackme.R.string.notification_channel_sos),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(`in`.shvms.trackme.R.string.notification_channel_sos_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(listOf(trackingChannel, syncChannel, sosChannel))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun finalizeRide(
        rideId: Long,
        finalDistance: Double,
        finalDuration: Long,
        discardNearEmptyRide: Boolean = false
    ) {
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
                            distanceMeters = finalDistance
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
            val splitNotification = NotificationCompat.Builder(this@TrackingService, SYNC_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Ride Auto-Split")
                .setContentText("Your ride reached 9,000 points and was split automatically.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notificationManager.notify(3, splitNotification)
        }
    }
    
    private fun showPointLimitWarning() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val warningNotification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Long Ride Warning")
            .setContentText("Approaching limit. Ride will auto-split at 9,000 points.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(2, warningNotification)
    }

    private fun showStorageLowNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val warningNotification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Storage almost full")
            .setContentText("Tracking is paused. Free device storage, then tap Resume in TrackMe.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(STORAGE_WARNING_NOTIFICATION_ID, warningNotification)
    }

    companion object {
        const val ACTION_START_OR_RESUME_SERVICE = "ACTION_START_OR_RESUME_SERVICE"
        const val ACTION_PAUSE_SERVICE = "ACTION_PAUSE_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_DISCARD_NEAR_EMPTY_RIDE = "ACTION_DISCARD_NEAR_EMPTY_RIDE"
        const val JUNK_RIDE_DISTANCE_METERS = 10.0
        const val JUNK_RIDE_DURATION_MILLIS = 2 * 60 * 1000L
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
        const val SYNC_CHANNEL_ID = "sync_channel"
        const val SOS_CHANNEL_ID = "sos_channel"
        const val STORAGE_WARNING_NOTIFICATION_ID = 4
        const val GPS_LOSS_TIMEOUT_MS = 15_000L
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
