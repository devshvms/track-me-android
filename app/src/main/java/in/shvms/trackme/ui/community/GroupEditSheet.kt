package `in`.shvms.trackme.ui.community

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.remote.GroupSessionState
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import `in`.shvms.trackme.domain.group.GroupStartReminder
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Calendar

/**
 * The leader's edit sheet — destination and scheduled start.
 *
 * Both are optional (D6) and both are clearable. The minimum path stays create → share → join →
 * go; this is enrichment for a leader who wants it, reachable from one icon, and never a gate.
 *
 * **D9 is stated on the sheet itself**, not just honoured in code: *"Nothing starts on its own —
 * you still press start."* A leader setting a time is exactly the person who might assume it will
 * begin without them, and the moment to correct that is while they are setting it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditSheet(
    session: GroupSessionState,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (destLat: Double?, destLng: Double?, startAtMillis: Long?) -> Unit,
    currentLocation: () -> Pair<Double, Double>?,
) {
    val context = LocalContext.current
    var destLat by remember { mutableStateOf(session.destinationLat) }
    var destLng by remember { mutableStateOf(session.destinationLng) }
    var startAt by remember { mutableStateOf(session.startAtMillis) }

    // Once the ride is under way the start time is a fact, not a plan — it is when the group
    // actually set off, and rewriting it would be editing history rather than the itinerary.
    // The destination stays editable, because §8 explicitly allows changing it mid-ride: "visible
    // to all, never silent. All ETAs recompute."
    val rideUnderWay = session.status == GroupSessionStatus.LIVE ||
        session.status == GroupSessionStatus.DEGRADED

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                strings.groupEditTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(16.dp)

            // --- Destination ---
            Text(
                strings.groupDestination,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (destLat != null && destLng != null) {
                    `in`.shvms.trackme.domain.group.GroupDestinationLinks
                        .formatCoordinates(destLat!!, destLng!!)
                } else {
                    strings.groupNotSet
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    currentLocation()?.let { (lat, lng) ->
                        destLat = lat
                        destLng = lng
                    }
                }) { Text(strings.groupUseMyLocation) }
                if (destLat != null) {
                    TextButton(onClick = { destLat = null; destLng = null }) {
                        Text(strings.groupClearDestination)
                    }
                }
            }

            Spacer(16.dp)

            // A40: once the ride is under way the editor exposes **destination only**.
            //
            // The start time has stopped being a plan and become a fact — when the group actually
            // set off — so there is nothing here to edit. It is hidden rather than shown disabled:
            // a permanently inert control with an apology beside it is worse than an editor that
            // simply offers what can still be changed.
            if (!rideUnderWay) {
                // --- Scheduled start ---
                Text(
                    strings.groupStartTimeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    startAt?.let { formatDateTime(it) } ?: strings.groupNotSet,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pickDateTime(context, startAt) { picked -> startAt = picked }
                    }) { Text(strings.groupStartTimeLabel) }
                    if (startAt != null) {
                        TextButton(onClick = { startAt = null }) { Text(strings.groupClearStartTime) }
                    }
                }
            }

            if (startAt != null && !rideUnderWay) {
                Spacer(8.dp)
                // D9, said out loud to the one person most likely to assume otherwise.
                Text(
                    strings.groupStartNeverAutoStarts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(24.dp)
            Button(
                onClick = { onSave(destLat, destLng, startAt) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.groupSave) }
            Spacer(24.dp)
        }
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(height))
}

/**
 * Date then time, using the platform pickers.
 *
 * Platform dialogs rather than a Compose date picker so the leader gets the calendar they already
 * know, in their own locale and 12/24-hour preference, with no new surface to get wrong.
 */
private fun pickDateTime(
    context: android.content.Context,
    current: Long?,
    onPicked: (Long) -> Unit,
) {
    val calendar = Calendar.getInstance().apply { if (current != null) timeInMillis = current }

    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(picked.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).apply {
        // A start time in the past is not an error (§8 — the group simply waits), but it is never
        // what someone means to pick, and offering it invites a mistake nobody would notice.
        datePicker.minDate = System.currentTimeMillis() - 60_000L
    }.show()
}

/** Exposed so the reminder's lead time is stated in one place. */
val startReminderLeadMinutes: Int get() = GroupStartReminder.LEAD_MINUTES
