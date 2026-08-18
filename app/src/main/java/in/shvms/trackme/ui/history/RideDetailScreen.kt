package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.ui.components.rememberMessenger
import `in`.shvms.trackme.ui.components.rememberMapStyle
import `in`.shvms.trackme.ui.components.Stat
import `in`.shvms.trackme.ui.components.StatGrid
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import java.io.FileInputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import `in`.shvms.trackme.theme.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.model.usesPace
import `in`.shvms.trackme.ui.components.icon
import `in`.shvms.trackme.ui.components.moveSafely
import `in`.shvms.trackme.ui.components.captureOffscreenMap
import `in`.shvms.trackme.ui.components.visibleBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.export.GPXExporterImpl
import `in`.shvms.trackme.domain.export.NativeSnapshotImageExporterImpl
import `in`.shvms.trackme.domain.export.trimGpsPointsForExport
import `in`.shvms.trackme.ui.home.components.MapLayerHorizontalDrawerButton
import `in`.shvms.trackme.config.AppConfig
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import `in`.shvms.trackme.domain.export.ExportOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import androidx.compose.material.icons.filled.Map
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.log2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import `in`.shvms.trackme.ui.localization.LocalAppStrings

// Currently has no callers. Kept, but pinned to the canonical ride-summary precision so it cannot
// reintroduce TASK-109's screen-vs-shared-artifact mismatch the moment someone does call it.
fun formatDistance(meters: Double, imperial: Boolean = false): String {
    if (!imperial && meters < 1000) return String.format("%.0f m", meters)
    return `in`.shvms.trackme.domain.UnitFormatter.rideDistance(meters, imperial)
}

