package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import com.google.firebase.Timestamp
import java.util.Date
import `in`.shvms.trackme.domain.model.RidePersona

// TASK-257 gave computeCalcFromPoints a persona, because the gap rule's speed ceiling depends on
// it. These cases are about coercing cloud field types, not about gaps, so they pass AUTO -- the
// most permissive ceiling -- to keep testing what they were written to test.
class SyncDownloadCoercionTest {

    @Test
    fun `dashboard cloud metadata preserves active duration point count and ride zone`() {
        val metadata = dashboardCloudMetadata(
            RideEntity(
                startTime = 1L,
                startZoneId = "Asia/Kolkata",
                dashboardActiveDurationMillis = 123_456L,
                dashboardMetadataVersion = `in`.shvms.trackme.data.local.HOME_DASHBOARD_METADATA_VERSION,
            ),
            pointCount = 42,
        )
        assertEquals(123_456L, metadata["activeDurationMillis"])
        assertEquals(42, metadata["rawPointCount"])
        assertEquals("Asia/Kolkata", metadata["startZoneId"])
    }

    @Test
    fun `dashboard cloud metadata omits placeholder duration before reconciliation`() {
        val metadata = dashboardCloudMetadata(RideEntity(startTime = 1L), pointCount = 7)
        assertFalse(metadata.containsKey("activeDurationMillis"))
        assertEquals(7, metadata["rawPointCount"])
    }

    @Test
    fun testCoerceEpochMillis_Long() {
        val ms = 1629837483000L
        assertEquals(ms, coerceEpochMillis(ms))
    }

    @Test
    fun testCoerceEpochMillis_Timestamp() {
        val seconds = 1629837483L
        val nanos = 500_000_000
        val ts = Timestamp(seconds, nanos)
        val expected = seconds * 1000 + nanos / 1_000_000
        assertEquals(expected, coerceEpochMillis(ts))
        assertEquals(ts.toDate().time, coerceEpochMillis(ts))
    }

    @Test
    fun testCoerceEpochMillis_Double_Seconds() {
        val seconds = 1.629837483E9
        assertEquals(1629837483000L, coerceEpochMillis(seconds))
    }

    @Test
    fun testCoerceEpochMillis_Double_Millis() {
        val millis = 1.629837483E12
        assertEquals(1629837483000L, coerceEpochMillis(millis))
    }

    @Test
    fun testCoerceEpochMillis_Date() {
        val date = Date(1629837483000L)
        assertEquals(1629837483000L, coerceEpochMillis(date))
    }

    @Test
    fun testCoerceEpochMillis_NullAndString() {
        assertNull(coerceEpochMillis(null))
        assertNull(coerceEpochMillis("2021-08-24"))
    }

    @Test
    fun testCoerceEpochMillis_Parity() {
        val ms = 1629837483500L
        val ts = Timestamp(Date(ms))
        assertEquals(coerceEpochMillis(ms), coerceEpochMillis(ts))
    }

    @Test
    fun testComputeCalcFromPoints_Normal() {
        val points = listOf(
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 5f, 1000L, false),
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 10f, 2000L, false)
        )
        val calc = computeCalcFromPoints(points, RidePersona.AUTO) { a, b -> 100f }

        assertEquals(10f, calc.maxSpeed)
        assertEquals(100.0, calc.distance, 0.001)
        assertEquals(100f, calc.avgSpeed)
        assertEquals(0L, calc.pauseDuration)
    }

    @Test
    fun testComputeCalcFromPoints_Paused() {
        val points = listOf(
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 5f, 1000L, false),
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 0f, 2000L, true),
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 8f, 3000L, false)
        )
        val calc = computeCalcFromPoints(points, RidePersona.AUTO) { a, b -> 100f }

        assertEquals(0.0, calc.distance, 0.001)
        assertEquals(0f, calc.avgSpeed)
        assertEquals(8f, calc.maxSpeed)
        assertEquals(2000L, calc.pauseDuration)
    }

    @Test
    fun testComputeCalcFromPoints_LessThanTwo() {
        val points = listOf(GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 5f, 1000L, false))
        val calc = computeCalcFromPoints(points, RidePersona.AUTO) { a, b -> 100f }
        assertEquals(0.0, calc.distance, 0.001)
        assertEquals(0f, calc.maxSpeed)
        assertEquals(0f, calc.avgSpeed)
        assertEquals(0L, calc.pauseDuration)
    }

    @Test
    fun testComputeCalcFromPoints_LargeGap() {
        val points = listOf(
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 5f, 1000L, false),
            GPSPointEntity(0L, 0L, 0.0, 0.0, 0.0, 0f, 10f, 100_000L, false) // 99s gap
        )
        val calc = computeCalcFromPoints(points, RidePersona.AUTO) { a, b -> 100f }
        assertEquals(100.0, calc.distance, 0.001)
        assertEquals(0f, calc.avgSpeed)
        assertEquals(10f, calc.maxSpeed)
        assertEquals(99_000L, calc.pauseDuration)
    }
}
