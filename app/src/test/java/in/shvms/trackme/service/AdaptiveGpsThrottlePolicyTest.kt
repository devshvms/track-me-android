package `in`.shvms.trackme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGpsThrottlePolicyTest {

    @Test
    fun throttleIntervalConstants_satisfyBatterySavingsAndAccuracy() {
        // Active mode: 1000ms minimum interval, 2000ms request interval
        assertEquals(1000L, AdaptiveGpsThrottlePolicy.ACTIVE_MIN_INTERVAL_MS)
        assertEquals(2000L, AdaptiveGpsThrottlePolicy.ACTIVE_INTERVAL_MS)

        // Throttled stationary mode: 5000ms minimum interval, 10000ms request interval
        assertEquals(5000L, AdaptiveGpsThrottlePolicy.THROTTLED_MIN_INTERVAL_MS)
        assertEquals(10000L, AdaptiveGpsThrottlePolicy.THROTTLED_INTERVAL_MS)

        // Threshold before throttling kicks in is exactly 3 minutes (180,000ms)
        assertEquals(180_000L, AdaptiveGpsThrottlePolicy.STATIONARY_THROTTLE_THRESHOLD_MS)
    }

    @Test
    fun shouldThrottle_requiresStationaryStateAndFull3MinutesStillness() {
        // Moving user should never be throttled, regardless of time elapsed
        assertFalse(
            AdaptiveGpsThrottlePolicy.shouldThrottle(
                isStationary = false,
                stillnessDurationMs = 200_000L,
            )
        )

        // Stationary user under 3 minutes (e.g. 30s stop sign or 2m traffic light) stays high accuracy
        assertFalse(
            AdaptiveGpsThrottlePolicy.shouldThrottle(
                isStationary = true,
                stillnessDurationMs = 30_000L,
            )
        )
        assertFalse(
            AdaptiveGpsThrottlePolicy.shouldThrottle(
                isStationary = true,
                stillnessDurationMs = 179_999L,
            )
        )

        // Stationary user reaching or exceeding 3 minutes is throttled
        assertTrue(
            AdaptiveGpsThrottlePolicy.shouldThrottle(
                isStationary = true,
                stillnessDurationMs = 180_000L,
            )
        )
        assertTrue(
            AdaptiveGpsThrottlePolicy.shouldThrottle(
                isStationary = true,
                stillnessDurationMs = 600_000L, // 10 minutes
            )
        )
    }

    @Test
    fun shouldRestore_triggersInstantlyWhenMovingAgain() {
        // If not currently throttled, no restoration action needed
        assertFalse(
            AdaptiveGpsThrottlePolicy.shouldRestore(
                isCurrentlyThrottled = false,
                isStationary = false,
            )
        )

        // If currently throttled and still stationary, do not restore
        assertFalse(
            AdaptiveGpsThrottlePolicy.shouldRestore(
                isCurrentlyThrottled = true,
                isStationary = true,
            )
        )

        // If currently throttled and physical motion is detected, restore immediately!
        assertTrue(
            AdaptiveGpsThrottlePolicy.shouldRestore(
                isCurrentlyThrottled = true,
                isStationary = false,
            )
        )
    }
}
