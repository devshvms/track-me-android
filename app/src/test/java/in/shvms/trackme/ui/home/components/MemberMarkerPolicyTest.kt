package `in`.shvms.trackme.ui.home.components

import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SCOPE_1.7.0 §2.6, §3.3, and amendment **A19** — the rules that decide whether a person appears
 * on someone else's map.
 *
 * Robolectric only because `LatLng`/`LatLngBounds` are Android-library types with real geometry;
 * the policy itself is pure. Pinned to sdk 34 with a bare Application for the same reasons as
 * `GroupSessionStoreTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MemberMarkerPolicyTest {

    private val now = 1_785_000_000_000L
    private val interval = 10

    private val bengaluru = LatLng(12.9716, 77.5946)
    private val nearby = LatLng(12.9720, 77.5950)
    private val faraway = LatLng(28.6139, 77.2090) // Delhi
    private val viewport = LatLngBounds(LatLng(12.96, 77.58), LatLng(12.98, 77.60))

    // --- Freshness (§2.6) -------------------------------------------------------------------

    @Test
    fun `a just-received fix is fresh`() {
        assertEquals(MarkerFreshness.FRESH, MemberMarkerPolicy.freshnessFor(now, now, interval))
        assertEquals(MarkerFreshness.FRESH, MemberMarkerPolicy.freshnessFor(now - 5_000, now, interval))
    }

    @Test
    fun `a member goes stale at twice the sync interval, not before`() {
        // §2.6: "after 2× the current sync interval a member's marker desaturates".
        assertEquals(
            MarkerFreshness.FRESH,
            MemberMarkerPolicy.freshnessFor(now - 19_999, now, interval),
        )
        assertEquals(
            MarkerFreshness.STALE,
            MemberMarkerPolicy.freshnessFor(now - 20_000, now, interval),
        )
    }

    @Test
    fun `staleness follows the server's cadence, not a client constant`() {
        // §7.2 lets the relay slow everyone down under load. If "stale" did not slow with it,
        // every marker would grey out the moment the server throttled — the feature would look
        // broken exactly when it is being protected.
        val slow = 60
        assertEquals(
            "a 30s-old fix is not stale on a 60s cadence",
            MarkerFreshness.FRESH,
            MemberMarkerPolicy.freshnessFor(now - 30_000, now, slow),
        )
        assertEquals(
            MarkerFreshness.STALE,
            MemberMarkerPolicy.freshnessFor(now - 121_000, now, slow),
        )
    }

    @Test
    fun `a member drops off the map after ten minutes`() {
        // §2.6: "After 10 minutes they drop off the map but stay in the roster."
        assertEquals(
            MarkerFreshness.STALE,
            MemberMarkerPolicy.freshnessFor(now - (10 * 60 * 1000L - 1), now, interval),
        )
        assertEquals(
            MarkerFreshness.DROPPED,
            MemberMarkerPolicy.freshnessFor(now - 10 * 60 * 1000L, now, interval),
        )
    }

    @Test
    fun `a stale member is never hidden silently`() {
        // The whole point of the STALE state. "Vanished" and "stopped moving" mean very different
        // things to someone waiting at a junction, so there is a visible middle state rather than
        // a jump from fresh to gone.
        val justStale = MemberMarkerPolicy.freshnessFor(now - 60_000, now, interval)
        assertEquals(MarkerFreshness.STALE, justStale)
        assertTrue(
            "a stale member must still render",
            MemberMarkerPolicy.renderFor(nearby, now - 60_000, now, interval, viewport) != null,
        )
    }

    @Test
    fun `a fix stamped in the future is trusted, not treated as stale`() {
        // The relay stamps ts, so a "future" fix means our clock is behind the server's — the fix
        // genuinely happened. §4.4 puts the server's clock in charge precisely so device skew
        // cannot poison freshness.
        assertEquals(MarkerFreshness.FRESH, MemberMarkerPolicy.freshnessFor(now + 30_000, now, interval))
    }

    @Test
    fun `a member with no timestamp is dropped rather than drawn at an unknown age`() {
        assertEquals(MarkerFreshness.DROPPED, MemberMarkerPolicy.freshnessFor(0L, now, interval))
        assertEquals(MarkerFreshness.DROPPED, MemberMarkerPolicy.freshnessFor(-1L, now, interval))
    }

    @Test
    fun `a zero or negative sync interval cannot make everything stale`() {
        // A malformed nextSyncInSec must not blank the map.
        assertEquals(MarkerFreshness.FRESH, MemberMarkerPolicy.freshnessFor(now - 500, now, 0))
        assertEquals(MarkerFreshness.FRESH, MemberMarkerPolicy.freshnessFor(now - 500, now, -5))
    }

    // --- Age chip ------------------------------------------------------------------------------

    @Test
    fun `age is whole minutes, floored, and never negative`() {
        assertEquals(0, MemberMarkerPolicy.ageMinutes(now - 59_000, now))
        assertEquals(1, MemberMarkerPolicy.ageMinutes(now - 60_000, now))
        assertEquals(2, MemberMarkerPolicy.ageMinutes(now - 150_000, now))
        assertEquals(0, MemberMarkerPolicy.ageMinutes(now + 10_000, now))
    }

    // --- A19: the map is the nearby view -------------------------------------------------------

    @Test
    fun `a member inside the viewport is drawn`() {
        assertTrue(MemberMarkerPolicy.isVisible(nearby, viewport))
        assertTrue(MemberMarkerPolicy.isVisible(bengaluru, viewport))
    }

    @Test
    fun `a member outside the viewport is not drawn, and the camera does not chase them`() {
        // A19: the camera stays on the rider. Seeing the wider picture is the rider zooming out,
        // not the app rearranging itself. Off-screen members live in the roster (A18).
        assertFalse(MemberMarkerPolicy.isVisible(faraway, viewport))
        assertNull(MemberMarkerPolicy.renderFor(faraway, now, now, interval, viewport))
    }

    @Test
    fun `nothing is drawn before the map has laid out`() {
        // Null bounds means no viewport yet. Drawing nothing for one frame beats drawing everyone
        // at a default camera and then yanking them away.
        assertFalse(MemberMarkerPolicy.isVisible(nearby, null))
        assertNull(MemberMarkerPolicy.renderFor(nearby, now, now, interval, null))
    }

    // --- The combined decision ------------------------------------------------------------------

    @Test
    fun `a fresh nearby member renders fresh`() {
        assertEquals(
            MarkerFreshness.FRESH,
            MemberMarkerPolicy.renderFor(nearby, now - 1_000, now, interval, viewport),
        )
    }

    @Test
    fun `age is checked as well as position, not instead of it`() {
        // An old fix inside the viewport must still drop out; a fresh fix outside must still be
        // culled. Getting either backwards leaves ghosts on the map or hides people who are there.
        assertNull(
            "an ancient fix was drawn because it was nearby",
            MemberMarkerPolicy.renderFor(nearby, now - 20 * 60 * 1000L, now, interval, viewport),
        )
        assertNull(
            "a distant member was drawn because the fix was fresh",
            MemberMarkerPolicy.renderFor(faraway, now, now, interval, viewport),
        )
    }

    // --- Marker tint ------------------------------------------------------------------------------

    @Test
    fun `a member keeps the same colour for the whole session`() {
        // §3.3: a stable per-member tint is what makes two members separable at a glance, and it
        // has to survive a reconnect or everyone changes colour mid-ride.
        val first = deterministicMarkerTint("uid-alice")
        repeat(50) { assertEquals(first, deterministicMarkerTint("uid-alice")) }
    }

    @Test
    fun `different members usually get different colours`() {
        val tints = (1..40).map { deterministicMarkerTint("uid-$it") }.toSet()
        assertTrue("the ramp collapsed to one colour", tints.size > 1)
    }

    @Test
    fun `the tint never overflows the ramp`() {
        // hashCode can be negative; a naive rem would index out of bounds and crash the map.
        for (uid in listOf("", "a", "uid-with-a-very-long-identifier", "🚴", "-", "zzzz")) {
            deterministicMarkerTint(uid) // must not throw
        }
    }
}
