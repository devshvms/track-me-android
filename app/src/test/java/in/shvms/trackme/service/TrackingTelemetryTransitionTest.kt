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
}
