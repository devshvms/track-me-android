package `in`.shvms.trackme.domain.config

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.processor.AutoPauseThresholds
import `in`.shvms.trackme.domain.processor.InferredActivityType

/**
 * Centralized developer configuration file for Persona-Based Live Auto-Pause values.
 * Adjust these thresholds to tune sensitivity for different ride personas.
 */
object PersonaAutoPauseConfig {

    // --- WALK Persona Configuration ---
    const val WALK_PAUSE_KMH = 1.2f
    const val WALK_RESUME_KMH = 2.2f
    const val WALK_STILLNESS_MS = 5000L
    const val WALK_DISTANCE_M = 4.0f

    // --- RUN Persona Configuration ---
    const val RUN_PAUSE_KMH = 2.0f
    const val RUN_RESUME_KMH = 3.5f
    const val RUN_STILLNESS_MS = 4000L
    const val RUN_DISTANCE_M = 5.0f

    // --- CYCLING Persona Configuration ---
    const val CYCLING_PAUSE_KMH = 2.5f
    const val CYCLING_RESUME_KMH = 5.0f
    const val CYCLING_STILLNESS_MS = 4000L
    const val CYCLING_DISTANCE_M = 6.0f

    // --- BIKE / MOTORCYCLE Persona Configuration ---
    const val BIKE_DRIVE_PAUSE_KMH = 3.0f
    const val BIKE_DRIVE_RESUME_KMH = 6.0f
    const val BIKE_DRIVE_STILLNESS_MS = 3000L
    const val BIKE_DRIVE_DISTANCE_M = 8.0f

    // --- CAR DRIVE Persona Configuration ---
    const val CAR_DRIVE_PAUSE_KMH = 4.0f
    const val CAR_DRIVE_RESUME_KMH = 7.0f
    const val CAR_DRIVE_STILLNESS_MS = 3000L
    const val CAR_DRIVE_DISTANCE_M = 10.0f

    fun getThresholdsForPersona(
        persona: RidePersona,
        currentInferredActivity: InferredActivityType = InferredActivityType.RUN_OR_TREK
    ): AutoPauseThresholds {
        return when (persona) {
            RidePersona.WALK -> AutoPauseThresholds(
                pauseSpeedMps = WALK_PAUSE_KMH / 3.6f,
                resumeSpeedMps = WALK_RESUME_KMH / 3.6f,
                requiredStillnessMs = WALK_STILLNESS_MS,
                distanceVariationM = WALK_DISTANCE_M
            )
            RidePersona.RUN -> AutoPauseThresholds(
                pauseSpeedMps = RUN_PAUSE_KMH / 3.6f,
                resumeSpeedMps = RUN_RESUME_KMH / 3.6f,
                requiredStillnessMs = RUN_STILLNESS_MS,
                distanceVariationM = RUN_DISTANCE_M
            )
            RidePersona.CYCLING -> AutoPauseThresholds(
                pauseSpeedMps = CYCLING_PAUSE_KMH / 3.6f,
                resumeSpeedMps = CYCLING_RESUME_KMH / 3.6f,
                requiredStillnessMs = CYCLING_STILLNESS_MS,
                distanceVariationM = CYCLING_DISTANCE_M
            )
            RidePersona.BIKE_DRIVE -> AutoPauseThresholds(
                pauseSpeedMps = BIKE_DRIVE_PAUSE_KMH / 3.6f,
                resumeSpeedMps = BIKE_DRIVE_RESUME_KMH / 3.6f,
                requiredStillnessMs = BIKE_DRIVE_STILLNESS_MS,
                distanceVariationM = BIKE_DRIVE_DISTANCE_M
            )
            RidePersona.CAR_DRIVE -> AutoPauseThresholds(
                pauseSpeedMps = CAR_DRIVE_PAUSE_KMH / 3.6f,
                resumeSpeedMps = CAR_DRIVE_RESUME_KMH / 3.6f,
                requiredStillnessMs = CAR_DRIVE_STILLNESS_MS,
                distanceVariationM = CAR_DRIVE_DISTANCE_M
            )
            RidePersona.AUTO -> {
                when (currentInferredActivity) {
                    InferredActivityType.DRIVE_OR_BIKE -> AutoPauseThresholds(
                        pauseSpeedMps = BIKE_DRIVE_PAUSE_KMH / 3.6f,
                        resumeSpeedMps = BIKE_DRIVE_RESUME_KMH / 3.6f,
                        requiredStillnessMs = BIKE_DRIVE_STILLNESS_MS,
                        distanceVariationM = BIKE_DRIVE_DISTANCE_M
                    )
                    InferredActivityType.CYCLING -> AutoPauseThresholds(
                        pauseSpeedMps = CYCLING_PAUSE_KMH / 3.6f,
                        resumeSpeedMps = CYCLING_RESUME_KMH / 3.6f,
                        requiredStillnessMs = CYCLING_STILLNESS_MS,
                        distanceVariationM = CYCLING_DISTANCE_M
                    )
                    InferredActivityType.RUN_OR_TREK -> AutoPauseThresholds(
                        pauseSpeedMps = RUN_PAUSE_KMH / 3.6f,
                        resumeSpeedMps = RUN_RESUME_KMH / 3.6f,
                        requiredStillnessMs = RUN_STILLNESS_MS,
                        distanceVariationM = RUN_DISTANCE_M
                    )
                }
            }
        }
    }
}
