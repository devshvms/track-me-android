package `in`.shvms.trackme.domain.gamification

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@JsonClass(generateAdapter = true)
data class GamificationVectors(
    val levels: List<LevelVector>,
    val milestones: List<MilestoneVector>,
    val comparisons: List<ComparisonVector>,
    val snapshots: List<SnapshotVector>
)

@JsonClass(generateAdapter = true)
data class LevelVector(
    val duration_millis: Long,
    val expected_level_id: String
)

@JsonClass(generateAdapter = true)
data class MilestoneVector(
    val activity_count: Int,
    val expected_milestone_id: String
)

@JsonClass(generateAdapter = true)
data class ComparisonVector(
    val current_value: Long,
    val previous_value: Long,
    val expected_state: String
)

@JsonClass(generateAdapter = true)
data class SnapshotVector(
    val description: String,
    val activities: List<ActivityVector>,
    val expected_snapshot: ExpectedSnapshot
)

@JsonClass(generateAdapter = true)
data class ActivityVector(
    val id: String,
    val active_duration_millis: Long
)

@JsonClass(generateAdapter = true)
data class ExpectedSnapshot(
    val active_duration_millis: Long,
    val activity_count: Int,
    val level_id: String,
    val level_name_key: String,
    val current_minutes: Long,
    val current_threshold_minutes: Long,
    val next_threshold_minutes: Long?,
    val progress_numerator_minutes: Long,
    val progress_denominator_minutes: Long,
    val latest_milestone_id: String,
    val unlocked_milestone_ids: List<String>,
    val unlocked_milestone_count: Int
)

class GamificationVectorsTest {

    @Test
    fun `decodes complete gamification vector file accurately`() {
        val file = File("src/test/resources/home-gamification-v1.json")
        assertTrue("Vector file must exist", file.exists())
        
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(GamificationVectors::class.java)
        
        val json = file.readText()
        val vectors = adapter.fromJson(json)
        
        assertNotNull("Vectors should decode successfully", vectors)
        
        // Assert new stress boundaries exist
        val stressSnapshot = vectors!!.snapshots.find { it.description == "5000 rows aggregation" }
        assertNotNull("Must contain the 5000-row stress vector", stressSnapshot)
        assertTrue("Stress vector has 5000 activities", stressSnapshot!!.activities.size == 5000)
        
        val maxLevelSnapshot = vectors.snapshots.find { it.description == "Max level overflow" }
        assertNotNull(maxLevelSnapshot)
        
        // Assert all expected_snapshot fields parse without error
        vectors.snapshots.forEach {
            assertNotNull(it.expected_snapshot.level_name_key)
            assertNotNull(it.expected_snapshot.progress_numerator_minutes)
        }
    }
}
