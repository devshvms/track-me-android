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
        startTimer()
    }

    private fun stopTracking() {
        updateState(TrackingState.IDLE)
        isTimerEnabled = false
        locationHelper.stopLocationTracking(locationCallback)
        
        val finalDistance = trackingManager.totalDistance.value.toDouble()
        val finalDuration = rideDuration
        
        currentRideId?.let { rideId ->
            serviceScope.launch {
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
                    val isPostProcessingEnabled = prefs.getBoolean("enable_gps_post_processing", false)
                    
                    val gpsProcessor = `in`.shvms.trackme.domain.processor.DefaultGPSProcessor()
                    gpsProcessor.processRide(rideId, rideDao, isPostProcessingEnabled)

                    val app = application as TrackMeApp
                    app.firestoreSyncManager.uploadRide(rideId)
                }
            }
        }
        
        trackingManager.reset()
        rideDuration = 0L
        lastLocation = null
        currentRideId = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wakeLock.isHeld) wakeLock.release()
        stopSelf()
    }

    private fun updateState(newState: TrackingState) {
        currentState = newState
        trackingManager.updateState(newState)
    }

    private fun startTimer() {
        isTimerEnabled = true
        timeStarted = System.currentTimeMillis()
        serviceScope.launch {
            while (isTimerEnabled) {
                val lapTime = System.currentTimeMillis() - timeStarted
                rideDuration += lapTime
                timeStarted = System.currentTimeMillis()
                trackingManager.updateDuration(rideDuration)
                delay(1000L)
            }
        }
    }

    private fun getNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TrackMe is recording your ride")
            .setContentText("Ongoing Ride")
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

    companion object {
        const val ACTION_START_OR_RESUME_SERVICE = "ACTION_START_OR_RESUME_SERVICE"
        const val ACTION_PAUSE_SERVICE = "ACTION_PAUSE_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tracking_channel"
    }
}
