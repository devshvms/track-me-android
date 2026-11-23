package `in`.shvms.trackme.domain.tracking

import `in`.shvms.trackme.domain.notifications.ForgottenRideNotice
import `in`.shvms.trackme.service.AdaptiveGpsThrottlePolicy
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-371..373 (v1.9.4): Unified Hands-Free Reliability State Machine & Privacy Telemetry Verification Matrix.
 * Covers all 6 cross-platform invariants (§3.1), all 7 privacy-safe telemetry events (§3.2),
 * and the §3.3 5/10/15/30-min throttled dwell simulation matrix.
 */
class HandsFreeReliabilityPolicyTest {

    @Test
    fun `invariant 1 - stationary ride open 90s+ wall-clock is never classified as short indoor test`() {
        // Stationary in Tracking V2: movingDurationMillis stays 0 while elapsed wall-clock advances past 90s
        assertFalse(
            HandsFreeReliabilityPolicy.isShortIndoorTest(
                elapsedDurationMillis = 90_000L,
                movingDurationMillis = 0L,
                distanceMeters = 0.0,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.isShortIndoorTest(
                elapsedDurationMillis = 125_000L,
                movingDurationMillis = 0L,
                distanceMeters = 12.0,
            )
        )
        // Genuine short indoor couch test (<50m AND <90s wall-clock) IS classified as short indoor test
        assertTrue(
            HandsFreeReliabilityPolicy.isShortIndoorTest(
                elapsedDurationMillis = 25_000L,
                movingDurationMillis = 0L,
                distanceMeters = 8.0,
            )
        )
        // Real movement >= 50m under 90s is never an indoor test
        assertFalse(
            HandsFreeReliabilityPolicy.isShortIndoorTest(
                elapsedDurationMillis = 25_000L,
                movingDurationMillis = 20_000L,
                distanceMeters = 50.0,
            )
        )
        // Service-side safeguard protects rides open >= 2m wall-clock even with 0 moving time
        assertFalse(
            HandsFreeReliabilityPolicy.canDiscardNearEmptyRide(
                finalDistanceMeters = 5.0,
                finalMovingDurationMillis = 0L,
                finalElapsedDurationMillis = 120_000L,
            )
        )
        assertTrue(
            HandsFreeReliabilityPolicy.canDiscardNearEmptyRide(
                finalDistanceMeters = 15.0,
                finalMovingDurationMillis = 10_000L,
                finalElapsedDurationMillis = 95_000L,
            )
        )
    }

    @Test
    fun `invariant 2 - timer-driven DwellDetector auto-ends at 5, 10, 15, and 30 min under stationary GPS throttling with zero callbacks after 180s`() {
        val anchorLat = 12.9716
        val anchorLng = 77.5946
        val t0 = 1_700_000_000_000L

        for (dwellMinutes in listOf(5, 10, 15, 30)) {
            val detector = DwellDetector(
                isAutoTripChainingEnabled = true,
                autoEndDwellMinutes = dwellMinutes,
                isAutoEndCropDwellEnabled = true,
            )

            // Rider stops indoors at t0; OS delivers location fixes only up to t = 180s (3 min throttle engages)
            var isGpsThrottled = false
            var autoEndedAtSec: Int? = null
            var autoEndResult: DwellEvaluationResult? = null

            val totalSimSeconds = dwellMinutes * 60 + 5
            for (sec in 0..totalSimSeconds) {
                val nowMs = t0 + sec * 1000L
                val stillnessMs = sec * 1000L

                if (AdaptiveGpsThrottlePolicy.shouldThrottle(isStationary = true, stillnessDurationMs = stillnessMs)) {
                    isGpsThrottled = true
                }

                // After t = 180s, simulate ZERO new OS GPS callbacks (15m distance filter silence)
                val osGpsDelivered = sec <= 180
                if (osGpsDelivered) {
                    val res = detector.evaluate(
                        lat = anchorLat,
                        lng = anchorLng,
                        speedKmh = 0.0,
                        nowEpochMs = nowMs,
                        accuracyMeters = 12f,
                    )
                    if (res.shouldAutoEndRide) {
                        autoEndedAtSec = sec
                        autoEndResult = res
                        break
                    }
                } else {
                    // Timer-driven evaluation must take over when throttled with zero OS callbacks
                    assertTrue(
                        "Timer tick must evaluate dwell under throttle for ${dwellMinutes}m at t=${sec}s",
                        HandsFreeReliabilityPolicy.shouldEvaluateDwellOnTimerTick(
                            isAutoTripChainingEnabled = detector.isAutoTripChainingEnabled,
                            isLiveShareBlockingDwell = false,
                            isAutoPaused = true,
                            isMotionStationary = true,
                            isGpsThrottled = isGpsThrottled,
                            dwellState = detector.state,
                        )
                    )
                    // False GPS_LOST must be suppressed throughout throttled stillness
                    assertTrue(
                        HandsFreeReliabilityPolicy.shouldSuppressGpsLost(
                            isGpsThrottled = isGpsThrottled,
                            isStationary = true,
                            isLocationServiceEnabled = true,
                        )
                    )
                    val res = detector.evaluate(
                        lat = anchorLat,
                        lng = anchorLng,
                        speedKmh = 0.0,
                        nowEpochMs = nowMs,
                        accuracyMeters = 12f,
                    )
                    if (res.shouldAutoEndRide) {
                        autoEndedAtSec = sec
                        autoEndResult = res
                        break
                    }
                }
            }

            assertTrue("GPS throttle should have engaged at 180s for ${dwellMinutes}m", isGpsThrottled)
            assertEquals("Auto-end must fire at exact ${dwellMinutes}m mark", dwellMinutes * 60, autoEndedAtSec)
            assertTrue(autoEndResult!!.shouldPruneTrailingPoints)
            assertEquals("End timestamp must backdate to dwell anchor t0", t0, autoEndResult.autoEndTimestampEpochMs)
        }
    }

    @Test
    fun `invariant 3 and 4 - suppresses GPS_LOST while stationary and throttled, un-throttles immediately on motion wake`() {
        // Stationary + throttled + OS location enabled -> suppress false GPS_LOST
        assertTrue(
            HandsFreeReliabilityPolicy.shouldSuppressGpsLost(
                isGpsThrottled = true,
                isStationary = true,
                isLocationServiceEnabled = true,
            )
        )
        // OS location disabled -> never suppress GPS_DISABLED
        assertFalse(
            HandsFreeReliabilityPolicy.shouldSuppressGpsLost(
                isGpsThrottled = true,
                isStationary = true,
                isLocationServiceEnabled = false,
            )
        )
        // Moving while throttled -> do NOT suppress GPS_LOST; immediately un-throttle on motion wake
        assertFalse(
            HandsFreeReliabilityPolicy.shouldSuppressGpsLost(
                isGpsThrottled = true,
                isStationary = false,
                isLocationServiceEnabled = true,
            )
        )
        assertTrue(
            HandsFreeReliabilityPolicy.shouldUnthrottleOnMotionWake(
                isGpsThrottled = true,
                isMotionStationary = false,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.shouldUnthrottleOnMotionWake(
                isGpsThrottled = true,
                isMotionStationary = true,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.shouldUnthrottleOnMotionWake(
                isGpsThrottled = false,
                isMotionStationary = false,
            )
        )
    }

    @Test
    fun `invariant 5 - active live share suppresses timer-driven dwell evaluation`() {
        assertFalse(
            HandsFreeReliabilityPolicy.shouldEvaluateDwellOnTimerTick(
                isAutoTripChainingEnabled = true,
                isLiveShareBlockingDwell = true,
                isAutoPaused = true,
                isMotionStationary = true,
                isGpsThrottled = true,
                dwellState = DwellState.DWELLING,
            )
        )
    }

    @Test
    fun `invariant 6 - standby expires strictly at 2 hours without movement`() {
        val detector = DwellDetector(isAutoTripChainingEnabled = true)
        val t0 = 1_700_000_000_000L
        detector.transitionToStandby(12.9716, 77.5946, t0)

        val beforeCap = detector.evaluate(12.9716, 77.5946, speedKmh = 0.0, nowEpochMs = t0 + 7_199_000L, accuracyMeters = 10f)
        assertEquals(DwellState.STANDBY_WAITING_FOR_NEXT_TRIP, beforeCap.state)
        assertFalse(beforeCap.shouldStopStandby)

        val atCap = detector.evaluate(12.9716, 77.5946, speedKmh = 0.0, nowEpochMs = t0 + 7_200_000L, accuracyMeters = 10f)
        assertEquals(DwellState.ACTIVE_TRACKING, atCap.state)
        assertTrue(atCap.shouldStopStandby)
        assertFalse(atCap.shouldAutoStartNextRide)
    }

    @Test
    fun `forgotten ride notice is suppressed when Hands-Free auto trip chaining is enabled`() {
        assertFalse(
            ForgottenRideNotice.shouldAsk(
                stillnessMillis = ForgottenRideNotice.STILLNESS_BEFORE_NOTICE_MILLIS,
                alreadyAsked = false,
                isTracking = true,
                autoTripChainingEnabled = true,
            )
        )
        assertTrue(
            ForgottenRideNotice.shouldAsk(
                stillnessMillis = ForgottenRideNotice.STILLNESS_BEFORE_NOTICE_MILLIS,
                alreadyAsked = false,
                isTracking = true,
                autoTripChainingEnabled = false,
            )
        )
    }

    @Test
    fun `home top slot never stacks standby banner and Hands-Free opt-in tip`() {
        assertTrue(
            HandsFreeReliabilityPolicy.shouldShowHomeHandsFreeTip(
                isStandbyActive = false,
                autoTripChainingEnabled = false,
                tipDismissed = false,
                hasUnreadBroadcast = false,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.shouldShowHomeHandsFreeTip(
                isStandbyActive = true,
                autoTripChainingEnabled = false,
                tipDismissed = false,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.shouldShowHomeHandsFreeTip(
                isStandbyActive = false,
                autoTripChainingEnabled = true,
                tipDismissed = false,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.shouldShowHomeHandsFreeTip(
                isStandbyActive = false,
                autoTripChainingEnabled = false,
                tipDismissed = true,
            )
        )
        assertFalse(
            HandsFreeReliabilityPolicy.shouldShowHomeHandsFreeTip(
                isStandbyActive = false,
                autoTripChainingEnabled = false,
                tipDismissed = false,
                hasUnreadBroadcast = true,
            )
        )
    }

    @Test
    fun `telemetry contract emits all 7 cross-platform events with zero PII or coordinates`() {
        val samples = HandsFreeTelemetryContract.privacySamples()
        val expectedNames = setOf(
            "hands_free_offer_shown",
            "hands_free_enabled",
            "hands_free_disabled",
            "hands_free_standby_armed",
            "hands_free_ride_auto_started",
            "hands_free_ride_auto_ended",
            "hands_free_false_start_aborted",
        )
        assertEquals(expectedNames, samples.map { it.name }.toSet())

        val forbiddenKeys = setOf("lat", "lng", "lon", "latitude", "longitude", "email", "user_email", "name", "title", "ride_id")
        for (sample in samples) {
            for (key in sample.properties.keys) {
                assertFalse("Forbidden key $key in ${sample.name}", key in forbiddenKeys)
            }
        }

        val autoEnded = HandsFreeTelemetryContract.rideAutoEnded(dwellMinutes = 15, croppedPoints = 42, wasGpsThrottled = true)
        assertEquals(15, autoEnded.properties["dwell_minutes"])
        assertEquals(42, autoEnded.properties["cropped_points"])
        assertEquals(true, autoEnded.properties["was_gps_throttled"])

        val falseStart = HandsFreeTelemetryContract.falseStartAborted(elapsedSeconds = 22, distanceMeters = 9)
        assertEquals(22, falseStart.properties["elapsed_seconds"])
        assertEquals(9, falseStart.properties["distance_meters"])
    }

    @Test
    fun `all 9 Hands-Free UI strings are localized across all 7 shipped locales`() {
        val en = getAppStrings("en")
        for (lang in listOf("en", "es", "fr", "de", "hi", "ja", "zh")) {
            val s = getAppStrings(lang)
            assertTrue(s.discardRideHandsFreeMessage.isNotBlank())
            assertTrue(s.discardAndAutoStartNextTrip.isNotBlank())
            assertTrue(s.justDiscard.isNotBlank())
            assertTrue(s.handsFreeTipTitle.isNotBlank())
            assertTrue(s.handsFreeTipSubtitle.isNotBlank())
            assertTrue(s.handsFreeTipEnable.isNotBlank())
            assertTrue(s.dismissHandsFreeTip.isNotBlank())
            assertTrue(s.postRideHandsFreeChip.isNotBlank())
            assertTrue(s.handsFreeEnabledConfirmation.isNotBlank())
            if (lang != "en") {
                assertFalse("Expected $lang translation for discardAndAutoStartNextTrip", s.discardAndAutoStartNextTrip == en.discardAndAutoStartNextTrip)
                assertFalse("Expected $lang translation for handsFreeTipTitle", s.handsFreeTipTitle == en.handsFreeTipTitle)
            }
        }
    }
}
