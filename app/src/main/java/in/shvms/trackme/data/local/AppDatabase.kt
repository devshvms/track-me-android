package `in`.shvms.trackme.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RideEntity::class, 
        GPSPointEntity::class
    ], 
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

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
    }
}
