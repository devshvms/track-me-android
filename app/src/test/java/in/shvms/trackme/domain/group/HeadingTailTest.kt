package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.3 §3 and §0 contract 7 — **the heading tail, and the promise it must not break.**
 *
 * A tail is definitionally a short position history of another person, and 1.7.0 §2.7 says
 * *"Nothing is saved. No group record, no member list, no position history."* The distinction that
 * makes it acceptable is that it is **in-memory, per-session, and never persisted or transmitted**.
 *
 * That is a property of *where the buffer is held*, not of the data — so the last section here
 * reads the source. A correct [HeadingTail] stored in `rememberSaveable` would pass every rule test
 * in this file while quietly writing other people's positions to disk on process death.
 */
class HeadingTailTest {

    private fun sample(ts: Long, lat: Double = 12.97, lng: Double = 77.59) =
        HeadingTail.Sample(lat, lng, ts)

    // --- Appending ------------------------------------------------------------------------------

    @Test
    fun `a fresh position extends the tail`() {
        val tail = HeadingTail.append(emptyList(), sample(1_000), nowMillis = 1_000)
        assertEquals(1, tail.size)
    }

    @Test
    fun `an idempotent resend does not extend the tail`() {
        // §3: "Not on an idempotent resend — the same position repeated ten times would draw a
        // fake stationary tail." A resend carries the timestamp it was first stamped with, so an
        // unchanged serverTs is exactly the signal that nothing new happened.
        var tail = HeadingTail.append(emptyList(), sample(1_000), 1_000)
        tail = HeadingTail.append(tail, sample(1_000), 1_100)
        tail = HeadingTail.append(tail, sample(1_000), 1_200)
        assertEquals("a resend grew the tail", 1, tail.size)
    }

    @Test
    fun `an out-of-order arrival is ignored rather than folded in backwards`() {
        var tail = HeadingTail.append(emptyList(), sample(2_000), 2_000)
        tail = HeadingTail.append(tail, sample(1_000), 2_100)
        assertEquals(1, tail.size)
        assertEquals(2_000L, tail.single().serverTsMillis)
    }

    // --- The window (Q3.3) -----------------------------------------------------------------------

    @Test
    fun `the tail is bounded by time, not by count`() {
        // Q3.3, and the reason it matters: at a 10s sync interval 10 points is ~100s of history,
        // but at the slowed cadence §7.2 can impose the same 10 points could span many minutes. A
        // tail that silently represents ten minutes of travel implies a speed and a proximity that
        // are not real.
        val old = (0 until 5).map { sample(1_000L + it * 1_000L) }
        // Anchored past the NEWEST sample, or the window would still legitimately cover the tail.
        val now = old.last().serverTsMillis + HeadingTail.WINDOW_MS + 1
        assertTrue(
            "samples outside the window must expire regardless of how few there are",
            HeadingTail.prune(old, now).isEmpty(),
        )
        // And the boundary: one millisecond earlier, the newest sample is still inside.
        assertEquals(1, HeadingTail.prune(old, now - 2).size)
    }

    @Test
    fun `samples inside the window survive`() {
        val samples = listOf(sample(10_000), sample(20_000))
        assertEquals(2, HeadingTail.prune(samples, nowMillis = 30_000).size)
    }

    @Test
    fun `the count cap is a memory bound and never the semantic one`() {
        // Every sample is inside the window; only MAX_POINTS survive, newest kept.
        val many = (0 until HeadingTail.MAX_POINTS * 3).map { sample(100_000L + it * 100L) }
        val pruned = HeadingTail.prune(many, nowMillis = 100_000L + HeadingTail.MAX_POINTS * 300L)
        assertEquals(HeadingTail.MAX_POINTS, pruned.size)
        assertEquals(
            "the cap must keep the NEWEST samples — a tail of the oldest points points backwards",
            many.last(),
            pruned.last(),
        )
    }

    @Test
    fun `a member who stops syncing watches their tail expire`() {
        // Without pruning on read, a rider who dropped out ten minutes ago would still show a
        // confident trail implying recent movement.
        var tail = HeadingTail.append(emptyList(), sample(1_000), 1_000)
        tail = HeadingTail.append(tail, sample(2_000), 2_000)
        assertEquals(2, tail.size)
        assertTrue(HeadingTail.prune(tail, nowMillis = 2_000 + HeadingTail.WINDOW_MS + 1).isEmpty())
    }

    // --- When it is drawn ------------------------------------------------------------------------

    @Test
    fun `a moving, fresh, non-self member with enough samples gets a tail`() {
        assertTrue(
            HeadingTail.shouldDraw(
                isSelf = false, moving = true, autoPaused = false, isStale = false, sampleCount = 2
            )
        )
    }

    @Test
    fun `you never get a tail for yourself`() {
        // Q3.2: solo, the route line is strictly better — the tail would be visually redundant.
        assertFalse(
            HeadingTail.shouldDraw(
                isSelf = true, moving = true, autoPaused = false, isStale = false, sampleCount = 10
            )
        )
    }

    @Test
    fun `a stationary or paused rider has no tail`() {
        // A trail behind a parked rider reads as movement that is not happening.
        assertFalse(
            HeadingTail.shouldDraw(
                isSelf = false, moving = false, autoPaused = false, isStale = false, sampleCount = 5
            )
        )
        assertFalse(
            HeadingTail.shouldDraw(
                isSelf = false, moving = true, autoPaused = true, isStale = false, sampleCount = 5
            )
        )
    }