// Camera position covering the route, computable before the map has a size so the
// initial composition never shows the world view at (0,0). The zoom is an estimate
// from the bounds span; onMapLoaded snaps to the exact bounds fit.
internal fun initialRouteCamera(latLngs: List<LatLng>, bounds: LatLngBounds): CameraPosition {
    if (latLngs.size == 1) return CameraPosition.fromLatLngZoom(latLngs.first(), 16f)
    val latSpan = bounds.northeast.latitude - bounds.southwest.latitude
    var lngSpan = bounds.northeast.longitude - bounds.southwest.longitude
    if (lngSpan < 0) lngSpan += 360.0
    val span = maxOf(latSpan, lngSpan, 1e-4)
    val zoom = (log2(360.0 / span) - 0.5).toFloat().coerceIn(2f, 17f)
    return CameraPosition.fromLatLngZoom(bounds.center, zoom)
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
    androidx.core.graphics.drawable.DrawableCompat.setTint(vectorDrawable, color)
    vectorDrawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** How close a recorded pause must be to the (possibly trimmed) route to be drawn on it. */
private const val PAUSE_MARKER_ON_ROUTE_METERS = 10f

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RideDetailScreen(
    rideId: Long,
    viewModel: RideDetailViewModel = viewModel(),
    navController: NavController? = null
) {
    val strings = LocalAppStrings.current
    val rideWithPoints by viewModel.rideWithPoints.collectAsState()
    val loadState by viewModel.loadState.collectAsState()
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val mapStyle = rememberMapStyle()
    val app = context.applicationContext as `in`.shvms.trackme.TrackMeApp
    val unitSystem by app.preferencesManager.unitSystem.collectAsState()
    val imperial = unitSystem == "imperial"
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

    var previewMapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }
    var pendingGpxFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingGalleryFile by remember { mutableStateOf<java.io.File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFailure by remember { mutableStateOf<ExportPreviewFailure?>(null) }
    var exportInProgress by remember { mutableStateOf(false) }

    val gpxSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val sourceFile = pendingGpxFile
        pendingGpxFile = null
        if (uri != null && sourceFile != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        sourceFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Unable to open destination")
                }.onSuccess {
                    // No withContext needed: show() is non-suspending and dispatches onto the
                    // app-level composition scope itself.
                    messenger.show("Saved successfully")
                }.onFailure { error ->
                    messenger.show("Error saving GPX: ${error.message}")
                }
            }
        }
    }

    val gallerySaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val sourceFile = pendingGalleryFile
        pendingGalleryFile = null
        if (uri == null) {
            exportInProgress = false
        } else if (sourceFile == null) {
            exportInProgress = false
            exportFailure = ExportPreviewFailure.Save
        } else {
            coroutineScope.launch(Dispatchers.IO) {
                val saved = saveImageToDocument(context, sourceFile, uri)
                withContext(Dispatchers.Main) {
                    exportInProgress = false
                    if (saved) {
                        messenger.show("Saved to gallery")
                        showExportDialog = false
                    } else {
                        exportFailure = ExportPreviewFailure.Save
                    }
                }
            }
        }
    }

    var mapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }

    val exportCanRender = remember(rideWithPoints?.points) {
        trimGpsPointsForExport(rideWithPoints?.points.orEmpty(), AppConfig.PRIVACY_TRIM_METERS).size >= 2
    }

    LaunchedEffect(rideId) {
        viewModel.loadRide(rideId)
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is RideDetailViewModel.UiEvent.NavigateBack -> {
                    messenger.show("Ride deleted")
                    navController?.popBackStack()
                }
                is RideDetailViewModel.UiEvent.ShowError -> {
                    messenger.show(event.message)
                }
            }
        }
    }

    val pauseCircleIcon = remember { ExportMarkers.pause(ExportMarkerStyle.StartFinish, 64) }
    val startCircleIcon = remember { ExportMarkers.start(ExportMarkerStyle.StartFinish, 64) }
    val finishCircleIcon = remember { ExportMarkers.finish(ExportMarkerStyle.StartFinish, 64) }

    val pausedLocations = remember(rideWithPoints?.points) {
        pausedMarkerLocations(rideWithPoints?.points.orEmpty()).map { marker ->
            LatLng(marker.latitude, marker.longitude)
        }
    }

    /**
     * Pause markers that actually sit on the exported route.
     *
     * Privacy trim removes the ends of the route, so a pause inside a trimmed section would
     * otherwise be drawn floating away from the line. The scan is O(pauses × points) — cheap per
     * call but not free on a long ride — and both the preview and the export need the same answer,
     * so it is computed once here rather than twice, once of them on the main thread at the moment
     * the user taps Share.
     */
    val pausedOnRoute: (List<GPSPointEntity>) -> List<LatLng> = remember(pausedLocations) {
        { routePoints ->
            pausedLocations.filter { location ->
                routePoints.any { point ->
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        point.latitude, point.longitude,
                        location.latitude, location.longitude,
                        distance
                    )
                    distance[0] <= PAUSE_MARKER_ON_ROUTE_METERS
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
                    val persona = `in`.shvms.trackme.utils.RideUtils.personaFromStoredName(ride.persona)
                    if (`in`.shvms.trackme.utils.RideUtils.isGeneratedTitle(t, ride.startTime, persona)) {
                        val maxKmh = (ride.postRideCalculation?.maxSpeed ?: 0f) * 3.6f
                        t = `in`.shvms.trackme.utils.RideUtils.getDefaultTitle(ride.startTime, persona, maxKmh)
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
                            Text(strings.done)
                        }
                    } else {
                        TextButton(onClick = { isEditing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = strings.edit, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.edit)
                        }
                    }
                }
            )
        },
    ) { padding ->
        if (rideWithPoints == null) {
            if (loadState == RideLoadState.NOT_FOUND) {
                // The ride no longer exists (e.g. a synced ride was cleared on sign-out). Return
                // to the list instead of showing an endless spinner.
                LaunchedEffect(Unit) { navController?.popBackStack() }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            val points = rideWithPoints!!.points
            val ride = rideWithPoints!!.ride
            var scrubIndex by rememberSaveable(rideId) { mutableStateOf<Int?>(null) }
            
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
                        
                        val cameraPositionState = rememberCameraPositionState {
                            position = initialRouteCamera(latLngs, bounds)
                        }
                        var isMapLoaded by remember { mutableStateOf(false) }

                        val pointerBitmap = remember {
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
                            bitmap
                        }

                        val finishFlagIcon = remember {
                            try {
                                val size = 64
                                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    style = android.graphics.Paint.Style.FILL
                                }
                                paint.color = android.graphics.Color.BLACK
                                canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                                paint.color = android.graphics.Color.WHITE
                                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5f, paint)
                                paint.color = android.graphics.Color.BLACK
                                val cs = 14f
                                val cx = size / 2f
                                val cy = size / 2f
                                canvas.drawRect(cx - cs, cy - cs, cx, cy, paint)
                                canvas.drawRect(cx, cy, cx + cs, cy + cs, paint)
                                BitmapDescriptorFactory.fromBitmap(bitmap)
                            } catch (e: Exception) {
                                null
                            }
                        }


                        var mapType by remember { mutableStateOf(MapType.NORMAL) }
                        var isTrafficEnabled by remember { mutableStateOf(false) }

                        if (!isMapLoaded) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                        GoogleMap(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (isMapLoaded) 1f else 0f),
                            cameraPositionState = cameraPositionState,
                            properties = MapProperties(mapType = mapType, isTrafficEnabled = isTrafficEnabled, mapStyleOptions = mapStyle),
                            uiSettings = MapUiSettings(zoomControlsEnabled = false),
                            onMapLoaded = {
                                if (latLngs.size > 1) {
                                    cameraPositionState.moveSafely { CameraUpdateFactory.newLatLngBounds(bounds, 100) }
                                }
                                isMapLoaded = true
                            }
                        ) {
                            MapEffect { map ->
                                mapInstance = map
                            }
                            Polyline(
                                points = latLngs,
                                color = TrackMeBlueDark,
                                width = 10f
                            )

                            pausedLocations.forEach { ll ->
                                Marker(
                                    state = MarkerState(position = ll),
                                    title = strings.statusPaused,
                                    snippet = "${strings.speed}: ${`in`.shvms.trackme.domain.UnitFormatter.speed(0.0, imperial)}",
                                    icon = pauseCircleIcon,
                                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                                )
                            }

                            Marker(
                                state = remember(latLngs.last()) { MarkerState(position = latLngs.last()) },
                                title = strings.mapFinish,
                                snippet = strings.whenRideEnds,
                                icon = finishFlagIcon,
                                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                            )

                            if (scrubIndex != null && scrubIndex!! in points.indices) {
                                val p = points[scrubIndex!!]
                                val scrubIcon = remember(pointerBitmap) {
                                    try {
                                        BitmapDescriptorFactory.fromBitmap(pointerBitmap)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                Marker(
                                    state = remember(p.latitude, p.longitude) {
                                        MarkerState(position = LatLng(p.latitude, p.longitude))
                                    },
                                    title = strings.scrub,
                                    snippet = "${strings.speed}: ${`in`.shvms.trackme.domain.UnitFormatter.speed(p.speed.toDouble(), imperial)}",
                                    icon = scrubIcon,
                                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                                )
                            }
                        }

                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 12.dp)) {
                            MapLayerHorizontalDrawerButton(
                                currentMapType = mapType,
                                onMapTypeSelected = { mapType = it },
                                isTrafficEnabled = isTrafficEnabled,
                                onTrafficToggle = { isTrafficEnabled = !isTrafficEnabled }
                            )
                        }
                    } else {
                        Text("No GPS data available", modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val chartPersona = remember(ride.persona) {
                    runCatching { RidePersona.valueOf(ride.persona) }.getOrDefault(RidePersona.AUTO)
                }
                val hasChartData = points.size > 1 &&
                    (ride.postRideCalculation?.distance ?: 0.0) >= `in`.shvms.trackme.service.TrackingService.JUNK_RIDE_DISTANCE_METERS

                // Splits are offered only on foot, and are the default there. The line chart
                // answers "what was I doing at this moment", which is what the scrubber is for;
                // splits answer "was I consistent, did I fade", which on foot is usually the
                // question. On wheels a per-kilometre table says little, so the toggle is absent
                // rather than present and pointless.
                val offersSplits = chartPersona.usesPace
                var showSplits by rememberSaveable(offersSplits) { mutableStateOf(offersSplits) }
                val splits = remember(points, imperial, offersSplits) {
                    if (offersSplits) {
                        `in`.shvms.trackme.domain.rideSplits(points, imperial)
                    } else {
                        emptyList()
                    }
                }

                if (hasChartData && offersSplits && splits.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = showSplits,
                            onClick = { showSplits = true },
                            label = { Text(strings.splitsTitle) }
                        )
                        FilterChip(
                            selected = !showSplits,
                            onClick = { showSplits = false },
                            label = { Text(strings.chartTitle) }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (hasChartData && showSplits && splits.isNotEmpty()) {
                    RideSplitsSection(
                        splits = splits,
                        imperial = imperial,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (hasChartData) {
                    // The effort series is pace on foot and speed on wheels. Plotting km/h for a
                    // walk gives a flat line between 4 and 6 -- a real change in effort is under
                    // two km/h, which is indistinguishable from GPS noise at chart scale, where
                    // the same change is over a minute per kilometre.
                    val chartUsesPace = chartPersona.usesPace
                    val speeds = points.map { effortValue(it.speed, chartUsesPace, imperial) }
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
                            if (!curr.isPaused) {
                                val result = FloatArray(1)
                                android.location.Location.distanceBetween(prev.latitude, prev.longitude, curr.latitude, curr.longitude, result)
                                if (result[0] >= 3.5f) {
                                    totalDist += result[0]
                                }
                            }
                            distances[i] = totalDist
                        }
                        distances
                    }

                    CombinedMetricLineChart(
                        points = points,
                        usesPace = chartUsesPace,
                        minSpeed = minSpeed,
                        maxSpeed = maxSpeed,
                        minAlt = minAlt,
                        maxAlt = maxAlt,
                        // C1: chart hues encode data series, not brand or state.
                        speedColor = ChartSpeed,
                        altColor = ChartAltitude,
                        scrubIndex = scrubIndex,
                        imperial = imperial,
                        modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    val indexToShow = scrubIndex ?: (points.size - 1)
                    val elapsedMs = points[indexToShow].timestamp - ride.startTime
                    val elapsedFormatted = formatDuration(elapsedMs)
                    val authoritativeDistKm = ((ride.postRideCalculation?.distance ?: 0.0) / 1000.0).toFloat()
                    val lastCumDist = cumulativeDistances.lastOrNull()?.takeIf { it > 0.01f } ?: 1f
                    val distKm = if (scrubIndex == null || scrubIndex == points.size - 1) {
                        authoritativeDistKm
                    } else {
                        (cumulativeDistances[indexToShow] / lastCumDist) * authoritativeDistKm
                    }
                    
                    Text(
                        text = "${strings.durationShortLabel}: $elapsedFormatted  |  ${strings.distanceShortLabel}: ${`in`.shvms.trackme.domain.UnitFormatter.rideDistance(distKm * 1000.0, imperial)}",
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
                        colors = SliderDefaults.colors(
                            thumbColor = TrackMeBlue,
                            activeTrackColor = TrackMeBlue,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .semantics {
                                contentDescription = strings.timelineScrubberAccessibility
                            }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (points.isNotEmpty()) {
                    Text(
                        text = strings.notEnoughGpsDataForChart,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val ridePersona = remember(ride.persona) {
                            runCatching { RidePersona.valueOf(ride.persona) }.getOrDefault(RidePersona.AUTO)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(strings.rideStats, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = ridePersona.icon(),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = strings.personaLabel(ridePersona),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        val dateFormat = remember {
                            java.text.SimpleDateFormat("MMM dd, yyyy - HH:mm", java.util.Locale.getDefault())
                        }
                        // Start time reads as a caption under the heading rather than as a grid
                        // cell. In a third of a row it was always truncated to "Aug 17, ..." — the one
                        // part of a timestamp that carries no information.
                        Text(
                            text = dateFormat.format(java.util.Date(ride.startTime)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // Hairline-separated cells with tabular figures so the columns stop
                        // jittering as values change.
                        //
                        // The effort column follows the persona: a walk or a run reports pace,
                        // everything else reports speed. Freeing the start-time cell left room for
                        // the best/max figure, which was already computed and only ever used to
                        // name the ride.
                        val effortIsPace = ridePersona.usesPace
                        val avgMps = (ride.postRideCalculation?.avgSpeed ?: 0f).toDouble()
                        val maxMps = (ride.postRideCalculation?.maxSpeed ?: 0f).toDouble()
                        StatGrid(
                            listOf(
                                Stat(strings.distance, `in`.shvms.trackme.domain.UnitFormatter.rideDistance(ride.postRideCalculation?.distance ?: 0.0, imperial)),
                                Stat(strings.duration, formatDuration((ride.endTime ?: ride.startTime) - ride.startTime)),
                                Stat(
                                    if (effortIsPace) strings.avgPace else strings.avgSpeed,
                                    if (effortIsPace) {
                                        `in`.shvms.trackme.domain.UnitFormatter.pace(avgMps, imperial)
                                    } else {
                                        `in`.shvms.trackme.domain.UnitFormatter.speed(avgMps, imperial)
                                    }
                                ),
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatGrid(
                            listOf(
                                Stat(strings.gpsPoints, points.size.toString()),
                                Stat(strings.maxGForce, String.format("%.2f G", (ride.postRideCalculation?.maxAcceleration ?: 0f) / 9.8f)),
                                Stat(
                                    if (effortIsPace) strings.bestPace else strings.maxSpeed,
                                    if (effortIsPace) {
                                        `in`.shvms.trackme.domain.UnitFormatter.pace(maxMps, imperial)
                                    } else {
                                        `in`.shvms.trackme.domain.UnitFormatter.speed(maxMps, imperial)
                                    }
                                ),
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                if (rideWithPoints != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (points.isNotEmpty()) {
                            TextButton(
                                onClick = { showExportDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = strings.share, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.share, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }

                            // E9: the replay-video action moved into the shared export preview
                            // (see the ExportPreviewDialog `videoAction` slot below). This row is
                            // deliberately back to three stable-width items — Share / Export GPX /
                            // Delete — so no growing label can squeeze its neighbours.
                            TextButton(
                                onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val exporter = GPXExporterImpl()
                                        val gpxFile = exporter.export(rideWithPoints!!, context)
                                        val values = android.content.ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, gpxFile.name)
                                            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                                        }
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                            if (uri != null) {
                                                context.contentResolver.openOutputStream(uri)?.use { out ->
                                                    gpxFile.inputStream().use { input -> input.copyTo(out) }
                                                } ?: error("Unable to open Downloads")
                                                messenger.show("Saved to Downloads")
                                            } else {
                                                error("Unable to create Downloads entry")
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                pendingGpxFile = gpxFile
                                                gpxSaveLauncher.launch(gpxFile.name)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        messenger.show(strings.exportFailed)
                                    }
                                }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = strings.exportGpx, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.exportGpx, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        TextButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = strings.delete, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.delete, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
            title = { Text(strings.deleteRide) },
            text = { Text("Are you sure you want to delete this ride? This action cannot be undone and will delete it from cloud if synced.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteRide(rideId)
                }) {
                    Text(strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showExportDialog) {
        fun handleExport(settings: ExportPreviewSettings, share: Boolean) {
            val ride = rideWithPoints ?: return
            val routePoints = if (settings.privacyTrim) {
                trimGpsPointsForExport(ride.points, AppConfig.PRIVACY_TRIM_METERS)
            } else {
                ride.points
            }
            if (routePoints.size < 2) {
                exportFailure = ExportPreviewFailure.Render
                return
            }
            exportFailure = null
            exportInProgress = true

            // The framing the user actually composed, as geographic bounds rather than as a zoom
            // level. Zoom is meaningless without a viewport size — the same zoom on a larger
            // surface shows more ground — so carrying bounds is what makes the export reproduce
            // the preview at a different resolution. Rotation and tilt are disabled on the preview
            // map, so bounds describe the framing completely.
            val framing = previewMapInstance?.visibleBounds()
            val (exportWidth, exportHeight) = settings.exportSize
            val markerSize = ExportRenderScale.markerSize(exportWidth)
            val markerStyle = settings.markerStyle
            val pausedForExport = pausedOnRoute(routePoints)
            val exportLatLngs = routePoints.map { LatLng(it.latitude, it.longitude) }

            captureOffscreenMap(
                context = context,
                widthPx = exportWidth,
                heightPx = exportHeight,
                mapType = settings.mapType,
                configure = { map ->
                    settings.mapStyle(context)?.let { map.setMapStyle(it) }
                    map.addPolyline(
                        com.google.android.gms.maps.model.PolylineOptions()
                            .addAll(exportLatLngs)
                            .color(TrackMeBlueDark.toArgb())
                            .width(ExportRenderScale.routeStroke(exportWidth))
                    )
                    ExportMarkers.pause(markerStyle, markerSize)?.let { icon ->
                        pausedForExport.forEach { location ->
                            map.addMarker(
                                com.google.android.gms.maps.model.MarkerOptions()
                                    .position(location).icon(icon).anchor(0.5f, 0.5f)
                            )
                        }
                    }
                    if (markerStyle.marksStart) {
                        map.addMarker(
                            com.google.android.gms.maps.model.MarkerOptions()
                                .position(exportLatLngs.first())
                                .icon(ExportMarkers.start(markerStyle, markerSize))
                        )
                    }
                    if (markerStyle.marksFinish) {
                        map.addMarker(
                            com.google.android.gms.maps.model.MarkerOptions()
                                .position(exportLatLngs.last())
                                .icon(ExportMarkers.finish(markerStyle, markerSize))
                        )
                    }
                    val target = framing ?: LatLngBounds.Builder()
                        .also { builder -> exportLatLngs.forEach(builder::include) }
                        .build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(target, 0))
                }
            ) { bitmap, _ ->
                if (bitmap == null) {
                    exportInProgress = false
                    exportFailure = ExportPreviewFailure.Render
                    return@captureOffscreenMap
                }
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching {
                        NativeSnapshotImageExporterImpl().export(
                            ride,
                            settings.ratio.first,
                            settings.ratio.second,
                            context,
                            bitmap,
                            ExportOptions(
                                showStats = settings.statsOverlay.isVisible,
                                statsPanel = settings.statsOverlay.rect()?.let { panel ->
                                    `in`.shvms.trackme.domain.export.StatsPanelRect(
                                        left = panel.left,
                                        top = panel.top,
                                        right = panel.right,
                                        bottom = panel.bottom,
                                        cornerFraction = panel.inset,
                                        alignEnd = settings.statsOverlay.alignsTextEnd,
                                    )
                                },
                                isDarkTheme = settings.darkTheme,
                                showDistance = settings.showDistance,
                                showDuration = settings.showDuration,
                                showDate = settings.showDate,
                                routePoints = routePoints
                            )
                        )
                    }.onSuccess { imageFile ->
                        val title = ride.ride.title?.ifEmpty { "TrackMe Ride" } ?: "TrackMe Ride"
                        if (share) {
                            withContext(Dispatchers.Main) {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, strings.shareImage))
                                exportInProgress = false
                                showExportDialog = false
                            }
                        } else if (shouldUseGalleryDocumentPicker()) {
                            withContext(Dispatchers.Main) {
                                pendingGalleryFile = imageFile
                                val launched = tryLaunchGalleryDocument {
                                    gallerySaveLauncher.launch(galleryImageDisplayName(title))
                                }
                                if (!launched) {
                                    pendingGalleryFile = null
                                    exportInProgress = false
                                    exportFailure = ExportPreviewFailure.Save
                                }
                            }
                        } else {
                            val saved = saveImageToGallery(context, imageFile, title)
                            withContext(Dispatchers.Main) {
                                exportInProgress = false
                                if (saved) {
                                    messenger.show("Saved to gallery")
                                    showExportDialog = false
                                } else {
                                    exportFailure = ExportPreviewFailure.Save
                                }
                            }
                        }
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            exportInProgress = false
                            exportFailure = ExportPreviewFailure.Render
                        }
                    }
                }
            } ?: run {
                exportInProgress = false
                exportFailure = ExportPreviewFailure.Render
            }
        }

        // E9: replay-video export now lives inside the preview rather than Ride Detail's action
        // row. Null when the ride has no loaded points, so the slot is absent rather than inert.
        val currentRideWithPoints = rideWithPoints
        val replayVideoAction: (@Composable (ExportPreviewSettings) -> Unit)? = if (currentRideWithPoints != null) {
            { videoSettings ->
                ReplayExportAction(
                    rideWithPoints = currentRideWithPoints,
                    context = context,
                    settings = videoSettings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            null
        }

        ExportPreviewDialog(
            title = strings.exportPreviewTitle,
            initialRatio = Pair(AppConfig.HQ_IMAGE_WIDTH, AppConfig.HQ_IMAGE_RATIO_9_16),
            initialPrivacyTrim = true,
            canExport = exportCanRender,
            isExporting = exportInProgress,
            errorMessage = when (exportFailure) {
                ExportPreviewFailure.Render -> strings.exportRetryMessage
                ExportPreviewFailure.Save -> strings.exportFailed
                null -> null
            },
            onDismiss = { showExportDialog = false },
            onShare = { settings -> handleExport(settings, share = true) },
            onSave = { settings ->
                handleExport(settings, share = false)
            },
            onRetry = { settings ->
                exportFailure = null
                handleExport(settings, share = true)
            },
            videoAction = replayVideoAction
        ) { modifier, settings ->
            val routePoints = remember(rideWithPoints?.points, settings.privacyTrim) {
                val points = rideWithPoints?.points.orEmpty()
                if (settings.privacyTrim) trimGpsPointsForExport(points, AppConfig.PRIVACY_TRIM_METERS) else points
            }
            if (routePoints.size < 2) {
                Box(modifier, contentAlignment = Alignment.Center) {
                    Text(strings.notEnoughGpsDataForExport)
                }
            } else {
                val latLngs = remember(routePoints) { routePoints.map { LatLng(it.latitude, it.longitude) } }
                val bounds = remember(latLngs) {
                    LatLngBounds.Builder().also { builder -> latLngs.forEach(builder::include) }.build()
                }
                val cameraPositionState = rememberCameraPositionState {
                    position = initialRouteCamera(latLngs, bounds)
                }
                val pausedForRoute = remember(pausedOnRoute, routePoints) { pausedOnRoute(routePoints) }
                BoxWithConstraints(modifier) {
                    // The preview's own pixel size. Everything drawn on it is scaled from this so
                    // the preview and the export render the same picture — see ExportRenderScale.
                    val density = LocalDensity.current
                    val previewWidthPx = with(density) { maxWidth.roundToPx() }
                    val previewHeightPx = with(density) { maxHeight.roundToPx() }

                    // Re-fit on ratio change, not only when the route changes. The viewport changes
                    // shape when the ratio does, and a fit computed for the previous shape leaves
                    // the route cropped or stranded in the middle of the new one.
                    var isPreviewMapLoaded by remember { mutableStateOf(false) }

                    // Gated on the map actually being loaded, not on a 200ms guess.

                    //

                    // `newLatLngBounds` throws if the map has no size yet, and `moveSafely` swallows that by

                    // design, so a fit that lost the race left the camera on `initialRouteCamera` -- an estimate

                    // from the bounds span that caps at zoom 17. On a short urban route that is street level, and

                    // the result was a preview showing the middle of the route with both ends running off the

                    // frame, looking as though the polyline had been drawn incompletely.

                    //

                    // The remaining delay is for the resize on a ratio change to settle, not for the map to exist.

                    LaunchedEffect(bounds, previewWidthPx, previewHeightPx, isPreviewMapLoaded) {

                        if (!isPreviewMapLoaded) return@LaunchedEffect

                        if (previewWidthPx <= 0 || previewHeightPx <= 0) return@LaunchedEffect

                        kotlinx.coroutines.delay(120)
                        cameraPositionState.moveSafely {
                            CameraUpdateFactory.newLatLngBounds(
                                bounds,
                                ExportRenderScale.fitPadding(previewWidthPx, previewHeightPx)
                            )
                        }
                    }

                    val previewMarkerIcons = remember(previewWidthPx, settings.markerStyle) {
                        val size = ExportRenderScale.markerSize(previewWidthPx)
                        Triple(
                            ExportMarkers.start(settings.markerStyle, size),
                            ExportMarkers.finish(settings.markerStyle, size),
                            ExportMarkers.pause(settings.markerStyle, size)
                        )
                    }

                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(
                            mapType = settings.mapType,
                            isTrafficEnabled = false,
                            mapStyleOptions = settings.mapStyle(context)
                        ),
                        // Rotation and tilt are off on purpose. A tilted or rotated export frame is
                        // not something this screen offers, and every extra gesture the map claims
                        // is one more way a pan can be misread. It also lets the export reproduce
                        // the framing from the visible bounds alone.
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled = false,
                            compassEnabled = false,
                            rotationGesturesEnabled = false,
                            tiltGesturesEnabled = false,
                            mapToolbarEnabled = false
                        )
                        ,
                        onMapLoaded = { isPreviewMapLoaded = true }
                    ) {
                        MapEffect { map -> previewMapInstance = map }
                        Polyline(
                            points = latLngs,
                            color = TrackMeBlueDark,
                            width = ExportRenderScale.routeStroke(previewWidthPx)
                        )
                        previewMarkerIcons.third?.let { pauseIcon ->
                            pausedForRoute.forEach { location ->
                                Marker(state = MarkerState(position = location), icon = pauseIcon, anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f))
                            }
                        }
                        if (settings.markerStyle.marksStart) {
                            Marker(state = remember(latLngs.first()) { MarkerState(position = latLngs.first()) }, title = strings.mapStart, icon = previewMarkerIcons.first)
                        }
                        if (settings.markerStyle.marksFinish) {
                            Marker(state = remember(latLngs.last()) { MarkerState(position = latLngs.last()) }, title = strings.mapFinish, icon = previewMarkerIcons.second)
                        }
                    }
                    settings.statsOverlay.rect()?.let { panel ->
                        val distanceStr = `in`.shvms.trackme.domain.UnitFormatter.rideDistance(rideWithPoints?.ride?.postRideCalculation?.distance ?: 0.0, imperial)
                        val durationMillis = (rideWithPoints?.ride?.endTime ?: rideWithPoints?.ride?.startTime ?: 0L) - (rideWithPoints?.ride?.startTime ?: 0L)
                        val seconds = durationMillis / 1000
                        val durationStr = String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(rideWithPoints?.ride?.startTime ?: 0L))
                        val stats = buildList {
                            if (settings.showDate) add(dateStr)
                            if (settings.showDuration) add(durationStr)
                            if (settings.showDistance) add(distanceStr)
                        }
                        val panelColor = if (settings.darkTheme) {
                            Color(AppConfig.OVERLAY_BANNER_COLOR).copy(alpha = AppConfig.OVERLAY_BANNER_ALPHA / 255f)
                        } else {
                            Color.White.copy(alpha = 0.85f)
                        }
                        val onPanel = if (settings.darkTheme) Color.White else Color.Black
                        val cornerPx = panel.cornerRadiusPx(previewWidthPx, previewHeightPx)
                        val cornerDp = with(density) { cornerPx.toDp() }

                        // Positioned from the shared normalised rect, the same one the exporter
                        // uses, so the panel lands in the same place in the file as on screen.
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { panel.leftPx(previewWidthPx).toDp() },
                                    y = with(density) { panel.topPx(previewHeightPx).toDp() }
                                )
                                .width(with(density) { (panel.widthFraction * previewWidthPx).toDp() })
                                .height(with(density) { (panel.heightFraction * previewHeightPx).toDp() })
                                .clip(RoundedCornerShape(cornerDp))
                                .background(panelColor)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = if (settings.statsOverlay.alignsTextEnd) {
                                Alignment.CenterEnd
                            } else {
                                Alignment.CenterStart
                            }
                        ) {
                            Column(
                                horizontalAlignment = if (settings.statsOverlay.alignsTextEnd) {
                                    Alignment.End
                                } else {
                                    Alignment.Start
                                }
                            ) {
                                Text(
                                    rideWithPoints?.ride?.title?.ifEmpty { "TrackMe Ride" } ?: "TrackMe Ride",
                                    color = onPanel,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = if (settings.statsOverlay.isCard) {
                                        MaterialTheme.typography.titleSmall
                                    } else {
                                        MaterialTheme.typography.titleMedium
                                    }
                                )
                                Text(
                                    stats.joinToString(" • "),
                                    color = onPanel,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = if (settings.statsOverlay.isCard) {
                                        MaterialTheme.typography.bodySmall
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    }
                                )
                            }
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
    /** Whether the effort series is pace (minutes per unit) rather than speed. */
    usesPace: Boolean = false,
    minSpeed: Float,
    maxSpeed: Float,
    minAlt: Float,
    maxAlt: Float,
    speedColor: Color,
    altColor: Color,
    scrubIndex: Int? = null,
    imperial: Boolean = false,
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

    // Adaptive visual smoothing window so long rides stay uniform and elegant without spiky noise,
    // while zero underlying data is lost for scrubber inspection.
    val smoothedSeries = remember(plotData) {
        val n = plotData.size
        val radius = when {
            n < 30 -> 1
            n < 150 -> 2
            n < 400 -> 4
            else -> (n / 65).coerceIn(4, 18)
        }
        val sSpeeds = FloatArray(n)
        val sAlts = FloatArray(n)
        for (i in 0 until n) {
            var sumSpeed = 0f
            var sumAlt = 0f
            var weightSum = 0f
            for (j in (i - radius)..(i + radius)) {
                if (j in 0 until n) {
                    val dist = kotlin.math.abs(j - i)
                    val w = 1f / (1f + dist * 0.8f)
                    sumSpeed += plotData[j].first.speed * 3.6f * w
                    sumAlt += plotData[j].first.altitude.toFloat() * w
                    weightSum += w
                }
            }
            sSpeeds[i] = sumSpeed / weightSum
            sAlts[i] = sumAlt / weightSum
        }
        sSpeeds to sAlts
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.semantics {
            contentDescription = buildChartAccessibilityDescription(points, imperial)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val topPadding = 36f
            val bottomPadding = 16f
            val usableHeight = height - topPadding - bottomPadding

            val maxX = plotData.last().second.coerceAtLeast(1f)

            // Draw subtle vertical red dotted lines for GPS signal gaps > 25 seconds
            for (i in 0 until plotData.size - 1) {
                val (p1, xVal1) = plotData[i]
                val (p2, xVal2) = plotData[i + 1]
                val gapMs = p2.timestamp - p1.timestamp
                if (gapMs > 25_000L) {
                    val xStart = (xVal1 / maxX) * width
                    val xEnd = (xVal2 / maxX) * width
                    var stripeX = xStart
                    while (stripeX <= xEnd) {
                        drawLine(
                            color = Color.Red.copy(alpha = 0.35f),
                            start = Offset(stripeX, 0f),
                            end = Offset(stripeX, height),
                            strokeWidth = 1.5f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                        )
                        stripeX += 14f
                    }
                }
            }

            // Draw clean, smoothed cubic curve paths
            val drawMetricPath = { isSpeed: Boolean ->
                val path = Path()
                var isFirst = true

                val values = if (isSpeed) smoothedSeries.first else smoothedSeries.second

                for (i in 0 until plotData.size - 1) {
                    val xVal1 = plotData[i].second
                    val x1 = (xVal1 / maxX) * width
                    val val1 = values[i]
                    val y1 = topPadding + usableHeight - (((val1 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * usableHeight)

                    if (isFirst) {
                        path.moveTo(x1, y1)
                        isFirst = false
                    } else {
                        val prevX = (plotData[i - 1].second / maxX) * width
                        val prevVal1 = values[i - 1]
                        val prevY = topPadding + usableHeight - (((prevVal1 - (if (isSpeed) minSpeed else minAlt)) / (if (isSpeed) speedRange else altRange)) * usableHeight)
                        val cpX = (prevX + x1) / 2f
                        path.cubicTo(cpX, prevY, cpX, y1, x1, y1)
                    }
                }
                
                val cColor = if (isSpeed) speedColor else altColor
                drawPath(path = path, color = cColor, style = Stroke(width = 3.5f))
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
                val sVal = effortValue(p.speed, usesPace, imperial)
                val aVal = p.altitude.toFloat()
                
                val sY = topPadding + usableHeight - (((sVal - minSpeed) / speedRange) * usableHeight)
                val aY = topPadding + usableHeight - (((aVal - minAlt) / altRange) * usableHeight)
                
                // Draw Speed Intersection
                drawCircle(color = speedColor, radius = 8f, center = Offset(scrubX, sY))
                drawCircle(color = Color.White, radius = 4f, center = Offset(scrubX, sY))
                
                val sText = if (usesPace) {
                    `in`.shvms.trackme.domain.UnitFormatter.pace(p.speed.toDouble(), imperial)
                } else {
                    `in`.shvms.trackme.domain.UnitFormatter.speed(p.speed.toDouble(), imperial)
                }
                val sTextLayout = textMeasurer.measure(sText, style = labelStyle.copy(color = Color.White))
                val sLabelW = sTextLayout.size.width + 16f
                val sLabelH = sTextLayout.size.height + 8f
                val sDrawRight = scrubX + 12f + sLabelW < size.width
                val sLabelX = if (sDrawRight) scrubX + 12f else scrubX - 12f - sLabelW
                drawRoundRect(
                    color = speedColor.copy(alpha = 0.8f),
                    topLeft = Offset(sLabelX - 8f, sY - 24f - 4f),
                    size = Size(sLabelW, sLabelH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                drawText(
                    textLayoutResult = sTextLayout,
                    topLeft = Offset(sLabelX, sY - 24f)
                )

                // Draw Altitude Intersection
                drawCircle(color = altColor, radius = 8f, center = Offset(scrubX, aY))
                drawCircle(color = Color.White, radius = 4f, center = Offset(scrubX, aY))
                
                val aText = String.format("%.0f m", aVal)
                val aTextLayout = textMeasurer.measure(aText, style = labelStyle.copy(color = Color.White))
                val aLabelW = aTextLayout.size.width + 16f
                val aLabelH = aTextLayout.size.height + 8f
                val aDrawRight = scrubX + 12f + aLabelW < size.width
                val aLabelX = if (aDrawRight) scrubX + 12f else scrubX - 12f - aLabelW
                drawRoundRect(
                    color = altColor.copy(alpha = 0.8f),
                    topLeft = Offset(aLabelX - 8f, aY - 24f - 4f),
                    size = Size(aLabelW, aLabelH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                drawText(
                    textLayoutResult = aTextLayout,
                    topLeft = Offset(aLabelX, aY - 24f)
                )

            }
        }
    }
}

internal fun buildChartAccessibilityDescription(points: List<GPSPointEntity>, imperial: Boolean = false): String {
    if (points.isEmpty()) return "Speed and altitude chart. No GPS data available."

    val duration = formatDuration((points.last().timestamp - points.first().timestamp).coerceAtLeast(0L))
    val averageSpeedMps = points.map { it.speed.toDouble() }.average()
    val minAltitude = points.minOf { it.altitude }
    val maxAltitude = points.maxOf { it.altitude }
    val gapCount = points.zipWithNext().count { (previous, current) ->
        current.timestamp - previous.timestamp > 25_000L
    }
    val gapSummary = when (gapCount) {
        0 -> "No GPS signal gaps."
        1 -> "1 GPS signal gap."
        else -> "$gapCount GPS signal gaps."
    }

    return "Speed and altitude chart. Duration $duration. " +
        "Average speed ${`in`.shvms.trackme.domain.UnitFormatter.speed(averageSpeedMps, imperial)}. " +
        "Altitude from ${String.format(Locale.getDefault(), "%.0f", minAltitude)} to " +
        "${String.format(Locale.getDefault(), "%.0f", maxAltitude)} meters. $gapSummary"
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "00:00:00"
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * One sample of the effort series, in whatever unit the chart is plotting.
 *
 * Speed goes up with effort and pace goes down, so the two curves are mirror images. That is not a
 * bug to correct: a pace chart dipping is what "went faster" looks like to anyone who reads one,
 * and flipping the axis to make it rise would disagree with every other running app.
 *
 * Stopped samples are clamped to the guard ceiling rather than allowed to run to infinity, which
 * would flatten the entire rest of the ride into a single line at the bottom of the plot.
 */
internal fun effortValue(speedMps: Float, usesPace: Boolean, imperial: Boolean): Float {
    if (!usesPace) return speedMps * if (imperial) 2.236936f else 3.6f
    if (speedMps < `in`.shvms.trackme.domain.UnitFormatter.PACE_MIN_SPEED_MPS) {
        return `in`.shvms.trackme.domain.UnitFormatter.PACE_MAX_MINUTES.toFloat()
    }
    val minutes = `in`.shvms.trackme.domain.UnitFormatter
        .paceSecondsPerUnit(speedMps.toDouble(), imperial).toFloat() / 60f
    return minutes.coerceAtMost(`in`.shvms.trackme.domain.UnitFormatter.PACE_MAX_MINUTES.toFloat())
}
