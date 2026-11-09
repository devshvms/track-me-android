package `in`.shvms.trackme.integration

import android.app.Application
import android.content.Context
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.analytics.InstallEventPolicy
import `in`.shvms.trackme.analytics.InstallEventState
import `in`.shvms.trackme.analytics.RecoveryTelemetry
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.quickstart.QuickStartRequest
import `in`.shvms.trackme.domain.stats.WeeklyRecap
import `in`.shvms.trackme.service.TrackingService
import `in`.shvms.trackme.service.quicksettings.QuickStartTilePolicy
import `in`.shvms.trackme.ui.history.ExportSharedReceiver
import `in`.shvms.trackme.ui.home.WeeklyRecapCardRenderer
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-End Integration Test Suite for TrackMe v1.9.2 across all 6 mobile scopes:
 * - TASK-355: Fresh install vs app update `Application Installed` state machine
 * - TASK-356: Orphaned ride recovery telemetry & GPS pause/resume debounce window
 * - TASK-357: One-tap QuickStartRequest + QuickStartTilePolicy lifecycle
 * - TASK-358: Weekly recap notification open attribution + 1080x1080 localized card rendering & bitmap hygiene
 * - TASK-359: Route card share channel attribution & fallback deep link
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V192IntegrationTest {

    @Test
    fun `TASK-355 and TASK-357 - fresh install consent flow and quick start shortcut lifecycle`() {
        // 1. Fresh install resolves to PENDING while consent is off, then emits once on consent
        val t0 = 1_700_000_000_000L
        val stateBeforeConsent = InstallEventPolicy.resolveState(
            stored = null,
            firstInstallTimeMs = t0,
            lastUpdateTimeMs = t0 + 1_200L,
        )
        assertEquals(InstallEventState.PENDING, stateBeforeConsent)
        assertFalse(InstallEventPolicy.shouldEmit(stateBeforeConsent, telemetryEnabled = false))
        assertTrue(InstallEventPolicy.shouldEmit(stateBeforeConsent, telemetryEnabled = true))

        // Once emitted and stored as SENT, never emits again
        val stateAfterEmit = InstallEventPolicy.resolveState(
            stored = InstallEventState.SENT,
            firstInstallTimeMs = t0,
            lastUpdateTimeMs = t0 + 1_200L,
        )
        assertFalse(InstallEventPolicy.shouldEmit(stateAfterEmit, telemetryEnabled = true))

        // 2. User triggers a launcher shortcut for CYCLING -> parsed and queued on TrackMeApp
        val app = TrackMeApp()
        val req = QuickStartRequest.parse(
            action = QuickStartRequest.ACTION,
            personaName = "CYCLING",
            sourceName = "SHORTCUT",
            nowMillis = t0,
        )
        assertNotNull(req)
        app.setPendingQuickStart(req)
        assertEquals(RidePersona.CYCLING, app.pendingQuickStart.value?.persona)
        assertTrue(QuickStartRequest.shouldStart(app.pendingQuickStart.value, t0 + 5_000L, isTrackingIdle = true))
        assertFalse(QuickStartRequest.shouldStart(app.pendingQuickStart.value, t0 + 5_000L, isTrackingIdle = false))
        app.consumePendingQuickStart()
        assertNull(app.pendingQuickStart.value)

        // 3. Quick Settings tile policy reflects active vs idle ride state with localized labels
        assertFalse(QuickStartTilePolicy.isTrackingActive(isRunning = false, activeRideId = null))
        assertEquals(Tile.STATE_INACTIVE, QuickStartTilePolicy.tileState(isTracking = false))
        assertEquals("Iniciar viaje", QuickStartTilePolicy.tileLabel(false, "Grabando", "Iniciar viaje"))

        assertTrue(QuickStartTilePolicy.isTrackingActive(isRunning = true, activeRideId = 42L))
        assertEquals(Tile.STATE_ACTIVE, QuickStartTilePolicy.tileState(isTracking = true))
        assertEquals("Grabando", QuickStartTilePolicy.tileLabel(true, "Grabando", "Iniciar viaje"))
    }

    @Test
    fun `TASK-356 - orphaned ride recovery telemetry and 60s GPS debounce integration`() {
        assertEquals(60_000L, TrackingService.GPS_PAUSE_TELEMETRY_DEBOUNCE_MS)

        assertFalse(RecoveryTelemetry.shouldReport(recoveredCount = 0, discardedCount = 0))
        assertTrue(RecoveryTelemetry.shouldReport(recoveredCount = 1, discardedCount = 2))

        val props = RecoveryTelemetry.properties(recoveredCount = 1, discardedCount = 2, totalMeters = 14_250.0)
        assertEquals(1, props["recovered_count"])
        assertEquals(2, props["discarded_count"])
        assertEquals("5_20km", props["distance_bucket"])
        assertFalse(props.containsKey("distance_meters"))
        assertFalse(props.containsKey("ride_id"))
    }

    @Test
    fun `TASK-358 and TASK-359 - weekly recap notification open source state machine, localized card render, and share attribution`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = TrackMeApp()

        // 1. Default recap open source is "in_app"
        assertEquals("in_app", app.consumeRecapOpenSource())

        // 2. When notification is tapped, open source is "notification" exactly once, then resets to "in_app"
        app.markRecapOpenedFromNotification()
        assertEquals("notification", app.consumeRecapOpenSource())
        assertEquals("in_app", app.consumeRecapOpenSource())

        // 3. Render localized (Spanish) 1080x1080 weekly recap card, write PNG, and verify sanitized path
        val recap = WeeklyRecap(
            weekKey = "2026-W45",
            weekStartEpochDay = 20760,
            rideCount = 6,
            distanceMeters = 58_400.0,
            streakWeeks = 4,
        )
        val esStrings = getAppStrings("es")
        val bitmap = WeeklyRecapCardRenderer.render(
            context = context,
            recap = recap,
            strings = esStrings,
            imperial = false,
        )
        assertEquals(1080, bitmap.width)
        assertEquals(1080, bitmap.height)

        val pngFile = try {
            WeeklyRecapCardRenderer.writePng(context, bitmap, "2026-W45/unsafe")
        } finally {
            bitmap.recycle()
        }
        assertTrue(bitmap.isRecycled)
        assertTrue(pngFile.exists())
        assertEquals("trackme-recap-2026-W45_unsafe.png", pngFile.name)
        assertTrue(pngFile.length() > 1_000L)
        pngFile.delete()

        // 4. Verify share channel attribution across social targets
        assertEquals("whatsapp", ExportSharedReceiver.resolveShareChannel("com.whatsapp"))
        assertEquals("instagram", ExportSharedReceiver.resolveShareChannel("com.instagram.android"))
        assertEquals("strava", ExportSharedReceiver.resolveShareChannel("com.strava"))
        assertEquals("x", ExportSharedReceiver.resolveShareChannel("com.x.android"))
        assertEquals("other", ExportSharedReceiver.resolveShareChannel("org.signal.messenger"))
        assertEquals("unknown", ExportSharedReceiver.resolveShareChannel(null))
    }
}
