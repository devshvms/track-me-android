package `in`.shvms.trackme.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min
import `in`.shvms.trackme.theme.BrandThemeConfig

private const val MAX_COMPARISON_LEGEND_ROWS = 8

internal data class ComparisonLegendLayout(
    val textSize: Float,
    val lineHeight: Float,
    val verticalPadding: Float,
    val panelHeight: Int
)

/** Computes a legend panel whose baselines always fit inside the exported bitmap. */
internal fun comparisonLegendLayout(bitmapWidth: Int, bitmapHeight: Int, rowCount: Int): ComparisonLegendLayout? {
    require(bitmapWidth > 0 && bitmapHeight > 0) { "Bitmap dimensions must be positive" }
    val rows = rowCount.coerceIn(0, MAX_COMPARISON_LEGEND_ROWS)
    if (rows == 0) return null
    val preferredTextSize = (bitmapWidth * 0.035f).coerceIn(14f, 42f)
    val maxTextSize = bitmapHeight / (rows * 1.25f + 1.6f)
    val textSize = min(preferredTextSize, maxTextSize).coerceAtLeast(1f)
    val lineHeight = textSize * 1.25f
    val verticalPadding = textSize * 0.8f
    val panelHeight = ceil(verticalPadding * 2f + lineHeight * rows).toInt().coerceAtMost(bitmapHeight)
    return ComparisonLegendLayout(textSize, lineHeight, verticalPadding, panelHeight)
}

/** Writes a rendered multi-ride map without adding ride titles or coordinates to metadata. */
class ComparisonImageExporter(
    private val legend: List<Pair<String, String>> = emptyList(),
    /**
     * Was hardcoded to the navy panel with white text, so the exported image ignored the Dark
     * theme control exactly as the on-screen legend did — the setting moved nothing on this
     * screen except a one-line route label above the panel.
     */
    private val darkTheme: Boolean = true,
) : ImageExporter {
    override suspend fun export(
        rideWithPoints: `in`.shvms.trackme.data.local.entity.RideWithPoints,
        ratioWidth: Int,
        ratioHeight: Int,
        context: Context,
        mapSnapshot: Bitmap?,
        options: ExportOptions
    ): File {
        require(mapSnapshot != null) { "mapSnapshot cannot be null for comparison export" }
        return export(mapSnapshot, context)
    }

    suspend fun export(snapshot: Bitmap, context: Context): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(!snapshot.isRecycled) { "snapshot cannot be recycled" }
        val exportsDir = File(context.cacheDir, "trackme_exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()
        val file = File(exportsDir, "TrackMe_Comparison_${UUID.randomUUID()}.png")
        val finalBitmap = snapshot.copy(Bitmap.Config.ARGB_8888, true)
        val rows = legend.take(MAX_COMPARISON_LEGEND_ROWS)
        comparisonLegendLayout(finalBitmap.width, finalBitmap.height, rows.size)?.let { layout ->
            val panelTop = (finalBitmap.height - layout.panelHeight).toFloat()
            val canvas = Canvas(finalBitmap)
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (darkTheme) {
                    BrandThemeConfig.navy800.copy(alpha = 0.87f).toArgb()
                } else {
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f).toArgb()
                }
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, panelTop, finalBitmap.width.toFloat(), finalBitmap.height.toFloat(), panelPaint)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                textSize = layout.textSize
                typeface = Typeface.DEFAULT_BOLD
            }
            val horizontalPadding = finalBitmap.width * 0.05f
            val availableWidth = (finalBitmap.width - horizontalPadding * 2f).coerceAtLeast(0f)
            rows.forEachIndexed { index, (label, title) ->
                val baseline = panelTop + layout.verticalPadding + textPaint.textSize + index * layout.lineHeight
                canvas.drawText(
                    ellipsizeLegendRow(label, title, availableWidth, textPaint),
                    horizontalPadding,
                    baseline,
                    textPaint
                )
            }
        }
        FileOutputStream(file).use { output ->
            check(finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode comparison image" }
        }
        finalBitmap.recycle()
        file
    }
}

private fun ellipsizeLegendRow(label: String, title: String, maxWidth: Float, paint: Paint): String {
    val prefix = "$label  "
    val full = prefix + title
    if (paint.measureText(full) <= maxWidth) return full
    val ellipsis = "…"
    val titleWidth = (maxWidth - paint.measureText(prefix) - paint.measureText(ellipsis)).coerceAtLeast(0f)
    val visibleChars = paint.breakText(title, true, titleWidth, null)
    return prefix + title.take(visibleChars) + ellipsis
}
