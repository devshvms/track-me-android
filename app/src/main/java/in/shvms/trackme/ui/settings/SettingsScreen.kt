package `in`.shvms.trackme.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Sync
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.remote.SyncResult
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.clip
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.ui.components.OfflineShieldBanner
import `in`.shvms.trackme.ui.components.rememberIsOffline
import android.widget.Toast

@Composable
fun SettingsScreen(
    navController: NavController? = null,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory((LocalContext.current.applicationContext as TrackMeApp))
    )
) {
    val strings = LocalAppStrings.current
    val user by viewModel.currentUser.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isOffline = rememberIsOffline()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isOffline) {
            OfflineShieldBanner(modifier = Modifier.padding(bottom = 16.dp))
        }

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
                    Text(strings.guest, style = MaterialTheme.typography.titleLarge)
                    Text(strings.rideHistoryLocalOnly, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            val result = viewModel.signInWithGoogle(context)
                            if (result.isFailure) {
                                val e = result.exceptionOrNull()
                                if ((context.applicationContext as TrackMeApp).authManager.isSignInCancellation(e)) {
                                    return@launch
                                }
                                val msg = if (e?.javaClass?.simpleName == "NoCredentialException" || e?.message?.contains("NoCredential") == true) {
                                    "Sign In Error: App Signing Key fingerprint (SHA-1) is missing in Firebase."
                                } else {
                                    e?.message ?: "Sign in failed"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.signInWithGoogle)
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
                            Text(user?.displayName?.takeIf { it.isNotBlank() } ?: strings.explorer, style = MaterialTheme.typography.titleMedium)
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
                            Text(strings.totalRides, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val joinTime = user?.metadata?.creationTimestamp ?: 0L
                            val timeStr = if (joinTime == 0L) strings.unknown else {
                                java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(joinTime))
                            }
                            Text(timeStr, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(strings.joined, style = MaterialTheme.typography.bodySmall)
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
                            Text("Cloud Backup & Sync", style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(
                                text = "Auto-syncs daily when connected & battery adequate",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val syncTimeStr = if (syncTime == 0L) strings.neverSynced else {
                                strings.lastSynced + java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(syncTime))
                            }
                            Text(syncTimeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AnimatedVisibility(visible = syncResult !is SyncResult.Idle) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    when (val result = syncResult) {
                                        is SyncResult.Syncing -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(strings.syncing, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        is SyncResult.Success -> {
                                            Text(
                                                text = strings.syncedSuccess,
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        is SyncResult.Error -> {
                                            Text(
                                                text = strings.syncError,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick = { viewModel.syncData() },
                            enabled = syncResult !is SyncResult.Syncing,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                        ) {
                            if (syncResult is SyncResult.Syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = strings.syncButton,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { navController?.navigate("account_management") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(strings.accountManagement)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        val prefs = context.getSharedPreferences("trackme_prefs", android.content.Context.MODE_PRIVATE)
        val preferencesManager = remember(context) {
            (context.applicationContext as TrackMeApp).preferencesManager
        }

        // App Preferences Card (Theme, Language & Units)
        var themeMode by remember { mutableStateOf(prefs.getInt("theme_mode", 0)) } // 0: System, 1: Light, 2: Dark
        val dynamicColor by preferencesManager.dynamicColor.collectAsState()
        val unitSystem by preferencesManager.unitSystem.collectAsState()
        var appLanguage by remember { mutableStateOf(prefs.getString("app_language", "en") ?: "en") }
        var showLangDropdown by remember { mutableStateOf(false) }

        val languages = listOf(
            "en" to "English",
            "es" to "Español",
            "fr" to "Français",
            "de" to "Deutsch",
            "hi" to "हिन्दी",
            "ja" to "日本語",
            "zh" to "中文"
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(strings.appPreferences, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))

                // 1. Theme Selector
                Text(strings.theme, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val themeOptions = listOf(0 to strings.themeSystem, 1 to strings.themeLight, 2 to strings.themeDark)
                    themeOptions.forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = {
                                themeMode = mode
                                prefs.edit().putInt("theme_mode", mode).apply()
                                (context.applicationContext as? TrackMeApp)?.preferencesManager?.setThemeMode(mode)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = dynamicColor,
                                role = Role.Switch,
                                onValueChange = preferencesManager::setDynamicColor
                            )
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(strings.dynamicColor, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = strings.dynamicColorDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = dynamicColor, onCheckedChange = null)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 2. Language Selector Dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.language, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = languages.find { it.first == appLanguage }?.second ?: "English",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        OutlinedButton(onClick = { showLangDropdown = true }) {
                            Text(languages.find { it.first == appLanguage }?.second ?: "English")
                        }
                        DropdownMenu(
                            expanded = showLangDropdown,
                            onDismissRequest = { showLangDropdown = false }
                        ) {
                            languages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        appLanguage = code
                                        showLangDropdown = false
                                        prefs.edit().putString("app_language", code).apply()
                                        (context.applicationContext as? TrackMeApp)?.preferencesManager?.setAppLanguage(code)
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 3. Units
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = unitSystem == "imperial",
                            role = Role.Switch,
                            onValueChange = { isImperial ->
                                preferencesManager.setUnitSystem(if (isImperial) "imperial" else "metric")
                            }
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(strings.units, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (unitSystem == "imperial") strings.miles else strings.kilometers,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = unitSystem == "imperial", onCheckedChange = null)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Advanced Settings Card
        var disablePostProcessing by remember { 
            mutableStateOf(prefs.getBoolean("disable_gps_post_processing", false)) 
        }
        var intelligentAutoPause by remember {
            mutableStateOf(prefs.getBoolean("intelligent_auto_pause", true))
        }
        var showGpsInfo by remember { mutableStateOf(false) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(strings.advancedSettings, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = intelligentAutoPause,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                intelligentAutoPause = checked
                                prefs.edit().putBoolean("intelligent_auto_pause", checked).apply()
                            }
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Intelligent Auto-Pause", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Dynamically pauses moving timer at traffic signals or stops based on vehicle/activity speed profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = intelligentAutoPause,
                        onCheckedChange = null
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = disablePostProcessing,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                disablePostProcessing = checked
                                prefs.edit().putBoolean("disable_gps_post_processing", checked).apply()
                            }
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.disableGpsPostProcessing, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { showGpsInfo = true }, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                                Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            strings.disableGpsDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = disablePostProcessing,
                        onCheckedChange = null
                    )
                }
            }
        }

        if (showGpsInfo) {
            AlertDialog(
                onDismissRequest = { showGpsInfo = false },
                title = { Text(strings.gpsPostProcessingTitle) },
                text = { 
                    Text(strings.gpsPostProcessingInfo) 
                },
                confirmButton = {
                    TextButton(onClick = { showGpsInfo = false }) {
                        Text(strings.gotIt)
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Live Location Sharing Card
        var liveShareFrequency by remember { 
            mutableStateOf(prefs.getInt("live_share_frequency_sec", 5)) 
        }
        var showLiveShareInfo by remember { mutableStateOf(false) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.liveLocationSharing, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showLiveShareInfo = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "Live Share Info", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                val freqLabel = when (liveShareFrequency) {
                    60 -> strings.minute1
                    300 -> strings.minutes5
                    else -> "$liveShareFrequency ${strings.seconds}"
                }
                Text("${strings.pushFrequency}: $freqLabel", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                val frequencyOptions = listOf(
                    5 to "5s",
                    10 to "10s",
                    30 to "30s",
                    60 to "1m",
                    300 to "5m"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    frequencyOptions.forEach { (sec, label) ->
                        val isSelected = liveShareFrequency == sec
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                liveShareFrequency = sec
                                prefs.edit().putInt("live_share_frequency_sec", sec).apply()
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (showLiveShareInfo) {
            AlertDialog(
                onDismissRequest = { showLiveShareInfo = false },
                title = { Text(strings.liveShareInfoTitle) },
                text = { 
                    Text(strings.liveShareInfoText) 
                },
                confirmButton = {
                    TextButton(onClick = { showLiveShareInfo = false }) {
                        Text(strings.understood)
                    }
                }
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(strings.helpFeedbackTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(strings.helpFeedbackDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { navController?.navigate("help_feedback") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(strings.helpFeedbackOpen) }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "Version ${it.versionName} ($vCode)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val app = context.applicationContext as? TrackMeApp
                        app?.let { trackMeApp ->
                            Toast.makeText(context, "Checking Google Play Store...", Toast.LENGTH_SHORT).show()
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val hasUpdate = trackMeApp.appUpdateChecker.checkForUpdate(forceCheck = true)
                                if (!hasUpdate) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "TrackMe is up to date on Google Play!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Check for Updates", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
