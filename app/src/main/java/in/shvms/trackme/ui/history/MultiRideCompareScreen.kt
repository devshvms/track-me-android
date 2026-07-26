package `in`.shvms.trackme.ui.history

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.export.ComparisonImageExporter
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val comparisonRouteColors = listOf(
    0xFF00A6C7.toInt(), // TrackMe cyan
    0xFF7557B5.toInt(),
    0xFF008577.toInt(),
    0xFFD97706.toInt(),
    0xFF2F6FED.toInt(),
    0xFFC2417A.toInt(),
    0xFF4B7F52.toInt(),
    0xFF8A5A2B.toInt()
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MultiRideCompareRoute(
    rideIds: List<Long>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rides by produceState<List<RideWithPoints>?>(initialValue = null, rideIds) {
        value = withContext(Dispatchers.IO) {
            val dao = (context.applicationContext as TrackMeApp).database.rideDao()
            rideIds.distinct().take(MAX_COMPARISON_RIDES).mapNotNull { dao.getRideWithPointsById(it) }
        }
    }
    when (val loaded = rides) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> MultiRideCompareScreen(loaded, onBack)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MultiRideCompareScreen(
    rides: List<RideWithPoints>,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val routes = remember(rides) { prepareComparisonRoutes(rides) }
    val visibleRoutes = remember(routes) { routes.filter { it.points.isNotEmpty() } }
    val connectors = remember(routes) { comparisonConnectors(routes) }
    var showPreview by remember { mutableStateOf(false) }

    val allLatLngs = remember(visibleRoutes) {
        visibleRoutes.flatMap { route -> route.points.map { LatLng(it.latitude, it.longitude) } }
    }
    val bounds = remember(allLatLngs) {
        if (allLatLngs.isEmpty()) null else LatLngBounds.Builder().also { builder ->
            allLatLngs.forEach(builder::include)
        }.build()
    }
    val cameraPositionState = rememberCameraPositionState {
        if (bounds != null) position = initialRouteCamera(allLatLngs, bounds)
    }
    val openPreview = {
        if (visibleRoutes.isEmpty()) {
            toast(context, strings.compareRidesMapNotReady)
        } else {
            showPreview = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.compareRidesTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
                actions = {
                    IconButton(onClick = openPreview, enabled = visibleRoutes.isNotEmpty()) {
                        Icon(Icons.Default.Share, contentDescription = strings.compareRidesShare)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (routes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings.compareRidesNoSelection)
                }
                return@Scaffold
            }

            if (visibleRoutes.isNotEmpty() && bounds != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                ) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(isTrafficEnabled = false),
                        uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = false)
                    ) {
                        visibleRoutes.forEachIndexed { index, route ->
                            val routeColor = comparisonRouteColors[index % comparisonRouteColors.size]
                            val latLngs = route.points.map { LatLng(it.latitude, it.longitude) }
                            Polyline(points = latLngs, color = Color(routeColor), width = 8f)
                            Marker(
                                state = remember(route.ride.ride.id) { MarkerState(position = latLngs.first()) },
                                icon = remember(route.label, routeColor) {
                                    letterMarkerIcon(context, route.label, routeColor)
                                },
                                title = route.label
                            )
                        }
                        connectors.forEach { connector ->
                            Polyline(
                                points = listOf(
                                    LatLng(connector.from.latitude, connector.from.longitude),
                                    LatLng(connector.to.latitude, connector.to.longitude)
                                ),
                                color = Color.Gray,
                                width = 5f,
                                pattern = listOf(Dot(), Gap(12f))
                            )
                        }
                    }
                    LaunchedEffect(bounds) {
                        kotlinx.coroutines.delay(200)
                        cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 72))
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = strings.compareRidesNoGps,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (connectors.isNotEmpty()) {
                Text(
                    text = strings.compareRidesSequenceLink,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(routes, key = { it.ride.ride.id }) { route ->
                    val color = Color(comparisonRouteColors[routes.indexOf(route) % comparisonRouteColors.size])
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(14.dp).background(color),
                            contentAlignment = Alignment.Center
                        ) { Text(route.label, color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        Spacer(Modifier.width(8.dp))
                        Text(route.ride.ride.title?.ifBlank { strings.rideHistoryTitle } ?: strings.rideHistoryTitle)
                    }
                }
            }
            Button(
                onClick = openPreview,
                enabled = visibleRoutes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(strings.compareRidesShare)
            }
        }
    }

    if (showPreview) {
        AggregateRidePreviewDialog(
            routes = routes,
            visibleRoutes = visibleRoutes,
            connectors = connectors,
            onDismiss = { showPreview = false }
        )
    }
}

