package com.example.trackme.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trackme.TrackMeApp
import com.example.trackme.data.remote.SyncResult
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory((LocalContext.current.applicationContext as TrackMeApp))
    )
) {
    val user by viewModel.currentUser.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        if (user == null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("You are not logged in.", style = MaterialTheme.typography.bodyLarge)
            Text("Ride history is saved locally only.", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                scope.launch {
                    val result = viewModel.signInWithGoogle(context)
                    // Error is shown inline via snackbar or toast if needed
                }
            }) {
                Text("Sign in with Google")
            }
        } else {
            val ridesCount by viewModel.syncedRidesCount.collectAsState()
            val syncTime by viewModel.lastSyncTime.collectAsState()

            Text(user?.displayName?.takeIf { it.isNotBlank() } ?: "Explorer", style = MaterialTheme.typography.headlineSmall)
            Text(user?.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ridesCount.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Total Rides", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val timeStr = if (syncTime == 0L) "Never" else {
                        java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(syncTime))
                    }
                    Text(timeStr, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Last Synced", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(32.dp))

            // Sync Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Cloud Sync", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sync your local rides to the cloud and download any rides saved from other devices.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Sync status indicator
                    AnimatedVisibility(visible = syncResult !is SyncResult.Idle) {
                        Column {
                            when (val result = syncResult) {
                                is SyncResult.Syncing -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Syncing...", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                is SyncResult.Success -> {
                                    Text(
                                        text = "✓ Done! Uploaded: ${result.uploaded}, Downloaded: ${result.downloaded}",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                is SyncResult.Error -> {
                                    Text(
                                        text = "✗ Error: ${result.message}",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                else -> {}
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    Button(
                        onClick = { viewModel.syncData() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = syncResult !is SyncResult.Syncing
                    ) {
                        Text(if (syncResult is SyncResult.Syncing) "Syncing..." else "Sync Now ↕")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { viewModel.signOut() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign Out")
            }
        }
    }
}
