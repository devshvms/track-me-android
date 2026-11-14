package `in`.shvms.trackme.domain.live

import org.json.JSONArray

/**
 * TASK-366 / SCOPE_1.9.3 §1.6: tracks active Live Share intervals within a single recorded ride
 * and resolves `"FULL"` vs `"PARTIAL"` mode without requiring schema migrations later.
 */
data class LiveShareInterval(
    val startMillis: Long,
    val endMillis: Long,
)

object LiveShareIntervals {
    const val MODE_FULL = "FULL"
    const val MODE_PARTIAL = "PARTIAL"

    /** Direct Share starts TrackingService and live session sequentially; 20s absorbs handoff latency. */
    private const val EDGE_TOLERANCE_MILLIS = 20_000L
    private const val FULL_COVERAGE_THRESHOLD = 0.90

    fun normalize(
        intervals: List<LiveShareInterval>,
        minStartMillis: Long = Long.MIN_VALUE,
        maxEndMillis: Long = Long.MAX_VALUE,
    ): List<LiveShareInterval> {
        val sorted = intervals
            .mapNotNull { interval ->
                val start = maxOf(interval.startMillis, minStartMillis)
                val end = minOf(interval.endMillis, maxEndMillis)
                if (end > start) LiveShareInterval(start, end) else null
            }
            .sortedBy { it.startMillis }
        if (sorted.isEmpty()) return emptyList()

        val merged = ArrayList<LiveShareInterval>(sorted.size)
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMillis <= current.endMillis) {
                current = current.copy(endMillis = maxOf(current.endMillis, next.endMillis))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    fun parse(json: String?): List<LiveShareInterval> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val outer = JSONArray(json)
            val raw = ArrayList<LiveShareInterval>(outer.length())
            for (i in 0 until outer.length()) {
                val pair = outer.optJSONArray(i) ?: continue
                if (pair.length() < 2) continue
                val start = pair.optLong(0, -1L)
                val end = pair.optLong(1, -1L)
                if (start > 0L && end > start) {
                    raw.add(LiveShareInterval(start, end))
                }
            }
            normalize(raw)
        }.getOrElse { emptyList() }
    }

    fun serialize(intervals: List<LiveShareInterval>): String? {
        val normalized = normalize(intervals)
        if (normalized.isEmpty()) return null
        val outer = JSONArray()
        normalized.forEach { span ->
            outer.put(JSONArray().apply {
                put(span.startMillis)
                put(span.endMillis)
            })
        }
        return outer.toString()
    }

    fun resolveMode(
        rideStartMillis: Long,
        rideEndMillis: Long,
        intervals: List<LiveShareInterval>,
    ): String? {
        if (rideEndMillis <= rideStartMillis) return null
        val normalized = normalize(
            intervals = intervals,
            minStartMillis = rideStartMillis,
            maxEndMillis = rideEndMillis,
        )
        if (normalized.isEmpty()) return null

        val coveredMillis = normalized.sumOf { it.endMillis - it.startMillis }
        if (coveredMillis <= 0L) return null

        val totalRideMillis = (rideEndMillis - rideStartMillis).coerceAtLeast(1L)
        val startedAtOrigin = (normalized.first().startMillis - rideStartMillis) <= EDGE_TOLERANCE_MILLIS
        val lastedToEnd = (rideEndMillis - normalized.last().endMillis) <= EDGE_TOLERANCE_MILLIS
        val ratio = coveredMillis.toDouble() / totalRideMillis.toDouble()

        return if (ratio >= FULL_COVERAGE_THRESHOLD || (normalized.size == 1 && startedAtOrigin && lastedToEnd)) {
            MODE_FULL
        } else {
            MODE_PARTIAL
        }
    }

    fun containsTimestamp(intervals: List<LiveShareInterval>, timestampMillis: Long): Boolean =
        intervals.any { timestampMillis in it.startMillis..it.endMillis }
}
