package `in`.shvms.trackme.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-356. Recovery telemetry stays coarse and silent when recovery did nothing. */
class RecoveryTelemetryTest {

    @Test
    fun `distance buckets split at 1, 5 and 20 km`() {
        assertEquals("lt_1km", RecoveryTelemetry.distanceBucket(0.0))
        assertEquals("lt_1km", RecoveryTelemetry.distanceBucket(999.9))
        assertEquals("1_5km", RecoveryTelemetry.distanceBucket(1_000.0))
        assertEquals("1_5km", RecoveryTelemetry.distanceBucket(4_999.0))
        assertEquals("5_20km", RecoveryTelemetry.distanceBucket(5_000.0))
        assertEquals("gte_20km", RecoveryTelemetry.distanceBucket(20_000.0))
        assertEquals("gte_20km", RecoveryTelemetry.distanceBucket(250_000.0))
    }

    @Test
    fun `properties carry counts and a bucket only`() {
        val props = RecoveryTelemetry.properties(recoveredCount = 2, discardedCount = 1, totalMeters = 12_300.0)
        assertEquals(setOf("recovered_count", "discarded_count", "distance_bucket"), props.keys)
        assertEquals(2, props["recovered_count"])
        assertEquals(1, props["discarded_count"])
        assertEquals("5_20km", props["distance_bucket"])
    }

    @Test
    fun `no property can leak a raw distance, id or title`() {
        val keys = RecoveryTelemetry.properties(1, 0, 4_321.0).keys
        listOf("ride_id", "title", "distance_meters", "latitude", "longitude", "timestamp").forEach {
            assertFalse("forbidden property $it", keys.contains(it))
        }
    }

    @Test
    fun `reports only when recovery changed something`() {
        assertFalse(RecoveryTelemetry.shouldReport(0, 0))
        assertTrue(RecoveryTelemetry.shouldReport(1, 0))
        assertTrue(RecoveryTelemetry.shouldReport(0, 3))
    }
}
