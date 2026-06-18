package com.example.trackme.utils

import java.util.Calendar
import java.util.TimeZone

object RideUtils {
    fun getDefaultTitle(startTimeMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = startTimeMillis
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        
        return when (hour) {
            in 5..11 -> "Morning Ride"
            in 12..16 -> "Afternoon Ride"
            in 17..20 -> "Evening Ride"
            else -> "Night Ride"
        }
    }
}
