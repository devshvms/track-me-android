package `in`.shvms.trackme.utils

import `in`.shvms.trackme.domain.model.RidePersona
import java.util.Calendar
import java.util.TimeZone

object RideUtils {
    fun getDefaultTitle(startTimeMillis: Long, maxSpeedKmh: Float? = null): String {
        return getDefaultTitle(startTimeMillis, RidePersona.AUTO, maxSpeedKmh)
    }

    fun getDefaultTitle(
        startTimeMillis: Long,
        persona: RidePersona,
        maxSpeedKmh: Float? = null
    ): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = startTimeMillis
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        val activity = when (persona) {
            RidePersona.WALK -> "Walk"
            RidePersona.RUN -> "Run"
            RidePersona.CYCLING -> "Cycling Ride"
            RidePersona.BIKE_DRIVE -> "BikeDrive"
            RidePersona.CAR_DRIVE -> "CarDrive"
            RidePersona.AUTO -> when {
                maxSpeedKmh == null -> "Ride"
                maxSpeedKmh > `in`.shvms.trackme.config.AppConfig.WALKING_MAX_SPEED_KMH -> "Bike Ride"
                else -> "Walk/Run"
            }
        }

        return when (hour) {
            in 5..11 -> "Morning $activity"
            in 12..16 -> "Afternoon $activity"
            in 17..20 -> "Evening $activity"
            else -> "Night $activity"
        }
    }

    fun personaFromStoredName(value: String): RidePersona =
        RidePersona.entries.firstOrNull { it.name == value } ?: RidePersona.AUTO

    fun isGeneratedTitle(title: String?, startTimeMillis: Long, persona: RidePersona): Boolean {
        if (title == null) return false
        return title == getDefaultTitle(startTimeMillis) ||
            title == getDefaultTitle(startTimeMillis, persona) ||
            title == getDefaultTitle(startTimeMillis, RidePersona.AUTO, 0f) ||
            title == getDefaultTitle(startTimeMillis, RidePersona.AUTO, Float.MAX_VALUE)
    }
}
