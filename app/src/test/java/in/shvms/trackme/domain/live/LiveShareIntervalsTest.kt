package `in`.shvms.trackme.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveShareIntervalsTest {

    @Test
    fun `normalizes overlapping and adjacent intervals and round-trips json`() {
        val raw = listOf(
            LiveShareInterval(startMillis = 5_000L, endMillis = 15_000L),
            LiveShareInterval(startMillis = 12_000L, endMillis = 25_000L),
            LiveShareInterval(startMillis = 40_000L, endMillis = 60_000L),
            LiveShareInterval(startMillis = 70_000L, endMillis = 70_000L), // empty, dropped
        )
        val json = LiveShareIntervals.serialize(raw)
        assertEquals("[[5000,25000],[40000,60000]]", json)

        val parsed = LiveShareIntervals.parse(json)
        assertEquals(
            listOf(
                LiveShareInterval(5_000L, 25_000L),
                LiveShareInterval(40_000L, 60_000L),
            ),
            parsed,
        )
    }

    @Test
    fun `resolves FULL vs PARTIAL mode accurately`() {
        // Full coverage within tolerance
        assertEquals(
            LiveShareIntervals.MODE_FULL,
            LiveShareIntervals.resolveMode(
                rideStartMillis = 0L,
                rideEndMillis = 120_000L,
                intervals = listOf(LiveShareInterval(10_000L, 115_000L)),
            ),
        )

        // Started mid-ride for 5 minutes of a 20 minute ride -> PARTIAL
        assertEquals(
            LiveShareIntervals.MODE_PARTIAL,
            LiveShareIntervals.resolveMode(
                rideStartMillis = 0L,
                rideEndMillis = 1_200_000L,
                intervals = listOf(LiveShareInterval(600_000L, 900_000L)),
            ),
        )

        // No intervals -> null
        assertNull(
            LiveShareIntervals.resolveMode(
                rideStartMillis = 0L,
                rideEndMillis = 120_000L,
                intervals = emptyList(),
            )
        )
    }

    @Test
    fun `containsTimestamp checks interval boundaries inclusively`() {
        val intervals = listOf(LiveShareInterval(1_000L, 5_000L))
        assertTrue(LiveShareIntervals.containsTimestamp(intervals, 1_000L))
        assertTrue(LiveShareIntervals.containsTimestamp(intervals, 3_500L))
        assertTrue(LiveShareIntervals.containsTimestamp(intervals, 5_000L))
        assertFalse(LiveShareIntervals.containsTimestamp(intervals, 999L))
        assertFalse(LiveShareIntervals.containsTimestamp(intervals, 5_001L))
    }
}
