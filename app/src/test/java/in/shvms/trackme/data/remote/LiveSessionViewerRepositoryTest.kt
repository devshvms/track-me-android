package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.SUPPORTED_LANGUAGE_CODES
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Locale

class LiveSessionViewerRepositoryTest {

    @Test
    fun `parses active snapshot with lng or lon coordinates and path`() {
        val json = """
            {
              "username": "Shivam",
              "status": "active",
              "destination": "Indiranagar",
              "lastLocation": {
                "lat": 12.9716,
                "lng": 77.5946,
                "speed": 36.0,
                "heading": 142.0,
                "batteryLevel": 81,
                "powerSaveMode": false
              },
              "path": [
                [12.9700, 77.5900],
                { "lat": 12.9716, "lon": 77.5946 }
              ]
            }
        """.trimIndent()

        val snapshot = LiveSessionViewerRepository.parseSnapshot("sess_1", json)
        assertNotNull(snapshot)
        snapshot!!
        assertEquals("sess_1", snapshot.sessionId)
        assertEquals("Shivam", snapshot.username)
        assertTrue(snapshot.isActive)
        assertEquals("Indiranagar", snapshot.destination)
        assertEquals(36.0f, snapshot.lastLocation!!.speedKmh!!, 0.001f)
        assertEquals(10.0f, snapshot.lastLocation!!.speedMps!!, 0.001f)
        assertEquals(142.0f, snapshot.lastLocation!!.headingDeg!!, 0.01f)
        assertEquals(81, snapshot.lastLocation!!.batteryLevel)
        assertEquals(2, snapshot.path.size)
        assertEquals(77.5900, snapshot.path.first().lng, 0.0001)
        assertEquals(77.5946, snapshot.path.last().lng, 0.0001)
    }

