package `in`.shvms.trackme.ui.home.components

import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds

/** How a member's marker should read right now. */
enum class MarkerFreshness {
    /** Recent enough to trust. Full colour, cyan ring. */
    FRESH,

    /**
     * Older than 2× the sync interval. §3.3: desaturate to ~40%, drop the ring to a muted slate,
     * and show an age chip. **Never hide a stale member silently** — "vanished" and "stopped
     * moving" mean very different things to someone waiting at a junction (§2.6).
     */
    STALE,

    /** Past the ghost horizon. Off the map, still in the roster (§2.6). */
    DROPPED,
}

/**
 * Marker rendering rules — SCOPE_1.7.0 §2.6, §3.3, and amendment **A19**.
 *
 * Pure, so the rules that decide whether a person appears on a map are testable without a map, a
 * device, or a GPS fix.
 */
object MemberMarkerPolicy {

    /** §2.6: "after 2× the current sync interval a member's marker desaturates". */
    const val STALE_MULTIPLIER = 2

    /** §2.6: "After 10 minutes they drop off the map but stay in the roster." */
    const val DROP_AFTER_MS = 10 * 60 * 1000L

    /**
     * Freshness from the **server-stamped** timestamp.
     *
     * §4.4 and §8: staleness is computed from the relay's clock, never the device's, so a member
     * with a skewed clock cannot make themselves look permanently fresh — or make everyone else
     * look stale.
     */
    fun freshnessFor(
        serverTsMillis: Long,
        nowMillis: Long,
        syncIntervalSec: Int,
    ): MarkerFreshness {
        if (serverTsMillis <= 0L) return MarkerFreshness.DROPPED
        val age = nowMillis - serverTsMillis
        // A future timestamp means our clock is behind the relay's, not that the fix is stale.
        // Treating it as fresh is right: the relay stamped it, so it happened.
        if (age <= 0L) return MarkerFreshness.FRESH
        if (age >= DROP_AFTER_MS) return MarkerFreshness.DROPPED
        val staleAfter = syncIntervalSec.coerceAtLeast(1) * 1000L * STALE_MULTIPLIER
        return if (age >= staleAfter) MarkerFreshness.STALE else MarkerFreshness.FRESH
    }

    /** Whole minutes since the fix, for the "2m ago" chip (§3.3). */
    fun ageMinutes(serverTsMillis: Long, nowMillis: Long): Int =
        ((nowMillis - serverTsMillis).coerceAtLeast(0L) / 60_000L).toInt()

    /**
     * **A19**: the map is the *nearby* view, so a member is drawn only if they are already inside
     * the viewport the rider is looking at. The camera never moves to find them — seeing the wider
     * picture is the rider zooming out.
     *
     * Null bounds means the map has not laid out yet; drawing nothing for one frame is better than
     * drawing everyone at a default camera and then yanking them away.
     */
    fun isVisible(position: LatLng, bounds: LatLngBounds?): Boolean =
        bounds != null && bounds.contains(position)

    /**
     * The complete decision for one member: draw, and if so how.
     *
     * Returns null when the member should not be on the map at all — either too old, or outside
     * the current viewport. **The roster is where they still exist** (A18/A19); this only decides
     * the map.
     */
    fun renderFor(
        position: LatLng,
        serverTsMillis: Long,
        nowMillis: Long,
        syncIntervalSec: Int,
        bounds: LatLngBounds?,
    ): MarkerFreshness? {
        val freshness = freshnessFor(serverTsMillis, nowMillis, syncIntervalSec)
        if (freshness == MarkerFreshness.DROPPED) return null
        if (!isVisible(position, bounds)) return null
        return freshness
    }
}
