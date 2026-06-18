package com.example.trackme.ui.history

import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.trackme.data.local.entity.GPSPointEntity
import com.example.trackme.domain.export.GPXExporterImpl
import com.example.trackme.domain.export.GoogleStaticApiImageExporterImpl
import com.example.trackme.domain.export.NativeSnapshotImageExporterImpl
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(
    rideId: Long,
    viewModel: RideDetailViewModel = viewModel(),
    navController: NavController? = null
) {
    val rideWithPoints by viewModel.rideWithPoints.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportShowStats by remember { mutableStateOf(true) }
    var exportRatio by remember { mutableStateOf(Pair(1, 1)) }
    var mapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }

    LaunchedEffect(rideId) {
        viewModel.loadRide(rideId)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is RideDetailViewModel.UiEvent.NavigateBack -> {
                    Toast.makeText(context, "Ride deleted successfully", Toast.LENGTH_SHORT).show()
                    navController?.popBackStack()
                }
                is RideDetailViewModel.UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            var isEditing by remember { mutableStateOf(false) }
            var editedTitle by remember(rideWithPoints?.ride?.title) { 
                mutableStateOf(rideWithPoints?.ride?.title ?: "Ride Details") 
            }

            TopAppBar(
                title = {
                    if (isEditing) {
                        TextField(
                            value = editedTitle,
                            onValueChange = { editedTitle = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(rideWithPoints?.ride?.title ?: "Ride Details")
                    }
                },
                actions = {
                    if (isEditing) {
                        TextButton(onClick = {
                            isEditing = false
                            if (editedTitle.isNotBlank()) {
                                viewModel.updateTitle(rideId, editedTitle)
                            }
                        }) {
                            Text("Done")
                        }
                    } else {
                        TextButton(onClick = { isEditing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (rideWithPoints != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share")
                        }

                        TextButton(onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val exporter = GPXExporterImpl()
                                    val gpxFile = exporter.export(rideWithPoints!!, context)
                                    val values = android.content.ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, gpxFile.name)
                                        put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                                        put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                                    }
                                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                    if (uri != null) {
                                        val outputStream = context.contentResolver.openOutputStream(uri)
                                        outputStream?.use { out ->
                                            gpxFile.inputStream().use { input -> input.copyTo(out) }
                                        }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "GPX saved to Downloads", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Error saving GPX: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "GPX", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("GPX")
                        }

                        TextButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (rideWithPoints == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val points = rideWithPoints!!.points
            val ride = rideWithPoints!!.ride

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    if (points.isNotEmpty()) {
                        val latLngs = points.map { LatLng(it.latitude, it.longitude) }
                        val bounds = remember(latLngs) {
                            val builder = LatLngBounds.Builder()
                            latLngs.forEach { builder.include(it) }
                            builder.build()
                        }
                        
                        val cameraPositionState = rememberCameraPositionState()

                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            uiSettings = MapUiSettings(zoomControlsEnabled = false)
                        ) {
                            MapEffect { map ->
                                mapInstance = map
                            }
                            Polyline(
                                points = latLngs,
                                color = Color(0xFF1565C0), // Darker primary
                                width = 10f
                            )
                            Marker(
                                state = MarkerState(position = latLngs.last()),
                                title = "Finish",
                                snippet = "End of Ride"
                            )
                        }

                        LaunchedEffect(bounds) {
                            cameraPositionState.animate(
                                update = CameraUpdateFactory.newLatLngBounds(bounds, 100),
                                durationMs = 1000
                            )
                        }
                    } else {
                        Text("No GPS data available", modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem("Distance", String.format("%.2f km", (ride.postRideCalculation?.distance ?: 0.0) / 1000f))
                            StatItem("Duration", formatDuration((ride.endTime ?: ride.startTime) - ride.startTime))
                            StatItem("Avg Speed", String.format("%.1f m/s", ride.postRideCalculation?.avgSpeed ?: 0f))
                            StatItem("Max Speed", String.format("%.1f m/s", ride.postRideCalculation?.maxSpeed ?: 0f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (points.size > 1) {
                    Text(
                        text = "Speed Over Time (m/s)",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MetricLineChart(
                        points = points,
                        metricSelector = { it.speed },
                        lineColor = Color(0xFF4CAF50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Altitude Over Time (m)",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MetricLineChart(
                        points = points,
                        metricSelector = { it.altitude.toFloat() },
                        lineColor = Color(0xFF2196F3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Ride") },
            text = { Text("Are you sure you want to delete this ride? This action cannot be undone and will delete it from cloud if synced.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteRide(rideId)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Settings") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportShowStats, onCheckedChange = { exportShowStats = it })
                        Text("Include stats overlay")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select Aspect Ratio:")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        FilterChip(
                            selected = exportRatio == Pair(1, 1),
                            onClick = { exportRatio = Pair(1, 1) },
                            label = { Text("1:1 (Recommended)") }
                        )
                        FilterChip(
                            selected = exportRatio == Pair(4, 3),
                            onClick = { exportRatio = Pair(4, 3) },
                            label = { Text("4:3") }
                        )
                        FilterChip(
                            selected = exportRatio == Pair(16, 9),
                            onClick = { exportRatio = Pair(16, 9) },
                            label = { Text("16:9") }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportDialog = false
                    mapInstance?.snapshot { bitmap ->
                        if (bitmap != null) {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val exporter = NativeSnapshotImageExporterImpl()
                                    val imageFile = exporter.export(rideWithPoints!!, exportRatio.first, exportRatio.second, context, bitmap, exportShowStats)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share Image"))
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Error sharing: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(context, "Could not capture map image", Toast.LENGTH_SHORT).show()
                        }
                    } ?: run {
                        Toast.makeText(context, "Map not ready", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun MetricLineChart(
    points: List<GPSPointEntity>,
    metricSelector: (GPSPointEntity) -> Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    val minTime = points.first().timestamp
    val maxTime = points.last().timestamp
    val timeRange = maxTime - minTime

    val values = points.map(metricSelector)
    val minValue = values.minOrNull() ?: 0f
    val maxValue = values.maxOrNull() ?: 1f
    val valueRange = if (maxValue == minValue) 1f else (maxValue - minValue)

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val width = size.width
            val height = size.height

            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                if (p1.isPaused) {
                    val x1 = ((p1.timestamp - minTime).toFloat() / timeRange.toFloat()) * width
                    val x2 = ((p2.timestamp - minTime).toFloat() / timeRange.toFloat()) * width
                    
                    drawRect(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        topLeft = Offset(x1, 0f),
                        size = Size(maxOf(1f, x2 - x1), height)
                    )
                }
            }

            val path = Path()
            points.forEachIndexed { index, point ->
                val x = if (timeRange == 0L) 0f else ((point.timestamp - minTime).toFloat() / timeRange.toFloat()) * width
                val value = metricSelector(point)
                val y = height - (((value - minValue) / valueRange) * height)

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 4f)
            )
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "00:00:00"
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}
