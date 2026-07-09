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
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(strings.rideSaved)
                }
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
    
    LaunchedEffect(uiState.pathPoints) {
        if (uiState.trackingState == TrackingState.TRACKING && uiState.pathPoints.isNotEmpty()) {
            val lastPoint = uiState.pathPoints.last()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(lastPoint, 17f))
        }
    }

    var showStartShareDialog by remember { mutableStateOf(false) }
    var showActiveShareDialog by remember { mutableStateOf(false) }
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
            
            if (uiState.trackingState == TrackingState.TRACKING && uiState.timeSinceLastGps > 10000L) {
                val seconds = uiState.timeSinceLastGps / 1000L
                val timeString = if (seconds > 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPadding + 16.dp, start = 16.dp, end = 16.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "No GPS signal for $timeString",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            var showMapOptions by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = topPadding + 80.dp, end = 12.dp)) {
                FloatingActionButton(
                    onClick = { showMapOptions = true },
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.Map, contentDescription = strings.mapLayers, modifier = Modifier.size(20.dp))
                }
                
                DropdownMenu(
                    expanded = showMapOptions,
                    onDismissRequest = { showMapOptions = false }
                ) {
                    DropdownMenuItem(text = { Text(strings.mapNormal) }, onClick = { mapType = MapType.NORMAL; showMapOptions = false })
                    DropdownMenuItem(text = { Text(strings.mapSatellite) }, onClick = { mapType = MapType.SATELLITE; showMapOptions = false })
                    DropdownMenuItem(text = { Text(strings.mapTerrain) }, onClick = { mapType = MapType.TERRAIN; showMapOptions = false })
                    DropdownMenuItem(text = { Text(strings.mapHybrid) }, onClick = { mapType = MapType.HYBRID; showMapOptions = false })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (isTrafficEnabled) strings.hideTraffic else strings.showTraffic) },
                        onClick = { isTrafficEnabled = !isTrafficEnabled; showMapOptions = false }
                    )
                }
            }

            // Emergency Trigger
            if (uiState.trackingState != TrackingState.IDLE) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (uiState.isEmergencyReady) {
                            if (uiState.isEmergencyActive) {
                                Button(
                                    onClick = { viewModel.stopEmergency() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Red,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.height(56.dp).fillMaxWidth()
                                ) {
                                    Text(strings.stopEmergencyBroadcast, style = MaterialTheme.typography.labelLarge)
                                }
                            } else {
                                SwipeToTriggerSlider(
                                    onTriggered = { viewModel.triggerEmergency() }
                                )
                            }
                        }
                    }
                }
            }

            // Live Share Button
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
            val isShareActiveWithoutTracking = uiState.liveShareState.status == LiveShareStatus.ACTIVE && uiState.trackingState == TrackingState.IDLE

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 88.dp, end = 16.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        if (uiState.liveShareState.status == LiveShareStatus.ACTIVE) {
                            showActiveShareDialog = true
                        } else {
                            showStartShareDialog = true
                        }
                    },
                    modifier = Modifier.alpha(if (isShareActiveWithoutTracking) blinkAlpha else 1f),
                    containerColor = if (uiState.liveShareState.status == LiveShareStatus.ACTIVE) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (uiState.liveShareState.status == LiveShareStatus.ACTIVE) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    if (uiState.liveShareState.status == LiveShareStatus.ACTIVE) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.Share, contentDescription = strings.liveShareButton, modifier = Modifier.size(16.dp))
                            Text(countdownText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Icon(Icons.Default.Share, contentDescription = strings.liveShareButton)
                    }
                }
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
        
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(strings.rideStats, style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Text(uiState.distanceText, style = MaterialTheme.typography.titleMedium)
                Text(uiState.durationText, style = MaterialTheme.typography.titleMedium)
                Text(uiState.speedText, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AnimatedContent(
                targetState = uiState.trackingState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                },
                label = "TrackingStateAnimation"
            ) { state ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (state) {
                        TrackingState.IDLE -> {
                            FilledIconButton(
                                onClick = { viewModel.startTracking(context) },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = strings.startTracking, modifier = Modifier.size(32.dp))
                            }
                        }
                        TrackingState.TRACKING -> {
                            FilledIconButton(
                                onClick = { viewModel.pauseTracking(context) },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = strings.pauseTracking, modifier = Modifier.size(32.dp))
                            }
                        }
                        TrackingState.PAUSED, TrackingState.GPS_LOST -> {
                            FilledIconButton(
                                onClick = { viewModel.startTracking(context) },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = strings.resumeTracking, modifier = Modifier.size(32.dp))
                            }
                            FilledIconButton(
                                onClick = { 
                                    viewModel.stopTracking(context)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(strings.savingRide)
                                    }
                                },
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = strings.stopTracking, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStartShareDialog) {
        var shareStopMode by remember { mutableStateOf(0) } // 0 = time, 1 = ride stop
        var selectedHours by remember { mutableStateOf(0f) }
        var selectedMinutes by remember { mutableStateOf(45f) }
        
        val totalMinutes = (selectedHours.toInt() * 60) + selectedMinutes.toInt()
        val summaryText = when {
            shareStopMode == 1 -> strings.activeUntilRideEnds
            totalMinutes == 0 -> strings.selectDurationWarn
            selectedHours.toInt() > 0 && selectedMinutes.toInt() > 0 -> "${strings.activeForPrefix}: ${selectedHours.toInt()}h ${selectedMinutes.toInt()}m"
            selectedHours.toInt() > 0 -> "${strings.activeForPrefix}: ${selectedHours.toInt()} hr"
            else -> "${strings.activeForPrefix}: ${selectedMinutes.toInt()} mins"
        }
        val isDurationValid = shareStopMode == 1 || totalMinutes > 0

        AlertDialog(
            onDismissRequest = { showStartShareDialog = false },
            title = {
                Text(
                    text = strings.startLiveShareTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Highlighted Summary Banner
                    Surface(
                        color = if (isDurationValid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDurationValid) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Text(
                        text = strings.selectExpirationMode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Mode 0: Custom Time Card
                    Card(
                        onClick = { shareStopMode = 0 },
                        colors = CardDefaults.cardColors(
                            containerColor = if (shareStopMode == 0) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        ),
                        border = BorderStroke(
                            width = if (shareStopMode == 0) 2.dp else 1.dp,
                            color = if (shareStopMode == 0) MaterialTheme.colorScheme.primary else Color.Transparent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = shareStopMode == 0,
                                    onClick = { shareStopMode = 0 },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.afterSpecificTime,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (shareStopMode == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            if (shareStopMode == 0) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(
                                    modifier = Modifier
                                        .padding(start = 28.dp, end = 4.dp)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Hours Slider Compact
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(strings.hours, style = MaterialTheme.typography.labelMedium)
                                            Text(
                                                text = "${selectedHours.toInt()} h",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = selectedHours,
                                            onValueChange = { selectedHours = it },
                                            valueRange = 0f..23f,
                                            steps = 22,
                                            modifier = Modifier.height(32.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Minutes Slider Compact (5 min interval)
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(strings.minutes, style = MaterialTheme.typography.labelMedium)
                                            Text(
                                                text = "${selectedMinutes.toInt()} m",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = selectedMinutes,
                                            onValueChange = { selectedMinutes = it },
                                            valueRange = 0f..55f,
                                            steps = 10,
                                            modifier = Modifier.height(32.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Mode 1: When Ride Ends Card
                    Card(
                        onClick = { shareStopMode = 1 },
                        colors = CardDefaults.cardColors(
                            containerColor = if (shareStopMode == 1) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        ),
                        border = BorderStroke(
                            width = if (shareStopMode == 1) 2.dp else 1.dp,
                            color = if (shareStopMode == 1) MaterialTheme.colorScheme.primary else Color.Transparent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = shareStopMode == 1,
                                onClick = { shareStopMode = 1 },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = strings.whenRideEnds,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (shareStopMode == 1) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = strings.autoStopWhenRideEnds,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val duration = if (shareStopMode == 0) {
                            (selectedHours.toInt() * 60) + selectedMinutes.toInt()
                        } else {
                            1440 // 24 hours fallback max
                        }
                        val stopOnRideEnd = shareStopMode == 1
                        
                        val finalDuration = duration.coerceAtLeast(1)
                        viewModel.startLiveShare(context, finalDuration, stopOnRideEnd)
                        showStartShareDialog = false
                    },
                    enabled = isDurationValid
                ) {
                    Text(strings.startSharing)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartShareDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showActiveShareDialog) {
        AlertDialog(
            onDismissRequest = { showActiveShareDialog = false },
            title = { Text(strings.liveLocationActive) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.expiresIn, style = MaterialTheme.typography.bodyMedium)
                    Text(countdownText, style = MaterialTheme.typography.displaySmall, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            uiState.liveShareState.shareLink?.let { link ->
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Share Link", link))
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(strings.linkCopied)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.copyLink)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            uiState.liveShareState.shareLink?.let { link ->
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, strings.shareRideSubject)
                                    putExtra(android.content.Intent.EXTRA_TEXT, "${strings.shareRideText}: $link")
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, strings.shareVia))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.shareLink)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActiveShareDialog = false }) {
                    Text(strings.close)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.stopLiveShare(context)
                        showActiveShareDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text(strings.stopSharing)
                }
            }
        )
    }
}
}
