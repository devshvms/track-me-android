package `in`.shvms.trackme.ui.notifications

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.bulletin.BulletinCopy
import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons

/**
 * SCOPE_1.8.7 §6.1.7 scenario 32 — the bulletin.
 *
 * The surface that makes §6.0's one-per-week cap a trade rather than a loss. Everything the budget
 * refuses lands here: levels, milestones, recaps, sync problems, version notes, and a copy of every
 * notification actually sent.
 *
 * ### Opening it marks it read, and there is no per-row state
 *
 * The feed is short and read at a glance. Per-row read state would turn the badge into a to-do
 * list, and a to-do list is an obligation — which is the opposite of what a surface designed to
 * absorb an interruption budget should feel like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulletinScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val app = context.applicationContext as TrackMeApp
    val entries by app.bulletinStore.entries.collectAsStateWithLifecycle()
    val unitSystem by app.preferencesManager.unitSystem.collectAsStateWithLifecycle()
    val imperial = unitSystem == "imperial"

    // Seen on open, not on scroll: a fact the user has had the chance to read is read.
    LaunchedEffect(entries.size) { app.bulletinStore.markAllSeen() }

    val copyStrings = remember(strings) { AppStringsBulletinCopy(strings) }

    // A Scaffold, like every other pushed route in the app. Without one this screen had no title
    // and no way back, and — the part that actually looked broken — no window insets, so the first
    // row rendered underneath the status bar and read as unformatted text floating at the top.
    BulletinScaffold(
        modifier = modifier,
        onBack = onBack,
        entries = entries,
        imperial = imperial,
        strings = strings,
        copyStrings = copyStrings,
        onClear = { app.bulletinStore.clear() },
    )
}

/**
 * The chrome and the feed, with no dependency on [TrackMeApp].
 *
 * Split out so the app bar can be rendered in a Robolectric test. [BulletinScreen] reads the store
 * from the application object, which a JVM test has no instance of — and the defect this screen was
 * reported for was *the absence of this chrome*, so the chrome is exactly the part worth pinning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulletinScaffold(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    entries: List<BulletinEntry>,
    imperial: Boolean,
    strings: AppStrings,
    copyStrings: BulletinCopy.Strings,
    onClear: () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(strings.bulletinTitle) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                        }
                    }
                },
            )
        },
    ) { padding ->
        BulletinContent(
            modifier = Modifier.padding(padding),
            entries = entries,
            imperial = imperial,
            strings = strings,
            copyStrings = copyStrings,
            onClear = onClear,
        )
    }
}

@Composable
private fun BulletinContent(
    modifier: Modifier,
    entries: List<BulletinEntry>,
    imperial: Boolean,
    strings: AppStrings,
    copyStrings: BulletinCopy.Strings,
    onClear: () -> Unit,
) {
    val context = LocalContext.current

    if (entries.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = strings.bulletinEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            val row = BulletinCopy.render(entry.withFormattedFacts(imperial), copyStrings)
            if (row != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = row.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                                .format(Date(entry.createdAtMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        row.link?.let { url ->
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                // https-only was enforced by OperatorBroadcast.parse at the
                                // boundary; nothing here can widen it.
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, url.toUri())
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) { Text(strings.broadcastLearnMore) }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onClear) { Text(strings.bulletinClear) }
            }
        }
    }
}

/**
 * Applies the locale- and unit-dependent formatting at read time.
 *
 * This is the mechanism behind "derived from local facts on read": the store holds numbers, and
 * these strings are produced fresh on every draw, so switching the app's language or unit system
 * re-renders the whole feed rather than leaving old rows frozen in the language they were written.
 */
internal fun BulletinEntry.withFormattedFacts(imperial: Boolean): BulletinEntry {
    val extra = mutableMapOf<String, String>()
    doubleFact(BulletinEntry.FACT_DISTANCE_METERS)?.let {
        extra[BulletinCopy.FORMATTED_DISTANCE] = UnitFormatter.rideDistance(it, imperial)
    }
    longFact(BulletinEntry.FACT_ENDED_AT_MILLIS)?.let {
        extra[BulletinCopy.FORMATTED_ENDED_AT] =
            DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(it))
    }
    longFact(BulletinEntry.FACT_SINCE_MILLIS)?.let {
        extra[BulletinCopy.FORMATTED_SINCE] =
            DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it))
    }
    return if (extra.isEmpty()) this else copy(facts = facts + extra)
}

/** Adapts the app's localisation table to the narrow interface `BulletinCopy` asks for. */
internal class AppStringsBulletinCopy(private val s: AppStrings) : BulletinCopy.Strings {
    override val rideSavedTitle get() = s.rideSavedTitle
    override val rideSavedBodyPlain get() = s.rideSavedBodyPlain
    override fun rideSavedBody(endedAt: String, distance: String) =
        String.format(Locale.getDefault(), s.rideSavedBody, endedAt, distance)
    override val weeklyRecapTitle get() = s.weeklyRecapNotificationTitle
    override fun weeklyRecapBody(rides: Int, distance: String) =
        String.format(Locale.getDefault(), s.weeklyRecapNotificationBody, rides, distance)
    override fun levelReachedTitle(level: String) =
        String.format(Locale.getDefault(), s.bulletinLevelReached, level)
    override val levelReachedBody get() = s.bulletinLevelReachedBody
    override fun milestoneTitle(count: Int) =
        String.format(Locale.getDefault(), s.bulletinMilestone, count)
    override val milestoneBody get() = s.bulletinMilestoneBody
    override val syncProblemTitle get() = s.bulletinSyncProblem
    override fun syncProblemBody(unsynced: Int, since: String) =
        String.format(Locale.getDefault(), s.bulletinSyncProblemBody, unsynced, since)
    override fun syncProblemBodyNoDate(unsynced: Int) =
        String.format(Locale.getDefault(), s.bulletinSyncProblemBodyNoDate, unsynced)
    override fun versionNoteTitle(version: String) =
        String.format(Locale.getDefault(), s.bulletinVersionNote, version)
    override val versionNoteBody get() = s.bulletinVersionNoteBody
    override val returnNoticeTitle get() = s.returnNoticeTitle
    override fun returnNoticeBody(days: Int) =
        String.format(Locale.getDefault(), s.returnNoticeBody, days)
    override val forgottenRideTitle get() = s.forgottenRideTitle
    override fun forgottenRideBody(elapsedMinutes: Int, stillSince: String) =
        String.format(Locale.getDefault(), s.forgottenRideBody, elapsedMinutes, stillSince)
    override fun forgottenRideBodyNoTime(elapsedMinutes: Int) =
        String.format(Locale.getDefault(), s.forgottenRideBodyNoTime, elapsedMinutes)
    override val groupStillLiveTitle get() = s.groupStillLiveTitle
    override fun groupStillLiveBody(groupName: String) =
        String.format(Locale.getDefault(), s.groupStillLiveBody, groupName)
    override val groupStillLiveBodyNoName get() = s.groupStillLiveBodyNoName
}
