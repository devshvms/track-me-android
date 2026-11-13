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
}
