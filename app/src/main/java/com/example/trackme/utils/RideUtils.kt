package com.example.trackme.utils

import java.util.Calendar
import java.util.TimeZone

object RideUtils {
    fun getDefaultTitle(startTimeMillis: Long, maxSpeedKmh: Float? = null): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = startTimeMillis
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        
        val activity = if (maxSpeedKmh != null) {
            if (maxSpeedKmh > com.example.trackme.config.AppConfig.WALKING_MAX_SPEED_KMH) "Bike Ride" else "Walk/Run"
        } else {
            "Ride"
        }
        
        return when (hour) {
            in 5..11 -> "Morning $activity"
            in 12..16 -> "Afternoon $activity"
            in 17..20 -> "Evening $activity"
            else -> "Night $activity"
        }
    }
}
