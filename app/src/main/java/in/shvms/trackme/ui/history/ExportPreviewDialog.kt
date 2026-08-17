package `in`.shvms.trackme.ui.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.maps.android.compose.MapType
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.theme.LocalTrackMeMotion
import `in`.shvms.trackme.theme.LocalTrackMeSpacing
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings

internal data class BoundedPreviewSize(val width: Float, val height: Float)

/** Computes a ratio-preserving preview size that never exceeds either bound. */
internal fun boundedPreviewSize(maxWidth: Float, maxHeight: Float, ratio: Float): BoundedPreviewSize {
    require(maxWidth > 0f && maxHeight > 0f && ratio > 0f)
    val width = minOf(maxWidth, maxHeight * ratio)
    return BoundedPreviewSize(width = width, height = width / ratio)
}

/**
 * Pixel dimensions of the exported image for a given aspect ratio.
 *
 * The export used to be a screenshot of the on-screen preview, so its resolution was whatever that
 * view happened to measure — which varied with screen density and a layout constant, and was always
 * below the intended size. This is the size the export is actually rendered at, independent of the
 * device it is rendered on.
 *
 * Short edge is [AppConfig.HQ_IMAGE_WIDTH]; the long edge is capped at [AppConfig.HQ_IMAGE_RATIO_9_16]
 * so a very wide or very tall selection cannot ask for an unbounded bitmap. 1:1 → 1080×1080,
 * 4:3 → 1440×1080, 16:9 → 1920×1080, 9:16 → 1080×1920.
 */
internal fun exportPixelSize(ratio: Float): Pair<Int, Int> {
    if (!ratio.isFinite() || ratio <= 0f) {
        return AppConfig.HQ_IMAGE_WIDTH to AppConfig.HQ_IMAGE_WIDTH
    }
    val shortEdge = AppConfig.HQ_IMAGE_WIDTH.toFloat()
    val longEdgeCap = AppConfig.HQ_IMAGE_RATIO_9_16.toFloat()

    var width = if (ratio >= 1f) shortEdge * ratio else shortEdge
    var height = if (ratio >= 1f) shortEdge else shortEdge / ratio

    val longEdge = maxOf(width, height)
    if (longEdge > longEdgeCap) {
        val scale = longEdgeCap / longEdge
        width *= scale
        height *= scale
    }
    return width.toInt().coerceAtLeast(1) to height.toInt().coerceAtLeast(1)
}

/**
 * Route stroke and marker sizes, expressed as a fraction of the rendered width.
 *
 * These were absolute pixel values — a 10px polyline and a 64px marker — used identically by the
 * on-screen preview and, via the screenshot export, by the output file. Absolute pixels mean the
 * route reads as a hairline on a high-density preview and as a thick band on a low-density one,
 * and the exported file inherits whichever the device happened to produce. As fractions, the
 * preview and the export render the same picture at different resolutions, which is what a preview
 * is for.
 *
 * The constants are the previous values divided by a ~1080px render, so an export at the default
 * size looks like the previous one; every other size now scales with it instead of not scaling.
 */
internal object ExportRenderScale {
    private const val ROUTE_STROKE_FRACTION = 0.012f
    private const val MARKER_FRACTION = 0.060f

    fun routeStroke(widthPx: Int): Float = (widthPx * ROUTE_STROKE_FRACTION).coerceAtLeast(4f)
    fun markerSize(widthPx: Int): Int = (widthPx * MARKER_FRACTION).toInt().coerceAtLeast(16)

    /**
     * Camera padding when fitting a route, as a fraction of the shorter edge rather than a fixed
     * pixel count. Fixed padding is a different proportion of a 9:16 frame than of a 16:9 one, so
     * the same route sat noticeably tighter in one ratio than another.
     */
    fun fitPadding(widthPx: Int, heightPx: Int): Int =
        (minOf(widthPx, heightPx) * 0.08f).toInt().coerceAtLeast(8)
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

    /** Pixel size the export renders at. See [exportPixelSize]. */
    val exportSize: Pair<Int, Int>
        get() = exportPixelSize(ratioFloat)
}

/**
 * The six control groups, in rail order.
 *
 * One group per thing a person adjusts, rather than one row per boolean. The previous layout put
 * up to eighteen controls in a vertical stack that shared its scroll container with the preview,
 * so reaching a control below the fold scrolled the preview out of sight — you could not see what
 * you were changing.
 */
internal enum class ExportControlCategory(val icon: ImageVector) {
    Ratio(Icons.Default.AspectRatio),
    MapStyle(Icons.Default.Layers),
    Privacy(Icons.Default.Shield),
    Markers(Icons.Default.Place),
    Stats(Icons.Default.BarChart),
    Legend(Icons.AutoMirrored.Filled.FormatListBulleted);

