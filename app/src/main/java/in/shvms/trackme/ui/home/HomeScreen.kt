package `in`.shvms.trackme.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.service.TrackingState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.*
import androidx.compose.material.icons.filled.Map
import `in`.shvms.trackme.ui.components.SwipeToTriggerSlider
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(
            (LocalContext.current.applicationContext as TrackMeApp).trackingManager,
            (LocalContext.current.applicationContext as TrackMeApp).emergencyManager,
            (LocalContext.current.applicationContext as TrackMeApp).authManager,
            (LocalContext.current.applicationContext as TrackMeApp).database.emergencyDao()
        )
    )
) {
    val context = LocalContext.current
    var hasLocationPermission by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    val receiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Ride saved")
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
                    title = { Text("Location Permission Required") },
                    text = { Text("TrackMe needs location access to track your rides and send your location in an emergency. Please grant the permission to use the app.") },
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
                            Text("Grant Permission")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = android.net.Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            context.startActivity(intent)
                        }) {
                            Text("Open Settings")
                        }
                    }
                )
            }

            val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            var showMapOptions by remember { mutableStateOf(false) }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = topPadding + 80.dp, end = 12.dp)) {
                FloatingActionButton(
                    onClick = { showMapOptions = true },
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.Map, contentDescription = "Map Layers", modifier = Modifier.size(20.dp))
                }
                
                DropdownMenu(
                    expanded = showMapOptions,
                    onDismissRequest = { showMapOptions = false }
                ) {
                    DropdownMenuItem(text = { Text("Normal") }, onClick = { mapType = MapType.NORMAL; showMapOptions = false })
                    DropdownMenuItem(text = { Text("Satellite") }, onClick = { mapType = MapType.SATELLITE; showMapOptions = false })
                    DropdownMenuItem(text = { Text("Terrain") }, onClick = { mapType = MapType.TERRAIN; showMapOptions = false })
                    DropdownMenuItem(text = { Text("Hybrid") }, onClick = { mapType = MapType.HYBRID; showMapOptions = false })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (isTrafficEnabled) "Hide Traffic" else "Show Traffic") },
                        onClick = { isTrafficEnabled = !isTrafficEnabled; showMapOptions = false }
                    )
                }
            }

            // Emergency Trigger and Share Button
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
                                    Text("STOP EMERGENCY BROADCAST", style = MaterialTheme.typography.labelLarge)
                                }
                            } else {
                                SwipeToTriggerSlider(
                                    onTriggered = { viewModel.triggerEmergency() }
                                )
                            }
                        }
                    }
                    
                    FloatingActionButton(
                        onClick = { /* TODO Share Live Location */ },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share Live Location")
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
                        text = "EMERGENCY BROADCAST ACTIVE",
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
            Text("Ride Stats", style = MaterialTheme.typography.titleLarge)
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
                                Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(32.dp))
                            }
                        }
                        TrackingState.TRACKING -> {
                            FilledIconButton(
                                onClick = { viewModel.pauseTracking(context) },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(32.dp))
                            }
                        }
                        TrackingState.PAUSED, TrackingState.GPS_LOST -> {
                            FilledIconButton(
                                onClick = { viewModel.startTracking(context) },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(32.dp))
                            }
                            FilledIconButton(
                                onClick = { 
                                    viewModel.stopTracking(context)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Saving ride...")
                                    }
                                },
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop & Save", modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
}
