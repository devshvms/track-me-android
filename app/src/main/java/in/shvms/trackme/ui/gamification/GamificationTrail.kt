package `in`.shvms.trackme.ui.gamification

import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import kotlin.math.hypot

/**
 * TASK-276: the pure geometry and state behind the My Progress trail.
 *
 * Kept out of the composable so the parts that can be wrong -- where the marker sits, which level a
 * node represents, which side of the path has room for a card -- are testable on the JVM without a
 * device, a font or a frame.
 *
 * **The curve maths is hand-rolled rather than delegated to `android.graphics.Path`, deliberately.**
 * The first version called `PathMeasure`, which is stubbed to zero in plain JVM unit tests: the
 * geometry tests passed vacuously against an empty path while claiming to prove the marker was in
 * the right place. Extracting the geometry to make it testable and then measuring it with something
 * untestable defeats the extraction. Sixty lines of Bézier arithmetic is the smaller price.
 */
object GamificationTrail {

    /** Design-space bounds. The composable scales this to whatever box it is given. */
    const val WIDTH = 300f
    const val HEIGHT = 430f

    data class Point(val x: Float, val y: Float)

    enum class NodeState { PASSED, CURRENT, AHEAD }

    data class Node(
        val levelIndex: Int,
        val levelId: String,
        val position: Point,
        val state: NodeState,
        /** True when there is room to the right; false means the card belongs on the left. */
        val cardOnRight: Boolean,
    )

    /** start, control 1, control 2, end */
    private data class Cubic(
        val p0: Point, val p1: Point, val p2: Point, val p3: Point,
    ) {
        fun at(t: Float): Point {
            val u = 1f - t
            val a = u * u * u
            val b = 3f * u * u * t
            val c = 3f * u * t * t
            val d = t * t * t
            return Point(
                a * p0.x + b * p1.x + c * p2.x + d * p3.x,
                a * p0.y + b * p1.y + c * p2.y + d * p3.y,
            )
        }
    }

    /**
     * A serpentine climb, drawn bottom-left to top-left. It alternates sides deliberately: that is
     * what leaves a clear column of space beside every waypoint for its card, and it is why the
     * screen needs no extra vertical room to explain a level.
     */
    private val segments = listOf(
        Cubic(Point(62f, 404f), Point(62f, 368f), Point(238f, 364f), Point(238f, 322f)),
        Cubic(Point(238f, 322f), Point(238f, 280f), Point(62f, 276f), Point(62f, 234f)),
        Cubic(Point(62f, 234f), Point(62f, 192f), Point(238f, 188f), Point(238f, 146f)),
        Cubic(Point(238f, 146f), Point(238f, 104f), Point(62f, 100f), Point(62f, 58f)),
    )

    /** Cumulative arc length, sampled once. 64 steps per segment is well under a pixel of error. */
    private const val STEPS_PER_SEGMENT = 64
    private val table: List<Pair<Float, Point>> by lazy {
        val points = mutableListOf<Pair<Float, Point>>()
        var length = 0f
        var previous = segments.first().p0
        points += 0f to previous
        segments.forEach { segment ->
            for (step in 1..STEPS_PER_SEGMENT) {
                val point = segment.at(step.toFloat() / STEPS_PER_SEGMENT)
                length += hypot(point.x - previous.x, point.y - previous.y)
                points += length to point
                previous = point
            }
        }
        points
    }

    /** Total drawn length of the trail in design units. */
    val totalLength: Float get() = table.last().first

    /** The point at a fraction of the way along the trail, linearly interpolated between samples. */
    fun pointAt(fraction: Float): Point {
        val target = fraction.coerceIn(0f, 1f) * totalLength
        val index = table.indexOfFirst { it.first >= target }.coerceAtLeast(1)
        val (beforeLen, before) = table[index - 1]
        val (afterLen, after) = table[index]
        val span = afterLen - beforeLen
        val t = if (span <= 0f) 0f else (target - beforeLen) / span
        return Point(before.x + (after.x - before.x) * t, before.y + (after.y - before.y) * t)
    }

    /**
     * Fraction of the whole path at which a level's waypoint sits. Levels are spaced evenly along
     * the drawn line rather than by threshold, because the thresholds grow by a factor of 75 from
     * first to last and a proportional trail would put five levels in the bottom eighth of it.
     */
    fun fractionForLevel(levelIndex: Int): Float {
        val last = GamificationEngine.levels.lastIndex
        if (last <= 0) return 0f
        return levelIndex.toFloat() / last.toFloat()
    }

    fun levelIndexOf(snapshot: GamificationSnapshot): Int =
        GamificationEngine.levels.indexOfFirst { it.id == snapshot.currentLevelId }.coerceAtLeast(0)

    /**
     * Progress through the current level, 0..1.
     *
     * A non-positive denominator is complete only at the maximum level. Anywhere else it means the
     * snapshot is malformed, and returning 1 there would draw a finished trail -- the single most
     * misleading thing this screen could say, and indistinguishable from genuine completion.
     */
    fun progressWithinLevel(snapshot: GamificationSnapshot): Float {
        if (snapshot.progressDenominatorMinutes <= 0L) {
            return if (levelIndexOf(snapshot) == GamificationEngine.levels.lastIndex) 1f else 0f
        }
        return (
            snapshot.progressNumeratorMinutes.toDouble() /
                snapshot.progressDenominatorMinutes.toDouble()
            ).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Where the rider stands: their level's waypoint, plus their progress through that level
     * interpolated toward the next one. This is the single progress encoding on the screen -- the
     * radial version had an arc for within-level and a ring for across-level, both circular and
     * concentric, and the eye had to reconcile them.
     */
    fun markerFraction(snapshot: GamificationSnapshot): Float {
        val index = levelIndexOf(snapshot)
        val here = fractionForLevel(index)
        val next = fractionForLevel((index + 1).coerceAtMost(GamificationEngine.levels.lastIndex))
        return (here + progressWithinLevel(snapshot) * (next - here)).coerceIn(0f, 1f)
    }

    /** Waypoints in level order, positioned on the trail and tagged with their state. */
    fun nodes(snapshot: GamificationSnapshot): List<Node> {
        val currentIndex = levelIndexOf(snapshot)
        return GamificationEngine.levels.mapIndexed { index, level ->
            val position = pointAt(fractionForLevel(index))
            Node(
                levelIndex = index,
                levelId = level.id,
                position = position,
                state = when {
                    index < currentIndex -> NodeState.PASSED
                    index == currentIndex -> NodeState.CURRENT
                    else -> NodeState.AHEAD
                },
                cardOnRight = position.x < WIDTH / 2f,
            )
        }
    }

    /** Where the rider's marker is drawn, in design space. */
    fun markerPosition(snapshot: GamificationSnapshot): Point = pointAt(markerFraction(snapshot))
}
