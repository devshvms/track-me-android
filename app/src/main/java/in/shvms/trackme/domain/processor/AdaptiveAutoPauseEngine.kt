package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.config.PersonaAutoPauseConfig
import `in`.shvms.trackme.domain.model.RidePersona
import kotlin.math.max

enum class InferredActivityType(val displayName: String, val iconEmoji: String) {
    DRIVE_OR_BIKE("Motorcycle / Drive", "🏍️"),
    CYCLING("Cycling", "🚴"),
    RUN_OR_TREK("Run / Trek", "🏃")
}

data class AutoPauseThresholds(
    val pauseSpeedMps: Float,
    val resumeSpeedMps: Float,
    val requiredStillnessMs: Long,
    val distanceVariationM: Float = 4.0f
)

/**
 * Intelligent Adaptive Auto-Pause Engine.
 * Dynamically infers the activity profile or uses explicitly selected RidePersona
 * and applies customizable hysteresis thresholds to prevent stat distortion
 * at traffic lights, toll plazas, or hydration stops.
 */
class AdaptiveAutoPauseEngine {
    private var currentActivity = InferredActivityType.RUN_OR_TREK
    private var peakSpeedMps = 0f
    private var lowSpeedStartTimestampMs: Long? = null

    fun updateActivityProfile(currentSpeedMps: Float): InferredActivityType {
        peakSpeedMps = max(peakSpeedMps, currentSpeedMps)
        val peakKmh = peakSpeedMps * 3.6f

        currentActivity = when {
            peakKmh >= 45f -> InferredActivityType.DRIVE_OR_BIKE
            peakKmh >= 20f -> InferredActivityType.CYCLING
            else -> InferredActivityType.RUN_OR_TREK
        }
        return currentActivity
    }

    fun getThresholds(activity: InferredActivityType = currentActivity): AutoPauseThresholds {
        return when (activity) {
            InferredActivityType.DRIVE_OR_BIKE -> AutoPauseThresholds(
                pauseSpeedMps = 3.0f / 3.6f,
                resumeSpeedMps = 5.0f / 3.6f,
                requiredStillnessMs = 3000L,
                distanceVariationM = 8.0f
            )
            InferredActivityType.CYCLING -> AutoPauseThresholds(
                pauseSpeedMps = 2.2f / 3.6f,
                resumeSpeedMps = 4.0f / 3.6f,
                requiredStillnessMs = 4000L,
                distanceVariationM = 6.0f
            )
            InferredActivityType.RUN_OR_TREK -> AutoPauseThresholds(
                pauseSpeedMps = 1.2f / 3.6f,
                resumeSpeedMps = 2.2f / 3.6f,
                requiredStillnessMs = 5000L,
                distanceVariationM = 4.0f
            )
        }
    }

    fun getThresholdsForPersona(
        persona: RidePersona
    ): AutoPauseThresholds {
        return PersonaAutoPauseConfig.getThresholdsForPersona(persona, currentActivity)
    }

    /**
     * Evaluates real-time GPS speed against the adaptive hysteresis state machine.
     * Returns true if ride should be AUTO-PAUSED, false if ACTIVE TRACKING.
     */
    fun evaluateAutoPause(
        currentSpeedMps: Float,
        currentlyPaused: Boolean,
        timestampMs: Long,
        persona: RidePersona = RidePersona.AUTO
    ): Boolean {
        updateActivityProfile(currentSpeedMps)
        val thresholds = getThresholdsForPersona(persona)

        if (currentlyPaused) {
            // Check if speed exceeds resume threshold significantly
            if (currentSpeedMps >= thresholds.resumeSpeedMps) {
                lowSpeedStartTimestampMs = null
                return false // Resume tracking
            }
            return true // Remain paused
        } else {
            // Currently active tracking: check if speed drops below pause threshold
            if (currentSpeedMps < thresholds.pauseSpeedMps) {
                val startMs = lowSpeedStartTimestampMs
                if (startMs == null) {
                    lowSpeedStartTimestampMs = timestampMs
                    // Immediately pause if speed is practically zero (< 0.5 m/s i.e. < 1.8 km/h)
                    if (currentSpeedMps < 0.5f) {
                        return true
                    }
                    return false
                } else if (timestampMs - startMs >= thresholds.requiredStillnessMs) {
                    return true // Trigger auto-pause after sustained stillness duration
                }
                return true // While in stillness countdown at very low speed, hold pause state
            } else {
                lowSpeedStartTimestampMs = null
                return false
            }
        }
    }

    fun reset() {
        currentActivity = InferredActivityType.RUN_OR_TREK
        peakSpeedMps = 0f
        lowSpeedStartTimestampMs = null
    }
}
