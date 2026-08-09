package `in`.shvms.trackme.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import `in`.shvms.trackme.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.service.TrackingState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.maps.android.compose.*
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ContentCopy
import `in`.shvms.trackme.data.remote.LiveShareStatus
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.launch
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.ui.home.components.RadialStartRideButton
import `in`.shvms.trackme.ui.home.components.ActiveRideHudPanel
import `in`.shvms.trackme.ui.components.animateSafely
import `in`.shvms.trackme.ui.components.rememberIsOffline
import `in`.shvms.trackme.ui.home.components.MapLayerHorizontalDrawerButton
import `in`.shvms.trackme.ui.home.components.MapControlCircleButton
import `in`.shvms.trackme.ui.home.components.GroupMapButton
import androidx.compose.ui.platform.LocalDensity
import `in`.shvms.trackme.ui.home.components.MemberMarkerPolicy
import `in`.shvms.trackme.ui.home.components.rememberMemberAvatarCache
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.analytics.RideStartAbortMethod

private const val LAST_CAMERA_LAT_KEY = "last_camera_lat"
private const val LAST_CAMERA_LNG_KEY = "last_camera_lng"
private const val LAST_CAMERA_ZOOM_KEY = "last_camera_zoom"
internal const val RIDE_START_UNDO_WINDOW_MILLIS = 10_000L

internal fun shouldShowRideStartUndo(
    elapsedDurationMillis: Long,
    distanceMeters: Float
): Boolean = elapsedDurationMillis < RIDE_START_UNDO_WINDOW_MILLIS &&
    distanceMeters < `in`.shvms.trackme.service.TrackingService.JUNK_RIDE_DISTANCE_METERS

// Country-level fallback used before a location fix has ever been persisted (center
// of India); anything is better than the (0,0) world view.
private val DEFAULT_HOME_CAMERA_TARGET = com.google.android.gms.maps.model.LatLng(22.5937, 78.9629)

private fun lastKnownHomeCamera(prefs: android.content.SharedPreferences): CameraPosition {
    val lat = prefs.getFloat(LAST_CAMERA_LAT_KEY, Float.NaN)
    val lng = prefs.getFloat(LAST_CAMERA_LNG_KEY, Float.NaN)
    if (lat.isNaN() || lng.isNaN()) {
        return CameraPosition.fromLatLngZoom(DEFAULT_HOME_CAMERA_TARGET, 4.5f)
    }
    val zoom = prefs.getFloat(LAST_CAMERA_ZOOM_KEY, 15f)
    return CameraPosition.fromLatLngZoom(
        com.google.android.gms.maps.model.LatLng(lat.toDouble(), lng.toDouble()),
        zoom
    )
}

