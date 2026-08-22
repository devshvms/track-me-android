package `in`.shvms.trackme.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.ui.history.CombinedMetricLineChart
import `in`.shvms.trackme.ui.history.ExportPreviewDialog
import `in`.shvms.trackme.ui.history.ExportPreviewSettings
import `in`.shvms.trackme.ui.history.RideHistoryCard
import `in`.shvms.trackme.ui.history.RoutePreviewThumbnail
import `in`.shvms.trackme.ui.home.components.ActiveRideHudPanel
import `in`.shvms.trackme.ui.home.components.InteractiveShareLocationButton
import `in`.shvms.trackme.ui.home.components.RadialStartRideButton
import `in`.shvms.trackme.ui.localization.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun RideOnboardingDemo(
    strings: AppStrings,
    selectedPersona: RidePersona,
    onPersonaSelected: (RidePersona) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixture = remember { OnboardingDemoFixture.create() }
    var shareState by remember { mutableStateOf(LiveShareState()) }
    var isPaused by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val instructions = remember(strings) {
        listOf(
            strings.obDemoRideStep1,
            strings.obDemoRideStep2,
            strings.obDemoRideStep3,
            strings.obDemoRideStep4,
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OnboardingDemoHost(
            strings = strings,
            instructions = instructions,
            onFinished = onFinished,
        ) { step, advance ->
            when (step) {
                0 -> Box(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RadialStartRideButton(
                        onStartRide = {
                            onPersonaSelected(it)
                            advance()
                        },
                    )
                }

                1 -> DemoShareControl(
                    shareState = shareState,
                    onStart = {
                        shareState = LiveShareState(status = LiveShareStatus.STARTING)
                        scope.launch {
                            delay(650)
                            shareState = LiveShareState(
                                status = LiveShareStatus.ACTIVE,
                                sessionId = "onboarding-demo",
                                shareLink = "https://trackme.app/s/DEMO",
                            )
                            advance()
                        }
                    },
                    onCopy = {},
                )

                2 -> DemoShareControl(
                    shareState = shareState.copy(
                        status = LiveShareStatus.ACTIVE,
                        shareLink = "https://trackme.app/s/DEMO",
                    ),
                    onStart = {},
                    onCopy = {
                        scope.launch { snackbar.showSnackbar(strings.obDemoCopyToast) }
                        advance()
                    },
                )

                else -> ActiveRideHudPanel(
                    trackingState = if (isPaused) TrackingState.PAUSED else TrackingState.TRACKING,
                    distanceText = UnitFormatter.distance(
                        fixture.ride.postRideCalculation?.distance ?: 0.0,
                        imperial = false,
                    ),
                    durationText = formatDemoDuration(OnboardingDemoFixture.DURATION_MILLIS),
                    elapsedDurationText = formatDemoDuration(OnboardingDemoFixture.DURATION_MILLIS),
                    speedText = UnitFormatter.speed(
                        OnboardingDemoFixture.AVERAGE_SPEED_METERS_PER_SECOND,
                        imperial = false,
                    ),
                    paceText = UnitFormatter.pace(
                        OnboardingDemoFixture.AVERAGE_SPEED_METERS_PER_SECOND,
                        imperial = false,
                    ),
                    selectedPersona = selectedPersona,
                    isAutoPaused = false,
                    timeSinceLastGps = 0L,
                    liveShareState = shareState.copy(
                        status = LiveShareStatus.ACTIVE,
                        shareLink = "https://trackme.app/s/DEMO",
                    ),
                    isAuthenticated = true,
                    liveShareAuthRequired = strings.liveShareAuthRequired,
                    onPauseToggle = { isPaused = !isPaused },
                    onStopRide = advance,
                    onStartShare = {},
                    onStopShare = { shareState = LiveShareState() },
                    onSendShare = {
                        scope.launch { snackbar.showSnackbar(strings.obDemoNoExternalAction) }
                    },
                    onCopyShare = {
                        scope.launch { snackbar.showSnackbar(strings.obDemoCopyToast) }
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DemoShareControl(
    shareState: LiveShareState,
    onStart: () -> Unit,
    onCopy: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(170.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        InteractiveShareLocationButton(
            liveShareState = shareState,
            isAuthenticated = true,
            onStartShare = onStart,
            onStopShare = {},
            onSendShare = {},
            onCopyShare = onCopy,
            modifier = Modifier.size(52.dp),
        )
    }
}

@Composable
internal fun HistoryOnboardingDemo(
    strings: AppStrings,
    selectedPersona: RidePersona,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixture = remember(selectedPersona, strings.obDemoSampleRideTitle) {
        OnboardingDemoFixture.create(title = strings.obDemoSampleRideTitle).let { original ->
            original.copy(ride = original.ride.copy(persona = selectedPersona.name))
        }
    }
    var scrubIndex by remember { mutableFloatStateOf(0f) }
    var showExport by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val instructions = remember(strings) {
        listOf(
            strings.obDemoHistoryStep1,
            strings.obDemoHistoryStep2,
            strings.obDemoHistoryStep3,
            strings.obDemoHistoryStep4,
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OnboardingDemoHost(
            strings = strings,
            instructions = instructions,
            onFinished = {
                showExport = false
                onFinished()
            },
        ) { step, advance ->
            when (step) {
                0 -> RideHistoryCard(
                    rideWithPoints = fixture,
                    onClick = advance,
                )

                1 -> DemoScrubber(
                    fixture = fixture,
                    scrubIndex = scrubIndex.toInt(),
                    onScrub = { next ->
                        scrubIndex = next
                        if (isMeaningfulOnboardingScrub(0, next.toInt(), fixture.points.size)) {
                            advance()
                        }
                    },
                )

                2 -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OnboardingRoutePreview(
                        points = fixture.points,
                        scrubIndex = scrubIndex.toInt(),
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                    )
                    DemoMetricRow(fixture)
                    Spacer(Modifier.height(72.dp))
                    Button(onClick = advance, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(strings.share)
                    }
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OnboardingRoutePreview(
                        points = fixture.points,
                        scrubIndex = scrubIndex.toInt(),
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                    OutlinedButton(
                        onClick = { showExport = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.obDemoCustomizeExport)
                    }

                    if (showExport) {
                        ExportPreviewDialog(
                            title = strings.exportPreviewTitle,
                            initialRatio = 1 to 1,
                            onDismiss = { showExport = false },
                            onShare = {
                                scope.launch { snackbar.showSnackbar(strings.obDemoNoExternalAction) }
                            },
                            onSave = {
                                showExport = false
                                scope.launch { snackbar.showSnackbar(strings.obDemoNoExternalAction) }
                                advance()
                            },
                            preview = { previewModifier, settings ->
                                DemoExportPreview(
                                    fixture = fixture,
                                    settings = settings,
                                    modifier = previewModifier,
                                )
                            },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DemoScrubber(
    fixture: RideWithPoints,
    scrubIndex: Int,
    onScrub: (Float) -> Unit,
) {
    val points = fixture.points
    val minSpeed = remember(points) { points.minOf { it.speed * 3.6f } }
    val maxSpeed = remember(points) { points.maxOf { it.speed * 3.6f } }
    val minAltitude = remember(points) { points.minOf { it.altitude.toFloat() } }
    val maxAltitude = remember(points) { points.maxOf { it.altitude.toFloat() } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OnboardingRoutePreview(
            points = points,
            scrubIndex = scrubIndex,
            modifier = Modifier.fillMaxWidth().height(145.dp),
        )
        CombinedMetricLineChart(
            points = points,
            minSpeed = minSpeed,
            maxSpeed = maxSpeed,
            minAlt = minAltitude,
            maxAlt = maxAltitude,
            speedColor = MaterialTheme.colorScheme.primary,
            altColor = MaterialTheme.colorScheme.tertiary,
            scrubIndex = scrubIndex,
            modifier = Modifier.fillMaxWidth().height(135.dp),
        )
        Slider(
            value = scrubIndex.toFloat(),
            onValueChange = onScrub,
            valueRange = 0f..points.lastIndex.toFloat(),
            steps = (points.size - 2).coerceAtLeast(0),
        )
    }
}

@Composable
private fun OnboardingRoutePreview(
    points: List<GPSPointEntity>,
    scrubIndex: Int,
    modifier: Modifier = Modifier,
) {
    val markerColor = MaterialTheme.colorScheme.tertiary
    Box(modifier = modifier) {
        RoutePreviewThumbnail(points = points, modifier = Modifier.fillMaxSize())
        if (points.size >= 2) {
            Canvas(Modifier.fillMaxSize().padding(6.dp).clearAndSetSemantics { }) {
                val minLat = points.minOf { it.latitude }
                val maxLat = points.maxOf { it.latitude }
                val minLng = points.minOf { it.longitude }
                val maxLng = points.maxOf { it.longitude }
                val latSpan = (maxLat - minLat).takeIf { it > 0.00001 } ?: 0.001
                val lngSpan = (maxLng - minLng).takeIf { it > 0.00001 } ?: 0.001
                val point = points[scrubIndex.coerceIn(points.indices)]
                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = Offset(
                        x = ((point.longitude - minLng) / lngSpan).toFloat() * size.width,
                        y = (1.0 - (point.latitude - minLat) / latSpan).toFloat() * size.height,
                    ),
                )
                drawCircle(
                    color = markerColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(
                        x = ((point.longitude - minLng) / lngSpan).toFloat() * size.width,
                        y = (1.0 - (point.latitude - minLat) / latSpan).toFloat() * size.height,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DemoMetricRow(fixture: RideWithPoints) {
    val aggregate = fixture.ride.postRideCalculation
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(UnitFormatter.rideDistance(aggregate?.distance ?: 0.0, imperial = false))
        Text(formatDemoDuration(OnboardingDemoFixture.DURATION_MILLIS))
        Text(UnitFormatter.speed(aggregate?.avgSpeed?.toDouble() ?: 0.0, imperial = false))
    }
}

@Composable
private fun DemoExportPreview(
    fixture: RideWithPoints,
    settings: ExportPreviewSettings,
    modifier: Modifier = Modifier,
) {
    val foreground = if (settings.darkTheme) Color.White else Color.Black
    Box(
        modifier = modifier
            .background(if (settings.darkTheme) Color(0xFF12161C) else Color(0xFFF8FAFC)),
    ) {
        OnboardingRoutePreview(
            points = fixture.points,
            scrubIndex = fixture.points.lastIndex,
            modifier = Modifier.fillMaxSize().padding(16.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background((if (settings.darkTheme) Color.Black else Color.White).copy(alpha = 0.78f))
                .padding(12.dp),
        ) {
            Text(
                fixture.ride.title.orEmpty(),
                color = foreground,
                fontWeight = FontWeight.Bold,
            )
            val fields = buildList {
                if (settings.showDistance) add(UnitFormatter.rideDistance(OnboardingDemoFixture.DISTANCE_METERS, false))
                if (settings.showDuration) add(formatDemoDuration(OnboardingDemoFixture.DURATION_MILLIS))
            }
            if (fields.isNotEmpty()) {
                Text(fields.joinToString(" • "), color = foreground)
            }
        }
    }
}

private fun formatDemoDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    return String.format(
        Locale.getDefault(),
        "%02d:%02d",
        totalSeconds / 60L,
        totalSeconds % 60L,
    )
}
