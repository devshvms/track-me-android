package com.example.trackme.ui.home

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
import com.example.trackme.TrackMeApp
import com.example.trackme.service.TrackingState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.*
import com.example.trackme.ui.components.SwipeToTriggerSlider

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

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (hasLocationPermission) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = true),
                    contentPadding = if (uiState.trackingState != TrackingState.IDLE) PaddingValues(bottom = 88.dp) else PaddingValues(0.dp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (uiState.trackingState) {
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
                            onClick = { viewModel.stopTracking(context) },
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
