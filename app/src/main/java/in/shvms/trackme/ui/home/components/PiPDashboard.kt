package `in`.shvms.trackme.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.domain.group.AlertPolicy
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.model.usesPace
import `in`.shvms.trackme.service.TrackingManager
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.theme.AmberWarn
import `in`.shvms.trackme.theme.CyanBright
import `in`.shvms.trackme.theme.Navy900
import `in`.shvms.trackme.theme.RedDestructive
import `in`.shvms.trackme.theme.Slate400
import `in`.shvms.trackme.theme.Slate600
import `in`.shvms.trackme.ui.community.statusLabelForCode
import `in`.shvms.trackme.ui.home.LiveRideMetricFormatter
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val PIP_REFRESH_INTERVAL_MS = 1_000L
internal const val PIP_RESOLVED_ALERT_MS = 8_000L

/** The recorder states which still own a live ride. Storage-low is deliberately inactive. */
internal enum class PiPRideState {
    INACTIVE,
    RECORDING,
    PAUSED,
    GPS_LOST,
}

internal fun TrackingState.toPiPRideState(): PiPRideState = when (this) {
    TrackingState.TRACKING -> PiPRideState.RECORDING
    TrackingState.PAUSED -> PiPRideState.PAUSED
    TrackingState.GPS_LOST, TrackingState.GPS_DISABLED -> PiPRideState.GPS_LOST
    TrackingState.IDLE, TrackingState.STORAGE_LOW -> PiPRideState.INACTIVE
}

internal enum class PiPSecondaryMetric {
    PACE,
    SPEED,
}

internal enum class PiPRemoteActionKind {
    PAUSE,
    RESUME,
}

internal enum class PiPEntryTrigger(val analyticsValue: String) {
    AUTO_ENTER("auto_enter"),
    USER_LEAVE_HINT("user_leave_hint"),
}

internal enum class PiPSessionDurationBucket(val analyticsValue: String) {
    UNDER_10_SECONDS("under_10_seconds"),
    SECONDS_10_TO_59("10_to_59_seconds"),
    MINUTES_1_TO_4("1_to_4_minutes"),
    MINUTES_5_TO_29("5_to_29_minutes"),
    MINUTES_30_PLUS("30_plus_minutes"),
    ;

    companion object {
        fun fromSeconds(seconds: Long): PiPSessionDurationBucket = when {
            seconds < 10L -> UNDER_10_SECONDS
            seconds < 60L -> SECONDS_10_TO_59
            seconds < 5L * 60L -> MINUTES_1_TO_4
            seconds < 30L * 60L -> MINUTES_5_TO_29
            else -> MINUTES_30_PLUS
        }
    }
}

/** Pure OS-entry policy. MainActivity consumes it; the composable never decides lifecycle. */
internal object PiPModePolicy {
    fun isEligible(rideState: PiPRideState, enabled: Boolean): Boolean =
        enabled && rideState != PiPRideState.INACTIVE

    fun remoteAction(rideState: PiPRideState): PiPRemoteActionKind? = when (rideState) {
        PiPRideState.PAUSED -> PiPRemoteActionKind.RESUME
        PiPRideState.RECORDING, PiPRideState.GPS_LOST -> PiPRemoteActionKind.PAUSE
        PiPRideState.INACTIVE -> null
    }
}

internal object PiPMetricPolicy {
    fun secondaryMetric(persona: RidePersona): PiPSecondaryMetric =
        if (persona.usesPace) PiPSecondaryMetric.PACE else PiPSecondaryMetric.SPEED
}

internal data class PiPGroupAlert(
    val eventId: Long,
    val signal: AlertPolicy.Signal,
    val memberName: String,
    val statusCode: String,
)

/**
 * App-process state for the edge-triggered group alert already approved by [AlertPolicy].
 * It holds no coordinate, distance, group id, member id, or relationship.
 */
internal class PiPAlertStore(private val scope: CoroutineScope) {
    private val eventIds = AtomicLong(0L)
    private val _alert = kotlinx.coroutines.flow.MutableStateFlow<PiPGroupAlert?>(null)
    val alert: StateFlow<PiPGroupAlert?> = _alert
    private var clearJob: Job? = null

    fun accept(signal: AlertPolicy.Signal, memberName: String, statusCode: String) {
        if (signal == AlertPolicy.Signal.NONE) return
        clearJob?.cancel()
        val event = PiPGroupAlert(
            eventId = eventIds.incrementAndGet(),
            signal = signal,
            memberName = memberName,
            statusCode = statusCode,
        )
        _alert.value = event
        if (signal == AlertPolicy.Signal.ALERT_RESOLVED) {
            clearJob = scope.launch {
                delay(PIP_RESOLVED_ALERT_MS)
                if (_alert.value?.eventId == event.eventId) _alert.value = null
            }
        }
    }

