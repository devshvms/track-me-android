package `in`.shvms.trackme.domain.group

/**
 * SCOPE_1.7.3 §3 and §0 contract 7 — **a short trail behind each other rider.**
 *
 * > *The heading tail is memory-only, per-session, never persisted and never transmitted.*
 *
 * ### What it is for
 *
 * In a group you have **no route line for anyone else**, only their current dot. A short tail
 * answers the question the map currently cannot: *which way are they coming from?* Solo it is
 * redundant — the full route polyline already exists — which is why Q3.2 decides **no tail for
 * yourself**: the route line is strictly better.
 *
 * ### The invariant this collides with, and why it survives
 *
 * §5.1.4 forbids retaining location history, and 1.7.0 §2.7 is blunt: *"Nothing is saved. No group
 * record, no member list, no position history."* A tail is, definitionally, a short position
 * history of another person.
 *
 * What makes it acceptable is exactly and only that it is **in-memory, per-session, and never
 * persisted or transmitted** — it dies with the screen and reconstructs from live syncs. If it were
 * ever written to disk, or survived the group, it would breach the promise the whole feature is
 * built on. That is a property of *where this object is held*, not of the object itself, so
 * [in.shvms.trackme.domain.group.HeadingTailTest] asserts it structurally rather than trusting it.
 *
 * ### Q3.3 — time-bounded, not count-bounded
 *
 * "Last N points" looks equivalent and is not. At a 10 s sync interval, 10 points is ~100 s of
 * history; at the slowed cadence §7.2 can impose, the same 10 points could span **many minutes**.
 * A tail that silently represents ten minutes of travel as a short trail is actively misleading —
 * it implies a speed and a proximity that are not real. The window is therefore wall-clock, and
 * [MAX_POINTS] exists only as a memory bound, never as the semantic one.
 */
object HeadingTail {

    /**
     * How much history a tail may represent. Q3.3.
     *
     * Ninety seconds is long enough to show a direction of travel at any plausible speed and short
     * enough that the oldest dot is still "where they just were" rather than "where they were when
     * you last looked at your phone".
     */
    const val WINDOW_MS = 90_000L

    /**
     * Hard cap on retained samples, as a **memory bound only**.
     *
     * §3's marker-count budget: 10 dots × up to 12 members is 120 extra map objects on top of
     * avatars and badges, and 1.7.0 §7.5 already names ~12 members as where the map degrades. The
     * tail is drawn as one polyline per member rather than N markers (Q3.1) precisely because a
     * polyline is dramatically cheaper, but the buffer still needs a ceiling so a fast sync cadence
     * cannot grow it without limit.
     */
    const val MAX_POINTS = 10

    /** One remembered position. No accuracy, battery, or status — a tail is a shape, not a record. */
    data class Sample(val lat: Double, val lng: Double, val serverTsMillis: Long)

    /**
     * Appends [sample] to [existing], dropping anything outside the window.
     *
     * **Only when `serverTs` advanced.** §3: *"Not on an idempotent resend — the same position
     * repeated ten times would draw a fake stationary tail."* A34's byte-identity rule is what makes
     * a resend detectable, and the server timestamp is what makes it decidable here: a resend
     * carries the timestamp it was first stamped with, so an unchanged `serverTs` is exactly the
     * signal that nothing new happened.
     *
     * @param nowMillis the relay's clock, not the device's — same reason as
     *   [in.shvms.trackme.ui.home.components.MemberMarkerPolicy.freshnessFor]: a member with a
     *   skewed device clock must not be able to age everyone else's tail.
     */
    fun append(existing: List<Sample>, sample: Sample, nowMillis: Long): List<Sample> {
        val last = existing.lastOrNull()
        if (last != null && sample.serverTsMillis <= last.serverTsMillis) return existing
        return prune(existing + sample, nowMillis)
    }

    /**
     * Drops samples older than [WINDOW_MS], then caps the result at [MAX_POINTS].
     *
     * Called on render as well as on append: a member who stops syncing must watch their tail
     * *expire*, not keep a frozen one indefinitely. Without this, a rider who dropped out ten
     * minutes ago would still show a confident trail implying recent movement.
     */
    fun prune(samples: List<Sample>, nowMillis: Long): List<Sample> =
        samples.filter { nowMillis - it.serverTsMillis < WINDOW_MS }.takeLast(MAX_POINTS)

    /**
     * Whether a tail should be drawn at all.
     *
     * §3 hides it in four cases, and the reasoning is the same each time — a fading trail is a
     * claim about recent motion, and we must only make that claim when we can vouch for it:
     *
     * - **[isSelf]**: Q3.2. The route polyline already shows this, better.
     * - **not [moving]** or **[autoPaused]**: they are stationary; a trail behind a parked rider
     *   reads as movement that is not happening.
     * - **[isStale]**: we have not heard from them recently enough to say which way they are going.
     * - fewer than two samples: a one-point tail is a dot on top of a dot.
     */
    fun shouldDraw(
        isSelf: Boolean,
        moving: Boolean,
        autoPaused: Boolean,
        isStale: Boolean,
        sampleCount: Int,
    ): Boolean = !isSelf && moving && !autoPaused && !isStale && sampleCount >= 2

    /**
     * Opacity for the segment ending at [index] of [total], newest last.
     *
     * Interpolates from [MIN_ALPHA] at the oldest end to [MAX_ALPHA] at the newest, so the tail
     * reads as direction without a legend: the bright end is where they are now.
     */
    fun alphaAt(index: Int, total: Int): Float {
        require(index in 0 until maxOf(total, 1)) { "index $index out of range for $total samples" }
        if (total <= 1) return MAX_ALPHA
        val t = index.toFloat() / (total - 1).toFloat()
        return MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * t
    }

    /** Width in px for the segment ending at [index], tapering from oldest to newest. */
    fun widthAt(index: Int, total: Int): Float {
        require(index in 0 until maxOf(total, 1)) { "index $index out of range for $total samples" }
        if (total <= 1) return MAX_WIDTH_PX
        val t = index.toFloat() / (total - 1).toFloat()
        return MIN_WIDTH_PX + (MAX_WIDTH_PX - MIN_WIDTH_PX) * t
    }

    /** Faint enough to read as past, opaque enough to see against satellite imagery. */
    const val MIN_ALPHA = 0.12f
    const val MAX_ALPHA = 0.72f

    /** Always thinner than the rider's own 10f route polyline — a tail must not outrank a route. */
    const val MIN_WIDTH_PX = 2f
    const val MAX_WIDTH_PX = 6f
}
