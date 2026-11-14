package `in`.shvms.trackme.domain.tracking

import org.junit.Assert.*
import org.junit.Test

class DwellDetectorTest {

    @Test
    fun `feature disabled returns active tracking always`() {
        val detector = DwellDetector(isAutoTripChainingEnabled = false)
        val res = detector.evaluate(12.9716, 77.5946, speedKmh = 0.0, nowEpochMs = 1000L)
        assertEquals(DwellState.ACTIVE_TRACKING, res.state)
        assertFalse(res.shouldAutoEndRide)
        assertFalse(res.shouldAutoStartNextRide)
    }

    @Test
    fun `normal riding stays active`() {
        val detector = DwellDetector(isAutoTripChainingEnabled = true)
        val res = detector.evaluate(12.9716, 77.5946, speedKmh = 22.5, nowEpochMs = 1000L)
        assertEquals(DwellState.ACTIVE_TRACKING, res.state)
        assertFalse(res.shouldAutoEndRide)
    }

    @Test
    fun `traffic light stop under 45s resets on resumption`() {
        val detector = DwellDetector(isAutoTripChainingEnabled = true, stationaryConfirmationDurationMs = 45_000L)

        // Stop starts at t = 10_000
        var res = detector.evaluate(12.9716, 77.5946, speedKmh = 0.5, nowEpochMs = 10_000L)
        assertEquals(DwellState.ACTIVE_TRACKING, res.state)

        // 30 seconds later (t = 40_000), still stopped
        res = detector.evaluate(12.9716, 77.5946, speedKmh = 0.2, nowEpochMs = 40_000L)
        assertEquals(DwellState.ACTIVE_TRACKING, res.state)

        // Resumes speed at 15 km/h at t = 42_000 (< 45s)
        res = detector.evaluate(12.9718, 77.5946, speedKmh = 18.0, nowEpochMs = 42_000L)
        assertEquals(DwellState.ACTIVE_TRACKING, res.state)
    }

    @Test
    fun `dwelling anchor dropped after 45s and indoor pacing remains in dwell`() {
        val detector = DwellDetector(
            isAutoTripChainingEnabled = true,
            autoEndDwellMinutes = 10,
            stationaryConfirmationDurationMs = 45_000L,
            dwellAnchorRadiusMeters = 75.0
        )

        val anchorLat = 12.9716
        val anchorLng = 77.5946
        val startDwellMs = 100_000L

        // Stop starts
        detector.evaluate(anchorLat, anchorLng, speedKmh = 0.0, nowEpochMs = startDwellMs)

        // 45 seconds later, anchor confirmed
        val res45 = detector.evaluate(anchorLat, anchorLng, speedKmh = 0.5, nowEpochMs = startDwellMs + 45_000L)
        assertEquals(DwellState.DWELLING, res45.state)
        assertEquals(anchorLat, res45.dwellAnchorLat!!, 0.0001)
        assertEquals(anchorLng, res45.dwellAnchorLng!!, 0.0001)

        // Walking inside apartment ~25m away with indoor GPS drift speeds (1.8 km/h)
        val indoorLat = anchorLat + 0.0002 // ~22 meters
        val resIndoor = detector.evaluate(indoorLat, anchorLng, speedKmh = 1.8, nowEpochMs = startDwellMs + 180_000L)
        assertEquals(DwellState.DWELLING, resIndoor.state)
        assertTrue("Distance should be ~22m", resIndoor.distanceFromAnchorMeters in 15.0..30.0)
        assertFalse(resIndoor.shouldAutoEndRide)

        // 10 minutes reached (startDwellMs + 600_000L)
        val resEnd = detector.evaluate(indoorLat, anchorLng, speedKmh = 0.0, nowEpochMs = startDwellMs + 600_000L)
        assertEquals(DwellState.TRIP_AUTO_ENDED, resEnd.state)
        assertTrue(resEnd.shouldAutoEndRide)
        assertEquals(startDwellMs, resEnd.autoEndTimestampEpochMs)
        assertTrue(resEnd.shouldPruneTrailingPoints)
    }

