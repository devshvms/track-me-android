package `in`.shvms.trackme.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.data.remote.TrustedWatcher
import `in`.shvms.trackme.theme.TrackMeRed
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale

private val LIVE_SHARE_DURATION_OPTIONS = listOf(30, 60, 120, 1440)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveShareSetupSheet(
    liveShareState: LiveShareState,
    isAuthenticated: Boolean,
    groupActive: Boolean,
    trustedWatchers: List<TrustedWatcher>,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDurationMinutes by rememberSaveable { mutableIntStateOf(30) }
    val isActive = liveShareState.status == LiveShareStatus.ACTIVE

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
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
                    text = strings.homeSharingExplanation,
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.liveShareDurationLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LIVE_SHARE_DURATION_OPTIONS.forEach { minutes ->
                            val label = when (minutes) {
                                30 -> "30 min"
                                60 -> "1 hr"
                                120 -> "2 hr"
                                else -> "24 hr"
                            }
                            FilterChip(
                                selected = selectedDurationMinutes == minutes,
                                onClick = { selectedDurationMinutes = minutes },
                                label = { Text(label) },
                            )
                        }
                    }
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
                                    IconButton(onClick = { onRemoveWatcher(watcher.uid) }) {
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
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.homeShareLiveLocation, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}
