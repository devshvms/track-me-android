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
import `in`.shvms.trackme.ui.components.SwipeToTriggerSlider
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
import `in`.shvms.trackme.ui.components.rememberIsOffline
import `in`.shvms.trackme.ui.home.components.MapLayerHorizontalDrawerButton
import `in`.shvms.trackme.ui.home.components.MapControlCircleButton
import `in`.shvms.trackme.domain.model.RidePersona

private const val LAST_CAMERA_LAT_KEY = "last_camera_lat"
private const val LAST_CAMERA_LNG_KEY = "last_camera_lng"
private const val LAST_CAMERA_ZOOM_KEY = "last_camera_zoom"

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

@Composable
fun HomeScreen(
    onNavigateToEmergencySetup: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(
            (LocalContext.current.applicationContext as TrackMeApp).trackingManager,
            (LocalContext.current.applicationContext as TrackMeApp).emergencyManager,
            (LocalContext.current.applicationContext as TrackMeApp).authManager,
            (LocalContext.current.applicationContext as TrackMeApp).database.emergencyDao(),
            (LocalContext.current.applicationContext as TrackMeApp).liveShareManager
        )
    )
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val app = context.applicationContext as TrackMeApp
    val uiPreferences = remember {
        context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
    }
    var showStartRideHint by remember {
        mutableStateOf(!uiPreferences.getBoolean("start_ride_hint_seen", false))
    }
    var showDiscardRideDialog by remember { mutableStateOf(false) }
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
    val smsPermissionRevoked by app.smsPermissionRevokedNotice.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

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

    val receiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                android.widget.Toast.makeText(context, strings.rideSaved, android.widget.Toast.LENGTH_SHORT).show()
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

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            val permissionsToRequest = mutableListOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

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
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(com.google.android.gms.maps.model.LatLng(loc.latitude, loc.longitude), 17f)
                            )
                        }
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    LaunchedEffect(uiState.pathPoints) {
        if (uiState.trackingState == TrackingState.TRACKING && uiState.pathPoints.isNotEmpty()) {
            val lastPoint = uiState.pathPoints.last()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(lastPoint, 17f))
        }
    }

    var countdownText by remember { mutableStateOf("") }
    
    LaunchedEffect(uiState.liveShareState) {
        if (uiState.liveShareState.status == LiveShareStatus.ACTIVE && uiState.liveShareState.expiresAt != null) {
            while(true) {
                val duration = Duration.between(Instant.now(), uiState.liveShareState.expiresAt)
                if (duration.isNegative || duration.isZero) {
                    countdownText = strings.expired
                    viewModel.stopLiveShare(context, "Max ride duration reached, stopping.")
                    break
                }
                val hours = duration.toHours()
                val minutes = duration.toMinutesPart()
                val seconds = duration.toSecondsPart()
                countdownText = if (hours > 0) {
                    String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
                }
                delay(1000)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            var mapType by remember { mutableStateOf(MapType.NORMAL) }
            var isTrafficEnabled by remember { mutableStateOf(false) }

            if (hasLocationPermission) {
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
                    contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp, bottom = if (uiState.trackingState != TrackingState.IDLE) 88.dp else 0.dp)
                ) {
                    if (uiState.pathPoints.isNotEmpty()) {
                        Polyline(
                            points = uiState.pathPoints,
                            color = TrackMeBlue,
                            width = 10f
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
                            val permissionsToRequest = mutableListOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                        }) {
                            Text(strings.grantPermission)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = android.net.Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            context.startActivity(intent)
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
                MapLayerHorizontalDrawerButton(
                    currentMapType = mapType,
                    onMapTypeSelected = { mapType = it },
                    isTrafficEnabled = isTrafficEnabled,
                    onTrafficToggle = { isTrafficEnabled = !isTrafficEnabled }
                )

                MapControlCircleButton(
                    icon = Icons.Default.MyLocation,
                    contentDescription = "Recenter",
                    onClick = {
                        val target = uiState.pathPoints.lastOrNull()
                        if (target != null) {
                            coroutineScope.launch {
                                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, 17f))
                            }
                        } else if (hasLocationPermission) {
                            try {
                                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                    .addOnSuccessListener { loc ->
                                        if (loc != null) {
                                            coroutineScope.launch {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(
                                                        com.google.android.gms.maps.model.LatLng(loc.latitude, loc.longitude),
                                                        17f
                                                    )
                                                )
                                            }
                                        } else {
                                            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                                                if (lastLoc != null) {
                                                    coroutineScope.launch {
                                                        cameraPositionState.animate(
                                                            CameraUpdateFactory.newLatLngZoom(
                                                                com.google.android.gms.maps.model.LatLng(lastLoc.latitude, lastLoc.longitude),
                                                                17f
                                                            )
                                                        )
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
                    contentDescription = "Compass North",
                    onClick = {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newCameraPosition(
                                    com.google.android.gms.maps.model.CameraPosition.Builder(cameraPositionState.position)
                                        .bearing(0f)
                                        .tilt(0f)
                                        .build()
                                )
                            )
                        }
                    }
                )
            }

            // Idle State: Radial Persona Start Button
            if (uiState.trackingState == TrackingState.IDLE) {
                if (hasLocationPermission && showStartRideHint) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
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
                        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback if device doesn't support this intent
                            }
                        }
                        viewModel.startTracking(context, persona)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )


                // Only show active sharing indicator if a live share session is actively running while idle
                if (uiState.liveShareState.status == LiveShareStatus.ACTIVE) {
                    val infiniteTransition = rememberInfiniteTransition(label = "shareBlink")
                    val blinkAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "blinkAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 80.dp, end = 16.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                viewModel.stopLiveShare(context)
                                android.widget.Toast.makeText(context, "Live Location Sharing Stopped", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            // C1: semantic — green here means "a live share is ACTIVE", not
                            // brand accent, so it stays green via the named success token
                            // rather than moving to cyan with the other brand actions.
                            containerColor = SuccessGreen,
                            // Keep this fixed token pair together under Material You; a
                            // wallpaper-derived onSecondary may not contrast with SuccessGreen.
                            contentColor = Navy900,
                            shape = androidx.compose.foundation.shape.CircleShape
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Icon(Icons.Default.Share, contentDescription = strings.liveShareButton, modifier = Modifier.size(16.dp))
                                Text(countdownText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .border(
                                    width = 3.dp,
                                    color = Color.White.copy(alpha = blinkAlpha),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                )
                        )
                    }
                }
            } else {
                // Active Recording / Non-Ideal State HUD Panel
                ActiveRideHudPanel(
                    trackingState = uiState.trackingState,
                    distanceText = uiState.distanceText,
                    durationText = uiState.durationText,
                    speedText = uiState.speedText,
                    selectedPersona = uiState.selectedPersona,
                    isAutoPaused = uiState.isAutoPaused,
                    timeSinceLastGps = uiState.timeSinceLastGps,
                    isEmergencyReady = uiState.isEmergencyReady,
                    isEmergencyPermissionRevoked = smsPermissionRevoked,
                    sosPermissionRevokedMessage = strings.sosPermissionRevoked,
                    reEnableSosDescription = strings.configureEmergencySetup,
                    dismissSosPermissionDescription = strings.close,
                    isEmergencyActive = uiState.isEmergencyActive,
                    liveShareState = uiState.liveShareState,
                    isAuthenticated = uiState.isAuthenticated,
                    liveShareAuthRequired = strings.liveShareAuthRequired,
                    isOffline = isOffline,
                    onPauseToggle = {
                        if (uiState.trackingState == TrackingState.TRACKING) {
                            viewModel.pauseTracking(context)
                        } else {
                            viewModel.startTracking(context, uiState.selectedPersona)
                        }
                    },
                    onStopRide = {
                        if (uiState.distanceMeters < `in`.shvms.trackme.service.TrackingService.JUNK_RIDE_DISTANCE_METERS &&
                            uiState.durationMillis < `in`.shvms.trackme.service.TrackingService.JUNK_RIDE_DURATION_MILLIS
                        ) {
                            showDiscardRideDialog = true
                        } else {
                            viewModel.stopTracking(context)
                            android.widget.Toast.makeText(context, strings.savingRide, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTriggerSos = { viewModel.triggerEmergency() },
                    onStopSos = { viewModel.stopEmergency() },
                    onOpenEmergencySetup = onNavigateToEmergencySetup,
                    onDismissSosPermissionNotice = { app.setSmsPermissionRevokedNotice(false) },
                    onStartShare = {
                        viewModel.startLiveShare(context, durationMinutes = 1440, stopOnRideEnd = true)
                    },
                    onStopShare = {
                        viewModel.stopLiveShare(context)
                        android.widget.Toast.makeText(context, "Live Location Sharing Stopped", android.widget.Toast.LENGTH_SHORT).show()
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
                            android.widget.Toast.makeText(context, "Shareable link copied!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }

            if (uiState.isEmergencyActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(4.dp, TrackMeRed)
                ) {
                    Text(
                        text = strings.emergencyBroadcastActive,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .border(2.dp, TrackMeRed)
                            .padding(8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (showDiscardRideDialog) {
                AlertDialog(
                    onDismissRequest = { showDiscardRideDialog = false },
                    title = { Text(strings.discardRideTitle) },
                    text = { Text(strings.discardRideMessage) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDiscardRideDialog = false
                            viewModel.stopTracking(context, discardNearEmptyRide = true)
                        }) {
                            Text(strings.discardRide)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDiscardRideDialog = false
                            viewModel.stopTracking(context)
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
