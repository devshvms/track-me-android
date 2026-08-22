package `in`.shvms.trackme.voice

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.voice.VoiceAction
import `in`.shvms.trackme.domain.voice.VoiceFailureReason
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceActionPolicyTest {
    @Test
    fun `only reversible Android actions are routed`() {
        assertEquals(VoiceAction.START, VoiceActionPolicy.actionFor(VoiceActionIntentContract.START))
        assertEquals(VoiceAction.PAUSE, VoiceActionPolicy.actionFor(VoiceActionIntentContract.PAUSE))
        assertEquals(VoiceAction.RESUME, VoiceActionPolicy.actionFor(VoiceActionIntentContract.RESUME))
        assertNull(VoiceActionPolicy.actionFor("actions.intent.STOP_EXERCISE"))
        assertNull(VoiceActionPolicy.actionFor(null))
    }

    @Test
    fun `start persona maps inline IDs raw BII values and bare ride`() {
        val cases = mapOf(
            null to RidePersona.AUTO,
            "ride" to RidePersona.AUTO,
            "Other" to RidePersona.AUTO,
            "PERSONA_WALK" to RidePersona.WALK,
            "Walking" to RidePersona.WALK,
            "Hiking" to RidePersona.WALK,
            "PERSONA_RUN" to RidePersona.RUN,
            "Running" to RidePersona.RUN,
            "jog" to RidePersona.RUN,
            "PERSONA_CYCLING" to RidePersona.CYCLING,
            "Biking" to RidePersona.CYCLING,
            "PERSONA_BIKE_DRIVE" to RidePersona.BIKE_DRIVE,
            "motorcycle" to RidePersona.BIKE_DRIVE,
            "PERSONA_CAR_DRIVE" to RidePersona.CAR_DRIVE,
            "driving" to RidePersona.CAR_DRIVE,
        )

        cases.forEach { (input, expected) ->
            assertEquals(input, expected, VoiceActionPolicy.personaFor(input))
        }
    }

    @Test
    fun `start executes only from idle and carries the persona`() {
        val ready = VoiceActionPolicy.decide(VoiceAction.START, TrackingState.IDLE, "Running")
            as VoiceActionDecision.Execute
        assertEquals(VoiceServiceCommand.START_OR_RESUME, ready.command)
        assertEquals(RidePersona.RUN, ready.persona)

        TrackingState.entries.filterNot { it == TrackingState.IDLE }.forEach { state ->
            val rejected = VoiceActionPolicy.decide(VoiceAction.START, state, "Running")
                as VoiceActionDecision.Reject
            assertEquals(state.name, VoiceFailureReason.INVALID_RIDE_STATE, rejected.reason)
        }
    }

    @Test
    fun `pause never starts a missing ride`() {
        val missing = VoiceActionPolicy.decide(VoiceAction.PAUSE, TrackingState.IDLE)
            as VoiceActionDecision.Reject
        assertEquals(VoiceFailureReason.NO_ACTIVE_RIDE, missing.reason)

        listOf(TrackingState.TRACKING, TrackingState.GPS_LOST, TrackingState.GPS_DISABLED).forEach { state ->
            val ready = VoiceActionPolicy.decide(VoiceAction.PAUSE, state)
                as VoiceActionDecision.Execute
            assertEquals(state.name, VoiceServiceCommand.PAUSE, ready.command)
        }
    }

    @Test
    fun `resume executes only from paused and never starts a missing ride`() {
        val missing = VoiceActionPolicy.decide(VoiceAction.RESUME, TrackingState.IDLE)
            as VoiceActionDecision.Reject
        assertEquals(VoiceFailureReason.NO_ACTIVE_RIDE, missing.reason)

        val ready = VoiceActionPolicy.decide(VoiceAction.RESUME, TrackingState.PAUSED)
            as VoiceActionDecision.Execute
        assertEquals(VoiceServiceCommand.START_OR_RESUME, ready.command)

        listOf(
            TrackingState.TRACKING,
            TrackingState.GPS_LOST,
            TrackingState.GPS_DISABLED,
            TrackingState.STORAGE_LOW,
        ).forEach { state ->
            val rejected = VoiceActionPolicy.decide(VoiceAction.RESUME, state)
                as VoiceActionDecision.Reject
            assertEquals(state.name, VoiceFailureReason.INVALID_RIDE_STATE, rejected.reason)
        }
    }

    @Test
    fun `spoken no-ride result stays English in every app locale`() {
        val expected = "You don't have a ride recording right now."
        assertEquals(expected, AppStrings().voiceNoActiveRide)
        assertEquals(expected, getAppStrings("hi").voiceNoActiveRide)
        assertEquals(expected, getAppStrings("de").voiceNoActiveRide)
    }

    @Test
    fun `shortcuts and manifest expose no end or visible activity path`() {
        val shortcuts = source("app/src/main/res/xml/shortcuts.xml")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val themes = source("app/src/main/res/values/themes.xml")
        val activity = source(
            "app/src/main/java/in/shvms/trackme/voice/VoiceActionActivity.kt",
        )

        assertEquals(3, Regex("<capability android:name=").findAll(shortcuts).count())
        assertTrue(shortcuts.contains("actions.intent.START_EXERCISE"))
        assertTrue(shortcuts.contains("actions.intent.PAUSE_EXERCISE"))
        assertTrue(shortcuts.contains("actions.intent.RESUME_EXERCISE"))
        assertFalse(shortcuts.contains("actions.intent.STOP_EXERCISE"))
        assertTrue(manifest.contains("android:name=\"android.app.shortcuts\""))
        assertTrue(manifest.contains("android:name=\".voice.VoiceActionActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/Theme.TrackMe.VoiceAction\""))
        assertTrue(themes.contains("android:style/Theme.NoDisplay"))
        assertTrue(activity.contains("finishAndRemoveTask()"))
        assertFalse(activity.contains("startActivity("))
        assertFalse(activity.contains("ACTION_STOP_SERVICE"))
    }

    private fun source(relative: String): String {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            File(directory, relative).takeIf(File::exists)?.let { return it.readText() }
            File(directory, relative.removePrefix("app/")).takeIf(File::exists)?.let { return it.readText() }
            directory = directory.parentFile
        }
        throw AssertionError("Source not found: $relative")
    }
}
