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
            Spacer(modifier = Modifier.height(16.dp))
            Text("You are not logged in.", style = MaterialTheme.typography.bodyLarge)
            Text("Ride history is saved locally only.", style = MaterialTheme.typography.bodyMedium)
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
                    val joinTime = user?.metadata?.creationTimestamp ?: 0L
                    val timeStr = if (joinTime == 0L) "Unknown" else {
                        java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(joinTime))
                    }
                    Text(timeStr, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Joined", style = MaterialTheme.typography.bodySmall)
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
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Cloud Sync", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Sync your local rides to the cloud.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val syncTimeStr = if (syncTime == 0L) "Never synced" else {
                                "Last synced: " + java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(syncTime))
                            }
                            Text(syncTimeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

            var showSignOutWarning by remember { mutableStateOf(false) }
            var showDeleteCloudWarning by remember { mutableStateOf(false) }
            var showDeleteAccountWarning by remember { mutableStateOf(false) }

            var isDangerZoneExpanded by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showSignOutWarning = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sign Out")
                        }
                        IconButton(onClick = { isDangerZoneExpanded = !isDangerZoneExpanded }) {
                            Icon(
                                if (isDangerZoneExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "More Options",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    
                    AnimatedVisibility(visible = isDangerZoneExpanded) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            OutlinedButton(
                                onClick = { showDeleteCloudWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete Cloud Data")
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Button(
                                onClick = { showDeleteAccountWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete Account & Data")
                            }
                        }
                    }
                }
            }

            if (showSignOutWarning) {
                AlertDialog(
                    onDismissRequest = { showSignOutWarning = false },
                    title = { Text("Sign Out Warning") },
                    text = { Text("Signing out will clear all your synced rides from this phone's local history. They will remain safely in the cloud, and any new rides will be saved locally. Are you sure you want to sign out?") },
                    confirmButton = {
                        TextButton(onClick = { 
                            showSignOutWarning = false
                            viewModel.signOut() 
                        }) {
                            Text("Sign Out", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSignOutWarning = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (showDeleteCloudWarning) {
                var isDeletingCloud by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { if (!isDeletingCloud) showDeleteCloudWarning = false },
                    title = { Text("Delete Cloud Data") },
                    text = { Text("This will permanently delete all your tracked rides from our servers. Your local rides on this phone will be preserved but marked as unsynced. Do you want to proceed?") },
                    confirmButton = {
                        TextButton(
                            onClick = { 
                                isDeletingCloud = true
                                scope.launch {
                                    val result = viewModel.deleteCloudData()
                                    isDeletingCloud = false
                                    showDeleteCloudWarning = false
                                    if (result.isSuccess) {
                                        snackbarHostState.showSnackbar("Data deleted from cloud")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to delete data")
                                    }
                                }
                            },
                            enabled = !isDeletingCloud
                        ) {
                            if (isDeletingCloud) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteCloudWarning = false }, enabled = !isDeletingCloud) {
                            Text("Cancel")
                        }
                    }
                )
            }
            
            if (showDeleteAccountWarning) {
                var isDeletingAccount by remember { mutableStateOf(false) }
                var feedbackText by remember { mutableStateOf("") }
                var confirmText by remember { mutableStateOf("") }
                
                AlertDialog(
                    onDismissRequest = { if (!isDeletingAccount) showDeleteAccountWarning = false },
                    title = { Text("Delete Account") },
                    text = { 
                        Column {
                            Text("This will permanently delete your account, wipe all your cloud data, and clear all local ride history on this device. This action CANNOT be undone.")
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = feedbackText,
                                onValueChange = { feedbackText = it },
                                label = { Text("Why are you leaving? (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isDeletingAccount
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("To confirm, type DELETE below:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedTextField(
                                value = confirmText,
                                onValueChange = { confirmText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isDeletingAccount
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { 
                                isDeletingAccount = true
                                scope.launch {
                                    val result = viewModel.deleteAccountAndData(feedbackText)
                                    isDeletingAccount = false
                                    showDeleteAccountWarning = false
                                    if (result.isSuccess) {
                                        snackbarHostState.showSnackbar("Account deleted")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to delete account. Please sign in again.")
                                    }
                                }
                            },
                            enabled = !isDeletingAccount && confirmText == "DELETE"
                        ) {
                            if (isDeletingAccount) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Delete Permanently", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteAccountWarning = false }, enabled = !isDeletingAccount) {
                            Text("Cancel")
                        }
                    }
                )
            }
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
