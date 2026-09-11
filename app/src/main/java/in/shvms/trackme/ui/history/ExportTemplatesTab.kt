package `in`.shvms.trackme.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.export.template.ExportTemplates
import `in`.shvms.trackme.domain.export.template.LightPhase
import `in`.shvms.trackme.domain.export.template.PlaceReference
import `in`.shvms.trackme.domain.export.template.TemplateCanvas
import `in`.shvms.trackme.ui.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SCOPE_1.8.9 §9.1 — the two ways to make a share image. */
enum class ExportPreviewMode { Templates, Custom }

/**
 * What the Templates tab lets someone choose. Ratio and the privacy trim are **global** — they
 * describe the artifact, not the look — so they live on [ExportPreviewSettings] and survive a tab
 * switch (§9.2); the place reference is global in intent and lives here only because the Custom tab
 * has no use for it.
 */
data class ExportTemplateChoice(
    val id: ExportTemplateId = ExportTemplateId.TRACE,
    val place: PlaceReference = PlaceReference.OFF,
    /** Null is the light the ride actually happened in. */
    val lightOverride: LightPhase? = null,
    val mapBackground: Boolean = false,
)

/**
 * What a host gives the Templates tab. Hosts that pass none get no tab bar at all — the aggregate
 * preview (1.8.9 Part 2) and the onboarding demo, which exists to show one clean outcome.
 */
class ExportTemplatesSupport(
    /** The templates this ride can honestly fill, in strip order. */
    val available: List<ExportTemplateId>,
    /** Draws [choice] at [canvas] and [widthPx]. Called off the main thread; null on failure. */
    val render: suspend (choice: ExportTemplateChoice, canvas: TemplateCanvas, privacyTrim: Boolean, widthPx: Int) -> Bitmap?,
    /** The user turned the place reference on; resolve the names once if they are not cached. */
    val onPlaceReferenceEnabled: () -> Unit = {},
    /** Changes whenever the host's content does (place names arrived), so previews redraw. */
    val contentVersion: Any? = null,
)

/**
 * The Templates stage: the chosen template, rendered by the same renderer the export uses at the
 * stage's own pixel width — one picture at two resolutions. The previous frame stays up while the
 * next renders, so changing a chip never flashes a spinner.
 */
@Composable
internal fun TemplatePreviewStage(
    support: ExportTemplatesSupport,
    choice: ExportTemplateChoice,
    canvas: TemplateCanvas,
    privacyTrim: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(choice, canvas, privacyTrim, widthPx, support.contentVersion) {
            if (widthPx <= 0) return@LaunchedEffect
            val rendered = withContext(Dispatchers.Default) {
                runCatching { support.render(choice, canvas, privacyTrim, widthPx) }.getOrNull()
            }
            if (rendered != null) bitmap = rendered
        }
        if (ExportTemplates.spec(choice.id).transparent) Checkerboard(Modifier.fillMaxSize())
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

/**
 * The horizontal strip of template cards — a real thumbnail of *this* ride in each design, so the
 * choice is made by looking rather than by reading names.
 */
@Composable
internal fun TemplateStrip(
    support: ExportTemplatesSupport,
    selected: ExportTemplateId,
    privacyTrim: Boolean,
    choice: ExportTemplateChoice,
    strings: AppStrings,
    onSelect: (ExportTemplateId) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(support.available) { id ->
            TemplateCard(
                support = support,
                id = id,
                selected = id == selected,
                privacyTrim = privacyTrim,
                choice = choice,
                label = templateName(id, strings),
                onClick = { onSelect(id) },
            )
        }
    }
}

