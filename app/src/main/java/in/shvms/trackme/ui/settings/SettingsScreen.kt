package `in`.shvms.trackme.ui.settings

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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.remote.SyncResult
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.clip

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
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header removed

        if (user == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(80.dp)
                    ) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", modifier = Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Guest", style = MaterialTheme.typography.titleLarge)
                    Text("Ride history is saved locally only.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            val result = viewModel.signInWithGoogle(context)
                            if (result.isFailure) {
                                val e = result.exceptionOrNull()
                                val msg = if (e?.javaClass?.simpleName == "NoCredentialException" || e?.message?.contains("NoCredential") == true) {
                                    "Sign In Error: App Signing Key fingerprint (SHA-1) is missing in Firebase."
                                } else {
                                    e?.message ?: "Sign in failed"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign in with Google")
                    }
                }
            }
        } else {
            val ridesCount by viewModel.totalRidesCount.collectAsState()
            val syncTime by viewModel.lastSyncTime.collectAsState()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (user?.photoUrl != null) {
                            coil.compose.AsyncImage(
                                model = user?.photoUrl,
                                contentDescription = "Profile Picture",
                                modifier = Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        } else {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.Person, contentDescription = "Profile", modifier = Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user?.displayName?.takeIf { it.isNotBlank() } ?: "Explorer", style = MaterialTheme.typography.titleMedium)
                            Text(user?.email ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    
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
                            val joinTime = user?.metadata?.creationTimestamp ?: 0L
                            val timeStr = if (joinTime == 0L) "Unknown" else {
                                java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(joinTime))
                            }
                            Text(timeStr, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Joined", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Cloud Sync", style = MaterialTheme.typography.bodyLarge)
                            val syncTimeStr = if (syncTime == 0L) "Never synced" else {
                                "Last synced: " + java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(syncTime))
                            }
                            Text(syncTimeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AnimatedVisibility(visible = syncResult !is SyncResult.Idle) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    when (val result = syncResult) {
                                        is SyncResult.Syncing -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Syncing...", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        is SyncResult.Success -> {
                                            Text(
                                                text = "✓ Synced",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        is SyncResult.Error -> {
                                            Text(
                                                text = "✗ Error",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { viewModel.syncData() },
                            enabled = syncResult !is SyncResult.Syncing,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(if (syncResult is SyncResult.Syncing) "..." else "Sync")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { navController?.navigate("account_management") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Account Management")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Advanced Settings Card
        val prefs = context.getSharedPreferences("trackme_prefs", android.content.Context.MODE_PRIVATE)
        var disablePostProcessing by remember { 
            mutableStateOf(prefs.getBoolean("disable_gps_post_processing", false)) 
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
                            Text("Disable GPS Post-Processing", style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { showGpsInfo = true }, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                                Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            "Turn on to save raw, uncompressed data. Skipping processing increases storage and keeps glitches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = disablePostProcessing,
                        onCheckedChange = { checked ->
                            disablePostProcessing = checked
                            prefs.edit().putBoolean("disable_gps_post_processing", checked).apply()
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
        
        Spacer(modifier = Modifier.weight(1f))
        
        val packageInfo = remember { 
            try { context.packageManager.getPackageInfo(context.packageName, 0) } catch(e: Exception) { null } 
        }
        packageInfo?.let {
            @Suppress("DEPRECATION")
            val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.longVersionCode.toString()
            } else {
                it.versionCode.toString()
            }
            Text(
                text = "Version ${it.versionName} ($vCode)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
    }
}
