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
}

