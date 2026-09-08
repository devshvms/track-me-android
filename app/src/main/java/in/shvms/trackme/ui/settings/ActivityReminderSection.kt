package `in`.shvms.trackme.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.ActivityReminderStore
import `in`.shvms.trackme.data.local.RideHistoryProfileSource
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.notifications.ActivityReminder
import `in`.shvms.trackme.domain.notifications.RideHistoryProfile
import `in`.shvms.trackme.service.notifications.ActivityReminderWorker
import `in`.shvms.trackme.ui.components.SettingsDivider
import `in`.shvms.trackme.ui.components.SettingsGroup
import `in`.shvms.trackme.ui.components.SettingsRow
import `in`.shvms.trackme.ui.components.SettingsSwitchRow
import `in`.shvms.trackme.ui.localization.AppStrings
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a — the screen where the inference becomes a courtesy.
 *
 * ### The suggestion is an offer, and the UI has to look like one
 *
 * Scenario 12 was cut for inferring a routine and acting on it. What makes 12a shippable is not
 * that the inference is weaker — it is the same inference — but that it arrives as *"Suggested from
 * your history: Saturday, 8:00"* next to a button the user has to press. The copy naming its own
 * source is load-bearing: an app that silently pre-fills the right answer is still an app that has
 * been watching, and hiding that is worse than saying it.
 *
 * So: the toggle is off, the suggestion is visibly a suggestion, and [RideHistoryProfile] returns
 * nothing at all when the history is thin — in which case this shows a plain form and says why.
 */
@Composable
fun ActivityReminderSection(strings: AppStrings) {
    val context = LocalContext.current
    val store = remember { ActivityReminderStore(context) }
    val settings by store.settings.collectAsState()

    var suggestion by remember { mutableStateOf<RideHistoryProfile.SuggestedSlot?>(null) }
    var historyLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        suggestion = runCatching {
            val app = context.applicationContext as TrackMeApp
            RideHistoryProfile.suggestSlot(RideHistoryProfileSource.samples(app.database.rideDao()))
        }.getOrNull()
        historyLoaded = true
    }

    fun persist(updated: ActivityReminder.Settings) {
        store.save(updated)
        if (updated.enabled) ActivityReminderWorker.schedule(context)
        else ActivityReminderWorker.cancel(context)
    }

    SettingsGroup(title = strings.reminderSectionTitle) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                strings.reminderSectionSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsDivider()
        SettingsSwitchRow(
            title = strings.reminderEnable,
            supportingText = null,
            checked = settings.enabled,
            onCheckedChange = { persist(settings.copy(enabled = it)) },
        )

        // The suggestion sits above the pickers and outside the enabled gate: it is the reason
        // someone would turn this on, so hiding it until they already have is backwards.
        if (historyLoaded) {
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val slot = suggestion
                if (slot == null) {
                    Text(
                        strings.reminderNoHistory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        String.format(
                            Locale.getDefault(),
                            strings.reminderSuggestion,
                            weekdayName(slot.dayOfWeek),
                            formatTime(context, slot.hour, 0),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        // Applies the slot; it does **not** enable the reminder. Filling a form is
                        // not consent to the thing the form describes.
                        onClick = {
                            persist(
                                ActivityReminder.prefill(slot).copy(enabled = settings.enabled)
                            )
                        },
                        label = { Text(strings.reminderSuggestionApply) },
                    )
                }
            }
        }

        SettingsDivider()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(strings.reminderDay, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..7).forEach { iso ->
                    FilterChip(
                        selected = settings.dayOfWeek == iso,
                        onClick = { persist(settings.copy(dayOfWeek = iso)) },
                        label = {
                            Text(weekdayShortName(iso), style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }
        }

        SettingsDivider()
        SettingsRow(
            title = strings.reminderTime,
            supportingText = formatTime(context, settings.hour, settings.minute),
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> persist(settings.copy(hour = hour, minute = minute)) },
                    settings.hour,
                    settings.minute,
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            },
        )

        SettingsDivider()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(strings.reminderActivity, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RidePersona.entries.forEach { persona ->
                    FilterChip(
                        selected = settings.persona == persona.name,
                        onClick = { persist(settings.copy(persona = persona.name)) },
                        label = {
                            // `personaLabel`, not `displayName` — the latter is an untranslated
                            // English enum label that reads as "BikeDrive" to a user.
                            Text(strings.personaLabel(persona), style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }
        }
    }
}

/** Localised full weekday name for an ISO weekday. */
private fun weekdayName(isoDayOfWeek: Int): String =
    DateFormatSymbols.getInstance().weekdays[RideHistoryProfileSource.calendarDayOfWeek(isoDayOfWeek)]

/** Localised short weekday name for an ISO weekday. */
private fun weekdayShortName(isoDayOfWeek: Int): String =
    DateFormatSymbols.getInstance().shortWeekdays[RideHistoryProfileSource.calendarDayOfWeek(isoDayOfWeek)]

/** Honours the device's 12/24-hour setting rather than hard-coding either. */
private fun formatTime(context: android.content.Context, hour: Int, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}
