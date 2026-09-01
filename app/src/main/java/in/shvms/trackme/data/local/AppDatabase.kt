package `in`.shvms.trackme.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.dao.HomeDashboardDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PauseOriginConverters
import `in`.shvms.trackme.data.local.entity.RideEntity
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RideEntity::class, 
        GPSPointEntity::class
    ], 
    version = 19,
    exportSchema = false
)
@TypeConverters(PauseOriginConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun homeDashboardDao(): HomeDashboardDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rides ADD COLUMN title TEXT DEFAULT NULL")
            }
        }
        
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `emergency_contacts` (
                        `contactId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `phoneNumber` TEXT NOT NULL, 
                        `medium` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `emergency_settings` (
                        `id` INTEGER PRIMARY KEY NOT NULL, 
                        `isEnabled` INTEGER NOT NULL, 
                        `messageTemplate` TEXT NOT NULL, 
                        `premiumToken` TEXT, 
                        `broadcastIntervalSeconds` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `emergency_settings_new` (
                        `id` INTEGER PRIMARY KEY NOT NULL, 
                        `isSmsEnabled` INTEGER NOT NULL, 
                        `isWhatsappEnabled` INTEGER NOT NULL, 
                        `smsTemplate` TEXT NOT NULL, 
                        `whatsappTemplate` TEXT NOT NULL, 
                        `premiumToken` TEXT, 
                        `broadcastIntervalSeconds` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `emergency_settings_new` (id, isSmsEnabled, isWhatsappEnabled, smsTemplate, whatsappTemplate, premiumToken, broadcastIntervalSeconds) 
                    SELECT id, isEnabled, 0, messageTemplate, messageTemplate, premiumToken, broadcastIntervalSeconds FROM `emergency_settings`
                """.trimIndent())
                database.execSQL("DROP TABLE `emergency_settings`")
                database.execSQL("ALTER TABLE `emergency_settings_new` RENAME TO `emergency_settings`")
            }
        }
        
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `emergency_settings_new` (
                        `id` INTEGER PRIMARY KEY NOT NULL, 
                        `isSetupComplete` INTEGER NOT NULL, 
                        `messageTemplate` TEXT NOT NULL, 
                        `premiumToken` TEXT, 
                        `broadcastIntervalSeconds` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `emergency_settings_new` (id, isSetupComplete, messageTemplate, premiumToken, broadcastIntervalSeconds) 
                    SELECT id, isSmsEnabled, smsTemplate, premiumToken, broadcastIntervalSeconds FROM `emergency_settings`
                """.trimIndent())
                database.execSQL("DROP TABLE `emergency_settings`")
                database.execSQL("ALTER TABLE `emergency_settings_new` RENAME TO `emergency_settings`")
            }
        }
        
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `maxAcceleration` REAL")
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `rawPointCount` INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `persona` TEXT DEFAULT 'AUTO' NOT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_gps_points_rideId ON gps_points(rideId)")
            }
        }

        /**
         * TG-A19 (1.6.5): drop the emergency_contacts and emergency_settings tables.
         * The emergency-contact feature is retired; its UI, DAO, entities, and Firestore
         * sync were removed in the same change. HAZARD-5: this is an explicit Migration,
         * not a fallbackToDestructiveMigration call.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS emergency_contacts")
                database.execSQL("DROP TABLE IF EXISTS emergency_settings")
            }
        }

        /**
         * SCOPE_1.7.3 §2(a), §0 contract 5: the local half of cascade deletion.
         *
         * `pendingDelete` marks a ride as on its way out before the cloud batch is committed, so a
         * crash between the two cannot resurrect it — the uploader refuses anything carrying the
         * flag, and only a genuine cloud rejection clears it again.
         *
         * Defaults to 0: every existing row is a ride the user still has.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `pendingDelete` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** TASK-188: additive marker for the local-only first-run sample ride. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `isSample` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** TASK-205A: additive, rebuildable metadata for projection-only Home aggregates. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `qualifiesForStats` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `dashboardActiveDurationMillis` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `dashboardMetadataVersion` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rides_dashboard_summary` " +
                        "ON `rides` (`qualifiesForStats`, `pendingDelete`, `isSample`, `startTime`)"
                )
            }
        }

        /** TASK-206: route availability and per-ride calendar zone, additive from the WIP v13. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `dashboardPointCount` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `startZoneId` TEXT"
                )
            }
        }

        /** TASK-215: persisted ascent is nullable until a ride has enough valid altitude data. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `elevationGainMeters` REAL")
            }
        }

        /**
         * TASK-231: the History thumbnail's route shape, stored on the ride row. Additive and
         * nullable, the same pattern as startZoneId -- existing rows are filled in by the bounded
         * metadata reconciler, not by this migration, so an upgrade never reads gps_points inline.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `dashboardRoutePolyline` TEXT")
            }
        }

        /**
         * TASK-232: the group marker and rider count. Additive, and there is deliberately no
         * backfill -- a ride recorded before this shipped has no record of having been a group
         * ride, and inventing one is not possible from anything stored.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `wasGroupRide` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `groupRiderCount` INTEGER")
            }
        }

        /**
         * TASK-270 repair: preserve whether a new paused point came from automatic sampling or a
         * manual boundary. Existing rows remain null because their origin cannot be reconstructed
         * truthfully from GPS values.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `gps_points` ADD COLUMN `pauseOrigin` TEXT")
            }
        }

        /**
         * TASK-275: ride provenance.
         *
         * `source` defaults to RECORDED for every existing row, which is the honest answer rather
         * than a convenient one: before this column existed the import path wrote rows the recorder
         * could not be distinguished from, so there is no evidence on which to call any of them
         * imported. New imports are marked going forward; history keeps the benefit of the doubt.
         *
         * `contentHash` is left null and filled by the metadata reconciler, so the migration stays
         * a schema change and does not read the whole gps_points table on the upgrade path.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rides` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'RECORDED'"
                )
                database.execSQL("ALTER TABLE `rides` ADD COLUMN `contentHash` TEXT")
            }
        }
    }
}
