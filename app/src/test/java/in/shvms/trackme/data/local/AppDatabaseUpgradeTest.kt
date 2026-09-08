package `in`.shvms.trackme.data.local

import android.app.Application
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * TASK-309 — the upgrade path, guarded.
 *
 * Nobody installs 1.6.3 and upgrades to 1.8.7 by hand before a release; the emulator always has
 * either a fresh database or yesterday's. So the failure mode this file exists for is the one that
 * is invisible in development and fatal in production: Room refuses to open a database whose
 * `user_version` it cannot reach through the registered migrations, and it refuses **on the first
 * database access after launch**.
 *
 * Android's half of TASK-309 did not touch the schema — `emergency_contacts` and
 * `emergency_settings` were already dropped by `MIGRATION_9_10` in 1.6.5, and the entity list has
 * not mentioned them since. What the task removed was Kotlin: `EmergencyManager`,
 * `SosStateCleanup`, `SosRemovalNoticePolicy` and the strings behind the removal notice. None of
 * that can break an upgrade, because nothing persisted by it is read any more.
 *
 * The tests below therefore guard the thing that *can* still break, which is the next person's
 * change rather than this one: a schema version bumped without a migration to match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppDatabaseUpgradeTest {

    /**
     * Every version between the oldest supported database and the current one must be reachable.
     *
     * A gap here is not a subtle bug. `fallbackToDestructiveMigration()` is on, so the user does
     * not get a crash — they get a silently empty History, which is worse: their rides are gone and
     * nothing tells them why. Read from `TrackMeApp` rather than from a list in the test, because
     * registering a migration and *forgetting to add it to the builder* is exactly the mistake.
     */
    @Test
    fun `every schema version is reachable by a registered migration`() {
        val app = source("TrackMeApp.kt")
        val addMigrations = app.substringAfter(".addMigrations(").substringBefore(")")

        val registered = Regex("""MIGRATION_(\d+)_(\d+)""").findAll(addMigrations)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        assertTrue("no migrations are registered at all", registered.isNotEmpty())

        val declared = Regex("""version\s*=\s*(\d+)""")
            .find(source("data/local/AppDatabase.kt"))
            ?.groupValues?.get(1)?.toInt()
        assertEquals("could not read the @Database version", 19, declared)

        val oldest = registered.minOf { it.first }
        val reachable = registered.toMap()
        var at = oldest
        while (at < declared!!) {
            val next = reachable[at]
            assertEquals(
                "no migration out of schema version $at — an install sitting on it loses its rides",
                at + 1,
                next,
            )
            at = next!!
        }
        assertEquals("the chain does not end at the declared version", declared, at)
    }

    /** Each step must move exactly one version; a MIGRATION_9_11 would skip a rung silently. */
    @Test
    fun `each registered migration advances exactly one version`() {
        val addMigrations = source("TrackMeApp.kt").substringAfter(".addMigrations(").substringBefore(")")
        Regex("""MIGRATION_(\d+)_(\d+)""").findAll(addMigrations).forEach {
            val (from, to) = it.groupValues[1].toInt() to it.groupValues[2].toInt()
            assertEquals("MIGRATION_${from}_$to does not advance one version", from + 1, to)
        }
    }

    /**
     * The migration that actually retired the emergency tables, run against real SQLite.
     *
     * It is also run twice. `DROP TABLE IF EXISTS` makes that safe, and it needs to be: a process
     * killed mid-upgrade re-runs the migration on the next launch.
     */
    @Test
    fun `the emergency tables are dropped and dropping them again is harmless`() {
        val file = File.createTempFile("trackme-migration-9-10", ".db").also { it.delete() }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext()
            )
                .name(file.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `emergency_contacts` (`contactId` INTEGER PRIMARY KEY " +
                                "AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                                "`phoneNumber` TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE `emergency_settings` (`id` INTEGER PRIMARY KEY NOT NULL, " +
                                "`isSetupComplete` INTEGER NOT NULL, `messageTemplate` TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO `emergency_contacts` (name, phoneNumber) " +
                                "VALUES ('Legacy contact', '+15550100')"
                        )
                        db.execSQL(
                            "INSERT INTO `emergency_settings` (id, isSetupComplete, messageTemplate) " +
                                "VALUES (1, 1, 'EMERGENCY!')"
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        val db = helper.writableDatabase
        assertTrue("the fixture did not create the legacy tables", tableExists(db, "emergency_contacts"))

        AppDatabase.MIGRATION_9_10.migrate(db)
        assertFalse(tableExists(db, "emergency_contacts"))
        assertFalse(tableExists(db, "emergency_settings"))

        AppDatabase.MIGRATION_9_10.migrate(db)
        assertFalse(tableExists(db, "emergency_contacts"))

        db.close()
        file.delete()
    }

    /** Nothing may quietly reintroduce an emergency entity to the shipped schema. */
    @Test
    fun `the shipped schema declares no emergency entity`() {
        val entities = source("data/local/AppDatabase.kt")
            .substringAfter("entities = [").substringBefore("]")
        assertEquals("RideEntity::class,GPSPointEntity::class", entities.filter { !it.isWhitespace() })
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name))
            .use { it.count > 0 }

    private fun source(name: String): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$name"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("$name not found")
    }
}
