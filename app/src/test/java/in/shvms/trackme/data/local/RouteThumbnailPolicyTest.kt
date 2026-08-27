package `in`.shvms.trackme.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-246, shvm: "default thumbnail only for less than 50 points or distance is 0 or no points".
 *
 * The rule is one line, so these cases exist to pin the boundary and the reason rather than the
 * arithmetic. 1.8.4 drew a shape whenever it had two points, which is what made a stalled ride
 * render as a meaningless tick; the threshold is the deliberate difference from that behaviour.
 */
class RouteThumbnailPolicyTest {

    @Test
    fun `a real ride draws its shape`() {
        assertTrue(routeThumbnailDrawsShape(pointCount = 1_200, distanceMeters = 18_900.0))
    }

    @Test
    fun `the point threshold is inclusive at its boundary`() {
        assertFalse(
            "49 points is below the bar",
            routeThumbnailDrawsShape(ROUTE_THUMBNAIL_MIN_POINTS - 1, distanceMeters = 500.0),
        )
        assertTrue(
            "50 points is the bar, not past it",
            routeThumbnailDrawsShape(ROUTE_THUMBNAIL_MIN_POINTS, distanceMeters = 500.0),
        )
    }

    @Test
    fun `a ride that never moved falls back however many points it logged`() {
        // The case the threshold exists for: standing still at a traffic light with GPS running
        // produces thousands of samples and no distance. Normalising that against a zero span
        // draws a dot, which reads as a broken thumbnail rather than an honest empty one.
        assertFalse(routeThumbnailDrawsShape(pointCount = 5_000, distanceMeters = 0.0))
    }

    @Test
    fun `no points never draws`() {
        assertFalse(routeThumbnailDrawsShape(pointCount = 0, distanceMeters = 0.0))
        assertFalse(
            "distance without points cannot be drawn either",
            routeThumbnailDrawsShape(pointCount = 0, distanceMeters = 9_000.0),
        )
    }
}
