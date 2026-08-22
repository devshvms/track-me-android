package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.domain.group.AlertPolicy
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.ui.localization.AppStrings
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiPDashboardPolicyTest {
    @Test
    fun `only live ride states are eligible and the setting always wins`() {
        val expected = mapOf(
            TrackingState.IDLE to false,
            TrackingState.TRACKING to true,
            TrackingState.PAUSED to true,
            TrackingState.GPS_LOST to true,
            TrackingState.GPS_DISABLED to true,
            TrackingState.STORAGE_LOW to false,
        )

        expected.forEach { (state, eligible) ->
            assertEquals(
                state.name,
                eligible,
                PiPModePolicy.isEligible(state.toPiPRideState(), enabled = true),
            )
            assertFalse(PiPModePolicy.isEligible(state.toPiPRideState(), enabled = false))
        }
    }

    @Test
    fun `remote control is exactly pause or resume and never stop`() {
        assertEquals(
            setOf(PiPRemoteActionKind.PAUSE, PiPRemoteActionKind.RESUME),
            PiPRemoteActionKind.entries.toSet(),
        )
        assertEquals(PiPRemoteActionKind.PAUSE, PiPModePolicy.remoteAction(PiPRideState.RECORDING))
        assertEquals(PiPRemoteActionKind.PAUSE, PiPModePolicy.remoteAction(PiPRideState.GPS_LOST))
        assertEquals(PiPRemoteActionKind.RESUME, PiPModePolicy.remoteAction(PiPRideState.PAUSED))
        assertNull(PiPModePolicy.remoteAction(PiPRideState.INACTIVE))
    }

    @Test
    fun `walk and run use pace while every other persona uses speed`() {
        val expected = mapOf(
            RidePersona.AUTO to PiPSecondaryMetric.SPEED,
            RidePersona.WALK to PiPSecondaryMetric.PACE,
            RidePersona.RUN to PiPSecondaryMetric.PACE,
            RidePersona.CYCLING to PiPSecondaryMetric.SPEED,
            RidePersona.BIKE_DRIVE to PiPSecondaryMetric.SPEED,
            RidePersona.CAR_DRIVE to PiPSecondaryMetric.SPEED,
        )
        assertEquals(expected, RidePersona.entries.associateWith(PiPMetricPolicy::secondaryMetric))
    }

    @Test
    fun `strip is absent when quiet and recorder warnings follow precedence`() {
        assertNull(PiPStripPolicy.select(PiPRideState.RECORDING, isAutoPaused = false, alert = null))
        assertEquals(
            PiPStripState.AutoPaused,
            PiPStripPolicy.select(PiPRideState.RECORDING, isAutoPaused = true, alert = null),
        )
        assertEquals(
            PiPStripState.Paused,
            PiPStripPolicy.select(PiPRideState.PAUSED, isAutoPaused = true, alert = null),
        )
        assertEquals(
            PiPStripState.GpsLost,
            PiPStripPolicy.select(PiPRideState.GPS_LOST, isAutoPaused = true, alert = null),
        )
    }

    @Test
    fun `approved alert signal overrides ride strip without positional content`() {
        val raised = alert(AlertPolicy.Signal.ALERT_RAISED)
        val resolved = alert(AlertPolicy.Signal.ALERT_RESOLVED)
        assertEquals(
            PiPStripState.AlertRaised(raised),
            PiPStripPolicy.select(PiPRideState.GPS_LOST, isAutoPaused = true, alert = raised),
        )
        assertEquals(
            PiPStripState.AlertResolved(resolved),
            PiPStripPolicy.select(PiPRideState.PAUSED, isAutoPaused = true, alert = resolved),
        )

        val display = PiPDashboardPolicy.build(
            rideState = PiPRideState.RECORDING,
            distanceMeters = 2_500f,
            speedMps = 5f,
            persona = RidePersona.RUN,
            isAutoPaused = false,
            imperial = false,
            alert = raised,
            strings = AppStrings(),
        ).strip
        assertEquals("Alice · Need help", display?.text)
        assertEquals(PiPStripKind.ALERT, display?.kind)
        assertFalse(display?.text.orEmpty().contains("coordinate", ignoreCase = true))
        assertFalse(display?.text.orEmpty().contains("ahead", ignoreCase = true))
        assertFalse(display?.text.orEmpty().contains("km", ignoreCase = true))
    }

    @Test
    fun `dashboard metrics use the shared foreground formatter`() {
        val metric = PiPDashboardPolicy.build(
            rideState = PiPRideState.RECORDING,
            distanceMeters = 1_234f,
            speedMps = 1000f / (5 * 60),
            persona = RidePersona.RUN,
            isAutoPaused = false,
            imperial = false,
            alert = null,
            strings = AppStrings(),
        )

        // Value and unit are split so the window can render the unit small instead of clipping it
        // off a 30sp string — but recombined they must still be exactly what the shared formatter
        // produces, which is what this test is named for.
        assertEquals("1.23", metric.distanceValue)
        assertEquals("km", metric.distanceUnit)
        assertEquals("1.23 km", "${metric.distanceValue} ${metric.distanceUnit}")
        assertEquals("Pace", metric.secondaryLabel)
        assertEquals("5:00", metric.secondaryValue)
        assertEquals("/km", metric.secondaryUnit)
        assertEquals("5:00 /km", "${metric.secondaryValue} ${metric.secondaryUnit}")
        assertNull(metric.strip)
        // The spoken description keeps the full unitful strings — a screen reader should say
        // "1.23 km", not a bare number.
        assertTrue(metric.accessibilityDescription.contains("1.23 km"))
        assertTrue(metric.accessibilityDescription.startsWith("Ride dashboard."))
    }

    @Test
    fun `an imperial rider still gets kilometre pace, labelled as such`() {
        // PiP shows kilometre-pace in both unit modes to match the active HUD. Labelling that
        // number "/mi" would be wrong by a factor of 1.6 — the one way this split could lie.
        val metric = PiPDashboardPolicy.build(
            rideState = PiPRideState.RECORDING,
            distanceMeters = 1_609f,
            speedMps = 1000f / (5 * 60),
            persona = RidePersona.RUN,
            isAutoPaused = false,
            imperial = true,
            alert = null,
            strings = AppStrings(),
        )

        assertEquals("mi", metric.distanceUnit)
        assertEquals("/km", metric.secondaryUnit)
    }

    @Test
    fun `a speed persona is labelled in speed units`() {
        val metric = PiPDashboardPolicy.build(
            rideState = PiPRideState.RECORDING,
            distanceMeters = 1_000f,
            speedMps = 10f,
            persona = RidePersona.CYCLING,
            isAutoPaused = false,
            imperial = false,
            alert = null,
            strings = AppStrings(),
        )

        assertEquals("km", metric.distanceUnit)
        assertEquals("km/h", metric.secondaryUnit)
        // The magnitude must carry no unit text of its own, or the window renders it twice.
        assertFalse(metric.distanceValue.contains("km"))
        assertFalse(metric.secondaryValue.contains("km"))
    }

    @Test
    fun `labels drop below 120dp and at accessibility font scale`() {
        assertFalse(PiPDashboardLayoutPolicy.showLabels(heightDp = 119.99f, fontScale = 1f))
        assertTrue(PiPDashboardLayoutPolicy.showLabels(heightDp = 120f, fontScale = 1f))
        assertTrue(PiPDashboardLayoutPolicy.showLabels(heightDp = 150f, fontScale = 1.29f))
        assertFalse(PiPDashboardLayoutPolicy.showLabels(heightDp = 150f, fontScale = 1.3f))
    }

    @Test
    fun `session duration telemetry uses closed anonymous buckets`() {
        val cases = mapOf(
            0L to PiPSessionDurationBucket.UNDER_10_SECONDS,
            9L to PiPSessionDurationBucket.UNDER_10_SECONDS,
            10L to PiPSessionDurationBucket.SECONDS_10_TO_59,
            59L to PiPSessionDurationBucket.SECONDS_10_TO_59,
            60L to PiPSessionDurationBucket.MINUTES_1_TO_4,
            299L to PiPSessionDurationBucket.MINUTES_1_TO_4,
            300L to PiPSessionDurationBucket.MINUTES_5_TO_29,
            1_799L to PiPSessionDurationBucket.MINUTES_5_TO_29,
            1_800L to PiPSessionDurationBucket.MINUTES_30_PLUS,
        )
        cases.forEach { (seconds, expected) ->
            assertEquals(expected, PiPSessionDurationBucket.fromSeconds(seconds))
        }
        assertEquals(
            setOf("auto_enter", "user_leave_hint"),
            PiPEntryTrigger.entries.mapTo(mutableSetOf(), PiPEntryTrigger::analyticsValue),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resolved alert auto clears after eight seconds and a newer alert wins`() = runTest {
        val store = PiPAlertStore(this)
        store.accept(AlertPolicy.Signal.NONE, "Ignored", "1GNH")
        assertNull(store.alert.value)

        store.accept(AlertPolicy.Signal.ALERT_RESOLVED, "Alice", "1GNH")
        val resolvedId = store.alert.value?.eventId
        advanceTimeBy(PIP_RESOLVED_ALERT_MS - 1)
        runCurrent()
        assertEquals(resolvedId, store.alert.value?.eventId)

        store.accept(AlertPolicy.Signal.ALERT_RAISED, "Bob", "1GNH")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("Bob", store.alert.value?.memberName)
        assertEquals(AlertPolicy.Signal.ALERT_RAISED, store.alert.value?.signal)
    }

    @Test
    fun `manifest and activity enforce native PiP lifecycle with no overlay or stop action`() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val activity = source("app/src/main/java/in/shvms/trackme/MainActivity.kt")
        assertTrue(manifest.contains("android:supportsPictureInPicture=\"true\""))
        assertTrue(manifest.contains("screenSize|smallestScreenSize|screenLayout|orientation"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
        assertTrue(activity.contains("setAutoEnterEnabled(pipEligible)"))
        assertTrue(activity.contains("override fun onUserLeaveHint()"))
        assertTrue(activity.contains("powerManager?.isInteractive != true"))
        assertTrue(activity.contains("finishAndRemoveTask()"))
        assertTrue(activity.contains("setAspectRatio(Rational(16, 9))"))
        assertFalse(activity.contains("ACTION_STOP_SERVICE"))
    }

    @Test
    fun `dashboard source has one hertz sampling no animation and private telemetry keys`() {
        val dashboard = source(
            "app/src/main/java/in/shvms/trackme/ui/home/components/PiPDashboard.kt",
        )
        val analytics = source("app/src/main/java/in/shvms/trackme/analytics/AnalyticsManager.kt")
        assertTrue(dashboard.contains("sample(PIP_REFRESH_INTERVAL_MS)"))
        assertEquals(1_000L, PIP_REFRESH_INTERVAL_MS)
        listOf("AnimatedVisibility", "Animatable", "animate", "infiniteTransition").forEach {
            assertFalse("PiP dashboard must not contain $it", dashboard.contains(it))
        }
        assertTrue(analytics.contains("\"pip_entered\", properties = mapOf(\"trigger\" to trigger)"))
        assertTrue(analytics.contains("\"pip_session_seconds\""))
        assertTrue(analytics.contains("mapOf(\"duration_bucket\" to durationBucket)"))
        val pipTelemetryOnly = Regex(
            "fun\\s+trackPiP(?:Entered|Session)\\([^)]*\\)\\s*\\{[^}]*}",
        ).findAll(analytics).joinToString("\n") { it.value }
        assertEquals(2, Regex("fun\\s+trackPiP").findAll(pipTelemetryOnly).count())
        listOf("coordinate", "latitude", "longitude", "group_id", "member_name", "ride_id").forEach {
            assertFalse("PiP telemetry must not contain $it", pipTelemetryOnly.contains(it))
        }
    }

    private fun alert(signal: AlertPolicy.Signal) = PiPGroupAlert(
        eventId = 7L,
        signal = signal,
        memberName = "Alice",
        statusCode = "1GNH",
    )

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
