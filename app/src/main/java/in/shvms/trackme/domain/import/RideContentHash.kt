package `in`.shvms.trackme.domain.`import`

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import java.security.MessageDigest
import java.util.Locale

/**
 * TASK-275: identifies a ride by *what it is*, not by what a file claims about itself.
 *
 * The import path used to dedupe on `originalTrackMeId`, an attribute TrackMe writes into its own
 * exports. That guard has two holes, and only the second one needs an adversary:
 *
 * 1. a GPX from any other app carries no such id, so the check was skipped entirely and importing
 *    the same Strava export twice produced two rides and double-counted its minutes; and
 * 2. deleting one XML attribute from a TrackMe export defeats it, because the track is unchanged.
 *
 * ## Why this hashes timestamps rather than every coordinate
 *
 * The first version hashed every point at five decimal places. On a device it failed the case it
 * was written for: exporting a recorded ride to GPX and importing it back produced a *different*
 * hash, because 18 of 361 points landed within a float's breath of a rounding boundary and tipped
 * the other way on the round-trip. Any fixed rounding has that failure -- a coarser grid only makes
 * the boundary rarer, never absent, and one flipped point changes the whole digest.
 *
 * Timestamps do not have that problem. They are integers, they survive every serialisation this app
 * performs, and a ride is far better identified by *when* each sample was taken than by where. The
 * coarse endpoints below are a guard against two rides at identical instants in different places;
 * at three decimals (~110 m) they are orders of magnitude above round-trip noise.
 *
 * **Known and accepted:** two riders on the same group ride produce near-identical timestamps and
 * endpoints, so importing a companion's GPX of a ride you also recorded may be reported as a
 * duplicate. Two files describing the same ride at the same instants are duplicates under any
 * reasonable definition, and the alternative -- failing to dedupe at all -- is the bug this exists
 * to fix. Recorded here rather than discovered later.
 */
object RideContentHash {

    /** ~110 m. Far above float round-trip noise, far below the gap between distinct rides. */
    private const val ENDPOINT_FORMAT = "%.3f"

    /**
     * Returns a stable hex digest of the track, or null when there is nothing to identify.
     *
     * Under two points hashes to null: one sample cannot distinguish two activities, and the caller
     * falls back to its other checks rather than calling every one-point import a duplicate of
     * every other.
     */
    fun of(points: List<GPSPointEntity>): String? {
        if (points.size < 2) return null
        val ordered = points.sortedBy { it.timestamp }
        val first = ordered.first()
        val last = ordered.last()
        val canonical = buildString {
            append(ordered.size)
            append('|')
            append(String.format(Locale.US, ENDPOINT_FORMAT, first.latitude))
            append(',')
            append(String.format(Locale.US, ENDPOINT_FORMAT, first.longitude))
            append('|')
            append(String.format(Locale.US, ENDPOINT_FORMAT, last.latitude))
            append(',')
            append(String.format(Locale.US, ENDPOINT_FORMAT, last.longitude))
            append('|')
            ordered.forEach { append(it.timestamp).append(';') }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