    @Test
    fun `a stale member has no tail`() {
        // §3: "a fading tail on a stale member implies motion we cannot vouch for."
        assertFalse(
            HeadingTail.shouldDraw(
                isSelf = false, moving = true, autoPaused = false, isStale = true, sampleCount = 5
            )
        )
    }

    @Test
    fun `one sample is not a tail`() {
        assertFalse(
            HeadingTail.shouldDraw(
                isSelf = false, moving = true, autoPaused = false, isStale = false, sampleCount = 1
            )
        )
    }

    // --- Taper -----------------------------------------------------------------------------------

    @Test
    fun `alpha and width both rise from oldest to newest`() {
        val total = 6
        val alphas = (0 until total).map { HeadingTail.alphaAt(it, total) }
        val widths = (0 until total).map { HeadingTail.widthAt(it, total) }
        assertEquals("the oldest end must be faintest", HeadingTail.MIN_ALPHA, alphas.first(), 1e-6f)
        assertEquals("the newest end must be brightest", HeadingTail.MAX_ALPHA, alphas.last(), 1e-6f)
        assertEquals(alphas.sorted(), alphas)
        assertEquals(widths.sorted(), widths)
    }

    @Test
    fun `a tail never outranks the rider's own route line`() {
        // The route polyline is width 10f. A tail that matched or exceeded it would read as a
        // route, which is precisely what it is not.
        assertTrue(HeadingTail.MAX_WIDTH_PX < 10f)
        assertTrue(HeadingTail.MAX_ALPHA < 1f)
    }

    // --- The promise (§0 contract 7) ---------------------------------------------------------------

    @Test
    fun `the tail is never persisted`() {
        // THE INVARIANT THE WHOLE FEATURE RESTS ON. §5.1.4 forbids retaining location history and
        // 1.7.0 §2.7 says "Nothing is saved… no position history."
        //
        // rememberSaveable is the specific trap: it is the reflex for "survive a rotation", and the
        // system serialises saved instance state to disk on process death. Reaching for it here
        // would write other people's positions to disk without anyone noticing.
        val buffer = strippedSource("ui/home/components/HeadingTailBuffer.kt")
        for (forbidden in listOf("rememberSaveable", "SharedPreferences", "DataStore", "Room", "@Entity")) {
            assertFalse(
                "HeadingTailBuffer mentions \"$forbidden\" — the tail must be memory-only (§0 contract 7)",
                buffer.contains(forbidden),
            )
        }
    }

    @Test
    fun `the tail is never transmitted`() {
        // "never persisted and never transmitted." Handing samples to the session manager, the
        // relay, or telemetry would turn a rendering aid into a broadcast of other people's tracks.
        val buffer = strippedSource("ui/home/components/HeadingTailBuffer.kt")
        for (forbidden in listOf("groupSessionManager", "updatePosition", "PostHog", "AnalyticsManager")) {
            assertFalse(
                "HeadingTailBuffer mentions \"$forbidden\" — the tail must never leave the device",
                buffer.contains(forbidden),
            )
        }
    }

    @Test
    fun `the buffer dies with the group session`() {
        // §3: it must "die with the screen and reconstruct from live syncs". Keyed by groupId for
        // the same reason the avatar cache is — a uid from a previous group is never valid in the
        // next one, and a tail that outlived its group is retained position history.
        val buffer = strippedSource("ui/home/components/HeadingTailBuffer.kt")
        assertTrue(
            "the buffer must be remembered against groupId so it cannot outlive its group",
            buffer.contains("remember(groupId)"),
        )
        assertTrue(
            "a member who left must lose their tail rather than leaving it on the map",
            buffer.contains("retainAll"),
        )
    }

    @Test
    fun `the tail is drawn as a polyline rather than as markers`() {
        // Q3.1, on cost grounds: 10 dots x 12 members is 120 extra map objects on top of avatars
        // and badges, and 1.7.0 §7.5 already names ~12 members as where the map degrades.
        val home = strippedSource("ui/home/HomeScreen.kt")
        // The tail loop is the first positions.forEach after the buffer update; the marker loop is
        // the next one. Brace-match so the two cannot be confused as the file grows.
        val updateAt = home.indexOf("headingTailBuffer.update(")
        assertTrue("the heading tail is not wired into the map at all", updateAt >= 0)
        val tailBlock = braceMatchedBlockAfter(home, home.indexOf("positions.forEach", updateAt))
        assertTrue("the tail must be drawn with Polyline", tailBlock.contains("Polyline("))
        assertFalse("the tail must not be drawn with Marker", tailBlock.contains("Marker("))
    }

    /** The brace-matched block opening at the first `{` at or after [from]. */
    private fun braceMatchedBlockAfter(source: String, from: Int): String {
        val open = source.indexOf('{', from)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces from offset $from")
    }

    private fun strippedSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            val f = File(dir, rel).takeIf { it.exists() }
                ?: File(dir, rel.removePrefix("app/")).takeIf { it.exists() }
            if (f != null) {
                return f.readText()
                    .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                    .replace(Regex("//.*"), "")
            }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