    fun clear() {
        clearJob?.cancel()
        clearJob = null
        _alert.value = null
    }
}

internal sealed interface PiPStripState {
    data object AutoPaused : PiPStripState
    data object Paused : PiPStripState
    data object GpsLost : PiPStripState
    data class AlertRaised(val alert: PiPGroupAlert) : PiPStripState
    data class AlertResolved(val alert: PiPGroupAlert) : PiPStripState
}

/** Alert beats recorder state; otherwise GPS loss beats manual pause, then auto-pause. */
internal object PiPStripPolicy {
    fun select(
        rideState: PiPRideState,
        isAutoPaused: Boolean,
        alert: PiPGroupAlert?,
    ): PiPStripState? = when (alert?.signal) {
        AlertPolicy.Signal.ALERT_RAISED -> PiPStripState.AlertRaised(alert)
        AlertPolicy.Signal.ALERT_RESOLVED -> PiPStripState.AlertResolved(alert)
        AlertPolicy.Signal.NONE, null -> when {
            rideState == PiPRideState.GPS_LOST -> PiPStripState.GpsLost
            rideState == PiPRideState.PAUSED -> PiPStripState.Paused
            isAutoPaused -> PiPStripState.AutoPaused
            else -> null
        }
    }
}

internal enum class PiPStripKind {
    WARNING,
    ALERT,
    RESOLVED,
}

internal data class PiPStripDisplay(
    val text: String,
    val kind: PiPStripKind,
    /** Non-null only for the once-per-transition accessibility live announcement. */
    val alertEventId: Long? = null,
)

internal data class PiPDashboardUiState(
    val distanceLabel: String,
    val distanceValue: String,
    val secondaryLabel: String,
    val secondaryValue: String,
    val strip: PiPStripDisplay?,
    val accessibilityDescription: String,
) {
    companion object {
        val EMPTY = PiPDashboardUiState(
            distanceLabel = "",
            distanceValue = "--",
            secondaryLabel = "",
            secondaryValue = "--",
            strip = null,
            accessibilityDescription = "",
        )
    }
}

internal object PiPDashboardPolicy {
    fun build(
        rideState: PiPRideState,
        distanceMeters: Float,
        speedMps: Float,
        persona: RidePersona,
        isAutoPaused: Boolean,
        imperial: Boolean,
        alert: PiPGroupAlert?,
        strings: AppStrings,
    ): PiPDashboardUiState {
        val secondaryMetric = PiPMetricPolicy.secondaryMetric(persona)
        val distance = LiveRideMetricFormatter.distance(distanceMeters, imperial)
        val secondary = when (secondaryMetric) {
            PiPSecondaryMetric.PACE -> LiveRideMetricFormatter.pace(speedMps)
            PiPSecondaryMetric.SPEED -> LiveRideMetricFormatter.speed(speedMps, imperial)
        }
        val secondaryLabel = when (secondaryMetric) {
            PiPSecondaryMetric.PACE -> strings.pace
            PiPSecondaryMetric.SPEED -> strings.speed
        }
        val strip = stripDisplay(
            PiPStripPolicy.select(rideState, isAutoPaused, alert),
            strings,
        )
        val stripDescription = strip?.text?.let { ". $it" }.orEmpty()
        return PiPDashboardUiState(
            distanceLabel = strings.distance,
            distanceValue = distance,
            secondaryLabel = secondaryLabel,
            secondaryValue = secondary,
            strip = strip,
            accessibilityDescription = String.format(
                Locale.getDefault(),
                strings.pipDashboardAccessibility,
                distance,
                secondary,
                stripDescription,
            ),
        )
    }

    private fun stripDisplay(state: PiPStripState?, strings: AppStrings): PiPStripDisplay? = when (state) {
        null -> null
        PiPStripState.AutoPaused -> PiPStripDisplay(strings.pipAutoPaused, PiPStripKind.WARNING)
        PiPStripState.Paused -> PiPStripDisplay(strings.statusPaused, PiPStripKind.WARNING)
        PiPStripState.GpsLost -> PiPStripDisplay(strings.notifTrackingGpsSearching, PiPStripKind.WARNING)
        is PiPStripState.AlertRaised -> PiPStripDisplay(
            text = alertText(
                state.alert.memberName,
                strings.statusLabelForCode(state.alert.statusCode) ?: state.alert.statusCode,
            ),
            kind = PiPStripKind.ALERT,
            alertEventId = state.alert.eventId,
        )
        is PiPStripState.AlertResolved -> PiPStripDisplay(
            text = alertText(state.alert.memberName, strings.pipAlertCleared),
            kind = PiPStripKind.RESOLVED,
            alertEventId = state.alert.eventId,
        )
    }