    fun label(strings: AppStrings): String = when (this) {
        Ratio -> strings.exportCategoryRatio
        MapStyle -> strings.exportCategoryMap
        Privacy -> strings.exportCategoryPrivacy
        Markers -> strings.exportCategoryMarkers
        Stats -> strings.exportCategoryStats
        Legend -> strings.exportCategoryLegend
    }
}

/** Legend/sequence are aggregate-only concepts, so the rail hides that group entirely elsewhere. */
internal fun exportCategoriesFor(showAggregateControls: Boolean): List<ExportControlCategory> =
    ExportControlCategory.entries.filter {
        it != ExportControlCategory.Legend || showAggregateControls
    }

/**
 * Single source of truth for export-preview layout and controls.
 *
 * ### Layout contract
 *
 * The preview is a **fixed stage**: it takes all space the app bar, control tiers and action row
 * leave, and nothing scrolls it. That is not only a visual preference — the preview hosts a live
 * `GoogleMap`, and a map inside a `verticalScroll` fights the scroll container for every vertical
 * drag, so panning the map sometimes scrolled the page instead. Taking the scroll container out of
 * the map's ancestry removes the conflict by construction rather than arbitrating it.
 *
 * Controls sit below in two tiers: a rail of category icons, and above it the values for whichever
 * category is selected.
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
    var category by remember { mutableStateOf(ExportControlCategory.Ratio) }

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

                // The stage. `weight(1f)` hands it everything the chrome does not need, so the
                // preview is sized by the space that actually exists rather than by a constant.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val previewSize = boundedPreviewSize(
                        maxWidth = maxWidth.value,
                        maxHeight = maxHeight.value,
                        ratio = settings.ratioFloat
                    )
                    Box(
                        modifier = Modifier
                            .width(previewSize.width.dp)
                            .height(previewSize.height.dp)
                    ) {
                        preview(Modifier.fillMaxSize(), settings)
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            errorMessage,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (onRetry != null) {
                            androidx.compose.material3.TextButton(onClick = { onRetry(settings) }) {
                                Text(strings.retry)
                            }
                        }
                    }
                }

                HorizontalDivider()

                ValuesTier(
                    category = category,
                    settings = settings,
                    showAggregateControls = showAggregateControls,
                    strings = strings,
                    onRatio = { ratio = it },
                    onMapType = { mapType = it },
                    onPrivacyTrim = { privacyTrim = it },
                    onHidePlaces = { hidePlaces = it },
                    onShowMarkers = { showMarkers = it },
                    onShowStats = { showStats = it },
                    onDarkTheme = { darkTheme = it },
                    onShowDistance = { showDistance = it },
                    onShowDuration = { showDuration = it },
                    onShowDate = { showDate = it },
                    onShowLegend = { showLegend = it },
                    onShowSequence = { showSequence = it },
                )

                CategoryRail(
                    categories = exportCategoriesFor(showAggregateControls),
                    selected = category,
                    strings = strings,
                    onSelect = { category = it }
                )

                // Video export gets its own full-width row above the image actions. Aggregate
                // (multi-ride) previews never show it: replay video is a single-route,
                // single-persona concept with no defined multi-ride semantics, so it is absent
                // rather than visible-and-inert. Callers in aggregate mode simply pass null; the
                // `showAggregateControls` guard keeps that true even if a future caller forgets.
                if (videoAction != null && !showAggregateControls) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    ) {
                        videoAction(settings)
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

/**
 * The upper tier: values for the selected category.
 *
 * Every group renders as the same horizontally scrollable row of chips, including the ones that
 * used to be switches. A switch in a scrolling row is a drag target inside a drag surface; a chip
 * is a tap target, which is the interaction this row actually wants.
 */
