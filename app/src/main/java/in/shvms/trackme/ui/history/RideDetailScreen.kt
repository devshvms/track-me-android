package `in`.shvms.trackme.ui.history

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import java.io.FileInputStream
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.export.GPXExporterImpl
import `in`.shvms.trackme.domain.export.GoogleStaticApiImageExporterImpl
import `in`.shvms.trackme.domain.export.NativeSnapshotImageExporterImpl
import `in`.shvms.trackme.config.AppConfig
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import `in`.shvms.trackme.domain.export.ExportOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Button
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import androidx.compose.material.icons.filled.Map
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDistance(meters: Double): String {
    if (meters < 1000) return String.format("%.0f m", meters)
    return String.format("%.2f km", meters / 1000)
}

fun vectorToBitmap(context: android.content.Context, id: Int, color: Int): BitmapDescriptor {
    val vectorDrawable = ContextCompat.getDrawable(context, id)!!
    val bitmap = android.graphics.Bitmap.createBitmap(
        vectorDrawable.intrinsicWidth * 2,
        vectorDrawable.intrinsicHeight * 2,
        android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bitmap)
    vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
    DrawableCompat.setTint(vectorDrawable, color)
    vectorDrawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RideDetailScreen(
    rideId: Long,
    viewModel: RideDetailViewModel = viewModel(),
    navController: NavController? = null
) {
    val rideWithPoints by viewModel.rideWithPoints.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    var previewMapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportShowStats by remember { mutableStateOf(true) }
    var exportRatio by remember { mutableStateOf(Pair(1, 1)) }
    
    var exportMapType by remember { mutableStateOf(MapType.NORMAL) }
    var exportHidePOIs by remember { mutableStateOf(false) }
    var exportRouteColor by remember { mutableStateOf(Color(0xFF1565C0)) }
    var exportShowMarkers by remember { mutableStateOf(true) }
    var exportOverlayDarkTheme by remember { mutableStateOf(true) }
    var exportShowDate by remember { mutableStateOf(true) }
    var exportShowDuration by remember { mutableStateOf(true) }
    var exportShowDistance by remember { mutableStateOf(true) }
    var mapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }

    LaunchedEffect(rideId) {
        viewModel.loadRide(rideId)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is RideDetailViewModel.UiEvent.NavigateBack -> {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Ride deleted")
                    }
                    navController?.popBackStack()
                }
                is RideDetailViewModel.UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            var isEditing by remember { mutableStateOf(false) }
            val displayTitle = remember(rideWithPoints) {
                val ride = rideWithPoints?.ride
                if (ride == null) "Ride Details"
                else {
                    var t = ride.title ?: "Ride Details"
                    if (t == `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime)) {
                         val maxKmh = (ride.postRideCalculation?.maxSpeed ?: 0f) * 3.6f
                         t = `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime, maxKmh)
                    }
                    t
                }
            }
            var editedTitle by remember(displayTitle) { mutableStateOf(displayTitle) }

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
                        Text(displayTitle)
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
    ) { padding ->
        if (rideWithPoints == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val points = rideWithPoints!!.points
            val ride = rideWithPoints!!.ride
            var scrubIndex by remember { mutableStateOf<Int?>(null) }
            
            var columnScrollEnabled by remember { mutableStateOf(true) }
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState, enabled = columnScrollEnabled)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val isTouched: Boolean = event.changes.any { change -> change.pressed }
                                    if (isTouched) {
                                        columnScrollEnabled = false
                                    } else {
                                        columnScrollEnabled = true
                                    }
                                }
                            }
                        }
                ) {
                    if (points.isNotEmpty()) {
                        val latLngs = points.map { LatLng(it.latitude, it.longitude) }
                        val bounds = remember(latLngs) {
                            val builder = LatLngBounds.Builder()
                            latLngs.forEach { builder.include(it) }
                            builder.build()
                        }
                        
                        val cameraPositionState = rememberCameraPositionState()
                        
                        val pointerIcon = remember {
                            val size = 40
                            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bitmap)
                            val paint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                color = android.graphics.Color.parseColor("#2196F3")
                                style = android.graphics.Paint.Style.FILL
                            }
                            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
                            paint.color = android.graphics.Color.WHITE
                            paint.style = android.graphics.Paint.Style.STROKE
                            paint.strokeWidth = 4f
                            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
                            BitmapDescriptorFactory.fromBitmap(bitmap)
                        }

                        var mapType by remember { mutableStateOf(MapType.NORMAL) }
                        var isTrafficEnabled by remember { mutableStateOf(false) }

                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            properties = MapProperties(mapType = mapType, isTrafficEnabled = isTrafficEnabled),
                            uiSettings = MapUiSettings(zoomControlsEnabled = false)
                        ) {
                            MapEffect { map ->
                                mapInstance = map
                            }
                            Polyline(
                                points = latLngs,
                                color = Color(0xFF1565C0),
                                width = 10f
                            )
                            Marker(
                                state = MarkerState(position = latLngs.last()),
                                title = "Finish",
                                snippet = "End of Ride"
                            )
                            
                            if (scrubIndex != null && scrubIndex!! in points.indices) {
                                val p = points[scrubIndex!!]
                                Marker(
                                    state = MarkerState(position = LatLng(p.latitude, p.longitude)),
                                    title = "Scrub",
                                    snippet = "Speed: ${String.format("%.1f", p.speed * 3.6f)} km/h",
                                    icon = pointerIcon,
                                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                                )
                            }
                            
                            val pausedGroups = remember(points) {
                                val groups = mutableListOf<List<GPSPointEntity>>()
                                var currentGroup = mutableListOf<GPSPointEntity>()
                                for (p in points) {
                                    if (p.isPaused) currentGroup.add(p)
                                    else if (currentGroup.isNotEmpty()) {
                                        groups.add(currentGroup)
                                        currentGroup = mutableListOf()
                                    }
                                }
                                if (currentGroup.isNotEmpty()) groups.add(currentGroup)
                                groups
                            }
                            
                            pausedGroups.forEach { group ->
                                val duration = group.last().timestamp - group.first().timestamp
                                val alpha = (0.4f + (duration / 60000f) * 0.4f).coerceIn(0.4f, 1.0f)
                                val center = group[group.size / 2]
                                Circle(
                                    center = LatLng(center.latitude, center.longitude),
                                    radius = 5.0,
                                    fillColor = Color.Red.copy(alpha = alpha),
                                    strokeColor = Color.Red,
                                    strokeWidth = 2f
                                )
                            }
                        }

                        LaunchedEffect(bounds) {
                            cameraPositionState.animate(
                                update = CameraUpdateFactory.newLatLngBounds(bounds, 100),
                                durationMs = 1000
                            )
                        }
                        
                        var showMapOptions by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 12.dp)) {
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
                    } else {
                        Text("No GPS data available", modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (points.size > 1) {
                    val speeds = points.map { it.speed * 3.6f }
                    val rawMinSpeed = speeds.minOrNull() ?: 0f
                    val rawMaxSpeed = speeds.maxOrNull() ?: 0f
                    val speedRange = if (rawMaxSpeed > rawMinSpeed) rawMaxSpeed - rawMinSpeed else 1f
                    val minSpeed = rawMinSpeed - speedRange * 0.1f
                    val maxSpeed = rawMaxSpeed + speedRange * 0.1f

                    val alts = points.map { it.altitude.toFloat() }
                    val rawMinAlt = alts.minOrNull() ?: 0f
                    val rawMaxAlt = alts.maxOrNull() ?: 0f
                    val altRange = if (rawMaxAlt > rawMinAlt) rawMaxAlt - rawMinAlt else 1f
                    val minAlt = rawMinAlt - altRange * 0.1f
                    val maxAlt = rawMaxAlt + altRange * 0.1f

                    val cumulativeDistances = remember(points) {
                        val distances = FloatArray(points.size)
                        var totalDist = 0f
                        for (i in 1 until points.size) {
                            val prev = points[i - 1]
                            val curr = points[i]
                            val result = FloatArray(1)
                            android.location.Location.distanceBetween(prev.latitude, prev.longitude, curr.latitude, curr.longitude, result)
                            totalDist += result[0]
                            distances[i] = totalDist
                        }
                        distances
                    }

                    CombinedMetricLineChart(
                        points = points,
                        minSpeed = minSpeed,
                        maxSpeed = maxSpeed,
                        minAlt = minAlt,
                        maxAlt = maxAlt,
                        speedColor = Color(0xFF4CAF50),
                        altColor = Color(0xFFFFC107),
                        scrubIndex = scrubIndex,
                        modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    val indexToShow = scrubIndex ?: (points.size - 1)
                    val elapsedMs = points[indexToShow].timestamp - ride.startTime
                    val elapsedFormatted = formatDuration(elapsedMs)
                    val distKm = cumulativeDistances[indexToShow] / 1000f
                    
                    Text(
                        text = "Time: $elapsedFormatted  |  Dist: ${String.format(java.util.Locale.getDefault(), "%.2f", distKm)} km",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Slider(
                        value = scrubIndex?.toFloat() ?: 0f,
                        onValueChange = { scrubIndex = it.toInt() },
                        valueRange = 0f..(points.size - 1).toFloat(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Ride Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem("Distance", String.format("%.2f km", (ride.postRideCalculation?.distance ?: 0.0) / 1000f), modifier = Modifier.weight(1f))
                            StatItem("Duration", formatDuration((ride.endTime ?: ride.startTime) - ride.startTime), modifier = Modifier.weight(1f))
                            StatItem("GPS Tag", points.size.toString(), modifier = Modifier.weight(1f))
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                            val startTimeStr = dateFormat.format(java.util.Date(ride.startTime))
                            StatItem("Start Time", startTimeStr, modifier = Modifier.weight(1f))
                            
                            StatItem("Max G-Force", String.format("%.2f G", (ride.postRideCalculation?.maxAcceleration ?: 0f) / 9.8f), modifier = Modifier.weight(1f))

                            val distanceKm = (ride.postRideCalculation?.distance ?: 0.0) / 1000.0
                            val durationHours = ((ride.endTime ?: ride.startTime) - ride.startTime) / 3600000.0
                            val avgSpeed = if (durationHours > 0) (distanceKm / durationHours).toFloat() else 0f
                            StatItem("Avg Speed", String.format("%.1f km/h", avgSpeed), modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                if (rideWithPoints != null && rideWithPoints!!.ride.endTime != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
                                            coroutineScope.launch { snackbarHostState.showSnackbar("Saved to Downloads") }
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Error saving GPX: ${e.message}") }
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
                
                Spacer(modifier = Modifier.height(32.dp))
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
        val handleExport = { isShare: Boolean ->
            previewMapInstance?.snapshot { bitmap ->
                if (bitmap != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val exporter = `in`.shvms.trackme.domain.export.NativeSnapshotImageExporterImpl()
                            val options = ExportOptions(exportShowStats, exportOverlayDarkTheme, exportShowDistance, exportShowDuration, exportShowDate)
                            val imageFile = exporter.export(rideWithPoints!!, exportRatio.first, exportRatio.second, context, bitmap, options)
                            val rideTitle = rideWithPoints!!.ride.title?.ifEmpty { "TrackMe Ride" } ?: "TrackMe Ride"
                            if (isShare) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                withContext(Dispatchers.Main) {
                                    context.startActivity(Intent.createChooser(intent, "Share Image"))
                                    showExportDialog = false
                                }
                            } else {
                                saveImageToGallery(context, imageFile, rideTitle)
                                withContext(Dispatchers.Main) {
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Saved to gallery") }
                                    showExportDialog = false
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                coroutineScope.launch { snackbarHostState.showSnackbar("Error: ${e.message}") }
                            }
                        }
                    }
                } else {
                    coroutineScope.launch { snackbarHostState.showSnackbar("Could not capture map") }
                }
            } ?: run {
                coroutineScope.launch { snackbarHostState.showSnackbar("Map not ready") }
            }
        }
        
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showExportDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text("Export Preview") },
                        navigationIcon = {
                            IconButton(onClick = { showExportDialog = false }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val ratioFloat = exportRatio.first.toFloat() / exportRatio.second.toFloat()
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratioFloat)
                                .padding(4.dp)
                        ) {
                            val latLngs = rideWithPoints!!.points.map { LatLng(it.latitude, it.longitude) }
                            val bounds = remember(latLngs) {
                                val builder = LatLngBounds.Builder()
                                latLngs.forEach { builder.include(it) }
                                builder.build()
                            }
                            
                            val cameraPositionState = rememberCameraPositionState()
                            LaunchedEffect(bounds, ratioFloat) {
                                // Wait for layout to be ready, then move camera
                                kotlinx.coroutines.delay(200)
                                cameraPositionState.move(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(bounds, 80))
                            }
                            
                            GoogleMap(
                                modifier = Modifier.fillMaxSize(),
                                cameraPositionState = cameraPositionState,
                                properties = MapProperties(
                                    mapType = exportMapType,
                                    isTrafficEnabled = false,
                                    mapStyleOptions = if (exportHidePOIs) MapStyleOptions("[{\"featureType\":\"poi\",\"stylers\":[{\"visibility\":\"off\"}]},{\"featureType\":\"transit\",\"elementType\":\"labels.icon\",\"stylers\":[{\"visibility\":\"off\"}]}]") else null
                                ),
                                uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = false)
                            ) {
                                MapEffect { map ->
                                    previewMapInstance = map
                                }
                                Polyline(
                                    points = latLngs,
                                    color = exportRouteColor,
                                    width = 10f
                                )
                                if (exportShowMarkers) {
                                    Marker(
                                        state = MarkerState(position = latLngs.first()),
                                        title = "Start",
                                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                                    )
                                    Marker(
                                        state = MarkerState(position = latLngs.last()),
                                        title = "Finish",
                                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                                    )
                                }
                            }
                            
                            if (exportShowStats) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(AppConfig.OVERLAY_BANNER_HEIGHT_RATIO)
                                        .align(Alignment.BottomCenter)
                                        .background(
                                            if (exportOverlayDarkTheme) Color(AppConfig.OVERLAY_BANNER_COLOR).copy(alpha = AppConfig.OVERLAY_BANNER_ALPHA / 255f)
                                            else Color.White.copy(alpha = 0.85f)
                                        )
                                        .padding(start = 16.dp, end = 16.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Column {
                                        val distanceKm = (rideWithPoints!!.ride.postRideCalculation?.distance ?: 0.0) / 1000.0
                                        val distanceStr = String.format(java.util.Locale.getDefault(), "%.2f km", distanceKm)
                                        val durationMillis = (rideWithPoints!!.ride.endTime ?: rideWithPoints!!.ride.startTime) - rideWithPoints!!.ride.startTime
                                        val seconds = durationMillis / 1000
                                        val durationStr = String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                                        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(rideWithPoints!!.ride.startTime))
                                        
                                        val textColor = if (exportOverlayDarkTheme) Color.White else Color.Black
                                        
                                        val rideTitle = rideWithPoints!!.ride.title?.ifEmpty { "TrackMe Ride" } ?: "TrackMe Ride"
                                        Text(rideTitle, color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        
                                        val statsList = mutableListOf<String>()
                                        if (exportShowDate) statsList.add(dateStr)
                                        if (exportShowDuration) statsList.add(durationStr)
                                        if (exportShowDistance) statsList.add(distanceStr)
                                        
                                        Text(statsList.joinToString(" • "), color = textColor, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Aspect Ratio:", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = exportRatio == Pair(1, 1), onClick = { exportRatio = Pair(1, 1) }, label = { Text("1:1") })
                                FilterChip(selected = exportRatio == Pair(4, 3), onClick = { exportRatio = Pair(4, 3) }, label = { Text("4:3") })
                                FilterChip(selected = exportRatio == Pair(16, 9), onClick = { exportRatio = Pair(16, 9) }, label = { Text("16:9") })
                            }
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Map Style:", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = exportMapType == MapType.NORMAL, onClick = { exportMapType = MapType.NORMAL }, label = { Text("Normal") })
                                FilterChip(selected = exportMapType == MapType.SATELLITE, onClick = { exportMapType = MapType.SATELLITE }, label = { Text("Sat") })
                                FilterChip(selected = exportMapType == MapType.TERRAIN, onClick = { exportMapType = MapType.TERRAIN }, label = { Text("Terrain") })
                            }
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = exportHidePOIs, onCheckedChange = { exportHidePOIs = it }, enabled = exportMapType == MapType.NORMAL)
                                Text("Hide Places", color = if(exportMapType == MapType.NORMAL) Color.Unspecified else Color.Gray)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = exportShowMarkers, onCheckedChange = { exportShowMarkers = it })
                                Text("Show Markers")
                            }
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Route Color:", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    Color(0xFF1565C0), Color(0xFFD32F2F), Color(0xFF388E3C), Color(0xFFF57C00), Color(0xFF7B1FA2), Color.Black
                                ).forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(color, shape = androidx.compose.foundation.shape.CircleShape)
                                            .border(
                                                width = if (exportRouteColor == color) 2.dp else 0.dp,
                                                color = if (exportRouteColor == color) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                            .clickable { exportRouteColor = color }
                                    )
                                }
                            }
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Stats Overlay:", fontWeight = FontWeight.SemiBold)
                            Switch(checked = exportShowStats, onCheckedChange = { exportShowStats = it })
                        }
                        
                        if (exportShowStats) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = exportOverlayDarkTheme, onCheckedChange = { exportOverlayDarkTheme = it })
                                    Text("Dark Theme")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = exportShowDistance, onClick = { exportShowDistance = !exportShowDistance }, label = { Text("Dist") })
                                    FilterChip(selected = exportShowDuration, onClick = { exportShowDuration = !exportShowDuration }, label = { Text("Dur") })
                                    FilterChip(selected = exportShowDate, onClick = { exportShowDate = !exportShowDate }, label = { Text("Date") })
                                }
                            }
                        }
                    }
                    
                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = { handleExport(true) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                        Button(
                            onClick = { handleExport(false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Save", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun CombinedMetricLineChart(
    points: List<GPSPointEntity>,
    minSpeed: Float,
    maxSpeed: Float,
    minAlt: Float,
    maxAlt: Float,
    speedColor: Color,
    altColor: Color,
    scrubIndex: Int? = null,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    val speedRange = if (maxSpeed == minSpeed) 1f else (maxSpeed - minSpeed)
    val altRange = if (maxAlt == minAlt) 1f else (maxAlt - minAlt)
    val scrubLineColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)

    val plotData = remember(points) {
        val list = mutableListOf<Pair<GPSPointEntity, Float>>()
        if (points.isEmpty()) return@remember list
        
        var currentTime = 0L
        list.add(points.first() to currentTime.toFloat())
        for (i in 1 until points.size) {
            val prev = points[i-1]
            val curr = points[i]
            val dt = curr.timestamp - prev.timestamp
            if (prev.isPaused && dt > 60000L) {
                currentTime += 60000L // Cap pauses to 1 minute visually
            } else {
                currentTime += dt
            }
            list.add(curr to currentTime.toFloat())
        }
        list
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val maxX = plotData.last().second.coerceAtLeast(1f)

            // Draw Paths
            val drawMetricPath = { isSpeed: Boolean ->
                val path = Path()
                val dottedPath = Path()
                var isFirst = true

                for (i in 0 until plotData.size - 1) {
                    val (p1, xVal1) = plotData[i]
                    val (p2, xVal2) = plotData[i+1]
                    
                    val x1 = (xVal1 / maxX) * width
                    val x2 = (xVal2 / maxX) * width
                    
                    val val1 = if (isSpeed) p1.speed * 3.6f else p1.altitude.toFloat()
                    val val2 = if (isSpeed) p2.speed * 3.6f else p2.altitude.toFloat()
                    
                    val y1 = height - (((val1 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * height)
                    val y2 = height - (((val2 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * height)

                    if (isFirst) {
                        path.moveTo(x1, y1)
                        dottedPath.moveTo(x1, y1)
                        isFirst = false
                    } else {
                        val prevX = (plotData[i-1].second / maxX) * width
                        val prevVal1 = if (isSpeed) plotData[i-1].first.speed * 3.6f else plotData[i-1].first.altitude.toFloat()
                        val prevY = height - (((prevVal1 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * height)
                        
                        val cpX = (prevX + x1) / 2
                        
                        if (p1.isPaused) {
                            dottedPath.cubicTo(cpX, prevY, cpX, y1, x1, y1)
                            path.moveTo(x1, y1) // Break solid path
                        } else {
                            path.cubicTo(cpX, prevY, cpX, y1, x1, y1)
                            dottedPath.moveTo(x1, y1) // Move dotted path along
                        }
                    }
                }
                
                val cColor = if (isSpeed) speedColor else altColor
                drawPath(path = path, color = cColor, style = Stroke(width = 4f))
                drawPath(path = dottedPath, color = cColor, style = Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)))
            }

            drawMetricPath(true)
            drawMetricPath(false)
            
            // Draw Scrub Line
            if (scrubIndex != null && scrubIndex in plotData.indices) {
                val scrubX = (plotData[scrubIndex].second / maxX) * width
                drawLine(
                    color = scrubLineColor,
                    start = Offset(scrubX, 0f),
                    end = Offset(scrubX, height),
                    strokeWidth = 4f
                )
                
                val p = plotData[scrubIndex].first
                val sVal = p.speed * 3.6f
                val aVal = p.altitude.toFloat()
                
                val sY = height - (((sVal - minSpeed) / speedRange) * height)
                val aY = height - (((aVal - minAlt) / altRange) * height)
                
                // Draw Speed Intersection
                drawCircle(color = speedColor, radius = 8f, center = Offset(scrubX, sY))
                drawCircle(color = Color.White, radius = 4f, center = Offset(scrubX, sY))
                
                val sText = String.format("%.1f km/h", sVal)
                val sTextLayout = textMeasurer.measure(sText, style = labelStyle.copy(color = Color.White))
                drawRoundRect(
                    color = speedColor.copy(alpha = 0.8f),
                    topLeft = Offset(scrubX + 12f - 8f, sY - 24f - 4f),
                    size = Size(sTextLayout.size.width + 16f, sTextLayout.size.height + 8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                drawText(
                    textLayoutResult = sTextLayout,
                    topLeft = Offset(scrubX + 12f, sY - 24f)
                )

                // Draw Altitude Intersection
                drawCircle(color = altColor, radius = 8f, center = Offset(scrubX, aY))
                drawCircle(color = Color.White, radius = 4f, center = Offset(scrubX, aY))
                
                val aText = String.format("%.0f m", aVal)
                val aTextLayout = textMeasurer.measure(aText, style = labelStyle.copy(color = Color.White))
                drawRoundRect(
                    color = altColor.copy(alpha = 0.8f),
                    topLeft = Offset(scrubX + 12f - 8f, aY - 24f - 4f),
                    size = Size(aTextLayout.size.width + 16f, aTextLayout.size.height + 8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                drawText(
                    textLayoutResult = aTextLayout,
                    topLeft = Offset(scrubX + 12f, aY - 24f)
                )
            }
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

fun saveImageToGallery(context: Context, imageFile: java.io.File, rideTitle: String) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "TrackMe_${rideTitle}_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(imageFile).use { input ->
                input.copyTo(out)
            }
        }
    }
}
