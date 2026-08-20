package `in`.shvms.trackme.ui.community

import `in`.shvms.trackme.ui.components.rememberMessenger
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.group.GroupDestinationLinks
import `in`.shvms.trackme.data.remote.GroupSessionState
import `in`.shvms.trackme.ui.localization.AppStrings
import java.text.DateFormat
import java.util.Date

/**
 * The destination and scheduled-start rows on the Community page.
 *
 * Both read `--` when unset rather than disappearing. A row that vanishes leaves the reader unsure
 * whether the group has no destination or the app has no opinion; `--` says "nothing set" plainly,
 * and keeps the layout stable as the leader fills things in.
 */
@Composable
fun DestinationRow(
    session: GroupSessionState,
    strings: AppStrings,
    onShowOnMap: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messenger = rememberMessenger()
    val lat = session.destinationLat
    val lng = session.destinationLng

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            strings.groupDestination,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (lat != null && lng != null) {
                GroupDestinationLinks.formatCoordinates(lat, lng)
            } else {
                strings.groupNotSet
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (lat != null && lng != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { openInMaps(context, lat, lng, session.groupName, strings, messenger::show) }) {
                    Text(strings.groupOpenInMaps)
                }
                TextButton(onClick = onShowOnMap) { Text(strings.groupShowOnMap) }
            }
        }
    }
}

@Composable
fun StartTimeRow(session: GroupSessionState, strings: AppStrings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messenger = rememberMessenger()
    val startAt = session.startAtMillis

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            strings.groupStartTimeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (startAt != null) formatDateTime(startAt) else strings.groupNotSet,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (startAt != null) {
            TextButton(onClick = { addToCalendar(context, session, startAt, strings, messenger::show) }) {
                Text(strings.groupAddToCalendar)
            }
        }
    }
}

fun formatDateTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

/**
 * Last known location, for "use my current location".
 *
 * Deliberately `lastLocation` rather than a fresh fix: setting a meeting point is not a
 * navigation-grade operation, and a cached position is instant where a fresh request would spin
 * with no feedback. Returns null when there is no permission or no cached fix, and the caller
 * simply leaves the destination unset — never a silent (0, 0), which would put the whole group in
 * the Gulf of Guinea.
 */
@Suppress("MissingPermission")
fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return null

    return try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )
        providers.asSequence()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) {
        null
    }
}

/**
 * Opens the destination in whichever maps app the user prefers.
 *
 * `geo:` first so the choice is theirs — on virtually every device that is Google Maps, but a user
 * who deliberately installed something else should not have it overridden. The https link is the
 * fallback for a device with no `geo:` handler at all, which is rare but real on stripped ROMs.
 */
private fun openInMaps(context: Context, lat: Double, lng: Double, label: String?, strings: AppStrings, onMessage: (String) -> Unit) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse(GroupDestinationLinks.geoUri(lat, lng, label)))
    try {
        context.startActivity(geo)
        return
    } catch (_: ActivityNotFoundException) {
        // fall through
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GroupDestinationLinks.webMapUrl(lat, lng))))
    } catch (_: ActivityNotFoundException) {
        onMessage(strings.groupNoMapsApp)
    }
}

/**
 * Hands the ride to the user's calendar — start time to the group's own expiry, named after the
 * group, with the maps link as the location.
 *
 * `ACTION_INSERT` opens the calendar app's own compose screen rather than writing anything, so this
 * needs no calendar permission and the user sees exactly what is being created before it exists.
 * Writing directly would need READ/WRITE_CALENDAR, and §16 is emphatic about not adding permissions
 * this feature does not need — the last two releases were spent removing some.
 *
 * The end time is the group's expiry rather than a guessed duration: it is the one honest bound we
 * have, and it is already what the countdown shows.
 */
private fun addToCalendar(
    context: Context,
    session: GroupSessionState,
    startAt: Long,
    strings: AppStrings,
    onMessage: (String) -> Unit,
) {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startAt)
        .putExtra(
            CalendarContract.EXTRA_EVENT_END_TIME,
            // A group whose expiry somehow precedes its start would create a negative-length
            // event, which some calendar apps reject outright and others render bizarrely.
            session.expiresAtMillis.takeIf { it > startAt } ?: (startAt + 60 * 60 * 1000L),
        )
        .putExtra(CalendarContract.Events.TITLE, session.groupName.orEmpty())
        .putExtra(
            CalendarContract.Events.EVENT_LOCATION,
            GroupDestinationLinks.calendarDescription(session.destinationLat, session.destinationLng),
        )

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onMessage(strings.groupNoCalendarApp)
    }
}
