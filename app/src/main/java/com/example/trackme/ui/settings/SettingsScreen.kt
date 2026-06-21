package com.example.trackme.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.example.trackme.TrackMeApp
import com.example.trackme.data.remote.SyncResult
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

@Composable
fun SettingsScreen(
    navController: NavController? = null,
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
            .verticalScroll(rememberScrollState())
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

            // Emergency Setup Card
            val app = LocalContext.current.applicationContext as TrackMeApp
            val emergencyDao = remember { app.database.emergencyDao() }
            val emergencySettings by emergencyDao.getSettingsFlow().collectAsState(initial = null)
            val isSetupComplete = emergencySettings?.isSetupComplete == true
            var showHowItWorks by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isSetupComplete) "Emergency Broadcast is Active" else "Emergency Broadcast", 
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSetupComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (isSetupComplete) {
                            IconButton(onClick = { showHowItWorks = true }) {
                                Icon(Icons.Default.Info, contentDescription = "How it Works")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isSetupComplete) "Your safety protocols are configured." else "Configure emergency contacts and broadcast messages for when you need help.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            navController?.navigate("emergency_setup")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSetupComplete) "Manage Safety Protocols" else "Setup Emergency Broadcast")
                    }
                }
            }
            
            if (showHowItWorks) {
                AlertDialog(
                    onDismissRequest = { showHowItWorks = false },
                    title = { Text("How it Works") },
                    text = { Text("During an emergency, your phone will broadcast your location to your contacts every 2 minutes for the first 10 minutes, then every 10 minutes for the next 1 hour, and then every 1 hour for the next 24 hours. A persistent notification will remain active until you stop the broadcast.") },
                    confirmButton = {
                        TextButton(onClick = { showHowItWorks = false }) {
                            Text("Got it")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced Settings Card
            val prefs = context.getSharedPreferences("trackme_prefs", android.content.Context.MODE_PRIVATE)
            var isPostProcessingEnabled by remember { 
                mutableStateOf(prefs.getBoolean("enable_gps_post_processing", false)) 
            }
            var showGpsInfo by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Advanced Settings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("GPS Post-Processing", style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { showGpsInfo = true }, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                                    Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                "Filters anomalies, smooths altitude, and compresses ride data after saving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isPostProcessingEnabled,
                            onCheckedChange = { checked ->
                                isPostProcessingEnabled = checked
                                prefs.edit().putBoolean("enable_gps_post_processing", checked).apply()
                            }
                        )
                    }
                }
            }

            if (showGpsInfo) {
                AlertDialog(
                    onDismissRequest = { showGpsInfo = false },
                    title = { Text("GPS Post-Processing") },
                    text = { 
                        Text("This feature uses advanced algorithms to clean up your raw GPS data immediately after a ride finishes.\n\n" +
                             "• Filters out GPS 'teleportation' glitches.\n" +
                             "• Smooths out noisy altitude and speed readings.\n" +
                             "• Detects when you were stopped and retroactively pauses the ride.\n" +
                             "• Compresses the total amount of data to save storage space and speed up cloud syncing, without losing the shape of your route on the map.") 
                    },
                    confirmButton = {
                        TextButton(onClick = { showGpsInfo = false }) {
                            Text("Got it")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
