package `in`.shvms.trackme.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

internal const val PAGE_WELCOME = 0
internal const val PAGE_TOUR = 1
internal const val PAGE_SETUP = 2
internal const val PAGE_COUNT = 3

/**
 * The one-time first-run walkthrough (condensed 3-card fast-start flow, TASK-335).
 *
 * Two rules shape the navigation, and they are deliberately different:
 *
 *  - **The tour is skippable.** Skip on pages 0–1 jumps *forward* to setup rather than out
 *    of the flow, so someone in a hurry still reaches the screen that sets the app up.
 *  - **Nothing here traps you.** Continue works whether or not location was granted, and the
 *    analytics toggle is a real switch with a visible off position. Forcing a yes would make the
 *    consent worthless as consent.
 *
 * @param onFinish receives everything the walkthrough learned, including the analytics choice as it
 *   stood on the last screen. The caller writes that choice explicitly — the value stops being an
 *   implicit default and becomes a recorded decision.
 */
@Composable
fun OnboardingScreen(onFinish: (OnboardingOutcome) -> Unit) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val dwell = remember {
        OnboardingDwellAccumulator(
            pageCount = PAGE_COUNT,
            nowMillis = SystemClock::elapsedRealtime,
            initiallyRunning = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
    }

    var locationGranted by remember { mutableStateOf(hasLocation(context)) }
    var locationDeclined by remember { mutableStateOf(false) }
    val notificationsGranted = hasNotifications(context)
    var analyticsEnabled by remember { mutableStateOf(defaultAnalytics(context)) }
    var selectedDemoPersona by remember { mutableStateOf(RidePersona.CYCLING) }
    var personaExplicitlyChosen by remember { mutableStateOf(false) }

    var attempts by remember { mutableIntStateOf(1) }
    var furthestPage by remember { mutableIntStateOf(0) }
    var usedSkip by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }

    LaunchedEffect(Unit) { attempts = OnboardingGate.recordAttempt(context) }
    LaunchedEffect(pager.settledPage) {
        furthestPage = maxOf(furthestPage, pager.settledPage)
        dwell.enter(pager.settledPage)
    }
    DisposableEffect(lifecycleOwner, pager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> dwell.resume(pager.settledPage)
                Lifecycle.Event.ON_PAUSE -> dwell.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            dwell.pause()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun finishOnboarding(grantedOverride: Boolean = locationGranted) {
        val pageDwell = dwell.snapshotSeconds()
        onFinish(
            OnboardingOutcome(
                attempts = attempts,
                furthestPage = furthestPage,
                usedSkip = usedSkip,
                seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt(),
                welcomeDwellSeconds = pageDwell[PAGE_WELCOME],
                rideDwellSeconds = pageDwell[PAGE_TOUR],
                historyDwellSeconds = -1,
                togetherDwellSeconds = -1,
                permissionsDwellSeconds = pageDwell[PAGE_SETUP],
                readyDwellSeconds = pageDwell[PAGE_SETUP],
                analyticsEnabled = analyticsEnabled,
                locationGranted = grantedOverride,
                notificationsGranted = notificationsGranted,
                selectedPersona = if (personaExplicitlyChosen) selectedDemoPersona else RidePersona.AUTO,
            )
        )
    }

    var finishAfterPermissionResult by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationGranted = granted
        locationDeclined = !granted
        if (finishAfterPermissionResult && granted) {
            finishAfterPermissionResult = false
            finishOnboarding(grantedOverride = true)
        } else {
            finishAfterPermissionResult = false
        }
    }
    fun goTo(page: Int) = scope.launch { pager.animateScrollToPage(page) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            OnboardingChrome(
                page = pager.currentPage,
                strings = strings,
                onBack = { goTo(pager.currentPage - 1) },
                onSkip = {
                    usedSkip = true
                    goTo(PAGE_SETUP)
                },
            )

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                userScrollEnabled = pager.currentPage < PAGE_SETUP,
            ) { page ->
                val clipIsActive = pager.settledPage == page && !pager.isScrollInProgress
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (page) {
                        PAGE_WELCOME -> WelcomePage(strings, clipIsActive)
                        PAGE_TOUR -> TourPage(
                            strings = strings,
                            selectedPersona = selectedDemoPersona,
                            onPersonaSelected = {
                                selectedDemoPersona = it
                                personaExplicitlyChosen = true
                            },
                            onFinished = { goTo(PAGE_SETUP) },
                        )
                        PAGE_SETUP -> SetupPage(
                            strings = strings,
                            locationGranted = locationGranted,
                            locationDeclined = locationDeclined,
                            onAllowLocation = {
                                finishAfterPermissionResult = false
                                locationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            },
                            analyticsEnabled = analyticsEnabled,
                            onAnalyticsChange = { analyticsEnabled = it },
                            onOpenBatterySettings = { openBatterySettings(context) },
                        )
                    }
                }
            }

            OnboardingActions(
                page = pager.currentPage,
                strings = strings,
                locationGranted = locationGranted,
                locationDeclined = locationDeclined,
                onNext = { goTo(pager.currentPage + 1) },
                onSkipToSetup = {
                    usedSkip = true
                    goTo(PAGE_SETUP)
                },
                onPrimarySetupAction = {
                    if (!locationGranted && !locationDeclined) {
                        finishAfterPermissionResult = true
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    } else {
                        finishOnboarding()
                    }
                },
                onFinishWithoutLocation = { finishOnboarding() },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------

@Composable
private fun OnboardingChrome(
    page: Int,
    strings: AppStrings,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp)) {
            if (page in (PAGE_WELCOME + 1)..PAGE_SETUP) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                }
            }
        }

        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(PAGE_COUNT) { i ->
                val active = i == page
                val width by animateDpAsState(if (active) 18.dp else 6.dp, label = "dot$i")
                Box(
                    Modifier
                        .height(6.dp)
                        .width(width)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }

        Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            if (page == PAGE_TOUR) {
                TextButton(onClick = onSkip) {
                    Text(strings.obSkip, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun OnboardingActions(
    page: Int,
    strings: AppStrings,
    locationGranted: Boolean,
    locationDeclined: Boolean,
    onNext: () -> Unit,
    onSkipToSetup: () -> Unit,
    onPrimarySetupAction: () -> Unit,
    onFinishWithoutLocation: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (page) {
            PAGE_WELCOME -> {
                PrimaryButton(strings.obWelcomeCta, onNext)
                TextButton(onClick = onSkipToSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.obWelcomeSkip, style = MaterialTheme.typography.labelMedium)
                }
            }
            PAGE_SETUP -> {
                val primaryLabel = if (!locationGranted && !locationDeclined) {
                    strings.obPermLocationCta
                } else {
                    strings.obFinishCta
                }
                PrimaryButton(primaryLabel, onPrimarySetupAction)
                if (!locationGranted) {
                    TextButton(onClick = onFinishWithoutLocation, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.obContinue, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            else -> PrimaryButton(strings.obNext, onNext)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun PageText(title: String, body: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun WelcomePage(strings: AppStrings, isActive: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        OnboardingClip(
            dark = R.raw.onboarding_welcome_dark,
            light = R.raw.onboarding_welcome_light,
            isActive = isActive,
            fallback = { WelcomeMark(Modifier.fillMaxSize()) },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f)
                .clearAndSetSemantics { },
        )
        PageText(strings.obWelcomeTitle, strings.obWelcomeBody)
    }
}

@Composable
private fun TourPage(
    strings: AppStrings,
    selectedPersona: RidePersona,
    onPersonaSelected: (RidePersona) -> Unit,
    onFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageText(strings.obRideTitle, strings.obRideBody, strings.obTogetherNote)
        RideOnboardingDemo(
            strings = strings,
            selectedPersona = selectedPersona,
            onPersonaSelected = onPersonaSelected,
            onFinished = onFinished,
        )
    }
}

@Composable
private fun SetupPage(
    strings: AppStrings,
    locationGranted: Boolean,
    locationDeclined: Boolean,
    onAllowLocation: () -> Unit,
    analyticsEnabled: Boolean,
    onAnalyticsChange: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PermissionsPage(
            strings = strings,
            locationGranted = locationGranted,
            locationDeclined = locationDeclined,
            onAllowLocation = onAllowLocation,
        )
        ReadyPage(
            strings = strings,
            analyticsEnabled = analyticsEnabled,
            onAnalyticsChange = onAnalyticsChange,
            onOpenBatterySettings = onOpenBatterySettings,
        )
    }
}

@Composable
private fun PermissionsPage(
    strings: AppStrings,
    locationGranted: Boolean,
    locationDeclined: Boolean,
    onAllowLocation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            strings.obPermTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        LocationScopeArt(
            offLabel = strings.obPermScopeOff,
            onLabel = strings.obPermScopeOn,
            modifier = Modifier.fillMaxWidth(),
        )

        PermissionCard(
            icon = Icons.Default.LocationOn,
            title = strings.obPermLocationTitle,
            body = strings.obPermLocationBody,
            badge = strings.obPermRequired,
            badgeIsPrimary = true,
            granted = locationGranted,
            grantedLabel = strings.obPermGranted,
            actionLabel = strings.obPermLocationCta,
            onAction = onAllowLocation,
        )

        AnimatedVisibility(visible = locationDeclined && !locationGranted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    .padding(12.dp),
            ) {
                Text(
                    strings.obPermDeniedNote,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    badge: String,
    badgeIsPrimary: Boolean,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (badgeIsPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (badgeIsPrimary) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (badgeIsPrimary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (granted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    grantedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReadyPage(
    strings: AppStrings,
    analyticsEnabled: Boolean,
    onAnalyticsChange: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            strings.obReadyTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                strings.obBatteryTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                strings.obBatteryBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Text(strings.obBatteryCta, style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    strings.obAnalyticsTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    strings.obAnalyticsBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = analyticsEnabled, onCheckedChange = onAnalyticsChange)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Platform helpers
// ---------------------------------------------------------------------------------------------

private fun hasLocation(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun hasNotifications(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        // No runtime permission below 33 — the card would be asking for something already held.
        true
    }

private fun defaultAnalytics(context: Context): Boolean {
    val sim = runCatching {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso
    }.getOrNull()
    val locale = runCatching {
        context.resources.configuration.locales[0].country
    }.getOrNull()
    return AnalyticsDefault.startsOn(sim, locale)
}

/**
 * Opens the battery-optimisation list. Deliberately the *settings* action rather than
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which needs a restricted permission and a Play
 * declaration; this just shows the list and lets the user decide.
 */
private fun openBatterySettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
