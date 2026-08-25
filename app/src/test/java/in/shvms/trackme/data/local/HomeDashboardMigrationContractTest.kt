package `in`.shvms.trackme.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDashboardMigrationContractTest {
    @Test fun `database upgrades add and register all dashboard metadata`() {
        val database = read("data/local/AppDatabase.kt")
        val app = read("TrackMeApp.kt")
        assertTrue(database.contains("version = 13"))
        assertTrue(database.contains("MIGRATION_12_13"))
        assertTrue(database.contains("`qualifiesForStats` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`dashboardActiveDurationMillis` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(database.contains("`dashboardMetadataVersion` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(app.contains("AppDatabase.MIGRATION_12_13"))
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
