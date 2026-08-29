package `in`.shvms.trackme.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDashboardMigrationContractTest {
    @Test fun `database upgrades add and register all dashboard metadata`() {
        val database = read("data/local/AppDatabase.kt")
        val app = read("TrackMeApp.kt")
        assertTrue(database.contains("version = 17"))
        assertTrue(database.contains("MIGRATION_12_13"))
        assertTrue(database.contains("MIGRATION_13_14"))
        assertTrue(database.contains("`qualifiesForStats` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`dashboardActiveDurationMillis` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`dashboardPointCount` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`dashboardMetadataVersion` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`startZoneId` TEXT"))
        assertTrue(database.contains("MIGRATION_14_15"))
        assertTrue(database.contains("`elevationGainMeters` REAL"))
        assertTrue(database.contains("MIGRATION_15_16"))
        assertTrue(database.contains("`dashboardRoutePolyline` TEXT"))
        assertTrue(database.contains("MIGRATION_16_17"))
        assertTrue(database.contains("`wasGroupRide` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`groupRiderCount` INTEGER"))
        assertTrue(app.contains("AppDatabase.MIGRATION_12_13"))
        assertTrue(app.contains("AppDatabase.MIGRATION_13_14"))
        assertTrue(app.contains("AppDatabase.MIGRATION_14_15"))
        assertTrue(app.contains("AppDatabase.MIGRATION_15_16"))
        assertTrue(app.contains("AppDatabase.MIGRATION_16_17"))
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