    private fun alertText(memberName: String, status: String): String =
        listOf(memberName.trim(), status.trim()).filter(String::isNotEmpty).joinToString(" · ")
}

internal object PiPDashboardLayoutPolicy {
    fun showLabels(heightDp: Float, fontScale: Float): Boolean = heightDp >= 120f && fontScale < 1.3f
}

private data class PiPRawMetrics(
    val rideState: PiPRideState,
    val distanceMeters: Float,
    val speedMps: Float,
)

private data class PiPRideContext(
    val persona: RidePersona,
    val isAutoPaused: Boolean,
)

/**
 * One sampled state stream is the dashboard's sole recomposition input. No animation or timer is
 * owned by the composable, and even a faster GPS producer cannot exceed one UI update per second.
 */
@OptIn(FlowPreview::class)
internal class PiPDashboardStateSource(
    private val trackingManager: TrackingManager,
    private val preferencesManager: AppPreferencesManager,
    private val alertStore: PiPAlertStore,
    scope: CoroutineScope,
) {
    private val rawMetrics = combine(
        trackingManager.trackingState,
        trackingManager.totalDistance,
        trackingManager.currentSpeed,
    ) { state, distance, speed ->
        PiPRawMetrics(state.toPiPRideState(), distance, speed)
    }

    private val rideContext = combine(
        trackingManager.selectedPersona,
        trackingManager.isAutoPaused,
    ) { persona, isAutoPaused -> PiPRideContext(persona, isAutoPaused) }

    private val snapshots = combine(
        rawMetrics,
        rideContext,
        preferencesManager.unitSystem,
        preferencesManager.appLanguage,
        alertStore.alert,
    ) { metrics, context, unitSystem, language, alert ->
        build(
            metrics = metrics,
            context = context,
            unitSystem = unitSystem,
            language = language,
            alert = alert,
        )
    }

    val state: StateFlow<PiPDashboardUiState> = snapshots
        .sample(PIP_REFRESH_INTERVAL_MS)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = buildCurrent(),
        )

    private fun buildCurrent(): PiPDashboardUiState = build(
        metrics = PiPRawMetrics(
            rideState = trackingManager.trackingState.value.toPiPRideState(),
            distanceMeters = trackingManager.totalDistance.value,
            speedMps = trackingManager.currentSpeed.value,
        ),
        context = PiPRideContext(
            persona = trackingManager.selectedPersona.value,
            isAutoPaused = trackingManager.isAutoPaused.value,
        ),
        unitSystem = preferencesManager.unitSystem.value,
        language = preferencesManager.appLanguage.value,
        alert = alertStore.alert.value,
    )

    private fun build(
        metrics: PiPRawMetrics,
        context: PiPRideContext,
        unitSystem: String,
        language: String,
        alert: PiPGroupAlert?,
    ): PiPDashboardUiState = PiPDashboardPolicy.build(
        rideState = metrics.rideState,
        distanceMeters = metrics.distanceMeters,
        speedMps = metrics.speedMps,
        persona = context.persona,
        isAutoPaused = context.isAutoPaused,
        imperial = unitSystem == "imperial",
        alert = alert,
        strings = getAppStrings(language),
    )
}

@Composable
internal fun PiPDashboard(
    state: PiPDashboardUiState,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Navy900)
            .semantics(mergeDescendants = true) {
                contentDescription = state.accessibilityDescription
            }
            .focusable(),
    ) {
        val showLabels = PiPDashboardLayoutPolicy.showLabels(maxHeight.value, fontScale)
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PiPMetric(
                    label = state.distanceLabel,
                    value = state.distanceValue,
                    showLabel = showLabels,
                    modifier = Modifier.weight(1f),
                )
                PiPMetric(
                    label = state.secondaryLabel,
                    value = state.secondaryValue,
                    showLabel = showLabels,
                    modifier = Modifier.weight(1f),
                )
            }
            state.strip?.let { strip -> PiPStrip(strip) }
        }
    }
}

@Composable
private fun PiPMetric(
    label: String,
    value: String,
    showLabel: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            color = CyanBright,
            fontSize = 30.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
        if (showLabel) {
            Text(
                text = label.uppercase(Locale.getDefault()),
                color = Slate400,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PiPStrip(strip: PiPStripDisplay) {
    val (background, foreground) = when (strip.kind) {
        PiPStripKind.WARNING -> AmberWarn to Navy900
        PiPStripKind.ALERT -> RedDestructive to Color.White
        PiPStripKind.RESOLVED -> Slate600 to Color.White
    }
    Text(
        text = strip.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics {
                if (strip.alertEventId != null) liveRegion = LiveRegionMode.Assertive
            },
        color = foreground,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}
