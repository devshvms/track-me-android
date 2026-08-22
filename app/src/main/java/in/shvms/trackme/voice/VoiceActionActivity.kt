package `in`.shvms.trackme.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.domain.voice.VoiceFailureReason
import `in`.shvms.trackme.domain.voice.VoiceSurface
import `in`.shvms.trackme.domain.voice.VoiceTelemetryContract
import `in`.shvms.trackme.service.TrackingService
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale

/**
 * App Actions requires an Activity fulfillment target. This NoDisplay trampoline performs no UI
 * work, never starts another Activity, and finishes in onCreate after dispatching a reversible
 * service command. TrackMe therefore never replaces the rider's navigation surface.
 */
class VoiceActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVoiceAction()
        finishAndRemoveTask()
    }

    private fun handleVoiceAction() {
        val action = VoiceActionPolicy.actionFor(intent?.action) ?: return
        val app = application as? TrackMeApp ?: return

        AnalyticsManager.trackVoiceEvent(
            VoiceTelemetryContract.commandInvoked(action.intent, VoiceSurface.ASSISTANT),
        )

        when (
            val decision = VoiceActionPolicy.decide(
                action = action,
                trackingState = app.trackingManager.trackingState.value,
                exerciseName = intent?.getStringExtra(VoiceActionIntentContract.EXERCISE_NAME),
            )
        ) {
            is VoiceActionDecision.Execute -> dispatch(app, decision)
            is VoiceActionDecision.Reject -> reject(decision)
        }
    }

    private fun dispatch(app: TrackMeApp, decision: VoiceActionDecision.Execute) {
        val previousPersona = app.trackingManager.selectedPersona.value
        if (decision.action == `in`.shvms.trackme.domain.voice.VoiceAction.START) {
            app.trackingManager.setSelectedPersona(checkNotNull(decision.persona))
        }

        val serviceAction = when (decision.command) {
            VoiceServiceCommand.START_OR_RESUME -> TrackingService.ACTION_START_OR_RESUME_SERVICE
            VoiceServiceCommand.PAUSE -> TrackingService.ACTION_PAUSE_SERVICE
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TrackingService::class.java).setAction(serviceAction),
            )
        } catch (error: RuntimeException) {
            if (decision.action == `in`.shvms.trackme.domain.voice.VoiceAction.START) {
                app.trackingManager.setSelectedPersona(previousPersona)
            }
            AnalyticsManager.trackVoiceEvent(
                VoiceTelemetryContract.commandFailed(decision.action.intent, VoiceFailureReason.UNAVAILABLE),
            )
            app.errorLogger.recordException(error)
        }
    }

    private fun reject(decision: VoiceActionDecision.Reject) {
        AnalyticsManager.trackVoiceEvent(
            VoiceTelemetryContract.commandFailed(decision.action.intent, decision.reason),
        )
        if (decision.reason == VoiceFailureReason.NO_ACTIVE_RIDE) {
            // Spoken copy intentionally ships in English only for 1.8.4 (HANDSFREE-01).
            VoiceActionSpeaker.speak(applicationContext, AppStrings().voiceNoActiveRide)
        }
    }
}

/** Process-scoped because Theme.NoDisplay requires the Activity to finish before it resumes. */
internal object VoiceActionSpeaker {
    private const val UTTERANCE_ID = "trackme_voice_action_result"
    private val lock = Any()
    private var engine: TextToSpeech? = null
    private var pendingText: String? = null

    fun speak(context: Context, text: String) {
        synchronized(lock) {
            pendingText = text
            engine?.let { ready ->
                speakNow(ready, text)
                pendingText = null
                return
            }

            engine = TextToSpeech(context.applicationContext) { status ->
                val initialized = synchronized(lock) { engine }
                if (status != TextToSpeech.SUCCESS || initialized == null) {
                    synchronized(lock) {
                        engine?.shutdown()
                        engine = null
                        pendingText = null
                    }
                    return@TextToSpeech
                }
                initialized.language = Locale.US
                val pending = synchronized(lock) {
                    pendingText.also { pendingText = null }
                }
                pending?.let { speakNow(initialized, it) }
            }
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }
}
