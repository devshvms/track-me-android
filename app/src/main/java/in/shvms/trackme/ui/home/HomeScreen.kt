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
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.service.TrackingState
import com.google.android.gms.maps.CameraUpdateFactory
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
import `in`.shvms.trackme.ui.home.components.MapLayerHorizontalDrawerButton
import `in`.shvms.trackme.ui.home.components.MapControlCircleButton
import `in`.shvms.trackme.domain.model.RidePersona

@Composable
fun HomeScreen(
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
    var hasLocationPermission by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    val receiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                android.widget.Toast.makeText(context, strings.rideSaved, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(context) {
        val filter = android.content.IntentFilter("in.shvms.trackme.RIDE_SAVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
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
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    val cameraPositionState = rememberCameraPositionState()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && uiState.pathPoints.isEmpty()) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
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
                            color = Color.Blue,
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
                RadialStartRideButton(
                    onStartRide = { persona ->
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
                            modifier = Modifier.alpha(blinkAlpha),
                            containerColor = Color(0xFF4CAF50),
                            contentColor = Color.White,
                            shape = androidx.compose.foundation.shape.CircleShape
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Icon(Icons.Default.Share, contentDescription = strings.liveShareButton, modifier = Modifier.size(16.dp))
                                Text(countdownText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
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
                    isEmergencyActive = uiState.isEmergencyActive,
                    liveShareState = uiState.liveShareState,
                    isOffline = isOffline,
                    onPauseToggle = {
                        if (uiState.trackingState == TrackingState.TRACKING) {
                            viewModel.pauseTracking(context)
                        } else {
                            viewModel.startTracking(context, uiState.selectedPersona)
                        }
                    },
                    onStopRide = {
                        viewModel.stopTracking(context)
                        android.widget.Toast.makeText(context, strings.savingRide, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onTriggerSos = { viewModel.triggerEmergency() },
                    onStopSos = { viewModel.stopEmergency() },
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
                                putExtra(android.content.Intent.EXTRA_TEXT, "${strings.liveShareReadyActive}: $shareLink")
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
                        .border(4.dp, Color.Red)
                ) {
                    Text(
                        text = strings.emergencyBroadcastActive,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .border(2.dp, Color.Red)
                            .padding(8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
}

@Composable
fun rememberIsOffline(): Boolean {
    val context = LocalContext.current
    var isOffline by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        fun checkOffline(): Boolean {
            val network = connectivityManager?.activeNetwork ?: return true
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return true
            return !(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        isOffline = checkOffline()

        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isOffline = false
            }
            override fun onLost(network: android.net.Network) {
                isOffline = true
            }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                networkCapabilities: android.net.NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                isOffline = !hasInternet
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {}

        onDispose {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }
    return isOffline
}

