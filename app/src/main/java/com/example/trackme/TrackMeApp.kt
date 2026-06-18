package com.example.trackme

import android.app.Application
import androidx.room.Room
import com.example.trackme.data.local.AppDatabase
import com.example.trackme.service.TrackingManager

import com.example.trackme.auth.AuthManager
import com.example.trackme.data.remote.FirestoreSyncManager

class TrackMeApp : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var trackingManager: TrackingManager
        private set
        
    lateinit var authManager: AuthManager
        private set

    lateinit var firestoreSyncManager: FirestoreSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "trackme_db"
        )
        .addMigrations(AppDatabase.MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()
        
        trackingManager = TrackingManager()
        authManager = AuthManager()
        firestoreSyncManager = FirestoreSyncManager(database.rideDao(), authManager)
    }
}
