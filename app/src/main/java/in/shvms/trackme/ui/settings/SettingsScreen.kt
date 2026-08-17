package `in`.shvms.trackme.ui.settings

import `in`.shvms.trackme.ui.components.rememberMessenger
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sync
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.analytics.AnalyticsManager
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
import `in`.shvms.trackme.ui.localization.SUPPORTED_LANGUAGE_CODES
import `in`.shvms.trackme.ui.components.OfflineShieldBanner
import `in`.shvms.trackme.ui.components.rememberIsOffline
import `in`.shvms.trackme.ui.components.SettingsGroup
import `in`.shvms.trackme.ui.components.SettingsDivider
import `in`.shvms.trackme.ui.components.SettingsRow
import `in`.shvms.trackme.ui.components.SettingsSwitchRow

private val languageDisplayNames = mapOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "hi" to "हिन्दी",
    "ja" to "日本語",
    "zh" to "中文",
)

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
    val messenger = rememberMessenger()
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
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
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                                // Reaching NoCredentialException now means both the authorized-account
                                // path and the account-picker fallback came back empty — no usable
                                // Google account on the device, or this build's SHA-1 is not
                                // registered in Firebase.
                                val msg = if (e?.javaClass?.simpleName == "NoCredentialException" || e?.message?.contains("NoCredential") == true) {
                                    "Sign in failed: no Google account available on this device."
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
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
                                contentDescription = null,
                                modifier = Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        } else {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
        val telemetryEnabled by preferencesManager.telemetryEnabled.collectAsState()
        val unitSystem by preferencesManager.unitSystem.collectAsState()
        var appLanguage by remember { mutableStateOf(prefs.getString("app_language", "en") ?: "en") }
        var showLangDropdown by remember { mutableStateOf(false) }

        val languages = SUPPORTED_LANGUAGE_CODES.map { it to (languageDisplayNames[it] ?: it) }

        SettingsGroup(title = strings.appPreferences) {
            // Theme stays a segmented choice rather than a list row: three mutually exclusive
            // options read faster as chips than as three rows or a hidden dropdown.
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(strings.theme, style = MaterialTheme.typography.bodyLarge)
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
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsDivider()
                SettingsSwitchRow(
                    title = strings.dynamicColor,
                    supportingText = strings.dynamicColorDescription,
                    checked = dynamicColor,
                    onCheckedChange = preferencesManager::setDynamicColor,
                )
            }

            SettingsDivider()
            SettingsRow(
                title = strings.language,
                supportingText = languages.find { it.first == appLanguage }?.second ?: "English",
                trailingContent = {
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
                },
            )

            SettingsDivider()
            SettingsSwitchRow(
                title = strings.units,
                supportingText = if (unitSystem == "imperial") strings.miles else strings.kilometers,
                checked = unitSystem == "imperial",
                onCheckedChange = { isImperial ->
                    preferencesManager.setUnitSystem(if (isImperial) "imperial" else "metric")
                },
            )
        }

        SettingsGroup(title = strings.privacyAndAnalytics) {
            SettingsSwitchRow(
                title = strings.shareAnalyticsData,
                supportingText = strings.shareAnalyticsDataDescription,
                checked = telemetryEnabled,
                onCheckedChange = { enabled ->
                    preferencesManager.setTelemetryEnabled(enabled)
                    AnalyticsManager.updateLocalConsent(enabled)
                },
            )
        }

        // Advanced Settings
        var disablePostProcessing by remember { 
            mutableStateOf(prefs.getBoolean("disable_gps_post_processing", false)) 
        }
        var intelligentAutoPause by remember {
            mutableStateOf(prefs.getBoolean("intelligent_auto_pause", true))
        }
        var showGpsInfo by remember { mutableStateOf(false) }

        SettingsGroup(title = strings.advancedSettings) {
            SettingsSwitchRow(
                title = "Intelligent Auto-Pause",
                supportingText = "Dynamically pauses moving timer at traffic signals or stops based on vehicle/activity speed profile",
                checked = intelligentAutoPause,
                onCheckedChange = { checked ->
                    intelligentAutoPause = checked
                    prefs.edit().putBoolean("intelligent_auto_pause", checked).apply()
                },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = strings.disableGpsPostProcessing,
                supportingText = strings.disableGpsDesc,
                checked = disablePostProcessing,
                onCheckedChange = { checked ->
                    disablePostProcessing = checked
                    prefs.edit().putBoolean("disable_gps_post_processing", checked).apply()
                },
                onInfoClick = { showGpsInfo = true },
                infoDescription = strings.info,
            )
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

        SettingsGroup(
            title = strings.liveLocationSharing,
            titleAction = {
                IconButton(onClick = { showLiveShareInfo = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Info, contentDescription = strings.liveShareInfoTitle, modifier = Modifier.size(18.dp))
                }
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val freqLabel = when (liveShareFrequency) {
                    60 -> strings.minute1
                    300 -> strings.minutes5
                    else -> "$liveShareFrequency ${strings.seconds}"
                }
                Text("${strings.pushFrequency}: $freqLabel", style = MaterialTheme.typography.bodyLarge)
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
        
        Spacer(modifier = Modifier.height(16.dp))

        // A navigating row rather than a card wrapping a full-width button. Same destination,
        // same one tap — but it reads as part of the list instead of interrupting it.
        SettingsGroup(title = strings.helpFeedbackTitle) {
            SettingsRow(
                title = strings.helpFeedbackOpen,
                supportingText = strings.helpFeedbackDescription,
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { navController?.navigate("help_feedback") },
            )
        }
        
        // 1.8.0 design system: debug-only entry to the token gallery. Stripped from release
        // builds by the BuildConfig.DEBUG guard. See docs/DESIGN_SYSTEM_1.8.md §9.
        if (`in`.shvms.trackme.BuildConfig.DEBUG) {
            OutlinedButton(
                onClick = { navController?.navigate("design_catalog") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Design catalog (debug)")
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
                            messenger.show("Checking Google Play Store...")
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val hasUpdate = trackMeApp.appUpdateChecker.checkForUpdate(forceCheck = true)
                                if (!hasUpdate) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        messenger.show("TrackMe is up to date on Google Play!")
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
