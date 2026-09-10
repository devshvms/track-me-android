package `in`.shvms.trackme.data.local

import android.content.Context
import `in`.shvms.trackme.domain.notifications.ActivityReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a — where the reminder the user set is kept.
 *
 * Small and deliberately dull. The only thing worth saying about it is the default: [load] returns
 * a disabled reminder for a device that has never stored one, and there is no migration, no
 * "enable on upgrade", and no path that writes `enabled = true` except [save] being called from the
 * settings screen. §6.1.3's whole distinction between the shipped 12a and the cut 12 is that a
 * human turned this on.
 */
class ActivityReminderStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ActivityReminder.Settings> = _settings.asStateFlow()

    /** The last epoch day this reminder fired, so a double wake cannot double-notify. */
    val lastFiredEpochDay: Long?
        get() = if (prefs.contains(KEY_LAST_FIRED)) prefs.getLong(KEY_LAST_FIRED, 0L) else null

    fun save(settings: ActivityReminder.Settings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_DAY, settings.dayOfWeek)
            .putStringSet(KEY_DAYS, settings.selectedDays.map { it.toString() }.toSet())
            .putInt(KEY_HOUR, settings.hour)
            .putInt(KEY_MINUTE, settings.minute)
            .putString(KEY_PERSONA, settings.persona)
            .apply()
        _settings.value = settings
    }

    fun recordFired(epochDay: Long) {
        prefs.edit().putLong(KEY_LAST_FIRED, epochDay).apply()
    }

    private fun load(): ActivityReminder.Settings = ActivityReminder.Settings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        dayOfWeek = prefs.getInt(KEY_DAY, ActivityReminder.Settings.DEFAULT_DAY),
        daysOfWeek = prefs.getStringSet(KEY_DAYS, null)?.map { it.toIntOrNull() ?: 0 }?.toSet(),
        hour = prefs.getInt(KEY_HOUR, ActivityReminder.Settings.DEFAULT_HOUR),
        minute = prefs.getInt(KEY_MINUTE, 0),
        persona = prefs.getString(KEY_PERSONA, ActivityReminder.Settings.DEFAULT_PERSONA)
            ?: ActivityReminder.Settings.DEFAULT_PERSONA,
    )

    companion object {
        const val PREFS = "trackme_activity_reminder"
        const val KEY_ENABLED = "enabled"
        const val KEY_DAY = "day_of_week"
        const val KEY_DAYS = "days_of_week"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"
        const val KEY_PERSONA = "persona"
        const val KEY_LAST_FIRED = "last_fired_epoch_day"
    }
}
