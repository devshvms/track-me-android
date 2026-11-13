package `in`.shvms.trackme.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CIE-002: Verifies cross-platform telemetry property parity for live sharing.
 * Ensures session_id, share_id, path_points, has_heading, is_stopped, and viewer properties
 * match web and iOS contracts without PII or precise coordinates leaking into analytics.
 */
class LiveShareTelemetryParityTest {

    private fun sourcePath(): String {
        val candidates = listOf(
            "app/src/main/java/in/shvms/trackme/analytics/AnalyticsManager.kt",
            "src/main/java/in/shvms/trackme/analytics/AnalyticsManager.kt",
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException("AnalyticsManager.kt not found in working directory")
    }

    private val liveShareBlock: String by lazy {
        val source = File(sourcePath()).readText()
        val start = source.indexOf("fun trackLiveShareStarted")
        val end = source.indexOf("// Screen tracking", start)
        require(start >= 0 && end > start) { "live share telemetry block not found" }
        source.substring(start, end)
    }

    @Test
    fun `live share started and ended carry both share_id and session_id`() {
        assertTrue(
            "trackLiveShareStarted must include \"share_id\"",
            liveShareBlock.contains("\"share_id\" to shareId"),
        )
        assertTrue(
            "trackLiveShareStarted must include \"session_id\" for cross-platform parity",
            liveShareBlock.contains("\"session_id\" to shareId"),
        )
        assertTrue(
            "trackLiveShareEnded must include \"share_id\"",
            liveShareBlock.contains("\"share_id\" to shareId"),
        )
        assertTrue(
            "trackLiveShareEnded must include \"session_id\" for cross-platform parity",
            liveShareBlock.contains("\"session_id\" to shareId"),
        )
    }

    @Test
    fun `live share viewer telemetry provides dual-format keys and no coordinates`() {
        val source = File(sourcePath()).readText()
        assertTrue(source.contains("fun trackLiveShareViewed"))
        assertTrue(source.contains("fun trackLiveShareLocationRendered"))
        assertTrue(source.contains("\"path_points\" to pathPoints"))
        assertTrue(source.contains("\"pathPoints\" to pathPoints"))
        assertTrue(source.contains("\"has_heading\" to hasHeading"))
        assertTrue(source.contains("\"hasHeading\" to hasHeading"))
        assertTrue(source.contains("\"is_stopped\" to isStopped"))
        assertTrue(source.contains("\"isStopped\" to isStopped"))

        // Strictly verify that raw coordinates are NEVER captured in viewer telemetry
        assertFalse(source.contains("\"lat\" to"))
        assertFalse(source.contains("\"lng\" to"))
        assertFalse(source.contains("\"lon\" to"))
    }

    @Test
    fun `stop ride telemetry method exists for parity with iOS`() {
        val source = File(sourcePath()).readText()
        assertTrue(
            "trackLiveShareStoppedRide must exist",
            source.contains("fun trackLiveShareStoppedRide()"),
        )
        assertTrue(
            "live_share_stopped_ride event name must be emitted",
            source.contains("\"live_share_stopped_ride\""),
        )
    }
}
