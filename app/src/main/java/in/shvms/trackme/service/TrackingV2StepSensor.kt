package `in`.shvms.trackme.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

data class TrackingV2StepSnapshot(
    val available: Boolean,
    val cumulativeSteps: Long?,
    val lastStepAgeMillis: Long?,
    val cadenceHz: Float?,
)

/** Debug shadow pedometer. It never changes V1 and is registered only by a debug ride. */
class TrackingV2StepSensor(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val recentStepTimes = ArrayDeque<Long>()

    private var registered = false
    private var cumulativeSteps = 0L
    private var lastStepElapsedRealtimeMillis: Long? = null

    fun reset() {
        cumulativeSteps = 0L
        lastStepElapsedRealtimeMillis = null
        recentStepTimes.clear()
    }

    fun start(): Boolean {
        if (registered) return true
        if (!hasPermission() || stepDetector == null) return false
        registered = sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
        return registered
    }

    fun stop() {
        if (registered) sensorManager.unregisterListener(this)
        registered = false
    }

    fun snapshot(nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): TrackingV2StepSnapshot {
        val last = lastStepElapsedRealtimeMillis
        while (recentStepTimes.isNotEmpty() && nowElapsedRealtimeMillis - recentStepTimes.first() > CADENCE_WINDOW_MILLIS) {
            recentStepTimes.removeFirst()
        }
        val cadence = if (recentStepTimes.size >= 2) {
            val spanSeconds = (recentStepTimes.last() - recentStepTimes.first()) / 1_000f
            if (spanSeconds > 0f) (recentStepTimes.size - 1) / spanSeconds else null
        } else null
        return TrackingV2StepSnapshot(
            available = registered,
            cumulativeSteps = cumulativeSteps.takeIf { registered },
            lastStepAgeMillis = last?.let { (nowElapsedRealtimeMillis - it).coerceAtLeast(0L) },
            cadenceHz = cadence,
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR) return
        val now = SystemClock.elapsedRealtime()
        cumulativeSteps++
        lastStepElapsedRealtimeMillis = now
        recentStepTimes.addLast(now)
        while (recentStepTimes.size > MAX_RECENT_STEPS) recentStepTimes.removeFirst()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CADENCE_WINDOW_MILLIS = 8_000L
        private const val MAX_RECENT_STEPS = 20
    }
}
