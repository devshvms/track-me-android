package `in`.shvms.trackme.ui.home.components

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil

/**
 * What the Home map camera should be doing, as pure geometry.
 *
 * Extracted from `HomeScreen` for the same reason [MemberMarkerPolicy] and `GroupPresencePolicy`
 * are separate: the decision is arithmetic on positions, the screen is the thing that renders it,
 * and only the former can be tested without a device.
 */
object RideCameraPolicy {

    /**
     * Tilt while recording. What is ahead of you occupies more screen than what is behind, which
     * is the correct priority when moving.
     */
    const val RIDING_TILT = 45f

    /**
     * Tilt while paused — eased back toward an overview rather than snapped flat, because a hard
     * reset mid-ride reads as a glitch rather than as a state change.
     */
    const val PAUSED_TILT = 30f

    /**
     * How far apart two fixes must be before the line between them is treated as a direction.
     *
     * Consecutive fixes a metre apart are mostly GPS noise, and steering the camera by them makes
     * it wobble while the rider is going perfectly straight. Twelve metres is roughly a second of
     * city riding and comfortably outside typical horizontal error.
     */
    const val HEADING_MIN_METERS = 12.0

    /**
     * Direction of travel from the tail of the recorded path, in degrees clockwise from north.
     *
     * Walks back from the newest point until it finds one far enough away to carry a real heading.
     * Returns `0` — north-up — when the path is too short or the rider has not actually moved,
     * which is the same camera the app shows when idle, so a stationary rider sees no rotation
     * rather than a random one.
     */
    fun headingOf(points: List<LatLng>): Float {
        if (points.size < 2) return 0f
        val to = points.last()
        val from = points.asReversed().firstOrNull {
            SphericalUtil.computeDistanceBetween(it, to) >= HEADING_MIN_METERS
        } ?: return 0f
        return SphericalUtil.computeHeading(from, to).toFloat()
    }
}
