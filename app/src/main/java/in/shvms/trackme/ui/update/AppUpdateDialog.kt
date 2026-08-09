package `in`.shvms.trackme.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.shvms.trackme.ui.localization.LocalAppStrings

@Composable
fun AppUpdateDialog(
    prompt: AppUpdatePrompt,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Dialog(
        onDismissRequest = { if (!prompt.isImmediate) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !prompt.isImmediate,
            dismissOnClickOutside = !prompt.isImmediate,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.NewReleases,
                        contentDescription = strings.newVersionAvailable,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = strings.updateAvailable,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(14.dp),
                ) {
                    ReleaseNotes(
                        notes = prompt.releaseNotes,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "Update Now",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                if (!prompt.isImmediate) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Later",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown once a flexible update has downloaded in the background.
 *
 * Play requires the host app to call `completeUpdate()` explicitly — without this the download
 * sits on disk and the user never gets the new version despite having accepted the update.
 */
@Composable
fun UpdateReadyDialog(
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update ready") },
        text = { Text("TrackMe has finished downloading. Restart to finish installing.") },
        confirmButton = { TextButton(onClick = onRestart) { Text("Restart") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
