package `in`.shvms.trackme.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * IMU Motion Sensor Fusion Engine.
 * Uses hardware Accelerometer / Linear Acceleration sensors to detect whether
 * the phone is physically moving or stationary.
 *
 * This completely eliminates indoor GPS multipath drift: if the accelerometer
 * proves the phone is sitting still on a desk or mounted on a stopped vehicle,
 * noisy GPS coordinate jumps are ignored.
 */
class MotionSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val hasMotionSensor = linearAccelerationSensor != null

    private val _motionEnergy = MutableStateFlow(0f)
    val motionEnergy: StateFlow<Float> = _motionEnergy.asStateFlow()

    private var lastUpdateMs = 0L
    private var emaEnergy = 0f
    private val alpha = 0.15f // Exponential Moving Average smoothing factor

    // Gravity filter fallback if TYPE_ACCELEROMETER is used
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var hasReceivedSample = false

    fun startListening() {
        linearAccelerationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
        emaEnergy = 0f
        hasReceivedSample = false
        _motionEnergy.value = 0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        hasReceivedSample = true

        val now = System.currentTimeMillis()
        val linearX: Float
        val linearY: Float
        val linearZ: Float

        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            linearX = event.values[0]
            linearY = event.values[1]
            linearZ = event.values[2]
        } else {
            // High-pass filter to isolate linear acceleration from gravity
            val gAlpha = 0.8f
            gravityX = gAlpha * gravityX + (1 - gAlpha) * event.values[0]
            gravityY = gAlpha * gravityY + (1 - gAlpha) * event.values[1]
            gravityZ = gAlpha * gravityZ + (1 - gAlpha) * event.values[2]

            linearX = event.values[0] - gravityX
            linearY = event.values[1] - gravityY
            linearZ = event.values[2] - gravityZ
        }

        val magnitude = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble()).toFloat()

        // Smooth acceleration magnitude
        emaEnergy = alpha * magnitude + (1 - alpha) * emaEnergy

        if (now - lastUpdateMs >= 200L) {
            lastUpdateMs = now
            _motionEnergy.value = emaEnergy
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Returns true if the hardware accelerometer indicates the phone is physically stationary.
     * Threshold 0.18 m/s² cleanly separates resting on a table/stopped vehicle from actual motion.
     */
    fun isDeviceStationary(): Boolean {
        return shouldTreatDeviceAsStationary(hasMotionSensor, hasReceivedSample, emaEnergy)
    }
}

internal fun shouldTreatDeviceAsStationary(
    sensorAvailable: Boolean,
    sampleReceived: Boolean,
    motionEnergy: Float
): Boolean = sensorAvailable && sampleReceived && motionEnergy < 0.18f
