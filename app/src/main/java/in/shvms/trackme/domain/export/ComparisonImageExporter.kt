package `in`.shvms.trackme.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Writes a rendered multi-ride map without adding ride titles or coordinates to metadata. */
class ComparisonImageExporter(
    private val legend: List<Pair<String, String>> = emptyList()
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
        if (legend.isNotEmpty()) {
            val panelHeight = (finalBitmap.height * 0.08f * legend.take(8).size + 32f).toInt().coerceAtLeast(96)
            val panelTop = (finalBitmap.height - panelHeight).toFloat()
            val canvas = Canvas(finalBitmap)
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xDD102A43.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, panelTop, finalBitmap.width.toFloat(), finalBitmap.height.toFloat(), panelPaint)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (panelHeight * 0.16f).coerceAtLeast(14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val lineHeight = textPaint.textSize * 1.25f
            legend.take(8).forEachIndexed { index, (label, title) ->
                val baseline = panelTop + textPaint.textSize + index * lineHeight
                canvas.drawText("$label  $title", finalBitmap.width * 0.05f, baseline, textPaint)
            }
        }
        FileOutputStream(file).use { output ->
            check(finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode comparison image" }
        }
        finalBitmap.recycle()
        file
    }
}
