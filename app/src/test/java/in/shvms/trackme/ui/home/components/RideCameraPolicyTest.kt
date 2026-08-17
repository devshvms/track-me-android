package `in`.shvms.trackme.ui.home.components

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The camera bearing is the one part of the follow camera a user notices immediately when it is
 * wrong: a heading computed from GPS noise makes the map spin while they ride in a straight line.
 */
class RideCameraPolicyTest {

    /** Roughly 111,320 m per degree of latitude at the equator; 1e-5 deg ~= 1.11 m. */
    private fun north(meters: Double) = meters / 111_320.0

    @Test
    fun emptyPath_hasNoHeading() {
        assertEquals(0f, RideCameraPolicy.headingOf(emptyList()), 0.001f)
    }

    @Test
    fun singlePoint_hasNoHeading() {
        assertEquals(0f, RideCameraPolicy.headingOf(listOf(LatLng(0.0, 0.0))), 0.001f)
    }

    @Test
    fun pointsWithinNoiseThreshold_haveNoHeading() {
        // A rider stopped at a light: every fix within a couple of metres of the last. The line
        // between any two of them points somewhere, but that direction is noise, not travel.
        val jitter = listOf(
            LatLng(0.0, 0.0),
            LatLng(north(1.5), 0.0),
            LatLng(north(3.0), 0.0),
            LatLng(north(2.0), 0.0),
        )
        assertEquals(0f, RideCameraPolicy.headingOf(jitter), 0.001f)
    }

    @Test
    fun travellingNorth_headsNorth() {
        val path = listOf(LatLng(0.0, 0.0), LatLng(north(200.0), 0.0))
        assertEquals(0f, RideCameraPolicy.headingOf(path), 0.5f)
    }

    @Test
    fun travellingEast_headsEast() {
        val path = listOf(LatLng(0.0, 0.0), LatLng(0.0, north(200.0)))
        assertEquals(90f, RideCameraPolicy.headingOf(path), 0.5f)
    }

    @Test
    fun tailNoise_doesNotOverrideTheRealDirection() {
        // The regression this guards: a long eastward run whose last two fixes happen to jitter
        // north. Taking the final pair alone would swing the camera 90 degrees; walking back past
        // the noise threshold keeps it pointing along the road.
        val path = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, north(100.0)),
            LatLng(0.0, north(200.0)),
            LatLng(north(1.0), north(200.0)),
        )
        assertEquals(90f, RideCameraPolicy.headingOf(path), 2.0f)
    }

    @Test
    fun tiltsAreDistinctAndPausedIsShallower() {
        // Paused easing back toward an overview is the whole point of having two values.
        assert(RideCameraPolicy.PAUSED_TILT < RideCameraPolicy.RIDING_TILT)
    }
}
