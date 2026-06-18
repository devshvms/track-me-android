package com.example.trackme.domain.processor

import com.example.trackme.data.local.dao.RideDao

interface GPSProcessor {
    suspend fun processRide(rideId: Long, rideDao: RideDao)
}

class DefaultGPSProcessor : GPSProcessor {
    override suspend fun processRide(rideId: Long, rideDao: RideDao) {
        // As per requirements: "Before save, after ride complete: (process gps points- we can set rules for this later, keep empty method for now.)"
        // This is a no-op placeholder for future enterprise rules (e.g. Kalman filtering, Douglas-Peucker reduction).
    }
}
