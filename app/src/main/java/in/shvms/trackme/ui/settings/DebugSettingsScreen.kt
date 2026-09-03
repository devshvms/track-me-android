package `in`.shvms.trackme.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.settings.DebugSettings
import `in`.shvms.trackme.ui.components.SettingsDivider
import `in`.shvms.trackme.ui.components.SettingsGroup
import `in`.shvms.trackme.ui.components.SettingsSwitchRow
import `in`.shvms.trackme.ui.localization.LocalAppStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val preferences = remember(context) {
        context.getSharedPreferences("trackme_prefs", android.content.Context.MODE_PRIVATE)
    }
    var debugModeEnabled by remember { mutableStateOf(DebugSettings.isEnabled(preferences)) }
    var intelligentAutoPause by remember {
        mutableStateOf(preferences.getBoolean(DebugSettings.AUTO_PAUSE_KEY, true))
    }
    var disablePostProcessing by remember {
        mutableStateOf(preferences.getBoolean(DebugSettings.DISABLE_POST_PROCESSING_KEY, false))
    }
    var showGpsInfo by remember { mutableStateOf(false) }

    LaunchedEffect(debugModeEnabled) {
        if (!debugModeEnabled) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.debugSettingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup(title = strings.debugSettingsTitle) {
                SettingsSwitchRow(
                    title = strings.debugModeTitle,
                    supportingText = strings.debugModeDisableDescription,
                    checked = debugModeEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) return@SettingsSwitchRow
                        DebugSettings.disableAndReset(preferences)
                        intelligentAutoPause = true
                        disablePostProcessing = false
                        debugModeEnabled = false
                    },
                )
            }

            SettingsGroup(title = strings.debugTrackingControlsTitle) {
                SettingsSwitchRow(
                    title = strings.intelligentAutoPauseTitle,
                    supportingText = strings.intelligentAutoPauseDescription,
                    checked = intelligentAutoPause,
                    onCheckedChange = { checked ->
                        intelligentAutoPause = checked
                        preferences.edit()
                            .putBoolean(DebugSettings.AUTO_PAUSE_KEY, checked)
                            .apply()
                    },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = strings.disableGpsPostProcessing,
                    supportingText = strings.disableGpsDesc,
                    checked = disablePostProcessing,
                    onCheckedChange = { checked ->
                        disablePostProcessing = checked
                        preferences.edit()
                            .putBoolean(DebugSettings.DISABLE_POST_PROCESSING_KEY, checked)
                            .apply()
                    },
                    onInfoClick = { showGpsInfo = true },
                    infoDescription = strings.info,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showGpsInfo) {
        AlertDialog(
            onDismissRequest = { showGpsInfo = false },
            title = { Text(strings.gpsPostProcessingTitle) },
            text = { Text(strings.gpsPostProcessingInfo) },
            confirmButton = {
                TextButton(onClick = { showGpsInfo = false }) {
                    Text(strings.gotIt)
                }
            },
        )
    }
}
