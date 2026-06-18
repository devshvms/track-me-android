package com.example.trackme.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory((LocalContext.current.applicationContext as TrackMeApp).trackingManager)
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
                    properties = MapProperties(isMyLocationEnabled = true)
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
                Text("Waiting for location permissions...", modifier = Modifier.align(Alignment.Center))
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
                        Button(onClick = { viewModel.startTracking(context) }) {
                            Text("Start")
                        }
                    }
                    TrackingState.TRACKING -> {
                        Button(onClick = { viewModel.pauseTracking(context) }) {
                            Text("Pause")
                        }
                    }
                    TrackingState.PAUSED, TrackingState.GPS_LOST -> {
                        Button(onClick = { viewModel.startTracking(context) }) {
                            Text("Resume")
                        }
                        Button(onClick = { viewModel.stopTracking(context) }) {
                            Text("Stop & Save")
                        }
                    }
                }
            }
        }
    }
}