    @Test
    fun `breakout from dwell before timeout aborts auto end`() {
        val detector = DwellDetector(
            isAutoTripChainingEnabled = true,
            autoEndDwellMinutes = 10,
            stationaryConfirmationDurationMs = 45_000L,
            dwellAnchorRadiusMeters = 75.0
        )

        val anchorLat = 12.9716
        val anchorLng = 77.5946
        val t0 = 100_000L

        detector.evaluate(anchorLat, anchorLng, speedKmh = 0.0, nowEpochMs = t0)
        detector.evaluate(anchorLat, anchorLng, speedKmh = 0.0, nowEpochMs = t0 + 45_000L)
        assertEquals(DwellState.DWELLING, detector.state)

        // User gets back on bike 3 minutes in and rides away (150m away at 18 km/h)
        val awayLat = anchorLat + 0.0015 // ~166 meters
        val resAway = detector.evaluate(awayLat, anchorLng, speedKmh = 18.0, nowEpochMs = t0 + 180_000L)
        assertEquals(DwellState.ACTIVE_TRACKING, resAway.state)
        assertFalse(resAway.shouldAutoEndRide)
    }

    @Test
    fun `standby mode auto starts when moving away with sustained speed`() {
        val detector = DwellDetector(
            isAutoTripChainingEnabled = true,
            breakoutRadiusMeters = 150.0,
            breakoutSpeedThresholdKmh = 8.0,
            breakoutConfirmationDurationMs = 20_000L
        )

        val homeLat = 12.9716
        val homeLng = 77.5946
        val t0 = 1_000_000L

        detector.transitionToStandby(homeLat, homeLng, t0)
        assertEquals(DwellState.STANDBY_WAITING_FOR_NEXT_TRIP, detector.state)

        // Still near home (30m away)
        val nearLat = homeLat + 0.0002
        var res = detector.evaluate(nearLat, homeLng, speedKmh = 0.0, nowEpochMs = t0 + 3600_000L) // 1h later
        assertEquals(DwellState.STANDBY_WAITING_FOR_NEXT_TRIP, res.state)
        assertFalse(res.shouldAutoStartNextRide)

        // Breaks out > 150m at transit speed (250m away at 20 km/h)
        val outLat = homeLat + 0.0025 // ~277 meters
        val tBreak = t0 + 7200_000L
        res = detector.evaluate(outLat, homeLng, speedKmh = 20.0, nowEpochMs = tBreak)
        assertEquals(DwellState.STANDBY_WAITING_FOR_NEXT_TRIP, res.state)
        assertFalse(res.shouldAutoStartNextRide)

        // Sustained for 20 seconds
        val resConfirmed = detector.evaluate(outLat + 0.001, homeLng, speedKmh = 22.0, nowEpochMs = tBreak + 20_000L)
        assertEquals(DwellState.ACTIVE_TRACKING, resConfirmed.state)
        assertTrue(resConfirmed.shouldAutoStartNextRide)
        assertEquals(tBreak, resConfirmed.autoStartTimestampEpochMs)
    }

    @Test
    fun `haversine distance handles valid and boundary coordinates`() {
        val p1Lat = 12.9716
        val p1Lng = 77.5946
        val p2Lat = 12.9726 // 0.001 deg lat is ~111m
        val p2Lng = 77.5946

        val dist = DwellDetector.computeDistanceMeters(p1Lat, p1Lng, p2Lat, p2Lng)
        assertTrue("Expected ~111m, got $dist", dist in 110.0..112.0)

        // Identical points
        assertEquals(0.0, DwellDetector.computeDistanceMeters(p1Lat, p1Lng, p1Lat, p1Lng), 0.001)

        // Invalid coords return 0.0
        assertEquals(0.0, DwellDetector.computeDistanceMeters(Double.NaN, p1Lng, p2Lat, p2Lng), 0.001)
        assertEquals(0.0, DwellDetector.computeDistanceMeters(95.0, p1Lng, p2Lat, p2Lng), 0.001)
    }
}