/**
 * Preview-first export surface for aggregate rides. The map snapshot is captured only after the
 * user confirms Share, so backing out never creates a temporary export file or opens the chooser.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AggregateRidePreviewDialog(
    routes: List<ComparisonRoute>,
    visibleRoutes: List<ComparisonRoute>,
    connectors: List<ComparisonConnector>,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewMapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }
    var exportRatio by remember { mutableStateOf(1f) }
    var exportRatioLabel by remember { mutableStateOf("1:1") }
    var exportMapType by remember { mutableStateOf(MapType.NORMAL) }
    var showLegend by remember { mutableStateOf(true) }
    var showSequence by remember { mutableStateOf(true) }
    var isSharing by remember { mutableStateOf(false) }

    val allLatLngs = remember(visibleRoutes) {
        visibleRoutes.flatMap { route -> route.points.map { LatLng(it.latitude, it.longitude) } }
    }
    val bounds = remember(allLatLngs) {
        if (allLatLngs.isEmpty()) null else LatLngBounds.Builder().also { builder ->
            allLatLngs.forEach(builder::include)
        }.build()
    }
    val cameraPositionState = rememberCameraPositionState {
        if (bounds != null) position = initialRouteCamera(allLatLngs, bounds)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(strings.aggregatePreviewTitle) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(exportRatio)
                    ) {
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            properties = MapProperties(mapType = exportMapType, isTrafficEnabled = false),
                            uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = false)
                        ) {
                            MapEffect { map -> previewMapInstance = map }
                            visibleRoutes.forEachIndexed { index, route ->
                                val routeColor = comparisonRouteColors[index % comparisonRouteColors.size]
                                val latLngs = route.points.map { LatLng(it.latitude, it.longitude) }
                                Polyline(points = latLngs, color = Color(routeColor), width = 8f)
                                Marker(
                                    state = remember(route.ride.ride.id) { MarkerState(position = latLngs.first()) },
                                    icon = remember(route.label, routeColor) {
                                        letterMarkerIcon(context, route.label, routeColor)
                                    },
                                    title = route.label
                                )
                            }
                            if (showSequence) {
                                connectors.forEach { connector ->
                                    Polyline(
                                        points = listOf(
                                            LatLng(connector.from.latitude, connector.from.longitude),
                                            LatLng(connector.to.latitude, connector.to.longitude)
                                        ),
                                        color = Color.Gray,
                                        width = 5f,
                                        pattern = listOf(Dot(), Gap(12f))
                                    )
                                }
                            }
                        }
                        LaunchedEffect(bounds, exportRatio) {
                            if (bounds != null) {
                                kotlinx.coroutines.delay(200)
                                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 72))
                            }
                        }
                    }

                    Text(strings.aspectRatio, style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1:1" to 1f, "4:3" to (4f / 3f), "16:9" to (16f / 9f), "9:16" to (9f / 16f)).forEach { (label, ratio) ->
                            FilterChip(
                                selected = exportRatioLabel == label,
                                onClick = {
                                    exportRatio = ratio
                                    exportRatioLabel = label
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text(strings.mapStyle, style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            MapType.NORMAL to strings.mapNormal,
                            MapType.SATELLITE to strings.mapSatellite,
                            MapType.TERRAIN to strings.mapTerrain
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = exportMapType == type,
                                onClick = { exportMapType = type },
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.aggregatePreviewLegend)
                        Switch(checked = showLegend, onCheckedChange = { showLegend = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.aggregatePreviewSequence)
                        Switch(checked = showSequence, onCheckedChange = { showSequence = it })
                    }
                }

                Button(
                    onClick = {
                        val map = previewMapInstance
                        if (map == null || isSharing) {
                            toast(context, strings.compareRidesMapNotReady)
                        } else {
                            isSharing = true
                            map.snapshot { snapshot ->
                                if (snapshot == null) {
                                    isSharing = false
                                    toast(context, strings.compareRidesMapNotReady)
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            ComparisonImageExporter(
                                                legend = aggregatePreviewLegend(routes, strings.rideHistoryTitle, showLegend)
                                            ).export(snapshot, context)
                                        }
                                            .onSuccess { file ->
                                                withContext(Dispatchers.Main) {
                                                    isSharing = false
                                                    shareComparisonFile(context, file)
                                                    onDismiss()
                                                }
                                            }
                                            .onFailure {
                                                withContext(Dispatchers.Main) {
                                                    isSharing = false
                                                    toast(context, strings.exportFailed)
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isSharing,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Share, contentDescription = strings.share)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(strings.aggregatePreviewShare)
                }
            }
        }
    }
}

private fun letterMarkerIcon(context: Context, label: String, color: Int): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (48f * density).toInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, paint)
    paint.apply {
        this.color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = size * 0.42f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText(label, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun shareComparisonFile(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "TrackMe"))
}

private fun toast(context: Context, message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
