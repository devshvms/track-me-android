package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.ui.components.TrackMeMapAttribution
import `in`.shvms.trackme.ui.components.rememberMapStyle
import `in`.shvms.trackme.ui.components.rememberMessenger
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.export.ComparisonImageExporter
import `in`.shvms.trackme.theme.BrandThemeConfig
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.ui.components.moveSafely
import `in`.shvms.trackme.ui.components.captureOffscreenMap
import `in`.shvms.trackme.ui.components.visibleBounds
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
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
    val mapStyle = rememberMapStyle()
    val messenger = rememberMessenger()
    val density = LocalDensity.current.density
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
            messenger.show(strings.compareRidesMapNotReady)
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
                }
                // No share action in the bar. It opened the same preview as the full-width
                // "Customize & share" button a few hundred pixels below it, so the screen offered
                // one destination through two controls — and the icon gave no hint that tapping it
                // leads to a customisation step rather than straight to the share sheet.
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
                        properties = MapProperties(isTrafficEnabled = false, mapStyleOptions = mapStyle),
                        uiSettings = MapUiSettings(zoomControlsEnabled = false, compassEnabled = false)
                    ) {
                        visibleRoutes.forEachIndexed { index, route ->
                            val routeColor = comparisonRouteColors[index % comparisonRouteColors.size]
                            val latLngs = route.points.map { LatLng(it.latitude, it.longitude) }
                            Polyline(points = latLngs, color = Color(routeColor), width = 8f)
                            Marker(
                                state = remember(route.ride.ride.id) { MarkerState(position = latLngs.first()) },
                                icon = remember(route.label, routeColor, density) {
                                    ExportMarkers.aggregate(
                                        ExportMarkerStyle.StartFinish, context, route.label, routeColor,
                                        (24f * density).toInt()
                                    )
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
                                color = MaterialTheme.colorScheme.outline,
                                width = 5f,
                                pattern = listOf(Dot(), Gap(12f))
                            )
                        }
                    }
                    LaunchedEffect(bounds) {
                        kotlinx.coroutines.delay(200)
                        cameraPositionState.moveSafely { CameraUpdateFactory.newLatLngBounds(bounds, 72) }
                    }
                    TrackMeMapAttribution(modifier = Modifier.align(Alignment.BottomStart))
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
        UnifiedAggregateRidePreviewDialog(
            routes = routes,
            visibleRoutes = visibleRoutes,
            onDismiss = { showPreview = false }
        )
    }
}

