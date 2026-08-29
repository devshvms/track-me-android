package `in`.shvms.trackme.ui.home

import androidx.compose.material3.SnackbarDuration
import `in`.shvms.trackme.ui.components.TrackMeMapAttribution
import `in`.shvms.trackme.ui.components.icon
import `in`.shvms.trackme.ui.components.rememberMessenger
import `in`.shvms.trackme.ui.components.rememberMapStyle
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import `in`.shvms.trackme.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
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
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.launch
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.ui.home.components.ActiveRideHudPanel
import `in`.shvms.trackme.ui.home.components.RadialStartRideButton
import `in`.shvms.trackme.ui.components.animateSafely
import `in`.shvms.trackme.ui.components.rememberIsOffline
import `in`.shvms.trackme.domain.group.GroupPresencePolicy
import `in`.shvms.trackme.domain.group.RiderStatusCodec
import `in`.shvms.trackme.ui.home.components.GroupPresenceHost
import `in`.shvms.trackme.ui.home.components.pauseDurationBucket
import `in`.shvms.trackme.ui.home.components.SeverityBadgeMarker
import `in`.shvms.trackme.ui.home.components.MarkerFreshness
import `in`.shvms.trackme.ui.home.components.RideCameraPolicy
import `in`.shvms.trackme.ui.home.components.MapLayerHorizontalDrawerButton
import `in`.shvms.trackme.ui.home.components.MapControlCircleButton
import `in`.shvms.trackme.ui.home.components.GroupMapButton
import `in`.shvms.trackme.ui.home.components.PiPModePolicy
import `in`.shvms.trackme.ui.home.components.toPiPRideState
import androidx.compose.ui.platform.LocalDensity
import `in`.shvms.trackme.ui.home.components.MemberMarkerPolicy
import `in`.shvms.trackme.ui.home.components.rememberMemberAvatarCache
import `in`.shvms.trackme.ui.home.components.rememberHeadingTailBuffer
import `in`.shvms.trackme.domain.group.HeadingTail
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.analytics.RideStartAbortMethod
import `in`.shvms.trackme.analytics.ActivityStartMethod
import `in`.shvms.trackme.domain.home.HomePresentationMode
import `in`.shvms.trackme.domain.home.HomePresentationModePolicy
import `in`.shvms.trackme.domain.map.CameraFollowPolicy
import `in`.shvms.trackme.domain.map.CameraMoveCause
import com.google.maps.android.compose.CameraMoveStartedReason

/**
 * Bridges the Maps SDK's reason enum onto the platform-neutral cause [CameraFollowPolicy] reasons
 * about (§7: iOS reads a different MapKit signal, but the observable rule is identical).
 *
 * `DEVELOPER_ANIMATION` is our own `animateSafely` — including the follow move itself — so it must
 * never read as a gesture, or follow would switch itself off on its first move.
 */
private fun CameraMoveStartedReason.toMoveCause(): CameraMoveCause = when (this) {
    CameraMoveStartedReason.GESTURE -> CameraMoveCause.USER_GESTURE
    CameraMoveStartedReason.DEVELOPER_ANIMATION, CameraMoveStartedReason.API_ANIMATION ->
        CameraMoveCause.APP_ANIMATION
    else -> CameraMoveCause.OTHER
}

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

/**
 * TASK-249: how much of the idle backdrop map still shows — ~90% transparent, per shvm, raised from
 * the ~80% TASK-244 shipped. The brief is "glass like": street labels should not be readable, but
 * the road and route lines should still register as structure behind the deck.
 *
 * Alpha alone is what moves here. The 18dp blur above already does the work of destroying small
 * shapes while leaving long lines legible as shapes — text is fine detail and dies first, roads are
 * continuous strokes and survive — so fading further trades away brightness without flattening the
 * structure. Reaching for a heavier scrim instead would grey the whole thing, which is the failure
 * TASK-221's amendment named and TASK-244 was written to avoid.
 */
private const val IDLE_MAP_ALPHA = 0.1f

