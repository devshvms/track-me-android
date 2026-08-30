package `in`.shvms.trackme.domain.gamification

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class GamificationVectorsTest {
    private fun vectors(): JSONObject = JSONObject(
        File("src/test/resources/home-gamification-v1.json").readText()
    )

    @Test
    fun `complete vector file decodes including real 5000 row input`() {
        val snapshots = vectors().getJSONArray("snapshots")
        var stressRows: Int? = null
        for (index in 0 until snapshots.length()) {
            val vector = snapshots.getJSONObject(index)
            val expected = vector.getJSONObject("expected_snapshot")
            // Touch every contract field so a missing or mistyped field fails this decoder.
            expected.getLong("active_duration_millis")
            expected.getInt("activity_count")
            expected.getString("level_id")
            expected.getString("level_name_key")
            expected.getLong("current_minutes")
            expected.getLong("current_threshold_minutes")
            if (!expected.isNull("next_threshold_minutes")) expected.getLong("next_threshold_minutes")
            expected.getLong("progress_numerator_minutes")
            expected.getLong("progress_denominator_minutes")
            expected.getString("latest_milestone_id")
            expected.getJSONArray("unlocked_milestone_ids")
            expected.getInt("unlocked_milestone_count")
            if (vector.getString("description") == "5000 rows aggregation") {
                stressRows = vector.getJSONArray("activities").length()
            }
        }
        assertNotNull(stressRows)
        assertEquals(5_000, stressRows)
    }

    @Test
    fun `engine consumes every expected snapshot field`() {
        val snapshots = vectors().getJSONArray("snapshots")
        for (index in 0 until snapshots.length()) {
            val vector = snapshots.getJSONObject(index)
            val activities = vector.getJSONArray("activities")
            var durationMillis = 0L
            for (activityIndex in 0 until activities.length()) {
                durationMillis += activities.getJSONObject(activityIndex).getLong("active_duration_millis")
            }
            val actual = GamificationEngine.deriveSnapshot(
                GamificationFacts(activities.length(), durationMillis)
            )
            val expected = vector.getJSONObject("expected_snapshot")
            val description = vector.getString("description")

            assertEquals(description, expected.getString("level_id"), actual.currentLevelId)
            assertEquals(description, expected.getString("level_name_key"), actual.currentLevelNameKey)
            assertEquals(description, expected.getLong("current_minutes"), actual.currentMinutes)
            assertEquals(description, expected.getLong("current_threshold_minutes"), actual.currentThresholdMinutes)
            val expectedNext = if (expected.isNull("next_threshold_minutes")) null else expected.getLong("next_threshold_minutes")
            assertEquals(description, expectedNext, actual.nextThresholdMinutes)
            assertEquals(description, expected.getLong("progress_numerator_minutes"), actual.progressNumeratorMinutes)
            assertEquals(description, expected.getLong("progress_denominator_minutes"), actual.progressDenominatorMinutes)
            val expectedLatest = expected.getString("latest_milestone_id").takeUnless { it == "milestone_none" }
            assertEquals(description, expectedLatest, actual.latestUnlockedMilestoneId)
            val expectedMilestones = expected.getJSONArray("unlocked_milestone_ids").let { array ->
                (0 until array.length()).map(array::getString)
            }
            assertEquals(description, expectedMilestones, actual.unlockedMilestoneIds)
            assertEquals(description, expected.getInt("unlocked_milestone_count"), actual.unlockedMilestoneCount)
        }
    }
}