@Composable
private fun UnifiedAggregateRidePreviewDialog(
    routes: List<ComparisonRoute>,
    visibleRoutes: List<ComparisonRoute>,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val scope = rememberCoroutineScope()
    var previewMapInstance by remember { mutableStateOf<com.google.android.gms.maps.GoogleMap?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportFailure by remember { mutableStateOf<ExportPreviewFailure?>(null) }
    var pendingGalleryFile by remember { mutableStateOf<java.io.File?>(null) }

    val gallerySaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val sourceFile = pendingGalleryFile
        pendingGalleryFile = null
        if (uri == null) {
            isExporting = false
        } else if (sourceFile == null) {
            isExporting = false
            exportFailure = ExportPreviewFailure.Save
        } else {
            scope.launch(Dispatchers.IO) {
                val saved = saveImageToDocument(context, sourceFile, uri)
                withContext(Dispatchers.Main) {
                    isExporting = false
                    if (saved) {
                        messenger.show("Saved to gallery")
                        onDismiss()
                    } else {
                        exportFailure = ExportPreviewFailure.Save
                    }
                }
            }
        }
    }

    fun exportPreview(settings: ExportPreviewSettings, share: Boolean) {
        val map = previewMapInstance
        if (map == null || isExporting) {
            messenger.show(strings.compareRidesMapNotReady)
            return
        }
        isExporting = true
        exportFailure = null

        // Re-rendered at the selected ratio's true pixel size rather than screenshotting the
        // preview view, which produced an image whose resolution depended on screen density. The
        // framing travels as bounds because zoom means nothing without a viewport size.
        val framing = map.visibleBounds()
        val (exportWidth, exportHeight) = settings.exportSize
        val exportRoutes = routes
            .map { route -> if (settings.privacyTrim) route else route.copy(points = route.ride.points) }
            .filter { it.points.isNotEmpty() }
        val exportConnectors = comparisonConnectors(exportRoutes)
        val exportMarkerSize = ExportRenderScale.markerSize(exportWidth)
        val exportStroke = ExportRenderScale.routeStroke(exportWidth)

        captureOffscreenMap(
            context = context,
            widthPx = exportWidth,
            heightPx = exportHeight,
            mapType = settings.mapType,
            configure = { exportMap ->
                settings.mapStyle(context)?.let { exportMap.setMapStyle(it) }
                val allPoints = mutableListOf<LatLng>()
                exportRoutes.forEachIndexed { index, route ->
                    val routeColor = comparisonRouteColors[index % comparisonRouteColors.size]
                    val latLngs = route.points.map { LatLng(it.latitude, it.longitude) }
                    allPoints += latLngs
                    exportMap.addPolyline(
                        PolylineOptions().addAll(latLngs).color(routeColor).width(exportStroke)
                    )
                    ExportMarkers.aggregate(settings.markerStyle, context, route.label, routeColor, exportMarkerSize)
                        ?.let { icon ->
                            exportMap.addMarker(
                                MarkerOptions().position(latLngs.first()).icon(icon).title(route.label)
                            )
                        }
                }
                if (settings.showSequence) {
                    exportConnectors.forEach { connector ->
                        exportMap.addPolyline(
                            PolylineOptions()
                                .add(
                                    LatLng(connector.from.latitude, connector.from.longitude),
                                    LatLng(connector.to.latitude, connector.to.longitude)
                                )
                                .color(android.graphics.Color.GRAY)
                                .width(exportStroke * 0.6f)
                                .pattern(listOf(Dot(), Gap(12f)))
                        )
                    }
                }
                val target = framing ?: LatLngBounds.Builder()
                    .also { builder -> allPoints.forEach(builder::include) }
                    .build()
                exportMap.moveCamera(CameraUpdateFactory.newLatLngBounds(target, 0))
            }
        ) { snapshot, _ ->
            if (snapshot == null) {
                isExporting = false
                exportFailure = ExportPreviewFailure.Render
                return@captureOffscreenMap
            }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    ComparisonImageExporter(
                        legend = aggregatePreviewLegend(visibleRoutes, strings.rideHistoryTitle, settings.showLegend),
                        darkTheme = settings.darkTheme,
                    ).export(snapshot, context)
                }.onSuccess { file ->
                    if (share) {
                        withContext(Dispatchers.Main) {
                            shareComparisonFile(context, file)
                            isExporting = false
                            onDismiss()
                        }
                    } else if (shouldUseGalleryDocumentPicker()) {
                        withContext(Dispatchers.Main) {
                            pendingGalleryFile = file
                            val launched = tryLaunchGalleryDocument {
                                gallerySaveLauncher.launch(galleryImageDisplayName("Aggregate"))
                            }
                            if (!launched) {
                                pendingGalleryFile = null
                                isExporting = false
                                exportFailure = ExportPreviewFailure.Save
                            }
                        }
                    } else {
                        val saved = saveComparisonImage(context, file)
                        withContext(Dispatchers.Main) {
                            isExporting = false
                            if (saved) {
                                messenger.show("Saved to gallery")
                                onDismiss()
                            } else {
                                exportFailure = ExportPreviewFailure.Save
                            }
                        }
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        isExporting = false
                        exportFailure = ExportPreviewFailure.Render
                    }
                }
            }
        }
    }

    ExportPreviewDialog(
        title = strings.aggregatePreviewTitle,
        initialRatio = Pair(1, 1),
        initialMarkerStyle = ExportMarkerStyle.StartFinish,
        initialShowLegend = true,
        initialShowSequence = true,
        showAggregateControls = true,
        canExport = visibleRoutes.isNotEmpty(),
        isExporting = isExporting,
        errorMessage = when (exportFailure) {
            ExportPreviewFailure.Render -> strings.exportRetryMessage
            ExportPreviewFailure.Save -> strings.exportFailed
            null -> null
        },
        onDismiss = onDismiss,
        onShare = { settings -> exportPreview(settings, share = true) },
        onSave = { settings -> exportPreview(settings, share = false) },
        onRetry = { settings -> exportPreview(settings, share = true) }
    ) { modifier, settings ->
        val previewRoutes = remember(routes, settings.privacyTrim) {
            routes.map { route ->
                if (settings.privacyTrim) route else route.copy(points = route.ride.points)
            }
        }.filter { it.points.isNotEmpty() }
        val previewConnectors = remember(previewRoutes) { comparisonConnectors(previewRoutes) }
        val allLatLngs = remember(previewRoutes) {
            previewRoutes.flatMap { route -> route.points.map { LatLng(it.latitude, it.longitude) } }
        }
        if (allLatLngs.isEmpty()) {
            Box(modifier, contentAlignment = Alignment.Center) { Text(strings.compareRidesNoGps) }
        } else {
            val bounds = remember(allLatLngs) {
                LatLngBounds.Builder().also { builder -> allLatLngs.forEach(builder::include) }.build()
            }
            val cameraPositionState = rememberCameraPositionState {
                position = initialRouteCamera(allLatLngs, bounds)
            }
            BoxWithConstraints(modifier) {
                // Preview drawing scales from the preview's own pixel size so that what is on
                // screen and what lands in the file are the same picture — see ExportRenderScale.
                val density = LocalDensity.current
                val previewWidthPx = with(density) { maxWidth.roundToPx() }
                val previewHeightPx = with(density) { maxHeight.roundToPx() }

                // Keyed on the viewport, not only the routes: changing ratio reshapes the viewport,
                // and a fit computed for the previous shape leaves the routes cropped.
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

                val previewStroke = ExportRenderScale.routeStroke(previewWidthPx)
                val previewMarkerSize = ExportRenderScale.markerSize(previewWidthPx)

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        mapType = settings.mapType,
                        isTrafficEnabled = false,
                        mapStyleOptions = settings.mapStyle(context)
                    ),
                    // Rotation and tilt off: the export has no notion of a rotated frame, and every
                    // extra gesture the map claims is one more way a pan can be misread.
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
                    previewRoutes.forEachIndexed { index, route ->
                        val routeColor = comparisonRouteColors[index % comparisonRouteColors.size]
                        val latLngs = route.points.map { LatLng(it.latitude, it.longitude) }
                        Polyline(points = latLngs, color = Color(routeColor), width = previewStroke)
                        val markerIcon = remember(route.label, routeColor, previewMarkerSize, settings.markerStyle) {
                            ExportMarkers.aggregate(
                                settings.markerStyle, context, route.label, routeColor, previewMarkerSize
                            )
                        }
                        if (markerIcon != null) {
                            Marker(
                                state = remember(route.ride.ride.id) { MarkerState(position = latLngs.first()) },
                                icon = markerIcon,
                                title = route.label
                            )
                        }
                    }
                    if (settings.showSequence) {
                        previewConnectors.forEach { connector ->
                            Polyline(
                                points = listOf(
                                    LatLng(connector.from.latitude, connector.from.longitude),
                                    LatLng(connector.to.latitude, connector.to.longitude)
                                ),
                                color = Color.Gray,
                                width = previewStroke * 0.6f,
                                pattern = listOf(Dot(), Gap(12f))
                            )
                        }
                    }
                }
                TrackMeMapAttribution(modifier = Modifier.align(Alignment.BottomStart))

                val panelRect = settings.statsOverlay.rect()
                if (panelRect != null && (settings.statsOverlay.isVisible || settings.showLegend)) {
                    val legendRows = remember(previewRoutes, strings.rideHistoryTitle) {
                        aggregatePreviewLegend(previewRoutes, strings.rideHistoryTitle, showLegend = true)
                    }
                    val panelColor = if (settings.darkTheme) {
                        BrandThemeConfig.navy800.copy(alpha = 0.87f)
                    } else {
                        Color.White.copy(alpha = 0.85f)
                    }
                    val onPanel = if (settings.darkTheme) Color.White else Color.Black
                    // Same placement vocabulary as the single-ride preview, so the two screens
                    // behave identically. Height wraps rather than following the rect: the legend
                    // is a list whose length depends on how many rides were selected, and a fixed
                    // fraction would clip the last row on a four-ride comparison.
                    val topPlacement = settings.statsOverlay == StatsOverlayStyle.TopLeft ||
                        settings.statsOverlay == StatsOverlayStyle.TopRight
                    val alignEnd = settings.statsOverlay.alignsTextEnd
                    val corner = with(LocalDensity.current) {
                        panelRect.cornerRadiusPx(previewWidthPx, previewHeightPx).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(panelRect.widthFraction)
                            .wrapContentHeight()
                            .align(
                                when {
                                    topPlacement && alignEnd -> Alignment.TopEnd
                                    topPlacement -> Alignment.TopStart
                                    alignEnd -> Alignment.BottomEnd
                                    else -> Alignment.BottomStart
                                }
                            )
                            .padding(
                                with(LocalDensity.current) {
                                    (panelRect.inset * minOf(previewWidthPx, previewHeightPx)).toDp()
                                }
                            )
                            .clip(RoundedCornerShape(corner))
                            // The panel background belongs here, wrapping everything. The route
                            // label line used to sit outside it with only a colour swap, so in
                            // light mode it was black text straight onto the map.
                            .background(panelColor)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (settings.statsOverlay.isVisible) {
                                Text(
                                    previewRoutes.joinToString(" • ") { it.label },
                                    color = onPanel,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            if (settings.showLegend) {
                                AggregateLegendPanel(legendRows, settings.darkTheme)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AggregateLegendPanel(
    legendRows: List<Pair<String, String>>,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    if (legendRows.isEmpty()) return
    // Rows only. This used to paint its own hardcoded navy background — which is both why the
    // Dark theme control did nothing here, and why it cannot paint one now: the caller wraps it
    // in the themed panel, and a second background inside would double the tint.
    val onPanel = if (darkTheme) Color.White else Color.Black
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        legendRows.take(MAX_COMPARISON_RIDES).forEach { (label, title) ->
            Text(
                text = "$label  $title",
                color = onPanel,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
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

private fun saveComparisonImage(context: Context, file: java.io.File): Boolean =
    saveImageToGallery(context, file, "Aggregate")

