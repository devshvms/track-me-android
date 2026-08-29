package `in`.shvms.trackme.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDashboardTelemetryPrivacyTest {
    @Test fun `dashboard taxonomy exposes only the approved coarse properties`() {
        val source = analyticsSource()
        val section = source.substring(
            source.indexOf("fun trackHomeDashboardViewed"),
            source.indexOf("fun trackVoiceEvent"),
        )
        listOf(
            "home_dashboard_viewed",
            "activity_start_cta_tapped",
            "home_insight_shown",
            "home_recent_activity_opened",
            "home_group_map_opened",
            "history_bucket",
            "insight_type",
            "persona",
            "method",
        ).forEach { assertTrue("missing $it", section.contains("\"$it\"")) }

        listOf(
            "ride_id", "title", "latitude", "longitude", "route", "timestamp",
            "email", "name", "distance_meters", "duration_millis", "personal_best",
        ).forEach { assertFalse("forbidden dashboard property $it", section.contains("\"$it\"")) }
    }

    private fun analyticsSource(): String {
        var directory: File? = File("").absoluteFile
        val relative = "app/src/main/java/in/shvms/trackme/analytics/AnalyticsManager.kt"
        while (directory != null) {
            File(directory, relative).takeIf(File::exists)?.let { return it.readText() }
            File(directory, relative.removePrefix("app/")).takeIf(File::exists)?.let { return it.readText() }
            directory = directory.parentFile
        }
        throw AssertionError("AnalyticsManager.kt not found")
    }
}
