package `in`.shvms.trackme.ui.community

import `in`.shvms.trackme.data.local.entity.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-232 / COMMUNITY_REDESIGN_SPEC §5. The acceptance criteria with teeth are about what is
 * *not* stored, so they are asserted here rather than left to review.
 */
class GroupRideRecordTest {

    @Test fun `a ride records the group as a marker and a count, and nothing else`() {
        val fields = RideEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("wasGroupRide" in fields)
        assertTrue("groupRiderCount" in fields)
        // §5.3: no card may name another rider, and the surest way to hold that is for the row to
        // have nowhere to put a name. A group id would also be a membership record.
        listOf("groupId", "groupName", "groupToken", "groupMembers", "groupRoster", "riderNames")
            .forEach { assertFalse("$it must not be stored on a ride", it in fields) }
    }

    @Test fun `a solo ride carries no group record`() {
        val ride = RideEntity(startTime = 1_000L)
        assertFalse(ride.wasGroupRide)
        assertNull(ride.groupRiderCount)
    }

    @Test fun `an unobserved group size renders no count rather than zero`() {
        // §5.5, the same honesty rule as HISTORY_DETAIL_REDESIGN_SPEC §5.2: absent is absent.
        val ride = RideEntity(startTime = 1_000L, wasGroupRide = true, groupRiderCount = null)
        assertTrue(ride.wasGroupRide)
        assertNull(ride.groupRiderCount)
    }

    @Test fun `the group fields are not in the Firestore field map`() {
        // §5.4: no new sync path, no new Data Safety surface. FirestoreSyncManager writes an
        // explicit map, so this holds only as long as nobody adds them to it.
        val sync = read("data/remote/FirestoreSyncManager.kt")
        assertFalse(sync.contains("wasGroupRide"))
        assertFalse(sync.contains("groupRiderCount"))
    }

    @Test fun `Community reads local ride records and joins nothing`() {
        val dao = read("data/local/dao/RideDao.kt")
        assertTrue("the query must exist", dao.contains("fun getGroupRideSummaries"))
        // The SQL for this one function, not a window of the file around it.
        val query = dao.substringBefore("fun getGroupRideSummaries")
            .substringAfterLast("@Query(")

        assertTrue("filters on the marker", query.contains("wasGroupRide = 1"))
        assertTrue("excludes rides pending deletion", query.contains("pendingDelete = 0"))
        assertTrue("reads the rides table", query.contains("FROM rides"))
        // The constraint TASK-216 added and TASK-231 kept: no path may pull route points per row.
        assertFalse("must never join gps_points", query.contains("gps_points"))
        assertFalse("must never join a membership table", query.contains("JOIN"))
    }

    private fun read(relative: String): String {
        var directory: File? = File("").absoluteFile
        val path = "app/src/main/java/in/shvms/trackme/$relative"
        while (directory != null) {
            File(directory, path).takeIf(File::exists)?.let { return it.readText() }
            File(directory, path.removePrefix("app/")).takeIf(File::exists)?.let { return it.readText() }
            directory = directory.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
