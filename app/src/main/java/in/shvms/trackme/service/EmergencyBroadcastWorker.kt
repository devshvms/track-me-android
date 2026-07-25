package `in`.shvms.trackme.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.BatteryManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import `in`.shvms.trackme.data.local.dao.EmergencyDao
import `in`.shvms.trackme.data.local.entity.EmergencyContactEntity
import `in`.shvms.trackme.data.local.entity.EmergencySettingsEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.remote.FirestoreSyncManager
import `in`.shvms.trackme.ui.localization.getAppStrings
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import android.annotation.SuppressLint
import java.util.Locale

class EmergencyBroadcastWorker(
    private val context: Context,
    private val emergencyDao: EmergencyDao,
    private val trackingManager: TrackingManager,
    private val emergencyManager: EmergencyManager,
    private val firestoreSyncManager: FirestoreSyncManager,
    private val errorLogger: `in`.shvms.trackme.utils.logger.ErrorLogger
) {
    private enum class SmsSendResult {
        ACCEPTED,
        REJECTED,
        UNKNOWN
    }

    private data class BroadcastResult(
        val accepted: Int,
        val failed: Int
    )

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingIntentRequestCode = 0

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
            val settings = emergencyDao.getSettings() ?: run {
                postEmergencyNotification(setupFailure = true)
                return@launch
            }
            if (!settings.isSetupComplete) {
                postEmergencyNotification(setupFailure = true)
                return@launch
            }

            val contacts = emergencyDao.getContacts()
            if (contacts.isEmpty()) {
                postEmergencyNotification(setupFailure = true)
                return@launch
            }

            val emergencyStartTime = System.currentTimeMillis()
            var messagesSentThisSession = 0

            while (isActive) {
                val result = broadcast(settings, contacts)
                messagesSentThisSession += result.accepted
                if (messagesSentThisSession <= AppConfig.MAX_HAPTIC_MESSAGES && result.accepted > 0) {
                    vibrate()
                }
                postEmergencyNotification(result)
                
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
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(SOS_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private suspend fun broadcast(settings: EmergencySettingsEntity, contacts: List<EmergencyContactEntity>): BroadcastResult {
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

        var accepted = 0
        var failed = 0
        contacts.forEach { contact ->
            try {
                if (settings.isSetupComplete) {
                    when (sendSms(smsManager, contact.phoneNumber, smsMessage)) {
                        SmsSendResult.ACCEPTED -> {
                            accepted++
                            firestoreSyncManager.logEmergencyMessage(System.currentTimeMillis(), smsMessage, contact.phoneNumber, "EMERGENCY")
                            Log.d("EmergencyWorker", "Submitted SMS emergency message to ***${contact.phoneNumber.takeLast(4)}")
                        }
                        SmsSendResult.REJECTED, SmsSendResult.UNKNOWN -> failed++
                    }
                }
            } catch (e: Exception) {
                errorLogger.log("Failed to send emergency broadcast")
                errorLogger.recordException(e)
                failed++
            }
        }
        return BroadcastResult(accepted = accepted, failed = failed)
    }

    private suspend fun sendSms(smsManager: SmsManager, phoneNumber: String, message: String): SmsSendResult {
        val requestCode = pendingIntentRequestCode++
        val action = "${context.packageName}.SMS_SENT_$requestCode"
        val result = CompletableDeferred<SmsSendResult>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                result.complete(
                    if (getResultCode() == Activity.RESULT_OK) {
                        SmsSendResult.ACCEPTED
                    } else {
                        SmsSendResult.REJECTED
                    }
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val sentIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, null)
            withTimeoutOrNull(SMS_RESULT_TIMEOUT_MS) { result.await() } ?: SmsSendResult.UNKNOWN
        } catch (e: Exception) {
            SmsSendResult.REJECTED
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun postEmergencyNotification(
        result: BroadcastResult? = null,
        setupFailure: Boolean = false
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    TrackingService.SOS_CHANNEL_ID,
                    context.getString(`in`.shvms.trackme.R.string.notification_channel_sos),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(`in`.shvms.trackme.R.string.notification_channel_sos_description)
                }
            )
        }

        val language = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
            .getString("app_language", "en") ?: "en"
        val strings = getAppStrings(language)
        val message = when {
            setupFailure -> strings.sosNotifSetupFailure
            result == null -> strings.sosNotifSetupFailure
            result.accepted > 0 && result.failed == 0 -> String.format(
                Locale.getDefault(),
                strings.sosNotifSubmitted,
                result.accepted
            )
            result.accepted > 0 -> String.format(
                Locale.getDefault(),
                strings.sosNotifPartial,
                result.accepted,
                result.failed
            )
            else -> strings.sosNotifFailed
        }
        val intent = Intent(context, `in`.shvms.trackme.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SOS_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, TrackingService.SOS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(strings.sosNotifTitle)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(SOS_NOTIFICATION_ID, notification)
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

    private companion object {
        const val SOS_NOTIFICATION_ID = 4001
        const val SMS_RESULT_TIMEOUT_MS = 15_000L
    }
}
