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
    private lateinit var wakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentState = TrackingState.IDLE
    private var currentRideId: Long? = null
    private var lastLocation: Location? = null

    private var isTimerEnabled = false
    private var timeStarted = 0L
    private var rideDuration = 0L
    private var currentPointCount = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            val location = result.lastLocation ?: return

            if (currentState == TrackingState.TRACKING) {
                val speed = if (location.hasSpeed()) location.speed else 0f
                trackingManager.updateSpeed(speed)

                val latLng = LatLng(location.latitude, location.longitude)
                trackingManager.addPathPoint(latLng)

                lastLocation?.let { prevLocation ->
                    val distance = prevLocation.distanceTo(location)
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
                                speed = speed,
                                timestamp = location.time,
                                isPaused = false
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
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationHelper = LocationHelper(this)
        val app = application as TrackMeApp
        rideDao = app.database.rideDao()
        trackingManager = app.trackingManager
        
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
        
        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            val rideId = rideDao.insertRide(
                RideEntity(
                    startTime = startTime,
                    title = RideUtils.getDefaultTitle(startTime)
                )
            )
            currentRideId = rideId
            locationHelper.startLocationTracking(locationCallback)
        }
        startTimer()
    }

    private fun pauseTracking() {
        updateState(TrackingState.PAUSED)
        isTimerEnabled = false
        lastLocation = null // prevent distance jumping when resumed
    }

    private fun resumeTracking() {
        updateState(TrackingState.TRACKING)
        currentRideId?.let { rideId ->
            serviceScope.launch {
                currentPointCount = rideDao.getPointsForRide(rideId).firstOrNull()?.size ?: 0
            }
        }
        startTimer()
    }

    private fun stopTracking() {
        updateState(TrackingState.IDLE)
        isTimerEnabled = false
        locationHelper.stopLocationTracking(locationCallback)
        
        val finalDistance = trackingManager.totalDistance.value.toDouble()
        val finalDuration = rideDuration
        val rideToProcess = currentRideId
        
        trackingManager.reset()
        rideDuration = 0L
        lastLocation = null
        currentRideId = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        serviceScope.launch {
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
                rideDuration += lapTime
                timeStarted = currentTime
                trackingManager.updateDuration(rideDuration)
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
            
            val distance = finalDistance
            val durationMs = finalDuration
            val avgSpeed = if (durationMs > 0) (distance / (durationMs / 1000f)).toFloat() else 0f
            val maxSpeed = points.maxOfOrNull { it.speed } ?: 0f
            
            val newTitle = if (ride.title == `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime)) {
                `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime, maxSpeed * 3.6f)
            } else ride.title

            val calc = `in`.shvms.trackme.data.local.entity.PostRideCalculation(
                distance = distance,
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
                    title = RideUtils.getDefaultTitle(startTime) + " (Part 2)"
                )
            )
            currentRideId = rideId
            
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
    }
}
