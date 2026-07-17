package `in`.shvms.trackme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.utils.RideUtils
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

enum class TrackingState {
    IDLE, TRACKING, PAUSED, GPS_LOST
}

class TrackingService : Service() {

    private lateinit var locationHelper: LocationHelper
    private lateinit var rideDao: RideDao
    private lateinit var trackingManager: TrackingManager
    private lateinit var liveShareManager: LiveShareManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentState = TrackingState.IDLE
    private var currentRideId: Long? = null
    private var lastLocation: Location? = null

    private var isTimerEnabled = false
    private var timeStarted = 0L
    private var rideDuration = 0L
    private var elapsedWallClockDuration = 0L
    private var currentPointCount = 0
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
                    serviceScope.launch {
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
        locationHelper = LocationHelper(this)
        motionSensorManager = MotionSensorManager(this)
        val app = application as TrackMeApp
        rideDao = app.database.rideDao()
        trackingManager = app.trackingManager
        liveShareManager = app.liveShareManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackMe::TrackingWakeLock")
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
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hour max lock
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, getNotification())
        
        updateState(TrackingState.TRACKING)
        currentPointCount = 0
        lastGpsTimeMs = System.currentTimeMillis()
        motionSensorManager.startListening()
        
        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            val rideId = rideDao.insertRide(
                RideEntity(
                    startTime = startTime,
                    title = RideUtils.getDefaultTitle(startTime),
                    persona = trackingManager.selectedPersona.value.name
                )
            )
            currentRideId = rideId
            activeRideId = rideId
            
            `in`.shvms.trackme.analytics.AnalyticsManager.trackRideStarted(
                rideId = rideId.toString(),
                startLat = lastLocation?.latitude ?: 0.0,
                startLng = lastLocation?.longitude ?: 0.0
            )
            
            locationHelper.startLocationTracking(locationCallback)
        }
        startTimer()
    }

    private fun pauseTracking() {
        updateState(TrackingState.PAUSED)
        isTimerEnabled = false
        motionSensorManager.stopListening()
        lastLocation = null // prevent distance jumping when resumed
    }

    private fun resumeTracking() {
        updateState(TrackingState.TRACKING)
        motionSensorManager.startListening()
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

    private fun stopTracking() {
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
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        serviceScope.launch {
            if (liveShareManager.state.value.status == LiveShareStatus.ACTIVE || liveShareManager.state.value.stopOnRideEnd) {
                liveShareManager.stopSession("Ride ended by user.")
            }
            rideToProcess?.let { rideId ->
                finalizeRide(rideId, finalDistance, finalDuration)
            }
            if (wakeLock.isHeld) wakeLock.release()
            stopSelf()
        }
    }

    private fun updateState(newState: TrackingState) {
        currentState = newState
        trackingManager.updateState(newState)
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
                
                if (currentState == TrackingState.TRACKING && lastGpsTimeMs > 0) {
                    trackingManager.updateTimeSinceLastGps(System.currentTimeMillis() - lastGpsTimeMs)
                } else {
                    trackingManager.updateTimeSinceLastGps(0L)
                }
                
                delay(1000L)
            }
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun finalizeRide(rideId: Long, finalDistance: Double, finalDuration: Long) {
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
            
            val newTitle = if (ride.title == `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime)) {
                `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime, maxSpeed * 3.6f)
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
                distanceKm = (finalDistance / 1000.0).toFloat()
            )
            
            val prefs = getSharedPreferences("trackme_prefs", android.content.Context.MODE_PRIVATE)
            val disablePostProcessing = prefs.getBoolean("disable_gps_post_processing", false)
            
            val gpsProcessor = `in`.shvms.trackme.domain.processor.DefaultGPSProcessor()
            gpsProcessor.processRide(rideId, rideDao, !disablePostProcessing)

            val app = application as TrackMeApp
            app.firestoreSyncManager.uploadRide(rideId)
            
            val bcastIntent = Intent("in.shvms.trackme.RIDE_SAVED")
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
                    title = RideUtils.getDefaultTitle(startTime) + " (Part 2)",
                    persona = trackingManager.selectedPersona.value.name
                )
            )
            currentRideId = rideId
            activeRideId = rideId
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val splitNotification = NotificationCompat.Builder(this@TrackingService, CHANNEL_ID)
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
        val warningNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Long Ride Warning")
            .setContentText("Approaching limit. Ride will auto-split at 9,000 points.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(2, warningNotification)
    }

    companion object {
        const val ACTION_START_OR_RESUME_SERVICE = "ACTION_START_OR_RESUME_SERVICE"
        const val ACTION_PAUSE_SERVICE = "ACTION_PAUSE_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"

        @Volatile
        var activeRideId: Long? = null
            private set
    }
}
