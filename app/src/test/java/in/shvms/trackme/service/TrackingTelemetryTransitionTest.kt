package `in`.shvms.trackme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTelemetryTransitionTest {
    @Test
    fun gpsPauseTelemetry_isOnlyEmittedWhenLeavingTracking() {
        assertTrue(shouldEmitGpsPauseTelemetry(TrackingState.TRACKING))
        assertFalse(shouldEmitGpsPauseTelemetry(TrackingState.GPS_LOST))
        assertFalse(shouldEmitGpsPauseTelemetry(TrackingState.GPS_DISABLED))
        assertFalse(shouldEmitGpsPauseTelemetry(TrackingState.PAUSED))
    }

    @Test
    fun gpsResumeTelemetry_isOnlyEmittedWhenRecoveringFromGpsLoss() {
        assertTrue(shouldEmitGpsResumeTelemetry(TrackingState.GPS_LOST))
        assertTrue(shouldEmitGpsResumeTelemetry(TrackingState.GPS_DISABLED))
        assertFalse(shouldEmitGpsResumeTelemetry(TrackingState.TRACKING))
        assertFalse(shouldEmitGpsResumeTelemetry(TrackingState.PAUSED))
    }

    @Test
    fun gpsPauseTelemetryDebounce_isAtLeastSixtySeconds() {
        org.junit.Assert.assertEquals(60_000L, TrackingService.GPS_PAUSE_TELEMETRY_DEBOUNCE_MS)
    }

    @Test
    fun gpsDebouncePolicy_preventsFlappingWithinDebounceWindow() {
        val lastEventMs = 100_000L
        val debounceWindow = TrackingService.GPS_PAUSE_TELEMETRY_DEBOUNCE_MS

        // Rapid flapping 15s later -> suppressed
        assertFalse((115_000L - lastEventMs) >= debounceWindow)
        // Flapping 30s later -> suppressed
        assertFalse((130_000L - lastEventMs) >= debounceWindow)
        // Slower transition >= 60s -> admitted
        assertTrue((160_000L - lastEventMs) >= debounceWindow)
    }
}

