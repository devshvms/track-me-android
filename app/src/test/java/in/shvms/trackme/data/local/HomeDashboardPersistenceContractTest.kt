package `in`.shvms.trackme.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDashboardPersistenceContractTest {
    @Test
    fun `recording and backfill use authoritative dashboard facts`() {
        val service = read("service/TrackingService.kt")
        val repository = read("data/local/HomeDashboardRepository.kt")
        val projection = read("data/local/dao/HomeDashboardProjection.kt")

        assertTrue(service.contains("startZoneId = java.time.ZoneId.systemDefault().id"))
        assertTrue(service.contains("activeTimeMs,"))
        assertTrue(service.contains("rawPointCount = points.size"))
        assertTrue(repository.contains("val points = rideDao.getPointsForRideSync(ride.id)"))
        assertFalse(repository.contains("endTime - ride.startTime"))
        assertTrue(projection.contains("rides.dashboardPointCount > 0 AS hasRoute"))
        assertFalse(projection.contains("EXISTS(SELECT 1 FROM gps_points"))
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