@Composable
private fun TemplateCard(
    support: ExportTemplatesSupport,
    id: ExportTemplateId,
    selected: Boolean,
    privacyTrim: Boolean,
    choice: ExportTemplateChoice,
    label: String,
    onClick: () -> Unit,
) {
    val spec = ExportTemplates.spec(id)
    val thumbWidthPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    var thumbnail by remember(id) { mutableStateOf<Bitmap?>(null) }
    // A thumbnail follows the ride and the global choices, never the other templates' own options.
    val thumbChoice = ExportTemplateChoice(id = id, place = choice.place)
    LaunchedEffect(id, privacyTrim, choice.place, support.contentVersion) {
        thumbnail = withContext(Dispatchers.Default) {
            runCatching { support.render(thumbChoice, spec.defaultCanvas, privacyTrim, thumbWidthPx * 2) }.getOrNull()
        }
    }
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp)),
        ) {
            if (spec.transparent) Checkerboard(Modifier.fillMaxSize())
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (spec.transparent) ContentScale.Fit else ContentScale.Crop,
                )
            }
        }
        // Two lines rather than an ellipsis: template names are a fixed-width control, and the
        // longest catalog value has to fit it (EXPORT_SHARE_CONTRACTS §3c).
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The chosen template's options, as one scrollable row of chips — tap targets, not drag targets. */
@Composable
internal fun TemplateOptionsRow(
    choice: ExportTemplateChoice,
    canvas: TemplateCanvas,
    privacyTrim: Boolean,
    supportsPlace: Boolean,
    strings: AppStrings,
    onCanvas: (TemplateCanvas) -> Unit,
    onPrivacyTrim: (Boolean) -> Unit,
    onPlace: (PlaceReference) -> Unit,
    onMapBackground: (Boolean) -> Unit,
    onLight: (LightPhase?) -> Unit,
) {
    val spec = ExportTemplates.spec(choice.id)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (spec.canvases.size > 1) {
                ExportTemplates.canvasChoices(choice.id).forEach { option -> OptionChip(option.label, option == canvas) { onCanvas(option) } }
            }
            OptionChip(strings.privacyTrim, privacyTrim) { onPrivacyTrim(!privacyTrim) }
            if (supportsPlace) {
                PlaceReference.entries.forEach { option -> OptionChip(placeLabel(option, strings), choice.place == option) { onPlace(option) } }
            }
            if (spec.supportsMapBackground) {
                OptionChip(strings.templateMapBackground, choice.mapBackground) { onMapBackground(!choice.mapBackground) }
            }
            if (choice.id == ExportTemplateId.HOUR) {
                // One tap to override the light: auto-picked colour charms when it matches the memory
                // and irritates when it does not (§6.5).
                OptionChip(strings.templateLightAuto, choice.lightOverride == null) { onLight(null) }
                listOf(LightPhase.DAWN, LightPhase.GOLDEN_MORNING, LightPhase.DAY, LightPhase.DUSK, LightPhase.NIGHT).forEach { phase ->
                    OptionChip(ExportTemplateContent.lightLabel(phase, strings), choice.lightOverride == phase) { onLight(phase) }
                }
            }
        }
        if (supportsPlace && choice.place != PlaceReference.OFF) {
            Text(
                text = strings.templatePlaceNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** Transparency made visible: the Sticker is a layer, and a layer on a flat colour hides that. */
@Composable
private fun Checkerboard(modifier: Modifier) {
    Canvas(modifier) {
        val cell = 8.dp.toPx()
        val light = Color(0xFF2A3138)
        val dark = Color(0xFF1C2127)
        drawRect(dark)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < size.width) {
                drawRect(light, topLeft = Offset(x, y), size = Size(cell, cell))
                x += cell * 2
            }
            y += cell
            row++
        }
    }
}

internal fun templateName(id: ExportTemplateId, strings: AppStrings): String = when (id) {
    ExportTemplateId.TRACE -> strings.templateTrace
    ExportTemplateId.INSTRUMENT -> strings.templateInstrument
    ExportTemplateId.STICKER -> strings.templateSticker
    ExportTemplateId.HOUR -> strings.templateHour
    ExportTemplateId.AWARD -> strings.templateAward
}

private fun placeLabel(reference: PlaceReference, strings: AppStrings): String = when (reference) {
    PlaceReference.OFF -> strings.templatePlaceOff
    PlaceReference.START_AND_FINISH -> strings.templatePlaceBoth
    PlaceReference.FINISH_ONLY -> strings.templatePlaceFinish
}

/** SCOPE_1.8.9 §12 R8: the last template chosen is remembered globally — a chosen look is wanted again. */
internal object TemplateMemory {
    private const val PREFS = "trackme_prefs"
    private const val KEY = "export_last_template"

    fun recall(context: android.content.Context, available: List<ExportTemplateId>): ExportTemplateId {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, null)
                ?.let(ExportTemplateId::valueOf)
        }.getOrNull()
        return stored?.takeIf { it in available } ?: available.firstOrNull() ?: ExportTemplateId.TRACE
    }

    fun remember(context: android.content.Context, id: ExportTemplateId) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit { putString(KEY, id.name) }
    }
}
