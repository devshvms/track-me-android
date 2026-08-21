package `in`.shvms.trackme.ui.onboarding

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDemoFixtureTest {
    @Test
    fun create_buildsCanonicalRideEntirelyInMemory() {
        val startTime = 1_700_000_000_000L
        val fixture = OnboardingDemoFixture.create(
            startTimeMillis = startTime,
            title = "localized title",
            rideId = 42L,
        )

        assertEquals(42L, fixture.ride.id)
        assertEquals(startTime, fixture.ride.startTime)
        assertEquals(startTime + OnboardingDemoFixture.DURATION_MILLIS, fixture.ride.endTime)
        assertEquals("localized title", fixture.ride.title)
        assertEquals("CYCLING", fixture.ride.persona)
        assertEquals(OnboardingDemoFixture.POINT_COUNT, fixture.points.size)
        assertTrue(fixture.points.all { it.rideId == fixture.ride.id })
        assertTrue(fixture.points.zipWithNext().all { (first, second) -> first.timestamp < second.timestamp })
        assertTrue(
            "demo samples must stay below the chart's GPS-gap threshold",
            fixture.points.zipWithNext().all { (first, second) ->
                second.timestamp - first.timestamp <= 25_000L
            },
        )

        val calculation = requireNotNull(fixture.ride.postRideCalculation)
        assertEquals(OnboardingDemoFixture.DISTANCE_METERS, calculation.distance, 0.001)
        assertEquals(
            OnboardingDemoFixture.AVERAGE_SPEED_METERS_PER_SECOND,
            calculation.avgSpeed.toDouble(),
            0.001,
        )
        assertEquals(OnboardingDemoFixture.MAX_SPEED_METERS_PER_SECOND, calculation.maxSpeed, 0.001f)
        assertEquals(OnboardingDemoFixture.POINT_COUNT, calculation.rawPointCount)
    }

    @Test
    fun route_hasRealShapeAndMatchesStoredAggregate() {
        val fixture = OnboardingDemoFixture.create()
        val routeDistance = fixture.points.zipWithNext().sumOf { (first, second) ->
            haversineMeters(
                first.latitude,
                first.longitude,
                second.latitude,
                second.longitude,
            )
        }
        val latitudeRange = fixture.points.maxOf { it.latitude } - fixture.points.minOf { it.latitude }
        val longitudeRange = fixture.points.maxOf { it.longitude } - fixture.points.minOf { it.longitude }
        val elevationRange = fixture.points.maxOf { it.altitude } - fixture.points.minOf { it.altitude }

        assertEquals(OnboardingDemoFixture.DISTANCE_METERS, routeDistance, 1.0)
        assertTrue("route should bend in both axes", latitudeRange > 0.004 && longitudeRange > 0.005)

        // The demo ride is a real recording (see demo_ride.gpx), and the simulator scenario it was
        // captured against supplies no terrain — so every fix sits at 0 m and this range is 0. Both
        // chart implementations already guard a zero altitude range, so the trace renders flat
        // rather than dividing by zero. Asserted explicitly, so that if a future re-recording DOES
        // carry elevation the intent of this line is still legible.
        assertEquals("recorded scenario carries no elevation", 0.0, elevationRange, 0.0001)

        // Recorded accuracy spans 5..50 m, wider than the old synthetic samples' tidy 3..8 m.
        assertTrue(fixture.points.all { it.speed > 0f && it.accuracy in 3f..60f })
    }

    private fun haversineMeters(
        firstLatitude: Double,
        firstLongitude: Double,
        secondLatitude: Double,
        secondLongitude: Double,
    ): Double {
        val firstLatRadians = Math.toRadians(firstLatitude)
        val secondLatRadians = Math.toRadians(secondLatitude)
        val latitudeDelta = Math.toRadians(secondLatitude - firstLatitude)
        val longitudeDelta = Math.toRadians(secondLongitude - firstLongitude)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLatRadians) * cos(secondLatRadians) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(haversine))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