@Composable
fun HomeScreen(
    onOpenCommunity: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenRideDetail: (Long) -> Unit = {},
    scrollToTopRequest: Int = 0,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(
            (LocalContext.current.applicationContext as TrackMeApp).trackingManager,
            (LocalContext.current.applicationContext as TrackMeApp).emergencyManager,
            (LocalContext.current.applicationContext as TrackMeApp).authManager,
            (LocalContext.current.applicationContext as TrackMeApp).liveShareManager,
            (LocalContext.current.applicationContext as TrackMeApp).preferencesManager,
            (LocalContext.current.applicationContext as TrackMeApp).homeDashboardRepository,
            (LocalContext.current.applicationContext as TrackMeApp).firestoreSyncManager,
            (LocalContext.current.applicationContext as TrackMeApp).gamificationRepository,
        )
    )
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val mapStyle = rememberMapStyle()
    val app = context.applicationContext as TrackMeApp
    val imperialUnits by app.preferencesManager.unitSystem.collectAsState()
    val pipDashboardEnabled by app.preferencesManager.pipDashboardEnabled.collectAsState()
    val uiPreferences = remember {
        context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
    }
    var showDiscardRideDialog by remember { mutableStateOf(false) }
    var showDashboardPersonaPicker by rememberSaveable { mutableStateOf(false) }
    var dashboardSelectionCameFromPicker by rememberSaveable { mutableStateOf(false) }
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
    val dashboardRoute by viewModel.dashboardRoute.collectAsState()
    val groupSession by app.groupSessionManager.state.collectAsState()
    val isOffline = rememberIsOffline()
    var explicitGroupMap by rememberSaveable { mutableStateOf(false) }
    val presentationMode = HomePresentationModePolicy.resolve(
        isTrackingIdle = uiState.trackingState == TrackingState.IDLE,
        explicitGroupMap = explicitGroupMap,
    )
    val isInteractiveMap = presentationMode != HomePresentationMode.IDLE_DASHBOARD
    val animationsEnabled = remember(context.contentResolver) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
    BackHandler(enabled = presentationMode == HomePresentationMode.EXPLICIT_GROUP_MAP) {
        explicitGroupMap = false
    }
    val recoveryNotice by app.recoveryNotice.collectAsState()
    val locationPermissionRevokedNotice by app.locationPermissionRevokedNotice.collectAsState()
    // B1: durable one-shot post-ride reveal (null unless a good ride was just saved).
    val pendingReveal by app.pendingRevealStore.pending.collectAsState()
    // B2: weekly recap for a completed week (null unless one is pending on foreground).
    val weeklyRecap by app.weeklyRecap.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current
    var previousTrackingState by remember { mutableStateOf(uiState.trackingState) }

    LaunchedEffect(uiState.trackingState, pipDashboardEnabled) {
        if (previousTrackingState != TrackingState.IDLE && uiState.trackingState == TrackingState.IDLE) {
            explicitGroupMap = false
        }
        val justStarted = previousTrackingState == TrackingState.IDLE &&
            PiPModePolicy.isEligible(uiState.trackingState.toPiPRideState(), pipDashboardEnabled)
        if (justStarted && !uiPreferences.getBoolean("pip_ride_start_hint_seen", false)) {
            uiPreferences.edit().putBoolean("pip_ride_start_hint_seen", true).apply()
            snackbarHostState.showSnackbar(
                message = strings.pipRideStartHint,
                duration = SnackbarDuration.Long,
            )
        }
        if (uiState.trackingState == TrackingState.IDLE) {
            hasRequestedStartRideUndo = false
        }
        previousTrackingState = uiState.trackingState
    }

    LaunchedEffect(groupSession.isActive) {
        if (!groupSession.isActive) explicitGroupMap = false
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
                    messenger.show(strings.liveShareAuthRequired, duration = SnackbarDuration.Long)
                is HomeViewModel.UiEvent.LiveShareStarted -> {
                    val msg = if (event.isTrackingActive) strings.liveShareReadyActive else strings.liveShareReadyIdle
                    messenger.show(msg, duration = SnackbarDuration.Long)
                }
                HomeViewModel.UiEvent.LiveShareAuthExpired ->
                    messenger.show(strings.liveShareAuthExpired, duration = SnackbarDuration.Long)
                is HomeViewModel.UiEvent.LiveShareGracefulError ->
                    messenger.show(event.message, duration = SnackbarDuration.Long)
                HomeViewModel.UiEvent.LiveShareStopped ->
                    messenger.show(strings.liveShareStoppedToast)
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
                    messenger.show(strings.rideSaved)
                }
            }
        }
    }

    // The single, truthful ride-end announcement. The service reports what actually happened;
    // this says it once, through the same messenger, so it replaces rather than stacks.
    LaunchedEffect(Unit) {
        app.trackingManager.rideEndOutcome.collect { outcome ->
            when (outcome) {
                `in`.shvms.trackme.service.RideEndOutcome.DISCARDED_NO_GPS ->
                    messenger.show(strings.rideDiscardedNoGps, duration = SnackbarDuration.Long)
                `in`.shvms.trackme.service.RideEndOutcome.DISCARDED_BY_USER ->
                    messenger.show(strings.rideDiscarded)
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Notification access is optional; ride tracking still proceeds. */ }
    )

    var pendingStartPersona by remember { mutableStateOf<RidePersona?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            hasLocationPermission = granted
            val persona = pendingStartPersona
            pendingStartPersona = null
            if (granted && persona != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.startTracking(persona)
            } else if (!granted && persona != null) {
                AnalyticsManager.trackRideStartAborted(RideStartAbortMethod.PRE_COMMIT)
            }
        }
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
    LaunchedEffect(hasLocationPermission, isInteractiveMap) {
        if (isInteractiveMap && hasLocationPermission && !hasCenteredOnLocation && uiState.pathPoints.isEmpty()) {
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

    // --- Camera follow (§1, §0 contract 1) ----------------------------------------------------
    //
    // Follow is a MODE, not a behaviour. Before this, a LaunchedEffect re-fired on every GPS fix
    // and animated to the newest point at zoom 17 unconditionally, so it overrode both a pan and a
    // zoom within a second or two — worst precisely in a group, where zooming out to see everyone
    // got yanked back before the screen could be read.
    //
    // The 1.8 branch had independently grown its own `followCamera` flag with the same intent. It
    // is gone: this policy is the better of the two — extracted, unit-tested, and stated in terms
    // iOS can implement — and it also handles the roster-focus case below, which the inline
    // version did not. The 1.8 ride camera (tilt and heading) now layers on top of it rather than
    // competing with it.
    //
    // rememberSaveable, not remember: Q1.2 decides follow SURVIVES backgrounding, because the
    // rider's last explicit intent is the best guess at their next one.
    var isFollowingRider by rememberSaveable { mutableStateOf(true) }
    val isRecording = uiState.trackingState != TrackingState.IDLE

    // Arm on the way INTO a ride, edge-triggered. A level-triggered "recording implies following"
    // would re-arm on the next recomposition and reproduce the original defect with extra steps.
    var wasRecording by rememberSaveable { mutableStateOf(isRecording) }
    LaunchedEffect(isRecording) {
        if (CameraFollowPolicy.armsOnRecordingStart(wasRecording, isRecording)) {
            isFollowingRider = true
        }
        wasRecording = isRecording
    }

    // Any user gesture drops into free-look. Maps Compose already reports why the camera moved, so
    // this needs no touch interception — and our own animateSafely calls report
    // DEVELOPER_ANIMATION, so follow cannot switch itself off on its first move.
    LaunchedEffect(cameraPositionState.isMoving, cameraPositionState.cameraMoveStartedReason, isInteractiveMap) {
        if (isInteractiveMap && cameraPositionState.isMoving &&
            CameraFollowPolicy.releasesFollow(cameraPositionState.cameraMoveStartedReason.toMoveCause())
        ) {
            isFollowingRider = false
        }
    }

    // --- Roster tap → focus that member (§4) ---------------------------------------------------
    //
    // A one-shot, consumed as it is applied: §4 is explicit that it must clear, or coming back to
    // Home later would re-focus a member the rider has moved on from.
    //
    // focusedMemberUid outlives the consumption on purpose — it is what tells the marker below to
    // open its info window, and that has to survive the recomposition the camera move causes.
    val pendingMemberFocus by app.pendingMemberFocus.collectAsState()
    var focusedMemberUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingMemberFocus) {
        val focus = pendingMemberFocus ?: return@LaunchedEffect
        if (!`in`.shvms.trackme.domain.group.MemberFocusPolicy.shouldApply(focus)) return@LaunchedEffect
        if (!isInteractiveMap) {
            explicitGroupMap = true
            // Let the explicit-map state commit before the one-shot is consumed. Camera state is
            // hoisted, so the move can be prepared on this frame and the map receives it on mount.
            withFrameNanos { }
        }
        // §4: "focusing a member is a camera move that must NOT be immediately undone by follow-me.
        // It should put the camera into free-look, exactly as a manual pan would." Without this the
        // next GPS fix drags the camera straight back to the rider — §1's defect, wearing a hat.
        isFollowingRider = CameraFollowPolicy.onFocusedElsewhere()
        focusedMemberUid = focus.uid
        cameraPositionState.animateSafely {
            CameraUpdateFactory.newLatLngZoom(
                com.google.android.gms.maps.model.LatLng(focus.lat, focus.lng),
                CameraFollowPolicy.RECENTRE_ZOOM,
            )
        }
        app.consumePendingMemberFocus()
    }

    // Ride over: put the map back the way it was found.
    //
    // The 1.8 ride camera pitches and turns the map while recording, so without this the map keeps
    // a 45° pitch and the last leg's bearing after the ride ends, and the only way back is the
    // compass — the app appears to have permanently changed its map. IDLE only: GPS_LOST,
    // GPS_DISABLED and STORAGE_LOW are interrupted rides, not finished ones.
    //
    // Deliberately does NOT re-arm follow. §1 Q1.1 is "button only", and the next ride re-arms
    // through armsOnRecordingStart anyway, so touching the flag here would only weaken that rule.
    LaunchedEffect(uiState.trackingState, isInteractiveMap) {
        if (!isInteractiveMap) return@LaunchedEffect
        if (uiState.trackingState != TrackingState.IDLE) return@LaunchedEffect
        if (cameraPositionState.position.tilt <= 0.5f) return@LaunchedEffect
        cameraPositionState.animateSafely {
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(cameraPositionState.position)
                    .tilt(0f)
                    .bearing(0f)
                    .build()
            )
        }
    }

    LaunchedEffect(uiState.pathPoints, isFollowingRider, isRecording) {
        if (!isInteractiveMap) return@LaunchedEffect
        val move = CameraFollowPolicy.moveFor(
            following = isFollowingRider,
            isRecording = uiState.trackingState == TrackingState.TRACKING,
            hasTarget = uiState.pathPoints.isNotEmpty(),
        )
        if (move == CameraFollowPolicy.FollowMove.KeepZoom) {
            val lastPoint = uiState.pathPoints.last()
            // Built FROM the current position, so zoom is carried over untouched. §1 is explicit
            // that a rider who zoomed out to 14 to see the group and is still following stays at
            // 14; the 1.8 branch's version forced zoom 17 here and reintroduced exactly that
            // defect. Only tilt and bearing are set, which is the ride camera's whole contribution.
            val tilt = when (uiState.trackingState) {
                TrackingState.TRACKING -> RideCameraPolicy.RIDING_TILT
                TrackingState.PAUSED -> RideCameraPolicy.PAUSED_TILT
                else -> cameraPositionState.position.tilt
            }
            val bearing = if (uiState.trackingState == TrackingState.TRACKING) {
                RideCameraPolicy.headingOf(uiState.pathPoints)
            } else {
                cameraPositionState.position.bearing
            }
            cameraPositionState.animateSafely {
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(cameraPositionState.position)
                        .target(lastPoint)
                        .tilt(tilt)
                        .bearing(bearing)
                        .build()
                )
            }
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

    var dashboardEntryTracked by remember { mutableStateOf(false) }
    var dashboardInsightTracked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        presentationMode,
        uiState.dashboardSummaryResolved,
        uiState.isDashboardReconciling,
        uiState.dashboardSummary.historyBucket,
    ) {
        if (presentationMode != HomePresentationMode.IDLE_DASHBOARD) {
            dashboardEntryTracked = false
            dashboardInsightTracked = null
        } else if (uiState.dashboardSummaryResolved &&
            !uiState.isDashboardReconciling &&
            !dashboardEntryTracked
        ) {
            AnalyticsManager.trackHomeDashboardViewed(uiState.dashboardSummary.historyBucket)
            dashboardEntryTracked = true
        }
    }
    LaunchedEffect(presentationMode, uiState.dashboardSummary.insight?.analyticsValue) {
        val type = uiState.dashboardSummary.insight?.analyticsValue
        if (presentationMode == HomePresentationMode.IDLE_DASHBOARD &&
            type != null && dashboardInsightTracked != type
        ) {
            AnalyticsManager.trackHomeInsightShown(type)
            dashboardInsightTracked = type
        }
    }
    LaunchedEffect(presentationMode, uiState.dashboardSummary.latestActivity?.localId) {
        if (presentationMode == HomePresentationMode.IDLE_DASHBOARD) {
            uiState.dashboardSummary.latestActivity?.localId?.let(viewModel::loadDashboardRoute)
        }
    }

    fun beginDashboardStart(persona: RidePersona, method: ActivityStartMethod) {
        AnalyticsManager.trackActivityStartCtaTapped(persona, method)
        if (!hasLocationPermission) {
            pendingStartPersona = persona
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.startTracking(persona)
    }

    if (pendingStartPersona != null && !hasLocationPermission) {
        AlertDialog(
            onDismissRequest = {
                pendingStartPersona = null
                AnalyticsManager.trackRideStartAborted(RideStartAbortMethod.PRE_COMMIT)
            },
            title = { Text(strings.locationPermissionRequired) },
            text = { Text(strings.locationPermissionDesc) },
            confirmButton = {
                Button(onClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    )
                }) { Text(strings.grantPermission) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingStartPersona = null
                    AnalyticsManager.trackRideStartAborted(RideStartAbortMethod.PRE_COMMIT)
                }) { Text(strings.cancel) }
            },
        )
    }

    if (showDashboardPersonaPicker) {
        AlertDialog(
            onDismissRequest = { showDashboardPersonaPicker = false },
            title = { Text(strings.dashboardChangeActivity) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RidePersona.entries.forEach { persona ->
                        TextButton(
                            onClick = {
                                viewModel.selectDashboardPersona(persona)
                                dashboardSelectionCameFromPicker = true
                                showDashboardPersonaPicker = false
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(persona.icon(), contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(strings.personaLabel(persona), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDashboardPersonaPicker = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            var mapType by remember { mutableStateOf(MapType.NORMAL) }
            var isTrafficEnabled by remember { mutableStateOf(false) }

            // Box scope, not map scope: the map draws member markers and the control stack draws
            // the group button, and both need the same session.
            val avatarCache = rememberMemberAvatarCache()
            // Keyed by groupId for the same reason the avatar cache is: a uid from a previous group
            // is never valid in the next one, and a tail that outlived its group would be exactly
            // the retained position history §5.1.4 forbids.
            val headingTailBuffer = rememberHeadingTailBuffer(groupSession.groupId)
            val canBlurMap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

            run {
                val groupSyncIntervalSec = groupSession.syncIntervalSec

                // Staleness is a function of wall-clock time, not of new data — a member who stops
                // syncing produces no recomposition, so without a tick their marker would stay
                // bright forever. One second is cheap and makes "2m ago" honest.
                var groupClockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(groupSession.isActive, isInteractiveMap) {
                    while (groupSession.isActive && isInteractiveMap) {
                        groupClockTick = System.currentTimeMillis()
                        delay(1_000L)
                    }
                }
                // §3.3: the bitmaps are per-session. A uid from a previous group is never valid in
                // the next one, and the cache would otherwise outlive its group.
                LaunchedEffect(groupSession.groupId) { avatarCache.clear() }

                // Hoisted, because the TrackMe wordmark has to sit on the same baseline as
                // Google's mark and Google's mark is drawn inside this padding. Two independent
                // expressions of the same number is how they drifted apart.
                val mapContentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                    // The map is full-bleed, so without the navigation-bar inset the attribution
                    // sits under the nav bar -- it is required to stay visible.
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        if (uiState.trackingState != TrackingState.IDLE) 88.dp else 0.dp
                )
                val isIdleDashboard = presentationMode == HomePresentationMode.IDLE_DASHBOARD
                val idleMapBlurRadiusPx = with(LocalDensity.current) { 18.dp.toPx() }

                GoogleMap(
                    modifier = Modifier
                        .fillMaxSize()
                        // Blur the frozen map before the scrim so street labels do not compete
                        // with the dashboard cards. RenderEffect is Android 12+; older devices
                        // keep the map covered by the heavier fallback scrim below.
                        .graphicsLayer {
                            // TASK-244/249, shvm: the idle backdrop is ~90% transparent — the map
                            // itself fades toward the app background rather than being buried
                            // under more black. That is deliberate: the spec's own amendment says
                            // "a heavier scrim would flatten it into grey", so the lever is the
                            // map's alpha, not the scrim's. The scrim below drops accordingly, or
                            // fading and then re-darkening would just be grey by another route.
                            alpha = if (isIdleDashboard) IDLE_MAP_ALPHA else 1f
                            renderEffect = if (isIdleDashboard && canBlurMap) {
                                BlurEffect(idleMapBlurRadiusPx, idleMapBlurRadiusPx)
                            } else {
                                null
                            }
                        },
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        // Idle Home owns a real map view but never owns location. The location
                        // layer and all controls switch on only after an explicit interactive mode.
                        isMyLocationEnabled = isInteractiveMap && hasLocationPermission,
                        mapType = mapType,
                        isTrafficEnabled = isTrafficEnabled,
                        // Null in light theme — Google's default basemap is already the light one.
                        mapStyleOptions = mapStyle,
                    ),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = false,
                        compassEnabled = isInteractiveMap,
                        mapToolbarEnabled = isInteractiveMap,
                        rotationGesturesEnabled = isInteractiveMap,
                        scrollGesturesEnabled = isInteractiveMap,
                        scrollGesturesEnabledDuringRotateOrZoom = isInteractiveMap,
                        tiltGesturesEnabled = isInteractiveMap,
                        zoomGesturesEnabled = isInteractiveMap,
                    ),
                    contentPadding = mapContentPadding
                ) {
                    if (isInteractiveMap) {
                    if (uiState.pathPoints.isNotEmpty()) {
                        Polyline(
                            points = uiState.pathPoints,
                            // Now that the basemap follows the theme, the route can too: the
                            // lighter dark-theme primary reads on the night map, the darker
                            // light-theme one reads on the day map. Pinned to TrackMeBlue this
                            // was only ever correct on the light basemap.
                            color = MaterialTheme.colorScheme.primary,
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

                    // --- Heading tails (§3, §0 contract 7) ---------------------------------
                    //
                    // In a group you have NO route line for anyone else, only their current dot.
                    // This answers the question the map cannot: which way are they coming from?
                    //
                    // In-memory and per-session by construction — see HeadingTailBuffer for why
                    // rememberSaveable would quietly breach §5.1.4 by writing other people's
                    // positions to disk.
                    val tails = headingTailBuffer.update(groupSession.positions, nowMs)
                    groupSession.positions.forEach { member ->
                        val samples = tails[member.uid].orEmpty()
                        val freshness = MemberMarkerPolicy.freshnessFor(
                            serverTsMillis = member.serverTsMillis,
                            nowMillis = nowMs,
                            syncIntervalSec = groupSyncIntervalSec,
                        )
                        if (!HeadingTail.shouldDraw(
                                // GroupWire already routes your own position to `ownPosition` and
                                // never into `positions`, so self is structurally absent here.
                                // Q3.2 is still satisfied, just one layer further up.
                                isSelf = false,
                                moving = member.moving,
                                // Auto-pause is not on the wire, and deliberately: the relay learns
                                // `riding`, not how the ride is going. `moving` is the transmitted
                                // signal, and it already reads false for a stationary rider — which
                                // is the observable case §3 asks to hide.
                                autoPaused = false,
                                isStale = freshness != MarkerFreshness.FRESH,
                                sampleCount = samples.size,
                            )
                        ) return@forEach

                        // Q3.1: a tapering POLYLINE, not 10 markers each. 10 dots x 12 members is
                        // 120 extra map objects on top of avatars and badges, and 1.7.0 §7.5 already
                        // names ~12 members as where the map degrades. One polyline per segment
                        // costs a fraction of that and reads as a trail rather than as samples.
                        val tint = `in`.shvms.trackme.ui.components.GroupMemberTint.colorFor(member.uid)
                        for (i in 0 until samples.size - 1) {
                            Polyline(
                                points = listOf(
                                    com.google.android.gms.maps.model.LatLng(samples[i].lat, samples[i].lng),
                                    com.google.android.gms.maps.model.LatLng(samples[i + 1].lat, samples[i + 1].lng),
                                ),
                                color = tint.copy(alpha = HeadingTail.alphaAt(i, samples.size)),
                                width = HeadingTail.widthAt(i, samples.size),
                            )
                        }
                    }

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
                        // §3.5, A37: the badge is a SEPARATE marker, never part of the avatar
                        // bitmap. §3.3 of 1.7.0 builds that bitmap once per member per session and
                        // is emphatic about why; tinting it by status would make it
                        // status-dependent and reintroduce exactly the per-change rebuild that rule
                        // exists to prevent. Badge bitmaps are cached by SEVERITY alone, so three
                        // serve the whole session however large the group.
                        val memberStatus = groupSession.statuses
                            .firstOrNull { it.uid == member.uid }
                            ?.let { RiderStatusCodec.parse(it.code) }

                        memberStatus?.let { parsed ->
                            SeverityBadgeMarker(
                                uid = member.uid,
                                position = point,
                                severity = parsed.severity,
                                dimmed = freshness == MarkerFreshness.STALE,
                            )
                        }

                        val markerState = rememberMarkerState(key = member.uid, position = point)

                        // §4/Q4.1: "focus the map and open that same sheet, so there is one detail
                        // surface rather than two." On Android that surface is this marker's info
                        // window — name and age, the same thing a marker tap already gives — so a
                        // roster tap lands on it rather than inventing a second one.
                        if (focusedMemberUid == member.uid) {
                            LaunchedEffect(member.uid, point) { markerState.showInfoWindow() }
                        }

                        Marker(
                            state = markerState,
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
                }
                // After the map, so it draws on top of it rather than under. Beside Google's own
                // mark and never over it — see MapAttribution.
                TrackMeMapAttribution(
                    modifier = Modifier.align(Alignment.BottomStart),
                    bottomOffset = mapContentPadding.calculateBottomPadding(),
                )
            }

            val scrimTopAlpha by animateFloatAsState(
                targetValue = when {
                    // The map is already faded to IDLE_MAP_ALPHA, so the scrim only has to give the
                    // deck a little depth. Without blur it keeps a touch more, because unblurred
                    // street labels still read faintly even at low alpha.
                    presentationMode == HomePresentationMode.IDLE_DASHBOARD && !canBlurMap -> 0.30f
                    presentationMode == HomePresentationMode.IDLE_DASHBOARD -> 0.18f
                    else -> 0.28f
                },
                animationSpec = if (animationsEnabled) tween(420, easing = LinearEasing) else snap(),
                label = "home_scrim_top",
            )
            val scrimBottomAlpha by animateFloatAsState(
                targetValue = when {
                    presentationMode == HomePresentationMode.IDLE_DASHBOARD && !canBlurMap -> 0.12f
                    presentationMode == HomePresentationMode.IDLE_DASHBOARD -> 0.06f
                    else -> 0f
                },
                animationSpec = if (animationsEnabled) tween(420, easing = LinearEasing) else snap(),
                label = "home_scrim_bottom",
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = scrimTopAlpha),
                                Color.Black.copy(alpha = scrimBottomAlpha),
                            )
                        )
                    )
            )

            val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            // **A29**: renders whenever the session is active, independent of `ActiveRideHudPanel`
            // — which only exists when tracking is not IDLE. A rider who stopped their ride but is
            // still in the group had no pill row at all before this, and §2.6 of 1.7.0 is explicit
            // that the person who got a flat tyre is the one the group most needs to see.
            var groupPresencePillShown by remember { mutableStateOf(false) }
            var pauseStartedElapsed by remember { mutableLongStateOf(0L) }
            var pauseCause by remember { mutableStateOf("") }
            if (groupSession.isActive && isInteractiveMap) {
                // A 1 Hz tick, because `elapsedRealtime()` is not observable state: without it the
                // pill would never appear when the threshold is crossed, and "Last shared 2m ago"
                // would freeze until some unrelated recomposition happened to occur. The map's
                // markers already tick at this rate for the same reason (HomeScreen.kt:408), so the
                // pill's age and the markers' ages advance together rather than drifting apart.
                var presenceTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(groupSession.isActive) {
                    while (groupSession.isActive) {
                        presenceTick = SystemClock.elapsedRealtime()
                        delay(1_000L)
                    }
                }
                val presencePill = GroupPresencePolicy.evaluate(
                    GroupPresencePolicy.Input(
                        sessionActive = true,
                        sessionStartedElapsed = groupSession.sessionStartedElapsed,
                        lastSuccessfulSyncElapsed = groupSession.lastSuccessfulSyncElapsed,
                        lastOwnPositionAckElapsed = groupSession.lastOwnPositionAckElapsed,
                        lastFailureKind = groupSession.lastSyncFailureKind,
                        isSharingPosition = groupSession.isSharingPosition,
                        isRideRecording = uiState.trackingState != TrackingState.IDLE,
                        selfStatus = groupSession.selfStatusCode?.let { RiderStatusCodec.parse(it) },
                        selfStatusAcknowledged = groupSession.selfStatusAcknowledged,
                        syncIntervalSec = groupSession.syncIntervalSec,
                        nowElapsed = presenceTick,
                    ),
                )
                // The shield is suppressed only for the states that contradict it — a status
                // reminder says nothing about connectivity and must not silence it.
                groupPresencePillShown = presencePill is GroupPresencePolicy.Pill.Paused ||
                    presencePill is GroupPresencePolicy.Pill.PausedWithUnsentAlert ||
                    presencePill is GroupPresencePolicy.Pill.NotSharing

                // §7: this is the event that finally distinguishes OUR outages from riders' dead
                // zones — today we cannot tell them apart at all. Emitted on the way OUT of a pause
                // rather than into one, because the duration is the interesting half and it does
                // not exist until the pause ends.
                val pausedCause = when (presencePill) {
                    is GroupPresencePolicy.Pill.Paused -> presencePill.cause
                    is GroupPresencePolicy.Pill.PausedWithUnsentAlert -> presencePill.cause
                    else -> null
                }
                LaunchedEffect(pausedCause) {
                    if (pausedCause != null) {
                        pauseStartedElapsed = SystemClock.elapsedRealtime()
                        pauseCause = pausedCause.name.lowercase()
                    } else if (pauseStartedElapsed > 0L) {
                        AnalyticsManager.trackGroupPresencePaused(
                            cause = pauseCause,
                            durationBucket = pauseDurationBucket(
                                SystemClock.elapsedRealtime() - pauseStartedElapsed,
                            ),
                        )
                        pauseStartedElapsed = 0L
                    }
                }

                GroupPresenceHost(
                    pill = presencePill,
                    strings = strings,
                    onOpenCommunity = onOpenCommunity,
                    onClearStatus = { app.groupSessionManager.clearStatus() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPadding + 16.dp),
                )
            }

            if (isInteractiveMap) {
            Column(

                modifier = Modifier.align(Alignment.TopEnd).padding(top = topPadding + 80.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                MapLayerHorizontalDrawerButton(
                    currentMapType = mapType,
                    onMapTypeSelected = { mapType = it },
                    isTrafficEnabled = isTrafficEnabled,
                    onTrafficToggle = { isTrafficEnabled = !isTrafficEnabled }
                )

                MapControlCircleButton(
                    icon = Icons.Default.MyLocation,
                    contentDescription = strings.recenterMap,
                    enabled = hasLocationPermission,
                    onClick = {
                        // §1: this is the ONLY thing that re-arms follow (Q1.1 — button only,
                        // never a timer). It also gives the control a real job rather than a
                        // redundant one, since the camera no longer chases the rider by itself.
                        isFollowingRider = CameraFollowPolicy.onRecentrePressed()
                        val target = uiState.pathPoints.lastOrNull()
                        if (target != null) {
                            coroutineScope.launch {
                                // Recentring is the one move that DOES set zoom — §1 says zoom is
                                // forced only on re-arm. Tilt and bearing come from the ride state
                                // so the camera lands in its final pose in one move; recentring
                                // flat and letting the follow effect pitch it a frame later reads
                                // as two separate moves.
                                val tilt = when (uiState.trackingState) {
                                    TrackingState.TRACKING -> RideCameraPolicy.RIDING_TILT
                                    TrackingState.PAUSED -> RideCameraPolicy.PAUSED_TILT
                                    else -> cameraPositionState.position.tilt
                                }
                                val bearing = if (uiState.trackingState == TrackingState.TRACKING) {
                                    RideCameraPolicy.headingOf(uiState.pathPoints)
                                } else {
                                    cameraPositionState.position.bearing
                                }
                                cameraPositionState.animateSafely {
                                    CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.Builder()
                                            .target(target)
                                            .zoom(CameraFollowPolicy.RECENTRE_ZOOM)
                                            .tilt(tilt)
                                            .bearing(bearing)
                                            .build()
                                    )
                                }
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

                // Third slot, and last in the stack on purpose. It used to sit on top, so the
                // moment a group went live the other two controls jumped down under the user's
                // thumb. Placed last, its appearance moves nothing. It renders only while a group
                // is active — GroupMapButton returns early otherwise.
                GroupMapButton(
                    session = groupSession,
                    // The badge counts EVERYONE in the group, you included. It was showing
                    // others-only, so a group of two read "1" — which looks like a bug rather than
                    // a definition. The accessibility sentence still says "visible to N people",
                    // where N deliberately excludes you, because audience and headcount are
                    // genuinely different numbers.
                    memberCount = groupSession.roster.size.coerceAtLeast(0),
                    audienceCount = (groupSession.roster.size - 1).coerceAtLeast(0),
                    onClick = onOpenCommunity,
                )

                // No compass control here.
                //
                // Google's own compass is already enabled on this map (`compassEnabled` in
                // MapUiSettings) and is visible whenever the map is turned, which during a ride is
                // always — the ride camera sets both a heading bearing and a tilt. So this button
                // was a second compass in the one slot next to layers and recentre, shown exactly
                // when it duplicated the platform's. Removed on shvm's call 2026-08-27.
            }
            }

            if (presentationMode == HomePresentationMode.EXPLICIT_GROUP_MAP) {
                MapControlCircleButton(
                    icon = Icons.Default.Close,
                    contentDescription = strings.close,
                    onClick = { explicitGroupMap = false },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = topPadding + 16.dp, start = 12.dp),
                )
            } else if (presentationMode == HomePresentationMode.ACTIVE_TRACKING_MAP) {
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
                    groupPresencePillShown = groupPresencePillShown,
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
                            // No optimistic "Saving ride…" here. Only the service knows whether the
                            // ride survives, and announcing an intent that the outcome then
                            // contradicts is what produced two stacked, opposing messages.
                            viewModel.stopTracking()
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
                            messenger.show(
                                strings.groupLiveShareBlocked,
                                duration = SnackbarDuration.Long,
                            )
                        } else {
                            viewModel.startLiveShare(durationMinutes = 1440, stopOnRideEnd = true)
                        }
                    },
                    onStopShare = {
                        viewModel.stopLiveShare()
                        messenger.show(strings.liveShareStoppedToast)
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
                            messenger.show(strings.linkCopied)
                        }
                    },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            val dashboardVisible = presentationMode == HomePresentationMode.IDLE_DASHBOARD
            AnimatedVisibility(
                visible = dashboardVisible,
                modifier = Modifier.matchParentSize(),
                enter = if (animationsEnabled) {
                    slideInVertically(
                        animationSpec = tween(420, delayMillis = 320, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it },
                    ) + fadeIn(tween(300, delayMillis = 320))
                } else EnterTransition.None,
                exit = if (animationsEnabled) {
                    slideOutVertically(
                        animationSpec = tween(420, easing = FastOutSlowInEasing),
                        targetOffsetY = { -it },
                    ) + fadeOut(tween(300, easing = FastOutLinearInEasing))
                } else ExitTransition.None,
            ) {
                HomeDashboardScreen(
                    summary = uiState.dashboardSummary,
                    gamificationLevel = uiState.gamificationLevel,
                    gamificationTotalActiveMinutes = uiState.gamificationTotalActiveMinutes,
                    gamificationUnlockedAchievements = uiState.gamificationUnlockedAchievements,
                    gamificationNewLevel = uiState.gamificationNewLevel,
                    gamificationNewAchievements = uiState.gamificationNewAchievements,
                    routePoints = dashboardRoute,
                    isSummaryResolved = uiState.dashboardSummaryResolved,
                    isReconciling = uiState.isDashboardReconciling,
                    groupActive = groupSession.isActive,
                    groupMemberCount = groupSession.roster.size,
                    syncNeedsAction = uiState.dashboardSyncNeedsAction,
                    isOffline = isOffline,
                    locationPermissionRevoked = locationPermissionRevokedNotice,
                    imperial = imperialUnits == "imperial",
                    onOpenRecent = { localId, persona ->
                        AnalyticsManager.trackHomeRecentActivityOpened(persona)
                        onOpenRideDetail(localId)
                    },
                    onOpenHistory = onOpenHistory,
                    onOpenCommunity = onOpenCommunity,
                    onOpenGroupMap = {
                        AnalyticsManager.trackHomeGroupMapOpened()
                        explicitGroupMap = true
                    },
                    // TASK-254: record which sheet the rider asked for, then switch to the tab
                    // that owns it. The sheets are deliberately not rebuilt here -- two
                    // implementations of a consent-bearing sheet is how the two drift apart.
                    onCreateGroup = {
                        app.requestGroupAction(TrackMeApp.GroupEntryAction.CREATE)
                        onOpenCommunity()
                    },
                    onJoinGroup = {
                        app.requestGroupAction(TrackMeApp.GroupEntryAction.JOIN)
                        onOpenCommunity()
                    },
                    onOpenSettings = { openAppSettings(context) },
                    onDismissPermissionNotice = app::dismissLocationPermissionRevokedNoticeForSession,
                    onAcknowledgeGamificationReveals = {
                        viewModel.acknowledgeGamificationReveals(uiState.gamificationNewLevel, uiState.gamificationNewAchievements)
                    },
                    scrollToTopRequest = scrollToTopRequest,
                )
            }

            AnimatedVisibility(
                visible = dashboardVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (animationsEnabled) fadeIn(tween(300, delayMillis = 320)) else EnterTransition.None,
                exit = if (animationsEnabled) fadeOut(tween(300)) else ExitTransition.None,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RadialStartRideButton(
                        onOpenAllPersonas = { showDashboardPersonaPicker = true },
                        onStartRide = { persona ->
                            val method = if (dashboardSelectionCameFromPicker ||
                                persona != uiState.selectedDashboardPersona
                            ) {
                                ActivityStartMethod.PERSONA_PICKER
                            } else ActivityStartMethod.PRIMARY
                            dashboardSelectionCameFromPicker = false
                            viewModel.selectDashboardPersona(persona)
                            beginDashboardStart(persona, method)
                        },
                        onAbortRideStart = { method ->
                            AnalyticsManager.trackRideStartAborted(method)
                        },
                        preselectedPersona = uiState.selectedDashboardPersona,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp),
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
                            // No optimistic "Saving ride…" here. Only the service knows whether the
                            // ride survives, and announcing an intent that the outcome then
                            // contradicts is what produced two stacked, opposing messages.
                            viewModel.stopTracking()
                        }) {
                            Text(strings.saveAnyway)
                        }
                    }
                )
            }
        }
    }
}