/** Unwrap the hosting Activity from a (possibly wrapped) Compose Context — for the B4 review flow. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = android.net.Uri.fromParts("package", context.packageName, null)
    context.startActivity(intent)
}

@Composable
fun HomeScreen(
    onOpenCommunity: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(
            (LocalContext.current.applicationContext as TrackMeApp).trackingManager,
            (LocalContext.current.applicationContext as TrackMeApp).emergencyManager,
            (LocalContext.current.applicationContext as TrackMeApp).authManager,
            (LocalContext.current.applicationContext as TrackMeApp).liveShareManager,
            (LocalContext.current.applicationContext as TrackMeApp).preferencesManager
        )
    )
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val app = context.applicationContext as TrackMeApp
    val imperialUnits by app.preferencesManager.unitSystem.collectAsState()
    val uiPreferences = remember {
        context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
    }
    var showStartRideHint by remember {
        mutableStateOf(!uiPreferences.getBoolean("start_ride_hint_seen", false))
    }
    var showDiscardRideDialog by remember { mutableStateOf(false) }
    var hasRequestedStartRideUndo by remember { mutableStateOf(false) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    val recoveryNotice by app.recoveryNotice.collectAsState()
    val locationPermissionRevokedNotice by app.locationPermissionRevokedNotice.collectAsState()
    // B1: durable one-shot post-ride reveal (null unless a good ride was just saved).
    val pendingReveal by app.pendingRevealStore.pending.collectAsState()
    // B2: weekly recap for a completed week (null unless one is pending on foreground).
    val weeklyRecap by app.weeklyRecap.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    LaunchedEffect(uiState.trackingState) {
        if (uiState.trackingState == TrackingState.IDLE) {
            hasRequestedStartRideUndo = false
        }
    }

    // §8's "clear notice", on Home as well as in the Community tab.
    //
    // Home is where a rider is actually looking, and it is where the loss is most confusing: the
    // group markers vanish and the group control disappears with them, so the one affordance that
    // could have explained it is the thing that just went away. Without this, the map simply goes
    // blank mid-ride.
    val groupEndNotice by app.groupSessionManager.endNotice.collectAsState()
    LaunchedEffect(groupEndNotice) {
        val notice = groupEndNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = `in`.shvms.trackme.ui.community.groupEndNoticeText(notice, strings),
            duration = SnackbarDuration.Long,
        )
        app.groupSessionManager.acknowledgeEndNotice()
    }

    LaunchedEffect(recoveryNotice) {
        val summary = recoveryNotice ?: return@LaunchedEffect
        val message = when {
            summary.recoveredCount > 0 && summary.discardedCount > 0 ->
                String.format(
                    java.util.Locale.getDefault(),
                    strings.rideRecoveryMixedNotice,
                    summary.recoveredCount,
                    summary.discardedCount
                )
            summary.recoveredCount > 0 ->
                String.format(
                    java.util.Locale.getDefault(),
                    strings.rideRecoveryNotice,
                    summary.recoveredCount
                )
            else ->
                String.format(
                    java.util.Locale.getDefault(),
                    strings.rideRecoveryDiscardNotice,
                    summary.discardedCount
                )
        }
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        app.consumeRecoveryNotice()
    }

    // HomeViewModel is Context-free; it emits WHAT happened (service commands, live-share
    // outcomes) and this screen decides how to present it (Intent/Toast), since only the
    // screen legitimately holds a Context.
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is HomeViewModel.UiEvent.SendServiceCommand -> {
                    val intent = android.content.Intent(context, `in`.shvms.trackme.service.TrackingService::class.java).apply {
                        action = event.action
                    }
                    context.startService(intent)
                }
                HomeViewModel.UiEvent.LiveShareAuthRequired ->
                    android.widget.Toast.makeText(context, strings.liveShareAuthRequired, android.widget.Toast.LENGTH_LONG).show()
                is HomeViewModel.UiEvent.LiveShareStarted -> {
                    val msg = if (event.isTrackingActive) strings.liveShareReadyActive else strings.liveShareReadyIdle
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
                HomeViewModel.UiEvent.LiveShareAuthExpired ->
                    android.widget.Toast.makeText(context, strings.liveShareAuthExpired, android.widget.Toast.LENGTH_LONG).show()
                is HomeViewModel.UiEvent.LiveShareGracefulError ->
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_LONG).show()
                HomeViewModel.UiEvent.LiveShareStopped ->
                    android.widget.Toast.makeText(context, strings.liveShareStoppedToast, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val receiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                // B1: a good ride shows the reveal dialog instead of this toast. Keep the toast
                // only as the fallback confirmation for rides that earn no reveal (e.g. a
                // sub-threshold "junk" ride the user chose to save anyway).
                if (app.pendingRevealStore.pending.value == null) {
                    android.widget.Toast.makeText(context, strings.rideSaved, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    DisposableEffect(context) {
        val filter = android.content.IntentFilter("in.shvms.trackme.RIDE_SAVED")
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Notification access is optional; ride tracking still proceeds. */ }
    )

    // The location AlertDialog below is the primer and sole trigger for the native
    // location prompt. Do not request permissions from a cold composition.

    // Seeded from the last persisted camera (country-level default before the first
    // fix) so the map never composes at the world view; rememberCameraPositionState
    // is saveable, so tab switches and rotation restore the live position instead.
    val cameraPositionState = rememberCameraPositionState {
        position = lastKnownHomeCamera(uiPreferences)
    }
    DisposableEffect(Unit) {
        onDispose {
            val position = cameraPositionState.position
            uiPreferences.edit()
                .putFloat(LAST_CAMERA_LAT_KEY, position.target.latitude.toFloat())
                .putFloat(LAST_CAMERA_LNG_KEY, position.target.longitude.toFloat())
                .putFloat(LAST_CAMERA_ZOOM_KEY, position.zoom)
                .apply()
        }
    }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var hasCenteredOnLocation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && !hasCenteredOnLocation && uiState.pathPoints.isEmpty()) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        hasCenteredOnLocation = true
                        coroutineScope.launch {
                            cameraPositionState.animateSafely {
                                CameraUpdateFactory.newLatLngZoom(com.google.android.gms.maps.model.LatLng(loc.latitude, loc.longitude), 17f)
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    LaunchedEffect(uiState.pathPoints) {
        if (uiState.trackingState == TrackingState.TRACKING && uiState.pathPoints.isNotEmpty()) {
            val lastPoint = uiState.pathPoints.last()
            cameraPositionState.animateSafely { CameraUpdateFactory.newLatLngZoom(lastPoint, 17f) }
        }
    }

    // Expiry watchdog: a share is capped at 24h, so stop it once the window closes even if the
    // ride is still running. (The ride-end teardown lives in TrackingService; this is the other
    // trigger.) There is no countdown UI to feed any more — the share drawer on the ride HUD owns
    // live-share presentation now.
    LaunchedEffect(uiState.liveShareState) {
        if (uiState.liveShareState.status == LiveShareStatus.ACTIVE && uiState.liveShareState.expiresAt != null) {
            while (true) {
                val duration = Duration.between(Instant.now(), uiState.liveShareState.expiresAt)
                if (duration.isNegative || duration.isZero) {
                    viewModel.stopLiveShare("Max ride duration reached, stopping.")
                    break
                }
                delay(1000)
            }
        }
    }

    // B1: surface the post-ride reveal once, when Home is composed after a good ride. Telemetry
    // fires only when the reveal is actually shown (keyed on ride ID → exactly once per reveal).
    pendingReveal?.let { reveal ->
        LaunchedEffect(reveal.rideId) {
            `in`.shvms.trackme.analytics.AnalyticsManager.trackPostRideRevealShown(reveal.revealType)
        }
        PostRideRevealDialog(
            reveal = reveal,
            imperial = imperialUnits == "imperial",
            onDismiss = {
                app.pendingRevealStore.consume(reveal.rideId)
                // B4: dismissing a good-ride reveal is a peak moment — ask an eligible, happy
                // user for a review (self-gated by ReviewPromptPolicy; OS throttles on top).
                (context.findActivity())?.let { activity ->
                    coroutineScope.launch {
                        `in`.shvms.trackme.ui.review.ReviewPrompter.maybeRequest(
                            activity, app.rideStatsStore.stats.value.totalRides
                        )
                    }
                }
            }
        )
    }

    // B2/B3: weekly recap (with the streak line). Emitted once when actually shown, then acked.
    // TASK-119: shown only while the app is calmly idle — never over a live/paused ride, an active
    // SOS, a GPS-lost/storage-low state, or a post-ride reveal (prompt 09, "Trigger"). This is the
    // render-time half of the gate; `TrackMeApp.checkWeeklyRecap()` is the check-time half. It has
    // to be re-evaluated here because the user can leave idle *after* a recap was queued. Skipping
    // never consumes the recap — it is acked only in `onDismiss` — so it returns on the next calm
    // frame, still within its week.
    weeklyRecap?.let { recap ->
        val isCalmMoment = `in`.shvms.trackme.domain.stats.CalmMomentGate.isCalm(
            `in`.shvms.trackme.domain.stats.CalmMomentGate.AppMoment(
                isTrackingIdle = uiState.trackingState == TrackingState.IDLE,
                isEmergencyActive = uiState.isEmergencyActive,
                hasPendingReveal = pendingReveal != null
            )
        )
        if (isCalmMoment) {
            LaunchedEffect(recap.weekStartEpochDay) {
                `in`.shvms.trackme.analytics.AnalyticsManager.trackWeeklyRecapShown(
                    weekKey = recap.weekKey,
                    rideCount = recap.rideCount,
                    distanceKm = recap.distanceMeters / 1000.0
                )
            }
            WeeklyRecapDialog(
                recap = recap,
                imperial = imperialUnits == "imperial",
                onDismiss = { app.consumeWeeklyRecap() }
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            var mapType by remember { mutableStateOf(MapType.NORMAL) }
            var isTrafficEnabled by remember { mutableStateOf(false) }

            // Box scope, not map scope: the map draws member markers and the control stack draws
            // the group button, and both need the same session.
            val groupSession by app.groupSessionManager.state.collectAsState()
            val avatarCache = rememberMemberAvatarCache()

            if (hasLocationPermission) {
                val groupSyncIntervalSec = groupSession.syncIntervalSec

                // Staleness is a function of wall-clock time, not of new data — a member who stops
                // syncing produces no recomposition, so without a tick their marker would stay
                // bright forever. One second is cheap and makes "2m ago" honest.
                var groupClockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(groupSession.isActive) {
                    while (groupSession.isActive) {
                        groupClockTick = System.currentTimeMillis()
                        delay(1_000L)
                    }
                }
                // §3.3: the bitmaps are per-session. A uid from a previous group is never valid in
                // the next one, and the cache would otherwise outlive its group.
                LaunchedEffect(groupSession.groupId) { avatarCache.clear() }

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        isMyLocationEnabled = true,
                        mapType = mapType,
                        isTrafficEnabled = isTrafficEnabled
                    ),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = false
                    ),
                    contentPadding = PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                        // Google's attribution and the compass live inside this padding. The map
                        // is full-bleed, so without the navigation-bar inset the attribution sits
                        // under the nav bar — it is required to stay visible.
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            if (uiState.trackingState != TrackingState.IDLE) 88.dp else 0.dp
                    )
                ) {
                    if (uiState.pathPoints.isNotEmpty()) {
                        Polyline(
                            points = uiState.pathPoints,
                            color = TrackMeBlue,
                            width = 10f
                        )
                    }

                    // --- Destination pin (§2.9) ---
                    //
                    // Shown unconditionally, unlike everything else group-related on this map: a
                    // pin is a FACT, not an estimate. §2.9 keeps it while holding ETA and arrival
                    // back precisely because it is accurate and costs nothing. It is deliberately
                    // NOT viewport-culled — the whole point of "we're going here" is that you can
                    // find it, and unlike a member it does not move.
                    val destLat = groupSession.destinationLat
                    val destLng = groupSession.destinationLng
                    if (groupSession.isActive && destLat != null && destLng != null) {
                        Marker(
                            state = rememberMarkerState(
                                key = "group-destination",
                                position = com.google.android.gms.maps.model.LatLng(destLat, destLng),
                            ),
                            title = strings.groupDestination,
                            snippet = `in`.shvms.trackme.domain.group.GroupDestinationLinks
                                .formatCoordinates(destLat, destLng),
                            // Monochrome flag, not another cyan pin: cyan already means "a person
                            // in this group", and a place must not read as the same kind of thing.
                            icon = `in`.shvms.trackme.ui.home.components.destinationFlagDescriptor(
                                LocalDensity.current.density,
                            ),
                            anchor = androidx.compose.ui.geometry.Offset(0.22f, 0.94f),
                        )
                    }

                    // --- Group members (§3.3, A19) ---
                    //
                    // A19: the camera stays on the rider. Members are drawn only where they
                    // already are — inside the viewport being looked at — and the map never moves
                    // itself to find someone. Anyone off-screen or stale is in the roster, which
                    // A18 makes the complete list.
                    //
                    // Own position is never drawn: the system blue dot is already there and §2.6
                    // is explicit that we never draw ourselves twice.
                    val bounds = cameraPositionState.projection?.visibleRegion?.latLngBounds
                    val nowMs = groupClockTick
                    groupSession.positions.forEach { member ->
                        val point = com.google.android.gms.maps.model.LatLng(member.lat, member.lng)
                        val freshness = MemberMarkerPolicy.renderFor(
                            position = point,
                            serverTsMillis = member.serverTsMillis,
                            nowMillis = nowMs,
                            syncIntervalSec = groupSyncIntervalSec,
                            bounds = bounds,
                        ) ?: return@forEach

                        val roster = groupSession.roster.firstOrNull { it.uid == member.uid }
                        val name = roster?.displayName ?: strings.groupStatusRiding
                        val age = MemberMarkerPolicy.ageMinutes(member.serverTsMillis, nowMs)

                        Marker(
                            state = rememberMarkerState(key = member.uid, position = point),
                            // §3.3: tap gives name, distance from you, last-update age. Nothing
                            // else — no history, no profile, no follow.
                            title = name,
                            snippet = if (age > 0) {
                                String.format(java.util.Locale.getDefault(), strings.groupTimeLeft, "${'$'}{age}m")
                            } else null,
                            icon = avatarCache.descriptorFor(member.uid, roster?.initials, freshness),
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        )
                    }
                }
            } else {
                AlertDialog(
                    onDismissRequest = { /* Blocking dialog, do nothing */ },
                    title = { Text(strings.locationPermissionRequired) },
                    text = { Text(strings.locationPermissionDesc) },
                    confirmButton = {
                        Button(onClick = {
                            val permissionsToRequest = arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                            locationPermissionLauncher.launch(permissionsToRequest)
                        }) {
                            Text(strings.grantPermission)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            openAppSettings(context)
                        }) {
                            Text(strings.openSettings)
                        }
                    }
                )
            }

            val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val isOffline = rememberIsOffline()

            Column(

                modifier = Modifier.align(Alignment.TopEnd).padding(top = topPadding + 80.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Above the layer/recentre/compass stack, and present only while a group is live
                // (A20). Its presence is the signal that someone can see you; tapping it opens the
                // roster, where Leave lives.
                GroupMapButton(
                    session = groupSession,
                    memberCount = (groupSession.roster.size - 1).coerceAtLeast(0),
                    onClick = onOpenCommunity,
                )

                MapLayerHorizontalDrawerButton(
                    currentMapType = mapType,
                    onMapTypeSelected = { mapType = it },
                    isTrafficEnabled = isTrafficEnabled,
                    onTrafficToggle = { isTrafficEnabled = !isTrafficEnabled }
                )

                MapControlCircleButton(
                    icon = Icons.Default.MyLocation,
                    contentDescription = strings.recenterMap,
                    onClick = {
                        val target = uiState.pathPoints.lastOrNull()
                        if (target != null) {
                            coroutineScope.launch {
                                cameraPositionState.animateSafely { CameraUpdateFactory.newLatLngZoom(target, 17f) }
                            }
                        } else if (hasLocationPermission) {
                            try {
                                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                    .addOnSuccessListener { loc ->
                                        if (loc != null) {
                                            coroutineScope.launch {
                                                cameraPositionState.animateSafely {
                                                    CameraUpdateFactory.newLatLngZoom(
                                                        com.google.android.gms.maps.model.LatLng(loc.latitude, loc.longitude),
                                                        17f
                                                    )
                                                }
                                            }
                                        } else {
                                            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                                                if (lastLoc != null) {
                                                    coroutineScope.launch {
                                                        cameraPositionState.animateSafely {
                                                            CameraUpdateFactory.newLatLngZoom(
                                                                com.google.android.gms.maps.model.LatLng(lastLoc.latitude, lastLoc.longitude),
                                                                17f
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                            } catch (_: SecurityException) {}
                        }
                    }
                )

                MapControlCircleButton(
                    icon = Icons.Default.Explore,
                    contentDescription = strings.compassNorth,
                    onClick = {
                        coroutineScope.launch {
                            cameraPositionState.animateSafely {
                                CameraUpdateFactory.newCameraPosition(
                                    com.google.android.gms.maps.model.CameraPosition.Builder(cameraPositionState.position)
                                        .bearing(0f)
                                        .tilt(0f)
                                        .build()
                                )
                            }
                        }
                    }
                )
            }

            // Idle State: Radial Persona Start Button
            if (uiState.trackingState == TrackingState.IDLE) {
                if (locationPermissionRevokedNotice) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topPadding + 16.dp, start = 12.dp, end = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = strings.locationPermissionRevokedTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = strings.locationPermissionRevokedBody,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { openAppSettings(context) }) {
                                Text(strings.openSettings)
                            }
                            IconButton(onClick = { app.dismissLocationPermissionRevokedNoticeForSession() }) {
                                Icon(Icons.Default.Close, contentDescription = strings.close)
                            }
                        }
                    }
                }

                if (hasLocationPermission && showStartRideHint) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Tracks the start button, which is itself lifted clear of the
                            // navigation bar — without this the hint would drift onto it.
                            .navigationBarsPadding()
                            .padding(bottom = 186.dp, start = 24.dp, end = 24.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.startRideHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    showStartRideHint = false
                                    uiPreferences.edit().putBoolean("start_ride_hint_seen", true).apply()
                                }
                            ) {
                                Text(strings.dismissStartRideHint)
                            }
                        }
                    }
                }

                RadialStartRideButton(
                    onStartRide = { persona ->
                        showStartRideHint = false
                        uiPreferences.edit().putBoolean("start_ride_hint_seen", true).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.startTracking(persona)
                    },
                    onAbortRideStart = AnalyticsManager::trackRideStartAborted,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The map is deliberately full-bleed (Scaffold contributes no insets),
                        // so every interactive overlay has to clear the navigation bar itself.
                        // Without this the start button sits under the 3-button nav bar.
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp)
                )


                // No live-share surface while idle. A share is always started with
                // stopOnRideEnd = true from the ride HUD's share drawer, so it can never
                // legitimately outlive the ride — the only way this branch could render was
                // during the async teardown gap after a stop, which flashed a stale green
                // "sharing" FAB for about a second. The drawer owns live share now.
            } else {
                // Active Recording / Non-Ideal State HUD Panel
                val showRideStartUndo = !hasRequestedStartRideUndo && shouldShowRideStartUndo(
                    elapsedDurationMillis = uiState.elapsedDurationMillis,
                    distanceMeters = uiState.distanceMeters
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Same as the idle branch: the ride HUD and its pause/stop controls sit
                        // over a full-bleed map, so they must clear the navigation bar.
                        .navigationBarsPadding()
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(visible = showRideStartUndo) {
                        OutlinedButton(
                            onClick = {
                                if (!hasRequestedStartRideUndo && shouldShowRideStartUndo(
                                        elapsedDurationMillis = uiState.elapsedDurationMillis,
                                        distanceMeters = uiState.distanceMeters
                                    )
                                ) {
                                    hasRequestedStartRideUndo = true
                                    AnalyticsManager.trackRideStartAborted(
                                        RideStartAbortMethod.POST_COMMIT_UNDO
                                    )
                                    viewModel.stopTracking(discardNearEmptyRide = true)
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TrackMeRed
                            ),
                            border = BorderStroke(1.dp, TrackMeRed),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(strings.discardRide)
                        }
                    }

                    ActiveRideHudPanel(
                    trackingState = uiState.trackingState,
                    distanceText = uiState.distanceText,
                    durationText = uiState.durationText,
                    elapsedDurationText = uiState.elapsedDurationText,
                    speedText = uiState.speedText,
                    paceText = uiState.paceText,
                    selectedPersona = uiState.selectedPersona,
                    isAutoPaused = uiState.isAutoPaused,
                    timeSinceLastGps = uiState.timeSinceLastGps,
                    liveShareState = uiState.liveShareState,
                    isAuthenticated = uiState.isAuthenticated,
                    liveShareAuthRequired = strings.liveShareAuthRequired,
                    isOffline = isOffline,
                    onPauseToggle = {
                        if (uiState.trackingState == TrackingState.TRACKING) {
                            viewModel.pauseTracking()
                        } else {
                            viewModel.startTracking(uiState.selectedPersona)
                        }
                    },
                    onStopRide = {
                        if (uiState.distanceMeters < `in`.shvms.trackme.service.TrackingService.JUNK_RIDE_DISTANCE_METERS &&
                            uiState.durationMillis < `in`.shvms.trackme.service.TrackingService.JUNK_RIDE_DURATION_MILLIS
                        ) {
                            showDiscardRideDialog = true
                        } else {
                            viewModel.stopTracking()
                            android.widget.Toast.makeText(context, strings.savingRide, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStartShare = {
                        // §17.4: "while a Group Ride is live, the Home FAB should be *disabled
                        // with a reason* rather than hidden — running both simultaneously is a
                        // battery and confusion problem, and silently removing a control users
                        // know is worse than explaining it."
                        //
                        // Two location broadcasts at once would also double the network cost of a
                        // feature whose entire battery budget (§7.4) assumes one.
                        if (groupSession.isActive) {
                            android.widget.Toast.makeText(
                                context,
                                strings.groupLiveShareBlocked,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            viewModel.startLiveShare(durationMinutes = 1440, stopOnRideEnd = true)
                        }
                    },
                    onStopShare = {
                        viewModel.stopLiveShare()
                        android.widget.Toast.makeText(context, strings.liveShareStoppedToast, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onSendShare = {
                        val shareLink = uiState.liveShareState.shareLink
                        if (shareLink != null) {
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, strings.liveShareButton)
                                val message = "Hey, you can track my live ride until it ends here: $shareLink\n\nThanks, ${uiState.userName ?: "Track Me User"}"
                                putExtra(android.content.Intent.EXTRA_TEXT, message)
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, strings.liveShareButton))
                        }
                    },
                    onCopyShare = {
                        val shareLink = uiState.liveShareState.shareLink
                        if (shareLink != null) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Live Share Link", shareLink)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, strings.linkCopied, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            if (showDiscardRideDialog) {
                AlertDialog(
                    // Blocking dialog: an outside tap or back press must not silently close this
                    // without picking a side. Neither stopTracking() branch would run, leaving the
                    // stop action frozen mid-flight since the ride was never actually stopped.
                    onDismissRequest = { /* Blocking dialog, do nothing */ },
                    title = { Text(strings.discardRideTitle) },
                    text = { Text(strings.discardRideMessage) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDiscardRideDialog = false
                            viewModel.stopTracking(discardNearEmptyRide = true)
                        }) {
                            Text(strings.discardRide)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDiscardRideDialog = false
                            viewModel.stopTracking()
                            android.widget.Toast.makeText(context, strings.savingRide, android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text(strings.saveAnyway)
                        }
                    }
                )
            }
        }
    }
}
}
