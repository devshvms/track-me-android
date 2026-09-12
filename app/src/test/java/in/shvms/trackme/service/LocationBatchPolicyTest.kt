package `in`.shvms.trackme.service

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationBatchPolicyTest {
    private data class Fix(val name: String, val elapsedNanos: Long, val wallMillis: Long, val lat: Double)

    @Test fun `orders the full batch and removes only exact duplicates`() {
        val newest = Fix("newest", 3_000, 300, 3.0)
        val oldest = Fix("oldest", 1_000, 100, 1.0)
        val middle = Fix("middle", 2_000, 200, 2.0)

        val ordered = orderedDistinctLocationBatch(
            listOf(newest, middle, oldest, middle.copy()),
            ::key,
        )

        assertEquals(listOf("oldest", "middle", "newest"), ordered.map { it.name })
    }

    @Test fun `keeps distinct fixes that share a timestamp`() {
        val a = Fix("a", 1_000, 100, 1.0)
        val b = Fix("b", 1_000, 100, 2.0)
        assertEquals(listOf(a, b), orderedDistinctLocationBatch(listOf(a, b), ::key))
    }

    @Test fun `falls back to wall time and preserves one-fix callbacks`() {
        val later = Fix("later", 0, 200, 2.0)
        val earlier = Fix("earlier", 0, 100, 1.0)
        assertEquals(listOf(earlier, later), orderedDistinctLocationBatch(listOf(later, earlier), ::key))
        assertEquals(listOf(earlier), orderedDistinctLocationBatch(listOf(earlier), ::key))
    }

    private fun key(fix: Fix) = LocationBatchKey(
        elapsedRealtimeNanos = fix.elapsedNanos,
        wallTimeMillis = fix.wallMillis,
        latitudeBits = fix.lat.toBits(),
        longitudeBits = 0.0.toBits(),
    )
}
