package `in`.shvms.trackme.ui.history

import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.export.GPXExporterImpl
import `in`.shvms.trackme.domain.export.GoogleStaticApiImageExporterImpl
import `in`.shvms.trackme.domain.export.NativeSnapshotImageExporterImpl
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

                            val rawCount = ride.postRideCalculation?.rawPointCount ?: points.size
                            val compressionPct = if (rawCount > points.size && rawCount > 0) ((rawCount - points.size).toFloat() / rawCount * 100).toInt() else 0
                            StatItem("Compression", "$compressionPct%", modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (points.size > 1) {
                    CombinedChartWithTable(points)
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
fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CombinedChartWithTable(points: List<GPSPointEntity>) {
    var showSpeed by remember { mutableStateOf(true) }
    var showAltitude by remember { mutableStateOf(true) }

    val speedColor = Color(0xFF4CAF50) // Green
    val altColor = Color(0xFF2196F3) // Blue

    // Calculate stats
    val speeds = points.map { it.speed * 3.6f }
    val minSpeed = speeds.minOrNull() ?: 0f
    val maxSpeed = speeds.maxOrNull() ?: 0f
    val meanSpeed = if (speeds.isNotEmpty()) speeds.average().toFloat() else 0f

    val alts = points.map { it.altitude.toFloat() }
    val minAlt = alts.minOrNull() ?: 0f
    val maxAlt = alts.maxOrNull() ?: 0f
    val meanAlt = if (alts.isNotEmpty()) alts.average().toFloat() else 0f

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })

    Column {
        val titleText = if (pagerState.currentPage == 0) "Metrics over Time" else "Metrics over Distance"
        Text(
            text = titleText,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp)
        ) { page ->
            CombinedMetricLineChart(
                points = points,
                showSpeed = showSpeed,
                showAltitude = showAltitude,
                minSpeed = minSpeed,
                maxSpeed = maxSpeed,
                minAlt = minAlt,
                maxAlt = maxAlt,
                speedColor = speedColor,
                altColor = altColor,
                isDistanceAxis = (page == 1),
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(2) { iteration ->
                val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.LightGray
                Box(modifier = Modifier.padding(2.dp).background(color, shape = CircleShape).size(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Table
        Card(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Metric", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Unit", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Min", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Mean", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Max", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Show", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Box(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color.LightGray.copy(alpha = 0.5f)))
                // Speed Row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(speedColor, shape = CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Spd", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("km/h", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(String.format("%.1f", minSpeed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(String.format("%.1f", meanSpeed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(String.format("%.1f", maxSpeed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = showSpeed, 
                            onCheckedChange = { showSpeed = it },
                            modifier = Modifier.height(24.dp),
                            colors = CheckboxDefaults.colors(checkedColor = speedColor)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Altitude Row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(altColor, shape = CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Alt", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("m", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(String.format("%.0f", minAlt), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(String.format("%.0f", meanAlt), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(String.format("%.0f", maxAlt), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = showAltitude, 
                            onCheckedChange = { showAltitude = it },
                            modifier = Modifier.height(24.dp),
                            colors = CheckboxDefaults.colors(checkedColor = altColor)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CombinedMetricLineChart(
    points: List<GPSPointEntity>,
    showSpeed: Boolean,
    showAltitude: Boolean,
    minSpeed: Float,
    maxSpeed: Float,
    minAlt: Float,
    maxAlt: Float,
    speedColor: Color,
    altColor: Color,
    isDistanceAxis: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    val speedRange = if (maxSpeed == minSpeed) 1f else (maxSpeed - minSpeed)
    val altRange = if (maxAlt == minAlt) 1f else (maxAlt - minAlt)

    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    val plotData = remember(points, isDistanceAxis) {
        val list = mutableListOf<Pair<GPSPointEntity, Float>>()
        if (points.isEmpty()) return@remember list
        
        if (isDistanceAxis) {
            var currentDist = 0f
            list.add(points.first() to currentDist)
            for (i in 1 until points.size) {
                val prev = points[i-1]
                val curr = points[i]
                if (!prev.isPaused) {
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        prev.latitude, prev.longitude,
                        curr.latitude, curr.longitude, results
                    )
                    currentDist += results[0]
                }
                list.add(curr to currentDist)
            }
        } else {
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
        }
        list
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val width = size.width
            val height = size.height
            val bottomPadding = 20.dp.toPx()
            val startPadding = 10.dp.toPx() 
            val endPadding = 10.dp.toPx()

            val chartWidth = width - startPadding - endPadding
            val chartHeight = height - bottomPadding

            // Draw Y-axis labels
            val ySteps = 4
            for (i in 0..ySteps) {
                val speedVal = minSpeed + (speedRange * i / ySteps)
                val altVal = minAlt + (altRange * i / ySteps)
                val yPos = chartHeight - (chartHeight * i / ySteps)

                if (showSpeed) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = String.format("%.1f", speedVal),
                        style = labelStyle.copy(color = speedColor, fontWeight = FontWeight.Bold),
                        topLeft = Offset(startPadding + 5f, yPos - 15f)
                    )
                }
                if (showAltitude) {
                    val altText = String.format("%.0f", altVal)
                    val style = labelStyle.copy(color = altColor, fontWeight = FontWeight.Bold)
                    val textLayout = textMeasurer.measure(altText, style)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = altText,
                        style = style,
                        topLeft = Offset(width - endPadding - textLayout.size.width - 5f, yPos - 15f)
                    )
                }
                
                // Grid line
                drawLine(
                    color = Color.LightGray.copy(alpha = 0.5f),
                    start = Offset(startPadding, yPos),
                    end = Offset(width - endPadding, yPos),
                    strokeWidth = 1f
                )
            }

            val maxX = plotData.last().second.coerceAtLeast(1f)

            // Draw X-axis labels
            val xSteps = 4
            for (i in 0..xSteps) {
                val fraction = i.toFloat() / xSteps
                val xPos = startPadding + (chartWidth * fraction)
                
                val label = if (isDistanceAxis) {
                    val distKm = (maxX * fraction) / 1000f
                    String.format("%.1fkm", distKm)
                } else {
                    val timeOffsetMillis = (maxX * fraction).toLong()
                    val minutes = (timeOffsetMillis / 1000) / 60
                    val seconds = (timeOffsetMillis / 1000) % 60
                    String.format("%02d:%02d", minutes, seconds)
                }
                
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    style = labelStyle,
                    topLeft = Offset(xPos - 15f, chartHeight + 5f)
                )
            }

            // Draw Paused areas
            for (i in 0 until plotData.size - 1) {
                val (p1, xVal1) = plotData[i]
                val (p2, xVal2) = plotData[i+1]
                if (p1.isPaused) {
                    val x1 = startPadding + (xVal1 / maxX) * chartWidth
                    val x2 = startPadding + (xVal2 / maxX) * chartWidth
                    drawRect(
                        color = Color.Red.copy(alpha = 0.15f), // Red tint for pause
                        topLeft = Offset(x1, 0f),
                        size = Size(maxOf(1f, x2 - x1), chartHeight)
                    )
                }
            }

            // Draw Paths
            val drawMetricPath = { isSpeed: Boolean ->
                val path = Path()
                val dottedPath = Path()
                var isFirst = true

                for (i in 0 until plotData.size - 1) {
                    val (p1, xVal1) = plotData[i]
                    val (p2, xVal2) = plotData[i+1]
                    
                    val x1 = startPadding + (xVal1 / maxX) * chartWidth
                    val x2 = startPadding + (xVal2 / maxX) * chartWidth
                    
                    val val1 = if (isSpeed) p1.speed * 3.6f else p1.altitude.toFloat()
                    val val2 = if (isSpeed) p2.speed * 3.6f else p2.altitude.toFloat()
                    
                    val y1 = chartHeight - (((val1 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * chartHeight)
                    val y2 = chartHeight - (((val2 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * chartHeight)

                    if (isFirst) {
                        path.moveTo(x1, y1)
                        dottedPath.moveTo(x1, y1)
                        isFirst = false
                    }

                    if (p1.isPaused) {
                        dottedPath.moveTo(x1, y1)
                        dottedPath.lineTo(x2, y2)
                        path.moveTo(x2, y2)
                    } else {
                        path.lineTo(x2, y2)
                        dottedPath.moveTo(x2, y2)
                    }
                }
                
                val cColor = if (isSpeed) speedColor else altColor
                drawPath(path = path, color = cColor, style = Stroke(width = 4f))
                drawPath(path = dottedPath, color = cColor, style = Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)))
            }

            if (showSpeed) drawMetricPath(true)
            if (showAltitude) drawMetricPath(false)
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
