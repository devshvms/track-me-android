package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveShareManagerTest {
    @Test
    fun formatGracefulErrorExplainsReauthenticationForUnauthorizedResponse() {
        val error = LiveShareHttpException(401)

        assertEquals(
            "Your sign-in expired. Please sign in again to share your location.",
            LiveShareManager.formatGracefulError(error)
        )
        assertEquals(true, LiveShareManager.isAuthenticationError(error))
    }

    @Test
    fun nonUnauthorizedResponsesAreNotTreatedAsAuthenticationFailures() {
        val error = LiveShareHttpException(500)

        assertEquals(false, LiveShareManager.isAuthenticationError(error))
    }

    @Test
    fun buildStartRequestBodyCapsDeduplicatesAndTrimsWatchers() {
        val body = LiveShareManager.buildStartRequestBody(
            durationMinutes = 30,
            username = "Shubham",
            watcherUids = listOf(" uid1 ", "", "uid2", "uid1", "uid3", "uid4", "uid5", "uid6"),
        )
        assertEquals(30, body.getInt("durationMinutes"))
        assertEquals("Shubham", body.getString("username"))
        val arr = body.getJSONArray("watcherUids")
        assertEquals(5, arr.length())
        assertEquals("uid1", arr.getString(0))
        assertEquals("uid5", arr.getString(4))
    }

    @Test
    fun parseStartResponseReadsNotifiedWatchersCount() {
        val parsed = LiveShareManager.parseStartResponse(
            """
            {
              "sessionId": "abc123",
              "shareLink": "https://trackme.shvms.in/live/abc123",
              "expiresAt": "2026-11-12T18:00:00Z",
              "notifiedWatchersCount": 3
            }
            """.trimIndent()
        )
        assertEquals("abc123", parsed.sessionId)
        assertEquals("https://trackme.shvms.in/live/abc123", parsed.shareLink)
        assertEquals(3, parsed.notifiedWatchersCount)
    }

    @Test
    fun parseActiveViewersHandlesValidMissingAndMalformedPayloads() {
        assertEquals(4, LiveShareManager.parseActiveViewers("""{"activeViewers": 4}""", fallback = 0))
        assertEquals(2, LiveShareManager.parseActiveViewers("""{"ok": true}""", fallback = 2))
        assertEquals(0, LiveShareManager.parseActiveViewers("""{"activeViewers": -1}""", fallback = 5))
        assertEquals(1, LiveShareManager.parseActiveViewers("not-json", fallback = 1))
    }

    @Test
    fun computePushBackoffDelayScalesPredictablyAndClampsAtCeiling() {
        val noJitter = { 0.0 }
        // 0 failures -> base (5000ms)
        assertEquals(5000L, LiveShareManager.computePushBackoffDelay(0, 5000L, 15000L, noJitter))
        assertEquals(5000L, LiveShareManager.computePushBackoffDelay(-1, 5000L, 15000L, noJitter))

        // 1 failure -> base + 1000 = 6000ms
        assertEquals(6000L, LiveShareManager.computePushBackoffDelay(1, 5000L, 15000L, noJitter))

        // 2 failures -> base + 3000 = 8000ms
        assertEquals(8000L, LiveShareManager.computePushBackoffDelay(2, 5000L, 15000L, noJitter))

        // 3 failures -> base + 6000 = 11000ms
        assertEquals(11000L, LiveShareManager.computePushBackoffDelay(3, 5000L, 15000L, noJitter))

        // 4 failures -> base + 10000 = 15000ms
        assertEquals(15000L, LiveShareManager.computePushBackoffDelay(4, 5000L, 15000L, noJitter))

        // 10 failures -> clamped at max (15000ms)
        assertEquals(15000L, LiveShareManager.computePushBackoffDelay(10, 5000L, 15000L, noJitter))

        // Injects jitter within bounds without exceeding max
        val fullJitter = { 0.999 }
        val delayWithJitter = LiveShareManager.computePushBackoffDelay(1, 5000L, 15000L, fullJitter)
        assertEquals(6499L, delayWithJitter)

        val clampedWithJitter = LiveShareManager.computePushBackoffDelay(4, 5000L, 15000L, fullJitter)
        assertEquals(15000L, clampedWithJitter)
    }

    @Test
    fun isEffectivePowerSaveActivatesOnLowBatteryThresholdAndOsPowerSave() {
        // <= 15% battery triggers effective power save regardless of OS power save switch
        assertEquals(true, LiveShareManager.isEffectivePowerSave(isOsPowerSave = false, batteryLevel = 15))
        assertEquals(true, LiveShareManager.isEffectivePowerSave(isOsPowerSave = false, batteryLevel = 10))
        assertEquals(true, LiveShareManager.isEffectivePowerSave(isOsPowerSave = false, batteryLevel = 0))

        // > 15% battery does not trigger effective power save if OS power save is off
        assertEquals(false, LiveShareManager.isEffectivePowerSave(isOsPowerSave = false, batteryLevel = 16))
        assertEquals(false, LiveShareManager.isEffectivePowerSave(isOsPowerSave = false, batteryLevel = 85))
        assertEquals(false, LiveShareManager.isEffectivePowerSave(isOsPowerSave = false, batteryLevel = null))

        // OS power save mode active always triggers effective power save regardless of battery level
        assertEquals(true, LiveShareManager.isEffectivePowerSave(isOsPowerSave = true, batteryLevel = 100))
        assertEquals(true, LiveShareManager.isEffectivePowerSave(isOsPowerSave = true, batteryLevel = 50))
        assertEquals(true, LiveShareManager.isEffectivePowerSave(isOsPowerSave = true, batteryLevel = null))
    }

    @Test
    fun computeAdaptiveBaseIntervalMsThrottlesUnderPowerSaveOrLowBattery() {
        // When active, minimum interval is throttled to POWER_SAVE_MIN_INTERVAL_MS (15,000ms)
        assertEquals(15000L, LiveShareManager.computeAdaptiveBaseIntervalMs(5000L, isPowerSaveOrLowBattery = true))
        assertEquals(15000L, LiveShareManager.computeAdaptiveBaseIntervalMs(10000L, isPowerSaveOrLowBattery = true))

        // User-configured intervals longer than 15s are respected
        assertEquals(30000L, LiveShareManager.computeAdaptiveBaseIntervalMs(30000L, isPowerSaveOrLowBattery = true))
        assertEquals(60000L, LiveShareManager.computeAdaptiveBaseIntervalMs(60000L, isPowerSaveOrLowBattery = true))

        // When inactive, original configured interval is preserved untouched
        assertEquals(5000L, LiveShareManager.computeAdaptiveBaseIntervalMs(5000L, isPowerSaveOrLowBattery = false))
        assertEquals(10000L, LiveShareManager.computeAdaptiveBaseIntervalMs(10000L, isPowerSaveOrLowBattery = false))
    }

    @Test
    fun evaluateZeroViewerHibernationTransitionsAtTenMinutesAndWakesOnViewerConnect_cie046() {
        val t0 = 1_700_000_000_000L

        // Below 10m with 0 viewers -> stays at configured cadence
        val beforeThreshold = LiveShareManager.evaluateZeroViewerHibernation(
            activeViewers = 0,
            zeroViewersSinceEpochMs = t0,
            nowEpochMs = t0 + 599_999L,
            configuredIntervalMs = 5_000L,
            isPowerSaveOrLowBattery = false,
        )
        assertEquals(false, beforeThreshold.isHibernating)
        assertEquals(t0, beforeThreshold.zeroViewersSinceEpochMs)
        assertEquals(5_000L, beforeThreshold.effectiveIntervalMs)

        // At >= 10m with 0 viewers -> steps to 30s hibernation interval
        val hibernating = LiveShareManager.evaluateZeroViewerHibernation(
            activeViewers = 0,
            zeroViewersSinceEpochMs = t0,
            nowEpochMs = t0 + 600_000L,
            configuredIntervalMs = 5_000L,
            isPowerSaveOrLowBattery = false,
        )
        assertEquals(true, hibernating.isHibernating)
        assertEquals(600_000L, hibernating.idleDurationMs)
        assertEquals(30_000L, hibernating.effectiveIntervalMs)

        // Longer configured user interval (60s) is respected even during hibernation
        val longerConfigured = LiveShareManager.evaluateZeroViewerHibernation(
            activeViewers = 0,
            zeroViewersSinceEpochMs = t0,
            nowEpochMs = t0 + 700_000L,
            configuredIntervalMs = 60_000L,
            isPowerSaveOrLowBattery = false,
        )
        assertEquals(true, longerConfigured.isHibernating)
        assertEquals(60_000L, longerConfigured.effectiveIntervalMs)

        // Viewer connecting immediately wakes broadcaster and resets timer
        val woken = LiveShareManager.evaluateZeroViewerHibernation(
            activeViewers = 1,
            zeroViewersSinceEpochMs = t0,
            nowEpochMs = t0 + 700_000L,
            configuredIntervalMs = 5_000L,
            isPowerSaveOrLowBattery = false,
        )
        assertEquals(false, woken.isHibernating)
        assertEquals(null, woken.zeroViewersSinceEpochMs)
        assertEquals(5_000L, woken.effectiveIntervalMs)

        // Push backoff never clamps below hibernated baseIntervalMs
        val noJitter = { 0.0 }
        assertEquals(30_000L, LiveShareManager.computePushBackoffDelay(0, 30_000L, 45_000L, noJitter))
        assertEquals(31_000L, LiveShareManager.computePushBackoffDelay(1, 30_000L, 45_000L, noJitter))
        assertEquals(30_000L, LiveShareManager.computePushBackoffDelay(1, 30_000L, 15_000L, noJitter))

        // Sanitized telemetry excludes coordinates, tokens, and PII
        val telemetry = LiveShareManager.buildZeroViewerHibernationTelemetryPayload(
            sessionId = "sess-46",
            isHibernating = true,
            idleDurationMs = 600_000L,
            intervalMs = 30_000L,
            platform = "android",
        )
        assertEquals("sess-46", telemetry["session_id"])
        assertEquals(true, telemetry["is_hibernating"])
        assertEquals(600L, telemetry["idle_seconds"])
        assertEquals(30L, telemetry["interval_seconds"])
        assertEquals("android", telemetry["platform"])
        assertEquals(false, telemetry.containsKey("lat"))
        assertEquals(false, telemetry.containsKey("lng"))
        assertEquals(false, telemetry.containsKey("token"))
        assertEquals(false, telemetry.containsKey("email"))
    }
}
