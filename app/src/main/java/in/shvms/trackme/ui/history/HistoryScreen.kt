package `in`.shvms.trackme.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.GoogleMapOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.CameraUpdateFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToDetail: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val rides by viewModel.rides.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    viewModel.importGPX(inputStream)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error opening file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is HistoryViewModel.UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is HistoryViewModel.UiEvent.Success -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ride History") },
                actions = {
                    IconButton(onClick = { launcher.launch("*/*") }) {
                        Icon(Icons.Default.Add, contentDescription = "Import GPX")
                    }
                }
            )
        }
    ) { padding ->
        if (rides.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No rides found", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(rides, key = { it.ride.id }) { rideWithPoints ->
                    RideHistoryCard(
                        rideWithPoints = rideWithPoints,
                        onClick = { onNavigateToDetail(rideWithPoints.ride.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun RideHistoryCard(
    rideWithPoints: RideWithPoints,
    onClick: () -> Unit
) {
    val ride = rideWithPoints.ride
    val points = rideWithPoints.points

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            Column {
            // Map Thumbnail
            if (points.isNotEmpty()) {
                val latLngs = points.map { LatLng(it.latitude, it.longitude) }
                val bounds = remember(latLngs) {
                    val builder = LatLngBounds.Builder()
                    latLngs.forEach { builder.include(it) }
                    builder.build()
                }
                
                val cameraPositionState = rememberCameraPositionState()
                
                LaunchedEffect(bounds) {
                    cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 50))
                }

                GoogleMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    cameraPositionState = cameraPositionState,
                    googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        scrollGesturesEnabled = false,
                        zoomGesturesEnabled = false,
                        tiltGesturesEnabled = false,
                        rotationGesturesEnabled = false
                    ),
                    onMapClick = { onClick() }
                ) {
                    Polyline(
                        points = latLngs,
                        color = MaterialTheme.colorScheme.primary,
                        width = 8f
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No path data")
                }
            }

            // Ride Stats
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = ride.title ?: formatDateTime(ride.startTime),
                    style = MaterialTheme.typography.titleMedium
                )
                if (ride.title != null) {
                    Text(
                        text = formatDateTime(ride.startTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.2f km", (ride.postRideCalculation?.distance ?: 0.0) / 1000f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = formatDuration((ride.endTime ?: ride.startTime) - ride.startTime),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } // Close inner Column (Ride Stats)
            } // Close outer Column
            
            // Sync Status Overlay
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(
                    text = if (ride.isSynced) "☁️" else "⏳",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } // Close Box
    } // Close Card
} // Close RideItem fun

private fun formatDateTime(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown Date"
    val sdf = SimpleDateFormat("MMM dd, yyyy - h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "00:00:00"
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}