    @Test
    fun `parses ended snapshot with endReason and stopReason`() {
        val json = """
            {
              "username": "Rider",
              "status": "ended",
              "endReason": "stopped_by_user",
              "stopReason": "manual",
              "lastLocation": { "lat": 12.9, "lon": 77.6 }
            }
        """.trimIndent()

        val snapshot = LiveSessionViewerRepository.parseSnapshot("sess_2", json)
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isActive)
        assertEquals("stopped_by_user", snapshot.endReason)
        assertEquals("manual", snapshot.stopReason)
        assertEquals(1, snapshot.path.size)
    }

    @Test
    fun `computes freshness age clamped at zero and flags delayed updates`() {
        val nowMs = 1_700_000_100_000L // exact epoch ms
        val freshIso = java.time.Instant.ofEpochMilli(nowMs - 12_000L).toString()
        val staleIso = java.time.Instant.ofEpochMilli(nowMs - 50_000L).toString()
        val futureIso = java.time.Instant.ofEpochMilli(nowMs + 15_000L).toString()

        assertEquals(12, LiveSessionViewerRepository.ageSeconds(freshIso, nowMs))
        assertEquals(0, LiveSessionViewerRepository.ageSeconds(futureIso, nowMs))
        assertNull(LiveSessionViewerRepository.ageSeconds("not-a-timestamp", nowMs))

        assertFalse(LiveSessionViewerRepository.isDelayedUpdate(powerSaveMode = false, timestampIso = freshIso, nowEpochMillis = nowMs))
        assertTrue(LiveSessionViewerRepository.isDelayedUpdate(powerSaveMode = false, timestampIso = staleIso, nowEpochMillis = nowMs))
        assertTrue(LiveSessionViewerRepository.isDelayedUpdate(powerSaveMode = true, timestampIso = freshIso, nowEpochMillis = nowMs))
    }

    @Test
    fun `returns null on malformed json`() {
        assertNull(LiveSessionViewerRepository.parseSnapshot("sess_bad", "{not-json"))
    }

    @Test
    fun `computes viewer backoff delay with progression and jitter ceiling`() {
        val noJitter = { 0.0 }
        // 0 errors -> base (5000ms)
        assertEquals(5000L, LiveSessionViewerRepository.computeViewerBackoffDelay(0, randomProvider = noJitter))
        assertEquals(5000L, LiveSessionViewerRepository.computeViewerBackoffDelay(-1, randomProvider = noJitter))

        // 1 error -> base + 1000 = 6000ms
        assertEquals(6000L, LiveSessionViewerRepository.computeViewerBackoffDelay(1, randomProvider = noJitter))

        // 2 errors -> base + 3000 = 8000ms
        assertEquals(8000L, LiveSessionViewerRepository.computeViewerBackoffDelay(2, randomProvider = noJitter))

        // 3 errors -> base + 6000 = 11000ms
        assertEquals(11000L, LiveSessionViewerRepository.computeViewerBackoffDelay(3, randomProvider = noJitter))

        // 4 errors -> base + 10000 = 15000ms
        assertEquals(15000L, LiveSessionViewerRepository.computeViewerBackoffDelay(4, randomProvider = noJitter))

        // 8 errors -> clamped to 15000ms
        assertEquals(15000L, LiveSessionViewerRepository.computeViewerBackoffDelay(8, randomProvider = noJitter))

        // Injects jitter without exceeding max
        val halfJitter = { 0.5 }
        assertEquals(6250L, LiveSessionViewerRepository.computeViewerBackoffDelay(1, randomProvider = halfJitter))
        assertEquals(15000L, LiveSessionViewerRepository.computeViewerBackoffDelay(4, randomProvider = halfJitter))
    }

    @Test
    fun `parses timestamp ms from numbers and iso strings`() {
        val nowMs = 1_700_000_100_000L
        val isoStr = java.time.Instant.ofEpochMilli(nowMs).toString()

        assertEquals(nowMs, LiveSessionViewerRepository.parseTimestampMs(nowMs.toString()))
        assertEquals(nowMs, LiveSessionViewerRepository.parseTimestampMs(isoStr))
        assertNull(LiveSessionViewerRepository.parseTimestampMs(null))
        assertNull(LiveSessionViewerRepository.parseTimestampMs(""))
        assertNull(LiveSessionViewerRepository.parseTimestampMs("not_a_time"))
        assertNull(LiveSessionViewerRepository.parseTimestampMs("-500"))
    }

    @Test
    fun `detects stale signals when inactive or older than threshold`() {
        val nowMs = 1_700_000_100_000L
        val freshIso = java.time.Instant.ofEpochMilli(nowMs - 30_000L).toString() // 30s ago
        val staleIso = java.time.Instant.ofEpochMilli(nowMs - 190_000L).toString() // > 3 min ago

        // Inactive ride never flags stale
        assertFalse(LiveSessionViewerRepository.isStaleSignal(isActive = false, timestampIso = staleIso, nowEpochMillis = nowMs))

        // Active ride: fresh vs stale
        assertFalse(LiveSessionViewerRepository.isStaleSignal(isActive = true, timestampIso = freshIso, nowEpochMillis = nowMs))
        assertTrue(LiveSessionViewerRepository.isStaleSignal(isActive = true, timestampIso = staleIso, nowEpochMillis = nowMs))
    }

    @Test
    fun `formats trip duration accurately across minutes and hours`() {
        val startMs = 1_700_000_000_000L

        // Under 1 minute
        assertEquals("< 1m", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 45_000L).toString()))

        // 42 minutes
        assertEquals("42m", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 42 * 60_000L).toString()))

        // Exact hour
        assertEquals("2h", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 120 * 60_000L).toString()))

        // Hours and minutes
        assertEquals("1h 15m", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 75 * 60_000L).toString()))

        // Invalid or null
        assertNull(LiveSessionViewerRepository.formatDuration(null))
        assertNull(LiveSessionViewerRepository.formatDuration("invalid"))
    }

    @Test
    fun `parses startedAt and formats snapshot duration`() {
        val startMs = 1_700_000_000_000L
        val nowMs = startMs + 25 * 60_000L // 25m later
        val json = """
            {
              "username": "Rider",
              "status": "active",
              "startedAt": "$startMs",
              "lastLocation": {
                "lat": 12.97,
                "lng": 77.59,
                "timestamp": "${java.time.Instant.ofEpochMilli(nowMs).toString()}"
              }
            }
        """.trimIndent()

        val snapshot = LiveSessionViewerRepository.parseSnapshot("sess_dur", json)
        assertNotNull(snapshot)
        assertEquals("25m", snapshot!!.formatDuration(nowMs))
        assertFalse(snapshot.isStaleSignal(nowMs))
    }

    @Test
    fun `computes path distance meters handling jitter jumps and invalid coordinates`() {
        // Empty or single point
        assertEquals(0.0, LiveSessionViewerRepository.computePathDistanceMeters(null), 0.001)
        assertEquals(0.0, LiveSessionViewerRepository.computePathDistanceMeters(emptyList()), 0.001)
        assertEquals(0.0, LiveSessionViewerRepository.computePathDistanceMeters(listOf(LiveViewerPoint(12.9716, 77.5946))), 0.001)

        // 2 points ~111 meters apart
        val p1 = LiveViewerPoint(12.9716, 77.5946)
        val p2 = LiveViewerPoint(12.9726, 77.5946)
        val dist2 = LiveSessionViewerRepository.computePathDistanceMeters(listOf(p1, p2))
        assertTrue("Expected ~111m, got $dist2", dist2 in 110.0..112.0)

        // Discards sub-2m jitter
        val pJitter = LiveViewerPoint(12.97260001, 77.5946)
        val distWithJitter = LiveSessionViewerRepository.computePathDistanceMeters(listOf(p1, p2, pJitter))
        assertEquals(dist2, distWithJitter, 0.001)

        // Discards supersonic jump (> 50 km)
        val pJump = LiveViewerPoint(18.0, 77.5946) // ~555 km away
        val distWithJump = LiveSessionViewerRepository.computePathDistanceMeters(listOf(p1, p2, pJump))
        assertEquals(dist2, distWithJump, 0.001)

        // Skips Null Island (0,0)
        val pNullIsland = LiveViewerPoint(0.0, 0.0)
        val distWithNullIsland = LiveSessionViewerRepository.computePathDistanceMeters(listOf(pNullIsland, p1, p2))
        assertEquals(dist2, distWithNullIsland, 0.001)
    }

    @Test
    fun `formats distance for metric and imperial modes`() {
        assertEquals("0.0 km", LiveSessionViewerRepository.formatDistance(0.0, useMiles = false))
        assertEquals("0.0 mi", LiveSessionViewerRepository.formatDistance(0.0, useMiles = true))
        assertEquals("0.0 km", LiveSessionViewerRepository.formatDistance(-10.0, useMiles = false))

        assertEquals("0.9 km", LiveSessionViewerRepository.formatDistance(900.0, useMiles = false))
        assertEquals("1.5 km", LiveSessionViewerRepository.formatDistance(1500.0, useMiles = false))
        assertEquals("14.2 km", LiveSessionViewerRepository.formatDistance(14200.0, useMiles = false))

        assertEquals("1.0 mi", LiveSessionViewerRepository.formatDistance(1609.344, useMiles = true))
        assertEquals("5.0 mi", LiveSessionViewerRepository.formatDistance(8046.72, useMiles = true))
        assertEquals("8.8 mi", LiveSessionViewerRepository.formatDistance(14200.0, useMiles = true))
    }

    @Test
    fun `snapshot calculates total distance and formats properly`() {
        val p1 = LiveViewerPoint(12.9716, 77.5946)
        val p2 = LiveViewerPoint(12.9726, 77.5946)
        val snapshot = LiveSessionSnapshot(
            sessionId = "dist_test",
            username = "Rider",
            status = "active",
            stopReason = null,
            endReason = null,
            endedAt = null,
            destination = null,
            deadlineAt = null,
            lastLocation = null,
            path = listOf(p1, p2),
        )

        assertTrue(snapshot.totalDistanceMeters in 110.0..112.0)
        assertEquals("0.1 km", snapshot.formatDistance(useMiles = false))
        assertEquals("0.1 mi", snapshot.formatDistance(useMiles = true))
    }

    @Test
    fun `evaluates deadline status for on-time and overdue sessions`() {
        val deadlineMs = 1_700_000_000_000L
        val fixedFormatter: (Long) -> String = { "6:30 PM" }

        // Null / invalid
        assertNull(LiveSessionViewerRepository.evaluateDeadline(null))
        assertNull(LiveSessionViewerRepository.evaluateDeadline(""))
        assertNull(LiveSessionViewerRepository.evaluateDeadline("invalid_date"))

        // On-time (15 minutes prior)
        val onTimeNow = deadlineMs - 15 * 60_000L
        val onTime = LiveSessionViewerRepository.evaluateDeadline(deadlineMs.toString(), onTimeNow, fixedFormatter)
        org.junit.Assert.assertNotNull(onTime)
        assertFalse(onTime!!.isOverdue)
        assertEquals(0L, onTime.overdueMinutes)
        assertEquals(deadlineMs, onTime.deadlineEpochMs)
        assertEquals("6:30 PM", onTime.timeFormatted)

        // Overdue (25 minutes past)
        val overdueNow = deadlineMs + 25 * 60_000L
        val overdue = LiveSessionViewerRepository.evaluateDeadline(deadlineMs.toString(), overdueNow, fixedFormatter)
        org.junit.Assert.assertNotNull(overdue)
        assertTrue(overdue!!.isOverdue)
        assertEquals(25L, overdue.overdueMinutes)
        assertEquals(deadlineMs, overdue.deadlineEpochMs)
        assertEquals("6:30 PM", overdue.timeFormatted)

        // Snapshot helper
        val snapshot = LiveSessionSnapshot(
            sessionId = "dl_test",
            username = "Rider",
            status = "active",
            stopReason = null,
            endReason = null,
            endedAt = null,
            destination = "Office",
            deadlineAt = deadlineMs.toString(),
            lastLocation = null,
            path = emptyList(),
        )
        val status = snapshot.deadlineStatus(overdueNow, fixedFormatter)
        org.junit.Assert.assertNotNull(status)
        assertTrue(status!!.isOverdue)
        assertEquals(25L, status.overdueMinutes)
    }

    @Test
    fun `parseSnapshot falls back to etaAt when deadlineAt is missing`() {
        val jsonWithDeadline = """{"username":"Rider","status":"active","deadlineAt":"1700000000000"}"""
        val snapWithDeadline = LiveSessionViewerRepository.parseSnapshot("s1", jsonWithDeadline)
        assertEquals("1700000000000", snapWithDeadline?.deadlineAt)

        val jsonWithEta = """{"username":"Rider","status":"active","etaAt":"1700000000000"}"""
        val snapWithEta = LiveSessionViewerRepository.parseSnapshot("s2", jsonWithEta)
        assertEquals("1700000000000", snapWithEta?.deadlineAt)
    }

    @Test
    fun `formats live viewer share ride message with username and fallback`() {
        val strings = AppStrings()
        val url = "https://trackme.shvms.in/live/abc-123"
        val withName = String.format(Locale.getDefault(), strings.liveViewerShareMessageWithName, "Alice", url)
        val fallback = String.format(Locale.getDefault(), strings.liveViewerShareMessageFallback, url)

        assertEquals("Track Alice's live ride on TrackMe: https://trackme.shvms.in/live/abc-123", withName)
        assertEquals("Track this live ride on TrackMe: https://trackme.shvms.in/live/abc-123", fallback)

        // Verify across all supported languages
        for (lang in SUPPORTED_LANGUAGE_CODES) {
            val s = getAppStrings(lang)
            val localizedWithName = String.format(Locale.getDefault(), s.liveViewerShareMessageWithName, "Bob", url)
            val localizedFallback = String.format(Locale.getDefault(), s.liveViewerShareMessageFallback, url)
            assertTrue(localizedWithName.contains("Bob"))
            assertTrue(localizedWithName.contains(url))
            assertTrue(localizedFallback.contains(url))
            assertTrue(s.liveViewerShareRide.isNotBlank())
            assertTrue(s.liveViewerShareRideA11y.isNotBlank())
            assertTrue(s.liveViewerShareRideSubject.isNotBlank())
        }
    }

    @Test
    fun `evaluates foreground reconnection state for active and finished sessions`() {
        // Active and valid session: triggers immediate fetch, clears errors, resets counter
        val activeRes = LiveSessionViewerRepository.evaluateForegroundReconnection(
            isActive = true,
            isExpiredOrMissing = false,
        )
        assertTrue(activeRes.shouldFetchImmediately)
        assertTrue(activeRes.resetConsecutiveErrors)
        assertTrue(activeRes.clearErrorMessage)

        // Expired or missing session: suppresses polling
        val expiredRes = LiveSessionViewerRepository.evaluateForegroundReconnection(
            isActive = true,
            isExpiredOrMissing = true,
        )
        assertFalse(expiredRes.shouldFetchImmediately)
        assertTrue(expiredRes.resetConsecutiveErrors)
        assertTrue(expiredRes.clearErrorMessage)

        // Inactive / finished session: suppresses polling
        val finishedRes = LiveSessionViewerRepository.evaluateForegroundReconnection(
            isActive = false,
            isExpiredOrMissing = false,
        )
        assertFalse(finishedRes.shouldFetchImmediately)
        assertTrue(finishedRes.resetConsecutiveErrors)
        assertTrue(finishedRes.clearErrorMessage)
    }

    @Test
    fun `computeShortestArcDelta and computeContinuousBearing handle north boundary crossing smoothly`() {
        // Crossing north clockwise: 355° to 5° should be +10° delta, not -350°
        val deltaClockwise = LiveSessionViewerRepository.computeShortestArcDelta(355.0, 5.0)
        assertEquals(10.0, deltaClockwise, 0.001)
        val continuousClockwise = LiveSessionViewerRepository.computeContinuousBearing(355f, 5f)
        assertEquals(365f, continuousClockwise, 0.001f)

        // Crossing north counter-clockwise: 5° to 355° should be -10° delta, not +350°
        val deltaCounter = LiveSessionViewerRepository.computeShortestArcDelta(5.0, 355.0)
        assertEquals(-10.0, deltaCounter, 0.001)
        val continuousCounter = LiveSessionViewerRepository.computeContinuousBearing(5f, 355f)
        assertEquals(-5f, continuousCounter, 0.001f)

        // Normal turn: 90° to 120°
        val deltaNormal = LiveSessionViewerRepository.computeShortestArcDelta(90.0, 120.0)
        assertEquals(30.0, deltaNormal, 0.001)
        val continuousNormal = LiveSessionViewerRepository.computeContinuousBearing(90f, 120f)
        assertEquals(120f, continuousNormal, 0.001f)

        // Preserves continuous angle across multi-revolution turns
        val multiTurn = LiveSessionViewerRepository.computeContinuousBearing(725f, 15f)
        // 725 % 360 = 5°, 5° to 15° is +10° -> 725 + 10 = 735°
        assertEquals(735f, multiTurn, 0.001f)

        // Edge case: NaN / Infinity
        assertEquals(0.0, LiveSessionViewerRepository.computeShortestArcDelta(Double.NaN, 5.0), 0.001)
        assertEquals(45f, LiveSessionViewerRepository.computeContinuousBearing(45f, Float.NaN), 0.001f)
    }

    @Test
    fun `computeStoppedSinceEpochMs handles transitions between motion and rest`() {
        val nowMs = 1_700_000_100_000L
        val locTimeMs = nowMs - 5 * 60_000L // 5m ago

        // In motion (speed >= 3.0 km/h) -> returns null (clears stopped timer)
        val inMotion = LiveSessionViewerRepository.computeStoppedSinceEpochMs(
            speedKmh = 18.5f,
            previousStoppedSinceMs = 1_700_000_000_000L,
            lastLocationTimestampEpochMs = locTimeMs,
            nowEpochMillis = nowMs,
        )
        assertNull(inMotion)

        // Stationary (speed < 3.0 km/h) with existing stopped timer -> preserves existing timestamp
        val existingStopped = 1_700_000_050_000L
        val keptStopped = LiveSessionViewerRepository.computeStoppedSinceEpochMs(
            speedKmh = 1.2f,
            previousStoppedSinceMs = existingStopped,
            lastLocationTimestampEpochMs = locTimeMs,
            nowEpochMillis = nowMs,
        )
        assertEquals(existingStopped, keptStopped)

        // Stationary without existing timer, recent location timestamp -> initializes to locTimeMs
        val initFromLoc = LiveSessionViewerRepository.computeStoppedSinceEpochMs(
            speedKmh = 0.0f,
            previousStoppedSinceMs = null,
            lastLocationTimestampEpochMs = locTimeMs,
            nowEpochMillis = nowMs,
        )
        assertEquals(locTimeMs, initFromLoc)

        // Stationary without existing timer, very stale location (> 30 min ago) -> initializes to nowMs
        val staleLocTimeMs = nowMs - 45 * 60_000L
        val initFromNow = LiveSessionViewerRepository.computeStoppedSinceEpochMs(
            speedKmh = 0.0f,
            previousStoppedSinceMs = null,
            lastLocationTimestampEpochMs = staleLocTimeMs,
            nowEpochMillis = nowMs,
        )
        assertEquals(nowMs, initFromNow)
    }

    @Test
    fun `evaluateSpeedOrStopped formats speed, stationary short pause, and stopped duration accurately`() {
        val strings = `in`.shvms.trackme.ui.localization.AppStrings()
        val nowMs = 1_700_000_200_000L

        // 1. Moving at 25 km/h
        val moving = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 25.0f,
            stoppedSinceEpochMs = null,
            nowEpochMillis = nowMs,
            useMiles = false,
            strings = strings,
        )
        assertFalse(moving.isStationary)
        assertEquals("25 km/h", moving.displayText)
        assertEquals("Rider speed: 25 km/h", moving.a11yText)

        // 2. Moving imperial (approx 16 mph)
        val movingImperial = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 25.0f,
            stoppedSinceEpochMs = null,
            nowEpochMillis = nowMs,
            useMiles = true,
            strings = strings,
        )
        assertFalse(movingImperial.isStationary)
        assertEquals("16 mph", movingImperial.displayText)
        assertEquals("Rider speed: 16 mph", movingImperial.a11yText)

        // 3. Just stopped (< 60s) -> displays 0 km/h
        val justStopped = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 0.5f,
            stoppedSinceEpochMs = nowMs - 35_000L, // 35s ago
            nowEpochMillis = nowMs,
            useMiles = false,
            strings = strings,
        )
        assertTrue(justStopped.isStationary)
        assertEquals("0 km/h", justStopped.displayText)
        assertEquals("Rider speed: 0 km/h", justStopped.a11yText)

        // 4. Stopped for 5 minutes (>= 60s) -> displays "Stopped · 5m"
        val stopped5m = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 0.0f,
            stoppedSinceEpochMs = nowMs - 5 * 60_000L,
            nowEpochMillis = nowMs,
            useMiles = false,
            strings = strings,
        )
        assertTrue(stopped5m.isStationary)
        assertEquals("Stopped · 5m", stopped5m.displayText)
        assertEquals("Rider stopped for 5m", stopped5m.a11yText)

        // 5. Stopped for 1 hour 15 minutes -> displays "Stopped · 1h 15m"
        val stoppedLong = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 1.0f,
            stoppedSinceEpochMs = nowMs - (75 * 60_000L),
            nowEpochMillis = nowMs,
            useMiles = false,
            strings = strings,
        )
        assertTrue(stoppedLong.isStationary)
        assertEquals("Stopped · 1h 15m", stoppedLong.displayText)
        assertEquals("Rider stopped for 1h 15m", stoppedLong.a11yText)

        // 6. Null speed -> treated as stationary 0 km/h
        val nullSpeed = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = null,
            stoppedSinceEpochMs = null,
            nowEpochMillis = nowMs,
            useMiles = false,
            strings = strings,
        )
        assertTrue(nullSpeed.isStationary)
        assertEquals("0 km/h", nullSpeed.displayText)
        assertEquals("Rider speed: 0 km/h", nullSpeed.a11yText)
    }

    @Test
    fun `evaluates metric and imperial speed and distance formats accurately`() {
        val nowMs = 1_700_000_100_000L
        val strings = `in`.shvms.trackme.ui.localization.AppStrings()

        // 35 km/h in metric -> "35 km/h"
        val metricSpeed = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 35.0f,
            stoppedSinceEpochMs = null,
            nowEpochMillis = nowMs,
            useMiles = false,
            strings = strings,
        )
        assertFalse(metricSpeed.isStationary)
        assertEquals("35 km/h", metricSpeed.displayText)
        assertEquals("Rider speed: 35 km/h", metricSpeed.a11yText)

        // 35 km/h in imperial -> 35 * 0.621371 = 21.748 -> round = 22 mph
        val imperialSpeed = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 35.0f,
            stoppedSinceEpochMs = null,
            nowEpochMillis = nowMs,
            useMiles = true,
            strings = strings,
        )
        assertFalse(imperialSpeed.isStationary)
        assertEquals("22 mph", imperialSpeed.displayText)
        assertEquals("Rider speed: 22 mph", imperialSpeed.a11yText)

        // Stationary 0 in imperial -> "0 mph"
        val zeroImperial = LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = 0.0f,
            stoppedSinceEpochMs = null,
            nowEpochMillis = nowMs,
            useMiles = true,
            strings = strings,
        )
        assertTrue(zeroImperial.isStationary)
        assertEquals("0 mph", zeroImperial.displayText)
        assertEquals("Rider speed: 0 mph", zeroImperial.a11yText)
    }

    @Test
    fun `buildShareUrl builds canonical URLs with proper parameter encoding and trailing slash handling`() {
        // Without rider name
        val urlWithoutName = LiveSessionViewerRepository.buildShareUrl("https://trackme.shvms.in", "ride-123", null)
        assertEquals("https://trackme.shvms.in/live/ride-123", urlWithoutName)

        // Trailing slashes on base URL stripped
        val urlTrailingSlash = LiveSessionViewerRepository.buildShareUrl("https://trackme.shvms.in///", "ride-123", "")
        assertEquals("https://trackme.shvms.in/live/ride-123", urlTrailingSlash)

        // Blank whitespace name omitted
        val urlBlankName = LiveSessionViewerRepository.buildShareUrl("https://trackme.shvms.in", "ride-123", "   ")
        assertEquals("https://trackme.shvms.in/live/ride-123", urlBlankName)

        // With rider name encoded
        val urlWithName = LiveSessionViewerRepository.buildShareUrl("https://trackme.shvms.in", "ride-123", "Alex Rivera")
        assertEquals("https://trackme.shvms.in/live/ride-123?name=Alex+Rivera", urlWithName)

        // Special characters in rider name and session ID
        val urlSpecial = LiveSessionViewerRepository.buildShareUrl("https://trackme.shvms.in", "ride/abc", "Jane & John")
        assertEquals("https://trackme.shvms.in/live/ride%2Fabc?name=Jane+%26+John", urlSpecial)
    }

    @Test
    fun `computeDistanceMeters returns zero for identical coordinates and accurate distance for displacements`() {
        val zero = LiveSessionViewerRepository.computeDistanceMeters(12.9716, 77.5946, 12.9716, 77.5946)
        assertEquals(0.0, zero, 0.001)

        // 1 degree latitude is approx 111.139 km
        val oneDegLatDist = LiveSessionViewerRepository.computeDistanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue(oneDegLatDist in 111_000.0..112_000.0)
    }

    @Test
    fun `shouldAnimateCamera honors 3-meter deadband threshold`() {
        // Null initial targets should always animate to establish initial center
        assertTrue(LiveSessionViewerRepository.shouldAnimateCamera(null, null, 12.9716, 77.5946))
        assertTrue(LiveSessionViewerRepository.shouldAnimateCamera(12.9716, null, 12.9716, 77.5946))

        // Tiny jitter (< 3m, e.g. ~1m displacement) should NOT animate
        // ~0.00001 deg lat is ~1.11 meters
        val jitterLat = 12.9716 + 0.00001
        assertFalse(
            LiveSessionViewerRepository.shouldAnimateCamera(
                lastTargetLat = 12.9716,
                lastTargetLng = 77.5946,
                newTargetLat = jitterLat,
                newTargetLng = 77.5946,
            )
        )

        // Larger displacement (> 3m, e.g. ~0.00005 deg lat is ~5.55 meters) SHOULD animate
        val movedLat = 12.9716 + 0.00005
        assertTrue(
            LiveSessionViewerRepository.shouldAnimateCamera(
                lastTargetLat = 12.9716,
                lastTargetLng = 77.5946,
                newTargetLat = movedLat,
                newTargetLng = 77.5946,
            )
        )
    }

    @Test
    fun `shouldUpdateBearing honors stationary status and 3-degree deadband threshold`() {
        // Stationary should NEVER update bearing (saves battery and preserves last known facing direction)
        assertFalse(LiveSessionViewerRepository.shouldUpdateBearing(currentBearing = 45f, targetBearing = 90f, isStationary = true))

        // NaN target should be ignored
        assertFalse(LiveSessionViewerRepository.shouldUpdateBearing(currentBearing = 45f, targetBearing = Float.NaN, isStationary = false))

        // Small jitter (< 3 degrees) while in motion should be suppressed
        assertFalse(LiveSessionViewerRepository.shouldUpdateBearing(currentBearing = 45f, targetBearing = 47f, isStationary = false))
        assertFalse(LiveSessionViewerRepository.shouldUpdateBearing(currentBearing = 1f, targetBearing = 359f, isStationary = false)) // 2 degree shortest arc

        // Substantial change (>= 3 degrees) while in motion should update
        assertTrue(LiveSessionViewerRepository.shouldUpdateBearing(currentBearing = 45f, targetBearing = 48f, isStationary = false))
        assertTrue(LiveSessionViewerRepository.shouldUpdateBearing(currentBearing = 355f, targetBearing = 2f, isStationary = false)) // 7 degree shortest arc
    }

    @Test
    fun `evaluateNetworkReconnection handles offline, reconnection, and ended transitions correctly`() {
        // Offline during active session: show offline toast, do not fetch immediately
        val offlineState = LiveSessionViewerRepository.evaluateNetworkReconnection(
            isOffline = true,
            wasOffline = false,
            isActive = true,
            isExpiredOrMissing = false,
        )
        assertFalse(offlineState.shouldFetchImmediately)
        assertFalse(offlineState.resetConsecutiveErrors)
        assertTrue(offlineState.showOfflineToast)

        // Reconnecting from offline to online: fetch immediately, reset errors, dismiss toast
        val reconnectingState = LiveSessionViewerRepository.evaluateNetworkReconnection(
            isOffline = false,
            wasOffline = true,
            isActive = true,
            isExpiredOrMissing = false,
        )
        assertTrue(reconnectingState.shouldFetchImmediately)
        assertTrue(reconnectingState.resetConsecutiveErrors)
        assertFalse(reconnectingState.showOfflineToast)

        // Steady online state: no toast, no immediate trigger needed
        val steadyOnline = LiveSessionViewerRepository.evaluateNetworkReconnection(
            isOffline = false,
            wasOffline = false,
            isActive = true,
            isExpiredOrMissing = false,
        )
        assertFalse(steadyOnline.shouldFetchImmediately)
        assertFalse(steadyOnline.resetConsecutiveErrors)
        assertFalse(steadyOnline.showOfflineToast)

        // Ended or expired session: suppresses toasts and immediate fetch
        val endedState = LiveSessionViewerRepository.evaluateNetworkReconnection(
            isOffline = true,
            wasOffline = true,
            isActive = false,
            isExpiredOrMissing = false,
        )
        assertFalse(endedState.shouldFetchImmediately)
        assertFalse(endedState.resetConsecutiveErrors)
        assertFalse(endedState.showOfflineToast)
    }

    @Test
    fun `snapshot caching and viewport preservation across configuration changes (CIE-035)`() {
        val testSessionId = "cie035_test_session"
        val sampleSnapshot = LiveSessionSnapshot(
            sessionId = testSessionId,
            username = "Rider",
            status = "active",
            stopReason = null,
            endReason = null,
            endedAt = null,
            destination = "Central Park",
            deadlineAt = null,
            lastLocation = LiveViewerLastLocation(
                lat = 28.6139,
                lng = 77.2090,
                speedKmh = 18f,
                headingDeg = 90f,
                batteryLevel = 85,
                powerSaveMode = false,
                timestampIso = "2026-11-13T12:00:00Z",
            ),
            path = listOf(LiveViewerPoint(28.6139, 77.2090)),
        )

        // Initial state
        LiveSessionViewerRepository.clearCachedSnapshot(testSessionId)
        assertNull(LiveSessionViewerRepository.getCachedSnapshot(testSessionId))

        // Cache snapshot
        LiveSessionViewerRepository.cacheSnapshot(testSessionId, sampleSnapshot)
        val retrieved = LiveSessionViewerRepository.getCachedSnapshot(testSessionId)
        assertNotNull(retrieved)
        assertEquals("Central Park", retrieved?.destination)
        assertEquals(85, retrieved?.lastLocation?.batteryLevel)

        // Clear cache
        LiveSessionViewerRepository.clearCachedSnapshot(testSessionId)
        assertNull(LiveSessionViewerRepository.getCachedSnapshot(testSessionId))

        // Viewport preservation evaluation:
        // 1. If not following rider and already centered initially, camera should not move
        val shouldMoveUserPanned = LiveSessionViewerRepository.evaluateViewportPreservation(
            isFollowingRider = false,
            lastTargetLat = 28.6139,
            lastTargetLng = 77.2090,
            newTargetLat = 28.6200,
            newTargetLng = 77.2100,
            hasCenteredInitially = true,
        )
        assertFalse(shouldMoveUserPanned)

        // 2. If not centered initially, camera must move regardless of follow mode
        val shouldMoveInitial = LiveSessionViewerRepository.evaluateViewportPreservation(
            isFollowingRider = false,
            lastTargetLat = null,
            lastTargetLng = null,
            newTargetLat = 28.6139,
            newTargetLng = 77.2090,
            hasCenteredInitially = false,
        )
        assertTrue(shouldMoveInitial)

        // 3. If following rider and rider moved >= 3m, camera should animate
        val shouldMoveFollow = LiveSessionViewerRepository.evaluateViewportPreservation(
            isFollowingRider = true,
            lastTargetLat = 28.6139,
            lastTargetLng = 77.2090,
            newTargetLat = 28.6150,
            newTargetLng = 77.2090, // ~122m displacement
            hasCenteredInitially = true,
        )
        assertTrue(shouldMoveFollow)

        // 4. If following rider but displacement < 3m, suppress camera jitter
        val shouldMoveJitter = LiveSessionViewerRepository.evaluateViewportPreservation(
            isFollowingRider = true,
            lastTargetLat = 28.6139,
            lastTargetLng = 77.2090,
            newTargetLat = 28.6139001,
            newTargetLng = 77.2090,
            hasCenteredInitially = true,
        )
        assertFalse(shouldMoveJitter)
    }

    @Test
    fun `reduced motion and screen reader accessibility parity (CIE-036)`() {
        // 1. Companion reduced motion evaluation
        assertTrue(LiveSessionViewerRepository.shouldAnimateTransition(isReducedMotion = false))
        assertFalse(LiveSessionViewerRepository.shouldAnimateTransition(isReducedMotion = true))

        // 2. Instance reduced motion evaluation
        val repo = LiveSessionViewerRepository()
        assertTrue(repo.shouldAnimateTransition(isReducedMotion = false))
        assertFalse(repo.shouldAnimateTransition(isReducedMotion = true))

        // 3. 7-locale parity for new network reconnected toast
        val languages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")
        for (lang in languages) {
            val s = getAppStrings(lang)
            assertTrue("reconnected toast must not be empty for $lang", s.liveViewerNetworkReconnectedToast.isNotBlank())
        }
    }

    @Test
    fun `low battery throttle and power-save mode parity (CIE-037)`() {
        val repo = LiveSessionViewerRepository()

        // 1. clampBatteryLevel (companion and instance)
        assertEquals(0, LiveSessionViewerRepository.clampBatteryLevel(0))
        assertEquals(15, LiveSessionViewerRepository.clampBatteryLevel(15))
        assertEquals(80, LiveSessionViewerRepository.clampBatteryLevel(80))
        assertEquals(100, LiveSessionViewerRepository.clampBatteryLevel(100))
        assertNull(LiveSessionViewerRepository.clampBatteryLevel(-1)) // Simulator fallback
        assertNull(LiveSessionViewerRepository.clampBatteryLevel(-25))
        assertNull(LiveSessionViewerRepository.clampBatteryLevel(101))
        assertNull(LiveSessionViewerRepository.clampBatteryLevel(250))
        assertNull(LiveSessionViewerRepository.clampBatteryLevel(null))

        assertEquals(50, repo.clampBatteryLevel(50))
        assertNull(repo.clampBatteryLevel(-1))

        // 2. shouldAnnounceLowBattery (companion and instance)
        // Initial entry into <= 15%
        assertTrue(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = null, newBattery = 15))
        assertTrue(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 80, newBattery = 15))
        assertTrue(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 16, newBattery = 14))

        // Suppresses intermediate 1% drops
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 15, newBattery = 14))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 14, newBattery = 13))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 13, newBattery = 10))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 10, newBattery = 8))

        // Critical entry (<= 5%)
        assertTrue(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 12, newBattery = 5))
        assertTrue(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 6, newBattery = 4))
        assertTrue(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 15, newBattery = 3))

        // Suppresses intermediate drops in critical zone
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 5, newBattery = 4))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 4, newBattery = 2))

        // Normal, charging, or invalid inputs
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = null, newBattery = 80))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 15, newBattery = 20))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 5, newBattery = 5))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = null, newBattery = -1))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = -1, newBattery = -1))
        assertFalse(LiveSessionViewerRepository.shouldAnnounceLowBattery(previousBattery = 80, newBattery = null))

        assertTrue(repo.shouldAnnounceLowBattery(previousBattery = 50, newBattery = 15))
        assertFalse(repo.shouldAnnounceLowBattery(previousBattery = 15, newBattery = 14))

        // 3. computeViewerAdaptivePollDelay (companion and instance)
        assertEquals(12_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = true, batteryLevel = 80))
        assertEquals(12_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = true, batteryLevel = null))
        assertEquals(12_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = 15))
        assertEquals(12_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = 5))
        assertEquals(5_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = 80))
        assertEquals(5_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = null))
        assertEquals(5_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = -1))
        assertEquals(7_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = 80, basePollDelay = 7_000L))
        assertEquals(12_000L, LiveSessionViewerRepository.computeViewerAdaptivePollDelay(powerSaveMode = true, batteryLevel = 80, basePollDelay = 7_000L))

        assertEquals(12_000L, repo.computeViewerAdaptivePollDelay(powerSaveMode = true, batteryLevel = 80))
        assertEquals(5_000L, repo.computeViewerAdaptivePollDelay(powerSaveMode = false, batteryLevel = 80))

        // 4. formatPowerSaveCadenceNotice
        assertNotNull(LiveSessionViewerRepository.formatPowerSaveCadenceNotice(powerSaveMode = true, batteryLevel = 80))
        assertNotNull(LiveSessionViewerRepository.formatPowerSaveCadenceNotice(powerSaveMode = false, batteryLevel = 14))
        assertNull(LiveSessionViewerRepository.formatPowerSaveCadenceNotice(powerSaveMode = false, batteryLevel = 50))
        assertNull(LiveSessionViewerRepository.formatPowerSaveCadenceNotice(powerSaveMode = false, batteryLevel = null))
        assertNull(LiveSessionViewerRepository.formatPowerSaveCadenceNotice(powerSaveMode = false, batteryLevel = -1))
    }

    @Test
    fun `breadcrumb polyline decimation and memory compaction parity (CIE-038)`() {
        val repo = LiveSessionViewerRepository()

        // 1. shouldDecimatePolyline (companion and instance)
        assertFalse(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 0, minPointsThreshold = 200))
        assertFalse(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 50, minPointsThreshold = 200))
        assertFalse(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 200, minPointsThreshold = 200))
        assertTrue(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 201, minPointsThreshold = 200))
        assertTrue(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 500, minPointsThreshold = 200))
        assertTrue(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 60, minPointsThreshold = 50))
        assertFalse(LiveSessionViewerRepository.shouldDecimatePolyline(pointCount = 40, minPointsThreshold = 50))

        assertTrue(repo.shouldDecimatePolyline(300, 200))
        assertFalse(repo.shouldDecimatePolyline(100, 200))

        // 2. computePerpendicularDistanceMeters
        val aLat = 28.6139
        val aLng = 77.2000
        val bLat = 28.6139
        val bLng = 77.2100

        // Midpoint on the line
        val midLat = 28.6139
        val midLng = 77.2050
        val zeroDist = LiveSessionViewerRepository.computePerpendicularDistanceMeters(
            midLat, midLng, aLat, aLng, bLat, bLng
        )
        assertEquals(0.0, zeroDist, 0.01)

        // Point offset North by 0.001 deg lat (approx 111.3 meters)
        val offsetLat = 28.6149
        val offsetLng = 77.2050
        val perpDist = LiveSessionViewerRepository.computePerpendicularDistanceMeters(
            offsetLat, offsetLng, aLat, aLng, bLat, bLng
        )
        assertEquals(111.3, perpDist, 1.0)

        // Coincident line endpoints
        val coincidentDist = LiveSessionViewerRepository.computePerpendicularDistanceMeters(
            offsetLat, offsetLng, aLat, aLng, aLat, aLng
        )
        assertTrue(coincidentDist > 100.0)

        // 3. decimatePoints (companion and instance)
        // Small arrays bypass
        val twoPoints = listOf(
            LiveViewerPoint(28.610, 77.200),
            LiveViewerPoint(28.615, 77.205)
        )
        assertEquals(2, LiveSessionViewerRepository.decimatePoints(twoPoints, minPointsThreshold = 0).size)

        // Threshold bypass
        val fourPoints = listOf(
            LiveViewerPoint(28.610, 77.200),
            LiveViewerPoint(28.611, 77.200),
            LiveViewerPoint(28.612, 77.200),
            LiveViewerPoint(28.613, 77.200)
        )
        assertEquals(4, LiveSessionViewerRepository.decimatePoints(fourPoints, minPointsThreshold = 10).size)

        // Collinear reduction
        val collinearPoints = listOf(
            LiveViewerPoint(28.6100, 77.2000),
            LiveViewerPoint(28.6110, 77.2000),
            LiveViewerPoint(28.6120, 77.2000),
            LiveViewerPoint(28.6130, 77.2000),
            LiveViewerPoint(28.6140, 77.2000),
            LiveViewerPoint(28.6150, 77.2000)
        )
        val decimatedCollinear = LiveSessionViewerRepository.decimatePoints(collinearPoints, epsilonMeters = 3.5, minPointsThreshold = 0)
        assertEquals(2, decimatedCollinear.size)
        assertEquals(collinearPoints.first().lat, decimatedCollinear.first().lat, 0.000001)
        assertEquals(collinearPoints.last().lat, decimatedCollinear.last().lat, 0.000001)

        // Right-angle corner retention
        val cornerPoints = listOf(
            LiveViewerPoint(28.6100, 77.2000), // Start
            LiveViewerPoint(28.6110, 77.2000), // Leg 1 collinear
            LiveViewerPoint(28.6120, 77.2000), // Corner turn
            LiveViewerPoint(28.6120, 77.2010), // Leg 2 collinear
            LiveViewerPoint(28.6120, 77.2020)  // Finish
        )
        val decimatedCorner = LiveSessionViewerRepository.decimatePoints(cornerPoints, epsilonMeters = 3.5, minPointsThreshold = 0)
        assertEquals(3, decimatedCorner.size)
        assertEquals(cornerPoints[0].lat, decimatedCorner[0].lat, 0.000001)
        assertEquals(cornerPoints[2].lat, decimatedCorner[1].lat, 0.000001) // Corner turn preserved
        assertEquals(cornerPoints[4].lng, decimatedCorner[2].lng, 0.000001) // Finish preserved

        val instanceDecimated = repo.decimatePoints(cornerPoints, epsilonMeters = 3.5, minPointsThreshold = 0)
        assertEquals(3, instanceDecimated.size)

        // 4. decimateLatLngs (companion and instance)
        val cornerLatLngs = cornerPoints.map { com.google.android.gms.maps.model.LatLng(it.lat, it.lng) }
        val decimatedLatLngs = LiveSessionViewerRepository.decimateLatLngs(cornerLatLngs, epsilonMeters = 3.5, minPointsThreshold = 0)
        assertEquals(3, decimatedLatLngs.size)
        assertEquals(cornerLatLngs[0].latitude, decimatedLatLngs[0].latitude, 0.000001)
        assertEquals(cornerLatLngs[2].latitude, decimatedLatLngs[1].latitude, 0.000001)
        assertEquals(cornerLatLngs[4].longitude, decimatedLatLngs[2].longitude, 0.000001)

        val instanceDecimatedLatLngs = repo.decimateLatLngs(cornerLatLngs, epsilonMeters = 3.5, minPointsThreshold = 0)
        assertEquals(3, instanceDecimatedLatLngs.size)
    }

    @Test
    fun `computeElapsedSeconds handles valid, missing, and inverted timestamps`() {
        val nowMs = 1_700_000_100_000L
        val startIso = java.time.Instant.ofEpochMilli(nowMs - 3_600_000L).toString() // 1 hr ago
        val endIso = java.time.Instant.ofEpochMilli(nowMs - 600_000L).toString() // 10 min ago

        // Companion & instance parity
        val repo = LiveSessionViewerRepository()
        assertEquals(3000L, LiveSessionViewerRepository.computeElapsedSeconds(startIso, endIso, nowMs))
        assertEquals(3000L, repo.computeElapsedSeconds(startIso, endIso, nowMs))

        // Null start returns null
        assertNull(LiveSessionViewerRepository.computeElapsedSeconds(null, endIso, nowMs))
        assertNull(repo.computeElapsedSeconds("", endIso, nowMs))

        // Missing end falls back to nowEpochMillis
        assertEquals(3600L, LiveSessionViewerRepository.computeElapsedSeconds(startIso, null, nowMs))

        // Inverted timestamps clamp at 0
        assertEquals(0L, LiveSessionViewerRepository.computeElapsedSeconds(endIso, startIso, nowMs))
    }

    @Test
    fun `computeAverageSpeed calculates mps and suppresses anomalies and micro-trips`() {
        val repo = LiveSessionViewerRepository()

        // 10,000 meters in 1,000 seconds = 10.0 m/s (~36 km/h)
        val normalSpeed = LiveSessionViewerRepository.computeAverageSpeed(10_000.0, 1_000L)
        assertNotNull(normalSpeed)
        assertEquals(10.0, normalSpeed!!, 0.001)
        assertEquals(10.0, repo.computeAverageSpeed(10_000.0, 1_000L)!!, 0.001)

        // Micro-duration (< 5 seconds) returns null
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(100.0, 4L))
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(100.0, 0L))
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(100.0, -10L))

        // Zero or negative distance returns null
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(0.0, 60L))
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(-50.0, 60L))

        // Supersonic teleport anomaly (> 55.55 m/s = 200 km/h) returns null
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(60_000.0, 100L)) // 600 m/s

        // Sub-0.1 m/s crawl returns null
        assertNull(LiveSessionViewerRepository.computeAverageSpeed(1.0, 100L)) // 0.01 m/s
    }

    @Test
    fun `formatAverageSpeed formats metric and imperial units with US locale numbers`() {
        val repo = LiveSessionViewerRepository()
        val speedMps = 10.0 // 36 km/h or ~22.4 mph

        assertEquals("36.0 km/h", LiveSessionViewerRepository.formatAverageSpeed(speedMps, useMiles = false))
        assertEquals("22.4 mph", LiveSessionViewerRepository.formatAverageSpeed(speedMps, useMiles = true))

        assertEquals("36.0 km/h", repo.formatAverageSpeed(speedMps, useMiles = false))
        assertEquals("22.4 mph", repo.formatAverageSpeed(speedMps, useMiles = true))

        assertNull(LiveSessionViewerRepository.formatAverageSpeed(null))
        assertNull(LiveSessionViewerRepository.formatAverageSpeed(Double.NaN))
        assertNull(LiveSessionViewerRepository.formatAverageSpeed(-5.0))
        assertNull(LiveSessionViewerRepository.formatAverageSpeed(0.0))
    }

    @Test
    fun `formatConcludedSummary formats recap with stopReason, distance, duration, and speed`() {
        val nowMs = 1_700_000_100_000L
        val startIso = java.time.Instant.ofEpochMilli(nowMs - 3_600_000L).toString() // 1h ago
        val endIso = java.time.Instant.ofEpochMilli(nowMs).toString()
        val distanceMeters = 18_000.0 // 18.0 km in 3600s = 5.0 m/s = 18.0 km/h

        val summary = LiveSessionViewerRepository.formatConcludedSummary(
            username = "Alice",
            startedAt = startIso,
            endedAt = endIso,
            stopReason = "Arrived at destination",
            totalDistanceMeters = distanceMeters,
            useMiles = false,
            nowEpochMillis = nowMs,
        )

        assertEquals("Arrived at destination", summary.statusText)
        assertEquals("18.0 km", summary.distanceStr)
        assertEquals("1h", summary.durationStr)
        assertEquals("18.0 km/h", summary.avgSpeedStr)
        assertEquals("18.0 km · 1h · Ø 18.0 km/h", summary.detailsText)
        assertEquals(
            "Alice's trip ended: Arrived at destination. Covered 18.0 km in 1h, average speed 18.0 km/h.",
            summary.a11yText,
        )

        // Imperial formatting
        val imperialSummary = LiveSessionViewerRepository.formatConcludedSummary(
            username = "Bob",
            startedAt = startIso,
            endedAt = endIso,
            stopReason = null,
            totalDistanceMeters = distanceMeters,
            useMiles = true,
            nowEpochMillis = nowMs,
        )
        assertEquals("Trip completed", imperialSummary.statusText)
        assertEquals("11.2 mi", imperialSummary.distanceStr)
        assertEquals("11.2 mph", imperialSummary.avgSpeedStr)
        assertEquals("11.2 mi · 1h · Ø 11.2 mph", imperialSummary.detailsText)
        assertEquals(
            "Bob's trip ended: Trip completed. Covered 11.2 mi in 1h, average speed 11.2 mph.",
            imperialSummary.a11yText,
        )

        // Snapshot helper integration
        val snapshot = LiveSessionSnapshot(
            sessionId = "sess_ended",
            username = "Charlie",
            status = "ended",
            startedAt = startIso,
            endedAt = endIso,
            stopReason = "Stopped by rider",
            endReason = null,
            destination = null,
            deadlineAt = null,
            lastLocation = null,
            path = listOf(
                LiveViewerPoint(28.6100, 77.2000),
                LiveViewerPoint(28.7100, 77.2000),
            ),
        )
        val snapSummary = snapshot.concludedSummary(useMiles = false, nowEpochMillis = nowMs)
        assertEquals("Stopped by rider", snapSummary.statusText)
        assertTrue(snapSummary.distanceStr.contains("km"))
        assertNotNull(snapSummary.avgSpeedStr)
    }

    @Test
    fun `avgSpeed localization is present across all 7 shipped languages`() {
        for (lang in SUPPORTED_LANGUAGE_CODES) {
            val strings = getAppStrings(lang)
            val avgSpeed = strings.avgSpeed
            assertTrue("avgSpeed missing or blank for $lang", avgSpeed.isNotBlank())
        }
    }

    @Test
    fun `evaluates off-screen distance badge and follow mode auto-resume`() {
        val centerLat = 37.7749
        val centerLng = -122.4194
        val nearLat = 37.7755
        val nearLng = -122.4190 // ~80m
        val farLat = 37.7880
        val farLng = -122.4050 // ~1.9km

        val nearDist = LiveSessionViewerRepository.computeOffCenterDistanceMeters(centerLat, centerLng, nearLat, nearLng)
        val farDist = LiveSessionViewerRepository.computeOffCenterDistanceMeters(centerLat, centerLng, farLat, farLng)
        assertTrue(nearDist < 200.0)
        assertTrue(farDist > 1000.0)

        // Threshold suppressions
        assertNull(LiveSessionViewerRepository.formatOffCenterDistance(null))
        assertNull(LiveSessionViewerRepository.formatOffCenterDistance(-50.0))
        assertNull(LiveSessionViewerRepository.formatOffCenterDistance(Double.NaN))
        assertNull(LiveSessionViewerRepository.formatOffCenterDistance(249.0, thresholdMeters = 250.0))

        // Formatting above threshold
        assertEquals("1.4 km", LiveSessionViewerRepository.formatOffCenterDistance(1400.0, useMiles = false))
        assertEquals("1.0 mi", LiveSessionViewerRepository.formatOffCenterDistance(1609.344, useMiles = true))

        // Recenter button label
        assertEquals(
            "Recenter",
            LiveSessionViewerRepository.formatRecenterButtonLabel(100.0, baseLabel = "Recenter"),
        )
        assertEquals(
            "Recenter · 1.4 km",
            LiveSessionViewerRepository.formatRecenterButtonLabel(1400.0, baseLabel = "Recenter"),
        )
        assertEquals(
            "Recenter · 1.0 mi",
            LiveSessionViewerRepository.formatRecenterButtonLabel(1609.344, useMiles = true, baseLabel = "Recenter"),
        )

        // Recenter accessibility label
        assertEquals(
            "Recenter on rider",
            LiveSessionViewerRepository.formatRecenterAccessibilityLabel(100.0, baseLabel = "Recenter on rider"),
        )
        assertEquals(
            "Recenter on rider, 1.4 km away",
            LiveSessionViewerRepository.formatRecenterAccessibilityLabel(1400.0, baseLabel = "Recenter on rider"),
        )

        // Follow-mode auto resume
        val baseMs = 1_700_000_000_000L
        val twentyOneSecLater = baseMs + 21_000L
        val tenSecLater = baseMs + 10_000L

        // Already following -> false
        assertFalse(
            LiveSessionViewerRepository.shouldResumeFollowMode(
                isFollowing = true,
                isEnded = false,
                lastInteractionEpochMs = baseMs,
                nowEpochMillis = twentyOneSecLater,
                timeoutMs = 20_000L,
            )
        )

        // Ended trip -> false
        assertFalse(
            LiveSessionViewerRepository.shouldResumeFollowMode(
                isFollowing = false,
                isEnded = true,
                lastInteractionEpochMs = baseMs,
                nowEpochMillis = twentyOneSecLater,
                timeoutMs = 20_000L,
            )
        )

        // Null last interaction -> false
        assertFalse(
            LiveSessionViewerRepository.shouldResumeFollowMode(
                isFollowing = false,
                isEnded = false,
                lastInteractionEpochMs = null,
                nowEpochMillis = twentyOneSecLater,
                timeoutMs = 20_000L,
            )
        )

        // Elapsed < timeout -> false
        assertFalse(
            LiveSessionViewerRepository.shouldResumeFollowMode(
                isFollowing = false,
                isEnded = false,
                lastInteractionEpochMs = baseMs,
                nowEpochMillis = tenSecLater,
                timeoutMs = 20_000L,
            )
        )

        // Elapsed >= timeout -> true
        assertTrue(
            LiveSessionViewerRepository.shouldResumeFollowMode(
                isFollowing = false,
                isEnded = false,
                lastInteractionEpochMs = baseMs,
                nowEpochMillis = twentyOneSecLater,
                timeoutMs = 20_000L,
            )
        )
    }

    // MARK: - CIE-041: Health Telemetry & Latency Diagnostics Tests

    @Test
    fun `computePollLatencyMs computes positive round latency and clamps negative to 0`() {
        assertEquals(245L, LiveSessionViewerRepository.computePollLatencyMs(1000L, 1245L))
        assertEquals(0L, LiveSessionViewerRepository.computePollLatencyMs(2000L, 1000L))
    }

    @Test
    fun `shouldSampleViewerHealth gates at 1 percent sample rate and clamps bounds`() {
        assertTrue(LiveSessionViewerRepository.shouldSampleViewerHealth(0.01) { 0.005 })
        assertFalse(LiveSessionViewerRepository.shouldSampleViewerHealth(0.01) { 0.015 })

        assertFalse(LiveSessionViewerRepository.shouldSampleViewerHealth(-0.5) { 0.0001 })
        assertTrue(LiveSessionViewerRepository.shouldSampleViewerHealth(1.5) { 0.999 })
    }

    @Test
    fun `classifyViewerError categorizes status codes and error messages correctly`() {
        assertEquals("not_found_or_expired", LiveSessionViewerRepository.classifyViewerError(404, null))
        assertEquals("not_found_or_expired", LiveSessionViewerRepository.classifyViewerError(410, null))
        assertEquals("rate_limited", LiveSessionViewerRepository.classifyViewerError(429, null))
        assertEquals("server_error", LiveSessionViewerRepository.classifyViewerError(500, null))
        assertEquals("server_error", LiveSessionViewerRepository.classifyViewerError(503, null))

        assertEquals("malformed_response", LiveSessionViewerRepository.classifyViewerError(null, "Invalid response from server"))
        assertEquals("malformed_response", LiveSessionViewerRepository.classifyViewerError(null, "org.json.JSONException: Value at 0"))
        assertEquals("offline_network", LiveSessionViewerRepository.classifyViewerError(0, null))
        assertEquals("offline_network", LiveSessionViewerRepository.classifyViewerError(null, "java.net.UnknownHostException: Unable to resolve host"))
        assertEquals("offline_network", LiveSessionViewerRepository.classifyViewerError(null, "Failed to connect to /127.0.0.1:443"))
        assertEquals("not_found_or_expired", LiveSessionViewerRepository.classifyViewerError(null, "Session expired"))
        assertEquals("unknown", LiveSessionViewerRepository.classifyViewerError(400, "Bad Request"))
    }

    @Test
    fun `buildViewerHealthTelemetryPayload builds sanitized, privacy-safe payload without coordinates`() {
        val payload = LiveSessionViewerRepository.buildViewerHealthTelemetryPayload(
            sessionId = "live-sess_123!@#",
            pollLatencyMs = 185L,
            statusCode = 200,
            isActive = true,
            hasLocation = true,
            batteryLevel = 82,
            pointCount = 14,
            isStale = false,
        )

        assertEquals("live-sess_123", payload["session_id"])
        assertEquals(185L, payload["poll_latency_ms"])
        assertEquals(200, payload["status_code"])
        assertEquals(true, payload["is_active"])
        assertEquals(true, payload["has_location"])
        assertEquals(82, payload["battery_level"])
        assertEquals(14, payload["point_count"])
        assertEquals(false, payload["is_stale"])
        assertEquals("android", payload["platform"])

        assertNull(payload["lat"])
        assertNull(payload["lng"])
        assertNull(payload["token"])

        // Out of bounds battery clamped to null
        val invalidBatPayload = LiveSessionViewerRepository.buildViewerHealthTelemetryPayload(
            sessionId = "sess_2",
            pollLatencyMs = -10L,
            statusCode = 500,
            isActive = false,
            hasLocation = false,
            batteryLevel = 150,
            pointCount = -1,
        )
        assertEquals(0L, invalidBatPayload["poll_latency_ms"])
        assertNull(invalidBatPayload["battery_level"])
        assertEquals(0, invalidBatPayload["point_count"])
    }

    // MARK: - CIE-042: Reconnection State Memory & Cache Resync Tests

    @Test
    fun `shouldPreserveViewerState preserves viewer state when location was previously rendered even during offline dead zones`() {
        assertTrue(LiveSessionViewerRepository.shouldPreserveViewerState(hasRenderedLocation = true, isOffline = true))
        assertTrue(LiveSessionViewerRepository.shouldPreserveViewerState(hasRenderedLocation = true, isOffline = false))

        assertFalse(LiveSessionViewerRepository.shouldPreserveViewerState(hasRenderedLocation = false, isOffline = true))
        assertFalse(LiveSessionViewerRepository.shouldPreserveViewerState(hasRenderedLocation = false, isOffline = false))
    }

    @Test
    fun `evaluateReconnectionResync handles trip ended, offline, and reconnected states properly`() {
        // Trip ended
        val ended = LiveSessionViewerRepository.evaluateReconnectionResync(
            isOnline = true,
            wasOffline = true,
            hasCachedSnapshot = true,
            isTripEnded = true
        )
        assertFalse(ended.shouldFetchImmediately)
        assertTrue(ended.shouldUseCachedState)
        assertFalse(ended.resetConsecutiveErrors)
        assertNull(ended.statusMessage)

        // Offline
        val offline = LiveSessionViewerRepository.evaluateReconnectionResync(
            isOnline = false,
            wasOffline = false,
            hasCachedSnapshot = true,
            isTripEnded = false
        )
        assertFalse(offline.shouldFetchImmediately)
        assertTrue(offline.shouldUseCachedState)
        assertFalse(offline.resetConsecutiveErrors)
        assertEquals("Viewer Offline", offline.statusMessage)

        // Reconnected after offline
        val reconnected = LiveSessionViewerRepository.evaluateReconnectionResync(
            isOnline = true,
            wasOffline = true,
            hasCachedSnapshot = true,
            isTripEnded = false,
            isFetchInProgress = false
        )
        assertTrue(reconnected.shouldFetchImmediately)
        assertTrue(reconnected.shouldUseCachedState)
        assertTrue(reconnected.resetConsecutiveErrors)
        assertEquals("Reconnecting...", reconnected.statusMessage)

        // Reconnected but fetch already in flight
        val inFlight = LiveSessionViewerRepository.evaluateReconnectionResync(
            isOnline = true,
            wasOffline = true,
            hasCachedSnapshot = true,
            isTripEnded = false,
            isFetchInProgress = true
        )
        assertFalse(inFlight.shouldFetchImmediately)
        assertTrue(inFlight.shouldUseCachedState)
        assertTrue(inFlight.resetConsecutiveErrors)
        assertEquals("Reconnecting...", inFlight.statusMessage)

        // Steady state online
        val steady = LiveSessionViewerRepository.evaluateReconnectionResync(
            isOnline = true,
            wasOffline = false,
            hasCachedSnapshot = true,
            isTripEnded = false
        )
        assertFalse(steady.shouldFetchImmediately)
        assertTrue(steady.shouldUseCachedState)
        assertFalse(steady.resetConsecutiveErrors)
        assertNull(steady.statusMessage)
    }
}


