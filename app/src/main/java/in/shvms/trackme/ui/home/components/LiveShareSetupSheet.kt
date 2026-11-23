package `in`.shvms.trackme.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.data.remote.TrustedWatcher
import `in`.shvms.trackme.theme.TrackMeRed
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale

internal val LIVE_SHARE_DURATION_OPTIONS = listOf(30, 60, 120, 1440)

internal fun formatLiveShareDurationShort(minutes: Int, strings: AppStrings): String {
    return if (minutes >= 60 && minutes % 60 == 0) {
        String.format(Locale.getDefault(), strings.liveShareDurationHoursShort, minutes / 60)
    } else {
        String.format(Locale.getDefault(), strings.liveShareIntervalMinutesShort, minutes)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveShareSetupSheet(
    liveShareState: LiveShareState,
    isAuthenticated: Boolean,
    groupActive: Boolean,
    trustedWatchers: List<TrustedWatcher>,
    stopsWithRide: Boolean = true,
    onDismiss: () -> Unit,
    onStartDirectShare: (durationMinutes: Int) -> Unit,
    onStopShare: () -> Unit,
    onShareLink: () -> Unit,
    onCopyLink: () -> Unit,
    onInviteWatcher: () -> Unit,
    onToggleWatcherAutoNotify: (uid: String, enabled: Boolean) -> Unit,
    onRemoveWatcher: (uid: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LiveShareSetupSheetContent(
            liveShareState = liveShareState,
            isAuthenticated = isAuthenticated,
            groupActive = groupActive,
            trustedWatchers = trustedWatchers,
            stopsWithRide = stopsWithRide,
            onDismiss = onDismiss,
            onStartDirectShare = onStartDirectShare,
            onStopShare = onStopShare,
            onShareLink = onShareLink,
            onCopyLink = onCopyLink,
            onInviteWatcher = onInviteWatcher,
            onToggleWatcherAutoNotify = onToggleWatcherAutoNotify,
            onRemoveWatcher = onRemoveWatcher,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveShareSetupSheetContent(
    liveShareState: LiveShareState,
    isAuthenticated: Boolean,
    groupActive: Boolean,
    trustedWatchers: List<TrustedWatcher>,
    stopsWithRide: Boolean = true,
    onDismiss: () -> Unit,
    onStartDirectShare: (durationMinutes: Int) -> Unit,
    onStopShare: () -> Unit,
    onShareLink: () -> Unit,
    onCopyLink: () -> Unit,
    onInviteWatcher: () -> Unit,
    onToggleWatcherAutoNotify: (uid: String, enabled: Boolean) -> Unit,
    onRemoveWatcher: (uid: String) -> Unit,
) {
    val strings = LocalAppStrings.current
    var selectedDurationMinutes by rememberSaveable { mutableIntStateOf(30) }
    var isEditingDuration by rememberSaveable { mutableStateOf(false) }
    var watcherToRemove by remember { mutableStateOf<TrustedWatcher?>(null) }
    val isActive = liveShareState.status == LiveShareStatus.ACTIVE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Sensors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = strings.homeLiveSharing,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (groupActive) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    text = strings.groupLiveShareBlocked,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        } else {
            Text(
                text = strings.liveSharePrivacySummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isAuthenticated) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = strings.liveShareAuthRequired,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }

        if (!isActive) {
            val selectedLabel = formatLiveShareDurationShort(selectedDurationMinutes, strings)
            val summaryText = String.format(
                Locale.getDefault(),
                strings.liveShareTimeLimitSummary,
                selectedLabel,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("live_share_time_limit_summary"),
                            )
                            TextButton(
                                onClick = { isEditingDuration = !isEditingDuration },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("live_share_time_limit_change"),
                            ) {
                                Text(if (isEditingDuration) strings.done else strings.changeAction)
                            }
                        }

                        AnimatedVisibility(visible = isEditingDuration) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                LIVE_SHARE_DURATION_OPTIONS.forEach { minutes ->
                                    val label = formatLiveShareDurationShort(minutes, strings)
                                    FilterChip(
                                        selected = selectedDurationMinutes == minutes,
                                        onClick = { selectedDurationMinutes = minutes },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("live_share_duration_$minutes"),
                                    )
                                }
                            }
                        }
                    }
                }

                val expiryCopy = if (stopsWithRide) {
                    "${strings.liveShareExpiryExplanation} ${strings.liveShareRideEndExplanation}"
                } else {
                    strings.liveShareExpiryExplanation
                }
                Text(
                    text = expiryCopy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("live_share_expiry_explanation"),
                )
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strings.trustedWatchersTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
                Text(
                    text = strings.trustedWatchersSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (trustedWatchers.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            text = strings.trustedWatchersEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        trustedWatchers.forEach { watcher ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = watcher.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = strings.trustedWatchersAutoNotify,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = watcher.autoNotify,
                                        onCheckedChange = { enabled ->
                                            onToggleWatcherAutoNotify(watcher.uid, enabled)
                                        },
                                    )
                                    IconButton(onClick = { watcherToRemove = watcher }) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = strings.trustedWatchersRemove,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.trustedWatchersMaxNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onInviteWatcher,
                        enabled = isAuthenticated && trustedWatchers.size < LiveShareManager.MAX_AUTO_WATCHERS,
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.trustedWatchersAddCta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            HorizontalDivider()

            if (isActive) {
                if (liveShareState.activeViewers > 0) {
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            strings.liveShareViewersWatching,
                            liveShareState.activeViewers,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCopyLink,
                        enabled = liveShareState.shareLink != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.copyLink)
                    }
                    Button(
                        onClick = onShareLink,
                        enabled = liveShareState.shareLink != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.shareLink)
                    }
                }
                Button(
                    onClick = {
                        onStopShare()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TrackMeRed,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.homeStopSharing)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(strings.close)
                    }
                    Button(
                        onClick = {
                            onStartDirectShare(selectedDurationMinutes)
                            onDismiss()
                        },
                        enabled = isAuthenticated && !groupActive,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_share_start_cta"),
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.homeShareLiveLocation, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }

    watcherToRemove?.let { watcher ->
        AlertDialog(
            onDismissRequest = { watcherToRemove = null },
            title = {
                Text(strings.trustedWatchersRemoveConfirmTitle)
            },
            text = {
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        strings.trustedWatchersRemoveConfirmMessage,
                        watcher.displayName.ifBlank { strings.unknown },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveWatcher(watcher.uid)
                        watcherToRemove = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = TrackMeRed,
                    ),
                ) {
                    Text(strings.trustedWatchersRemove)
                }
            },
            dismissButton = {
                TextButton(onClick = { watcherToRemove = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}
