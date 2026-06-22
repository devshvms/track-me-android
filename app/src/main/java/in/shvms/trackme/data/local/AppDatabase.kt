package `in`.shvms.trackme.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.dao.EmergencyDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.EmergencyContactEntity
import `in`.shvms.trackme.data.local.entity.EmergencySettingsEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RideEntity::class, 
        GPSPointEntity::class,
        EmergencyContactEntity::class,
        EmergencySettingsEntity::class
    ], 
    version = 7, 
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun emergencyDao(): EmergencyDao

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
    }
}
