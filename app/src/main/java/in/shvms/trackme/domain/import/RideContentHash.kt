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
 * Hashing the track closes both, because the track is the thing that was actually duplicated.
 *
 * **Normalisation is part of the contract, not an implementation detail.** Coordinates are rounded
 * to five decimals (~1.1 m) so that a re-export whose formatting differs in the sixth decimal still
 * matches, and points are ordered by timestamp so a writer that emits them out of order does not
 * produce a different identity for the same ride. Altitude, speed and accuracy are deliberately
 * excluded: they are the fields most likely to be recomputed or dropped by another tool, and a ride
 * is the same ride without them.
 */
object RideContentHash {

    /** Five decimals is ~1.1 m at the equator — finer than any GPS fix this app records. */
    private const val COORDINATE_FORMAT = "%.5f"

    /**
     * Returns a stable hex digest of the track, or null when there is nothing to identify.
     *
     * A single point is not enough to call two rides the same activity, so anything under two
     * points hashes to null and the caller falls back to its other duplicate checks rather than
     * treating every one-point import as a duplicate of every other.
     */
    fun of(points: List<GPSPointEntity>): String? {
        if (points.size < 2) return null
        val canonical = buildString {
            points.sortedBy { it.timestamp }.forEach { point ->
                append(String.format(Locale.US, COORDINATE_FORMAT, point.latitude))
                append(',')
                append(String.format(Locale.US, COORDINATE_FORMAT, point.longitude))
                append(',')
                append(point.timestamp)
                append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
