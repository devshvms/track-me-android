package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.*
import org.junit.Test

class TrackingV2SessionTest {
    private fun sample(seconds: Long, metres: Double) = TrackingV2Sample(
        latitude = 0.0, longitude = metres / 111195,
        horizontalAccuracyMeters = 5f, elapsedRealtimeMillis = seconds * 1000,
        gpsSpeedMetersPerSecond = 4f, gpsSpeedAccuracyMetersPerSecond = .5f,
        motionEnergyMetersPerSecondSquared = 1f, motionSampleAgeMillis = 0,
        cumulativeStepCount = null, stepAgeMillis = null, stepCadenceHz = null,
        persona = RidePersona.CYCLING, powerMode = TrackingV2PowerMode.NORMAL)

    @Test fun `restore keeps checkpoint without charging downtime`() {
        val session = TrackingV2Session()
        session.reset(RidePersona.CYCLING, distance = 500.0, duration = 100000, peak = 8.0)
        session.add(sample(100, 10000.0))
        assertEquals(500.0, session.distanceMeters, .001)
        assertEquals(100000L, session.movingDurationMillis)
        assertEquals(8.0, session.maxSpeedMps, .001)
    }
    @Test fun `manual pause travel is excluded and reset clears totals`() {
        val session = TrackingV2Session()
        session.reset(RidePersona.CYCLING)
        (0..12).forEach { session.add(sample(it * 2L, it * 8.0)) }
        val distance = session.distanceMeters
        val duration = session.movingDurationMillis
        session.pause()
        session.add(sample(30, 500.0))
        session.resume()
        session.add(sample(32, 1000.0))
        assertEquals(distance, session.distanceMeters, .001)
        assertEquals(duration, session.movingDurationMillis)
        session.reset(RidePersona.CYCLING)
        assertEquals(0.0, session.distanceMeters, .001)
        assertEquals(0L, session.movingDurationMillis)
    }
}