@Composable
private fun ValuesTier(
    category: ExportControlCategory,
    settings: ExportPreviewSettings,
    showAggregateControls: Boolean,
    strings: AppStrings,
    onRatio: (Pair<Int, Int>) -> Unit,
    onMapType: (MapType) -> Unit,
    onPrivacyTrim: (Boolean) -> Unit,
    onHidePlaces: (Boolean) -> Unit,
    onShowMarkers: (Boolean) -> Unit,
    onShowStats: (Boolean) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onShowDistance: (Boolean) -> Unit,
    onShowDuration: (Boolean) -> Unit,
    onShowDate: (Boolean) -> Unit,
    onShowLegend: (Boolean) -> Unit,
    onShowSequence: (Boolean) -> Unit,
) {
    val motion = LocalTrackMeMotion.current
    val spacing = LocalTrackMeSpacing.current
    AnimatedContent(
        targetState = category,
        transitionSpec = {
            // Effects tokens: this is a crossfade, and an alpha that overshoots past 1 clips.
            androidx.compose.animation.fadeIn(motion.effectsDefault.spec()) togetherWith
                androidx.compose.animation.fadeOut(motion.effectsFast.spec())
        },
        label = "exportValuesTier"
    ) { active ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenMargin, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.betweenCards),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (active) {
                ExportControlCategory.Ratio -> {
                    // The 9:16 entry carries the historical (1080, 1920) pair rather than (9, 16).
                    // Both normalise through `ratioFloat`, and the pair is what callers persist.
                    listOf(
                        "1:1" to Pair(1, 1),
                        "4:3" to Pair(4, 3),
                        "16:9" to Pair(16, 9),
                        "9:16" to Pair(AppConfig.HQ_IMAGE_WIDTH, AppConfig.HQ_IMAGE_RATIO_9_16)
                    ).forEach { (label, value) ->
                        ValueChip(
                            label = label,
                            selected = settings.ratioFloat == value.first.toFloat() / value.second.toFloat(),
                            onClick = { onRatio(value) }
                        )
                    }
                }

                ExportControlCategory.MapStyle -> listOf(
                    MapType.NORMAL to strings.mapNormal,
                    MapType.SATELLITE to strings.mapSatellite,
                    MapType.TERRAIN to strings.mapTerrain
                ).forEach { (type, label) ->
                    ValueChip(
                        label = label,
                        selected = settings.mapType == type,
                        onClick = { onMapType(type) }
                    )
                }

                ExportControlCategory.Privacy -> {
                    ValueChip(
                        label = strings.privacyTrim,
                        selected = settings.privacyTrim,
                        onClick = { onPrivacyTrim(!settings.privacyTrim) }
                    )
                    // Places can only be hidden on the styleable base map; satellite and terrain
                    // imagery carries its own labels that no style JSON can turn off.
                    ValueChip(
                        label = strings.hidePlaces,
                        selected = settings.hidePlaces,
                        enabled = settings.mapType == MapType.NORMAL,
                        onClick = { onHidePlaces(!settings.hidePlaces) }
                    )
                }

                ExportControlCategory.Markers -> ValueChip(
                    label = strings.showMarkers,
                    selected = settings.showMarkers,
                    onClick = { onShowMarkers(!settings.showMarkers) }
                )

                ExportControlCategory.Stats -> {
                    ValueChip(
                        label = strings.statsOverlay,
                        selected = settings.showStats,
                        onClick = { onShowStats(!settings.showStats) }
                    )
                    ValueChip(
                        label = strings.darkTheme,
                        selected = settings.darkTheme,
                        enabled = settings.showStats,
                        onClick = { onDarkTheme(!settings.darkTheme) }
                    )
                    if (!showAggregateControls) {
                        ValueChip(
                            label = strings.distanceShortLabel,
                            selected = settings.showDistance,
                            enabled = settings.showStats,
                            onClick = { onShowDistance(!settings.showDistance) }
                        )
                        ValueChip(
                            label = strings.durationShortLabel,
                            selected = settings.showDuration,
                            enabled = settings.showStats,
                            onClick = { onShowDuration(!settings.showDuration) }
                        )
                        ValueChip(
                            label = strings.dateShortLabel,
                            selected = settings.showDate,
                            enabled = settings.showStats,
                            onClick = { onShowDate(!settings.showDate) }
                        )
                    }
                }

                ExportControlCategory.Legend -> {
                    ValueChip(
                        label = strings.aggregatePreviewLegend,
                        selected = settings.showLegend,
                        onClick = { onShowLegend(!settings.showLegend) }
                    )
                    ValueChip(
                        label = strings.aggregatePreviewSequence,
                        selected = settings.showSequence,
                        onClick = { onShowSequence(!settings.showSequence) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) }
    )
}

/**
 * The lower tier: which group the values row is showing.
 *
 * Scrollable for the same reason the values row is — six labels in a language with long words do
 * not fit a phone width, and a rail that wraps to two lines stops reading as a rail.
 */
@Composable
private fun CategoryRail(
    categories: List<ExportControlCategory>,
    selected: ExportControlCategory,
    strings: AppStrings,
    onSelect: (ExportControlCategory) -> Unit
) {
    val spacing = LocalTrackMeSpacing.current
    val motion = LocalTrackMeMotion.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.betweenCards, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            categories.forEach { entry ->
                val isSelected = entry == selected
                // Colour change only — effects token, since a tint that overshoots past its
                // endpoint is a visible flash on a control the eye is already resting on.
                val tint by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = motion.effectsDefault.spec(),
                    label = "railTint"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .semanticsSelectable(isSelected) { onSelect(entry) }
                        // The design system's minimum touch target is stated as having no
                        // exceptions. A short label in one locale would otherwise shrink the
                        // target below it without anything failing.
                        .sizeIn(
                            minWidth = spacing.minTouchTarget,
                            minHeight = spacing.minTouchTarget
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = entry.label(strings),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Tab semantics rather than a bare click: these select which values are shown, they do not perform
 * an action, and a screen reader that announces them as buttons gives no sense of the current one.
 */
private fun Modifier.semanticsSelectable(selected: Boolean, onClick: () -> Unit): Modifier =
    this.selectable(selected = selected, role = Role.Tab, onClick = onClick)
