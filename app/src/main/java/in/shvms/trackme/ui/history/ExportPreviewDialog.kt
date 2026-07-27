package `in`.shvms.trackme.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.maps.android.compose.MapType
import `in`.shvms.trackme.ui.localization.LocalAppStrings

internal data class BoundedPreviewSize(val width: Float, val height: Float)

/** Computes a ratio-preserving preview size that never exceeds either bound. */
internal fun boundedPreviewSize(maxWidth: Float, maxHeight: Float, ratio: Float): BoundedPreviewSize {
    require(maxWidth > 0f && maxHeight > 0f && ratio > 0f)
    val width = minOf(maxWidth, maxHeight * ratio)
    return BoundedPreviewSize(width = width, height = width / ratio)
}

/** All controls shared by single-ride and aggregate export previews. */
data class ExportPreviewSettings(
    val ratio: Pair<Int, Int>,
    val mapType: MapType,
    val privacyTrim: Boolean,
    val hidePlaces: Boolean,
    val showMarkers: Boolean,
    val showStats: Boolean,
    val darkTheme: Boolean,
    val showDate: Boolean,
    val showDuration: Boolean,
    val showDistance: Boolean,
    val showLegend: Boolean,
    val showSequence: Boolean
) {
    val ratioFloat: Float
        get() = ratio.first.toFloat() / ratio.second.toFloat()
}

/**
 * Single source of truth for export-preview layout and controls.
 *
 * The preview slot is intentionally bounded by height before applying the selected aspect ratio.
 * This prevents tall 9:16 previews from measuring through the app bar or action controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportPreviewDialog(
    title: String,
    initialRatio: Pair<Int, Int>,
    initialMapType: MapType = MapType.NORMAL,
    initialPrivacyTrim: Boolean = true,
    initialShowMarkers: Boolean = true,
    initialShowLegend: Boolean = false,
    initialShowSequence: Boolean = false,
    showAggregateControls: Boolean = false,
    canExport: Boolean = true,
    isExporting: Boolean = false,
    errorMessage: String? = null,
    shareLabel: String? = null,
    onDismiss: () -> Unit,
    onShare: (ExportPreviewSettings) -> Unit,
    onSave: ((ExportPreviewSettings) -> Unit)? = null,
    onRetry: ((ExportPreviewSettings) -> Unit)? = null,
    videoAction: (@Composable (ExportPreviewSettings) -> Unit)? = null,
    preview: @Composable (Modifier, ExportPreviewSettings) -> Unit
) {
    val strings = LocalAppStrings.current
    var ratio by remember(initialRatio) { mutableStateOf(initialRatio) }
    var mapType by remember(initialMapType) { mutableStateOf(initialMapType) }
    var privacyTrim by remember(initialPrivacyTrim) { mutableStateOf(initialPrivacyTrim) }
    var hidePlaces by remember { mutableStateOf(false) }
    var showMarkers by remember(initialShowMarkers) { mutableStateOf(initialShowMarkers) }
    var showStats by remember { mutableStateOf(true) }
    var darkTheme by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(true) }
    var showDuration by remember { mutableStateOf(true) }
    var showDistance by remember { mutableStateOf(true) }
    var showLegend by remember(initialShowLegend) { mutableStateOf(initialShowLegend) }
    var showSequence by remember(initialShowSequence) { mutableStateOf(initialShowSequence) }

    val settings = ExportPreviewSettings(
        ratio = ratio,
        mapType = mapType,
        privacyTrim = privacyTrim,
        hidePlaces = hidePlaces,
        showMarkers = showMarkers,
        showStats = showStats,
        darkTheme = darkTheme,
        showDate = showDate,
        showDuration = showDuration,
        showDistance = showDistance,
        showLegend = showLegend,
        showSequence = showSequence
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(title) },
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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .wrapContentHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val maxPreviewHeight = 420.dp
                        val previewSize = boundedPreviewSize(maxWidth.value, maxPreviewHeight.value, settings.ratioFloat)
                        Box(
                            modifier = Modifier
                                .width(previewSize.width.dp)
                                .height(previewSize.height.dp)
                        ) {
                            preview(Modifier.fillMaxSize(), settings)
                        }
                    }

                    Text(strings.aspectRatio, style = MaterialTheme.typography.labelLarge)
                    RatioChips(
                        selected = ratio,
                        onSelected = { ratio = it },
                        portraitRatio = Pair(1080, 1920)
                    )

                    videoAction?.invoke(settings)

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
                                selected = mapType == type,
                                onClick = { mapType = type },
                                label = { Text(label) }
                            )
                        }
                    }

                    ToggleRow(strings.privacyTrim, privacyTrim) { privacyTrim = it }
                    ToggleRow(strings.hidePlaces, hidePlaces, enabled = mapType == MapType.NORMAL) { hidePlaces = it }
                    ToggleRow(strings.showMarkers, showMarkers) { showMarkers = it }
                    ToggleRow(strings.statsOverlay, showStats) { showStats = it }

                    if (showStats) {
                        ToggleRow(strings.darkTheme, darkTheme) { darkTheme = it }
                        if (!showAggregateControls) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(selected = showDistance, onClick = { showDistance = !showDistance }, label = { Text(strings.distanceShortLabel) })
                                FilterChip(selected = showDuration, onClick = { showDuration = !showDuration }, label = { Text(strings.durationShortLabel) })
                                FilterChip(selected = showDate, onClick = { showDate = !showDate }, label = { Text(strings.dateShortLabel) })
                            }
                        }
                    }

                    if (showAggregateControls) {
                        ToggleRow(strings.aggregatePreviewLegend, showLegend) { showLegend = it }
                        ToggleRow(strings.aggregatePreviewSequence, showSequence) { showSequence = it }
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(errorMessage, modifier = Modifier.weight(1f))
                        if (onRetry != null) {
                            androidx.compose.material3.TextButton(onClick = { onRetry(settings) }) {
                                Text(strings.retry)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onShare(settings) },
                        modifier = Modifier.weight(1f),
                        enabled = canExport && !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Share, contentDescription = strings.share, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(shareLabel ?: strings.share)
                    }
                    if (onSave != null) {
                        Button(
                            onClick = { onSave(settings) },
                            modifier = Modifier.weight(1f),
                            enabled = canExport && !isExporting
                        ) {
                            Icon(Icons.Default.Download, contentDescription = strings.save, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.save)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioChips(
    selected: Pair<Int, Int>,
    onSelected: (Pair<Int, Int>) -> Unit,
    portraitRatio: Pair<Int, Int>
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "1:1" to Pair(1, 1),
            "4:3" to Pair(4, 3),
            "16:9" to Pair(16, 9),
            "9:16" to portraitRatio
        ).forEach { (label, value) ->
            FilterChip(selected = selected == value, onClick = { onSelected(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
