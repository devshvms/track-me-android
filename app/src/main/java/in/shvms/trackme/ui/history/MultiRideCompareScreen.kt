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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import `in`.shvms.trackme.domain.export.ExportOptions
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
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    val routes = remember(rides) { prepareComparisonRoutes(rides) }
    val visibleRoutes = remember(routes) { routes.filter { it.points.isNotEmpty() } }
    val connectors = remember(routes) { comparisonConnectors(routes) }
    var mapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }

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
                    IconButton(
                        onClick = {
                            mapInstance?.snapshot { snapshot ->
                                if (snapshot == null) {
                                    toast(context, strings.compareRidesMapNotReady)
                                    return@snapshot
                                }
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        ComparisonImageExporter(comparisonLegend(routes, strings.rideHistoryTitle)).export(
                                            visibleRoutes.first().ride,
                                            1,
                                            1,
                                            context,
                                            snapshot,
                                            ExportOptions(showStats = false)
                                        )
                                    }
                                        .onSuccess { file -> withContext(Dispatchers.Main) { shareComparisonFile(context, file) } }
                                        .onFailure { toast(context, strings.exportFailed) }
                                }
                            } ?: toast(context, strings.compareRidesMapNotReady)
                        },
                        enabled = visibleRoutes.isNotEmpty()
                    ) {
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
                        MapEffect { map -> mapInstance = map }
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
                onClick = {
                    mapInstance?.snapshot { snapshot ->
                        if (snapshot == null) {
                            toast(context, strings.compareRidesMapNotReady)
                        } else {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    ComparisonImageExporter(comparisonLegend(routes, strings.rideHistoryTitle)).export(
                                        visibleRoutes.first().ride,
                                        1,
                                        1,
                                        context,
                                        snapshot,
                                        ExportOptions(showStats = false)
                                    )
                                }
                                    .onSuccess { file -> withContext(Dispatchers.Main) { shareComparisonFile(context, file) } }
                                    .onFailure { toast(context, strings.exportFailed) }
                            }
                        }
                    }
                },
                enabled = visibleRoutes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(strings.compareRidesShare)
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

private fun comparisonLegend(
    routes: List<ComparisonRoute>,
    fallbackTitle: String
): List<Pair<String, String>> = routes.take(MAX_COMPARISON_RIDES).map { route ->
    route.label to (route.ride.ride.title?.ifBlank { fallbackTitle } ?: fallbackTitle)
}

private fun toast(context: Context, message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
