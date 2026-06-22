package `in`.shvms.trackme.service

import android.content.Context
import android.telephony.SmsManager
import android.os.Build
import android.util.Log
import `in`.shvms.trackme.data.local.dao.EmergencyDao
import `in`.shvms.trackme.data.local.entity.EmergencyContactEntity
import `in`.shvms.trackme.data.local.entity.EmergencySettingsEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.remote.FirestoreSyncManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import android.annotation.SuppressLint

class EmergencyBroadcastWorker(
    private val context: Context,
    private val emergencyDao: EmergencyDao,
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val firestoreSyncManager: FirestoreSyncManager,
    private val errorLogger: `in`.shvms.trackme.utils.logger.ErrorLogger
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            emergencyManager.isEmergencyActive.collect { isActive ->
                if (isActive) {
                    startBroadcastLoop()
                } else {
                    stopBroadcastLoop()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopBroadcastLoop()
    }

    private var broadcastJob: Job? = null

    private fun startBroadcastLoop() {
        if (broadcastJob?.isActive == true) return
        broadcastJob = scope.launch {
            val settings = emergencyDao.getSettings() ?: return@launch
            if (!settings.isSetupComplete) return@launch

            val contacts = emergencyDao.getContacts()
            if (contacts.isEmpty()) return@launch

            val emergencyStartTime = System.currentTimeMillis()
            var messagesSentThisSession = 0

            while (isActive) {
                broadcast(settings, contacts) {
                    messagesSentThisSession++
                    if (messagesSentThisSession <= AppConfig.MAX_HAPTIC_MESSAGES) {
                        vibrate()
                    }
                }
                
                val elapsedMinutes = (System.currentTimeMillis() - emergencyStartTime) / 60000
                val delayMs = when {
                    elapsedMinutes < 10 -> 2 * 60 * 1000L // 2 min for first 10 min
                    elapsedMinutes < 60 -> 10 * 60 * 1000L // 10 min for next 1 hour
                    elapsedMinutes < 1440 -> 60 * 60 * 1000L // 1 hour for next 24 hours
                    else -> {
                        emergencyManager.stopEmergency()
                        return@launch
                    }
                }
                delay(delayMs)
            }
        }
    }

    private fun stopBroadcastLoop() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun broadcast(settings: EmergencySettingsEntity, contacts: List<EmergencyContactEntity>, onSmsSent: () -> Unit) {
        var lastPoint = trackingManager.pathPoints.value.lastOrNull()
        
        try {
            val freshLocation = withTimeoutOrNull(2000L) {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (location != null) {
                    com.google.android.gms.maps.model.LatLng(location.latitude, location.longitude)
                } else null
            }
            if (freshLocation != null) {
                lastPoint = freshLocation
            }
        } catch (e: Exception) {
            errorLogger.log("Failed to get fresh location in emergency")
            errorLogger.recordException(e)
        }
        
        fun buildMessage(template: String): String {
            var msg = template
            if (lastPoint != null) {
                val mapsLink = "https://maps.google.com/?q=${lastPoint!!.latitude},${lastPoint!!.longitude}"
                msg = msg.replace("[Location Link]", mapsLink)
            } else {
                msg = msg.replace("[Location Link]", "Location unknown")
            }
            
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val batteryPct: Float? = batteryStatus?.let { intent ->
                val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level * 100 / scale.toFloat()
            }
            val batteryString = batteryPct?.toInt()?.toString()?.plus("%") ?: "Unknown"
            
            val timeString = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val deviceName = Build.MODEL ?: "Unknown Device"
            
            msg = msg.replace("[Battery Percent]", batteryString)
            msg = msg.replace("[Device Name]", deviceName)
            msg = msg.replace("[Timestamp]", timeString)
            
            return msg
        }
        
        val smsMessage = buildMessage(settings.messageTemplate)

        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        contacts.forEach { contact ->
            try {
                if (settings.isSetupComplete) {
                    smsManager.sendTextMessage(contact.phoneNumber, null, smsMessage, null, null)
                    firestoreSyncManager.logEmergencyMessage(System.currentTimeMillis(), smsMessage, contact.phoneNumber, "EMERGENCY")
                    Log.d("EmergencyWorker", "Sent SMS emergency message to ${contact.phoneNumber}")
                    onSmsSent()
                }
            } catch (e: Exception) {
                errorLogger.log("Failed to send emergency broadcast")
                errorLogger.recordException(e)
            }
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(AppConfig.HAPTIC_VIBRATION_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(AppConfig.HAPTIC_VIBRATION_DURATION_MS)
            }
        } catch (e: Exception) {
            errorLogger.log("Failed to vibrate device")
            errorLogger.recordException(e)
        }
    }
}
