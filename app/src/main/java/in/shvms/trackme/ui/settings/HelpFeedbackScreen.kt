package `in`.shvms.trackme.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import `in`.shvms.trackme.BuildConfig
import `in`.shvms.trackme.LocalSnackbarHostState
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.support.SupportContact
import `in`.shvms.trackme.support.SupportDiagnostics
import `in`.shvms.trackme.support.SupportDiagnosticsInput
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.launch
import java.util.Locale

private data class FaqItem(val question: String, val answer: String, val batterySettings: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpFeedbackScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(setOf<Int>()) }

    val faqs = remember(strings) {
        listOf(
            FaqItem(strings.helpFaqRecordingQuestion, strings.helpFaqRecordingAnswer, batterySettings = true),
            FaqItem(strings.helpFaqBatteryQuestion, strings.helpFaqBatteryAnswer),
            FaqItem(strings.helpFaqDistanceQuestion, strings.helpFaqDistanceAnswer),
            FaqItem(strings.helpFaqOfflineQuestion, strings.helpFaqOfflineAnswer),
            FaqItem(strings.helpFaqShareQuestion, strings.helpFaqShareAnswer),
            FaqItem(strings.helpFaqDataQuestion, strings.helpFaqDataAnswer)
        )
    }

    LaunchedEffect(Unit) { AnalyticsManager.trackHelpOpened() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.helpFeedbackTitle) },
                navigationIcon = {
                    IconButton(onClick = { navController?.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(strings.helpFeedbackDescription, style = MaterialTheme.typography.bodyMedium)
            faqs.forEachIndexed { index, faq ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (index in expanded) expanded - index else expanded + index
                    }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        stateDescription = if (index in expanded) strings.mapLayersExpanded else strings.mapLayersCollapsed
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(faq.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (index in expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
                    }
                    if (index in expanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(faq.answer, style = MaterialTheme.typography.bodyMedium)
                        if (faq.batterySettings) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { openBatterySettings(context) }) {
                                Text(strings.helpOpenBatterySettings)
                            }
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val body = buildSupportBody(context, strings)
                    AnalyticsManager.trackSupportContactStarted(expanded.size)
                    openSupportEmail(context, strings, body, snackbarHostState, scope)
                }
            ) { Text(strings.contactSupport) }
        }
    }
}

private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Some OEMs do not expose this list; the FAQ answer remains useful without a crash.
    }
}

private fun openSupportEmail(
    context: Context,
    strings: `in`.shvms.trackme.ui.localization.AppStrings,
    body: String,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val subject = "${strings.contactSupportSubject} — Android ${BuildConfig.VERSION_NAME}"
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportContact.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        context.startActivity(Intent.createChooser(intent, strings.contactSupport))
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(strings.contactSupport, "${SupportContact.EMAIL}\n\n$body"))
        scope.launch { snackbarHostState.showSnackbar(strings.contactSupportCopied) }
    }
}

private fun buildSupportBody(
    context: Context,
    strings: `in`.shvms.trackme.ui.localization.AppStrings
): String {
    val prefs = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val location = when {
        fine && background -> strings.helpLocationPreciseBackground
        fine -> strings.helpLocationPrecise
        coarse -> strings.helpLocationApproximate
        else -> strings.helpLocationDenied
    }
    val notification = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) strings.helpPermissionGranted
    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) strings.helpPermissionGranted
    else strings.helpPermissionDenied
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val source = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        } ?: strings.helpUnknown
    } catch (_: Exception) { strings.helpUnknown }
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    val diagnostics = SupportDiagnostics.render(
        SupportDiagnosticsInput(
            appVersion = "${BuildConfig.VERSION_NAME} ($versionCode)",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            appLanguage = prefs.getString("app_language", "en") ?: "en",
            deviceLocale = Locale.getDefault().toLanguageTag(),
            units = prefs.getString("unit_system", "metric") ?: "metric",
            installSource = source,
            locationPermission = location,
            notificationPermission = notification,
            batteryOptimization = if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) strings.helpPermissionGranted else strings.helpPermissionDenied,
            signedIn = FirebaseAuth.getInstance().currentUser?.isAnonymous == false
        )
    )
    return "${strings.helpDiagnosticInstruction}\n\n${strings.helpDiagnosticSeparator}\n$diagnostics"
}
