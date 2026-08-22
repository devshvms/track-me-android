package `in`.shvms.trackme.voice

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.voice.VoiceAction
import `in`.shvms.trackme.domain.voice.VoiceFailureReason
import `in`.shvms.trackme.service.TrackingState
import java.util.Locale

/** Intent actions declared in shortcuts.xml. STOP is deliberately absent (SCOPE_1.8.4 §3.4a). */
internal object VoiceActionIntentContract {
    const val START = "in.shvms.trackme.action.VOICE_START"
    const val PAUSE = "in.shvms.trackme.action.VOICE_PAUSE"
    const val RESUME = "in.shvms.trackme.action.VOICE_RESUME"
    const val EXERCISE_NAME = "exercise_name"
}

internal enum class VoiceServiceCommand {
    START_OR_RESUME,
    PAUSE,
}

internal sealed interface VoiceActionDecision {
    val action: VoiceAction

    data class Execute(
        override val action: VoiceAction,
        val command: VoiceServiceCommand,
        val persona: RidePersona? = null,
    ) : VoiceActionDecision

    data class Reject(
        override val action: VoiceAction,
        val reason: VoiceFailureReason,
    ) : VoiceActionDecision
}

/**
 * Pure routing for the Assistant adapter. Keeping state validation here prevents an invalid
 * PAUSE/RESUME intent from accidentally taking TrackingService's start path.
 */
internal object VoiceActionPolicy {
    fun actionFor(intentAction: String?): VoiceAction? = when (intentAction) {
        VoiceActionIntentContract.START -> VoiceAction.START
        VoiceActionIntentContract.PAUSE -> VoiceAction.PAUSE
        VoiceActionIntentContract.RESUME -> VoiceAction.RESUME
        else -> null
    }

    fun decide(
        action: VoiceAction,
        trackingState: TrackingState,
        exerciseName: String? = null,
    ): VoiceActionDecision = when (action) {
        VoiceAction.START -> if (trackingState == TrackingState.IDLE) {
            VoiceActionDecision.Execute(
                action = action,
                command = VoiceServiceCommand.START_OR_RESUME,
                persona = personaFor(exerciseName),
            )
        } else {
            VoiceActionDecision.Reject(action, VoiceFailureReason.INVALID_RIDE_STATE)
        }

        VoiceAction.PAUSE -> when (trackingState) {
            TrackingState.IDLE -> VoiceActionDecision.Reject(action, VoiceFailureReason.NO_ACTIVE_RIDE)
            TrackingState.TRACKING,
            TrackingState.GPS_LOST,
            TrackingState.GPS_DISABLED,
            -> VoiceActionDecision.Execute(action, VoiceServiceCommand.PAUSE)
            TrackingState.PAUSED,
            TrackingState.STORAGE_LOW,
            -> VoiceActionDecision.Reject(action, VoiceFailureReason.INVALID_RIDE_STATE)
        }

        VoiceAction.RESUME -> when (trackingState) {
            TrackingState.IDLE -> VoiceActionDecision.Reject(action, VoiceFailureReason.NO_ACTIVE_RIDE)
            TrackingState.PAUSED -> VoiceActionDecision.Execute(action, VoiceServiceCommand.START_OR_RESUME)
            TrackingState.TRACKING,
            TrackingState.GPS_LOST,
            TrackingState.GPS_DISABLED,
            TrackingState.STORAGE_LOW,
            -> VoiceActionDecision.Reject(action, VoiceFailureReason.INVALID_RIDE_STATE)
        }

        // Android never maps END into this policy. It remains in the shared contract for Siri.
        VoiceAction.END -> VoiceActionDecision.Reject(action, VoiceFailureReason.UNAVAILABLE)
    }

    /** Maps both inline-inventory IDs and Assistant's raw BII exercise values. */
    fun personaFor(exerciseName: String?): RidePersona {
        val normalized = exerciseName
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.replace(Regex("[^A-Z0-9]+"), "_")
            ?.trim('_')
            .orEmpty()

        return when (normalized) {
            "PERSONA_WALK", "WALK", "WALKING", "HIKE", "HIKING" -> RidePersona.WALK
            "PERSONA_RUN", "RUN", "RUNNING", "JOG", "JOGGING", "SPRINT" -> RidePersona.RUN
            "PERSONA_CYCLING", "BIKE", "BIKING", "BICYCLE", "CYCLE", "CYCLING" -> RidePersona.CYCLING
            "PERSONA_BIKE_DRIVE", "MOTORBIKE", "MOTORBIKING", "MOTORCYCLE", "MOTORCYCLING" ->
                RidePersona.BIKE_DRIVE
            "PERSONA_CAR_DRIVE", "CAR", "DRIVE", "DRIVING" -> RidePersona.CAR_DRIVE
            else -> RidePersona.AUTO
        }
    }
}
