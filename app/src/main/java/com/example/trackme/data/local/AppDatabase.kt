package com.example.trackme.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.trackme.data.local.dao.RideDao
import com.example.trackme.data.local.entity.GPSPointEntity
import com.example.trackme.data.local.entity.RideEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RideEntity::class, GPSPointEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rides ADD COLUMN title TEXT DEFAULT NULL")
            }
        }
    }
}
