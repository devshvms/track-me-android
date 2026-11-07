package `in`.shvms.trackme.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import `in`.shvms.trackme.R
import `in`.shvms.trackme.analytics.ExportArtifactKind
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.stats.WeeklyRecap
import `in`.shvms.trackme.ui.history.shareExportedArtifact
import `in`.shvms.trackme.ui.localization.AppStrings
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * TASK-358: Pure, deterministic, high-resolution share card renderer for weekly recap.
 *
 * Renders a 1080x1080 square social asset carrying:
 *  - Brand header with TrackMe logo badge + "WEEKLY RECAP"
 *  - Formatted ISO week key (e.g. "2026-W45")
 *  - Hero total distance with unit
 *  - Ride count and streak statistics
 *  - Quiet corner brand link ("trackme.shvms.in")
 *  - No PII, no coordinates, pure local summary facts.
 */
object WeeklyRecapCardRenderer {
    const val CARD_SIZE = 1080

    fun render(
        context: Context,
        recap: WeeklyRecap,
        strings: AppStrings,
        imperial: Boolean,
        widthPx: Int = CARD_SIZE,
    ): Bitmap {
        val scale = widthPx.toFloat() / CARD_SIZE
        val bitmap = Bitmap.createBitmap(widthPx, widthPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.inter_variable)
        }.getOrNull() ?: Typeface.DEFAULT

        // Deep charcoal background (#111318)
        val bgPaint = Paint().apply { color = Color.parseColor("#111318") }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), widthPx.toFloat(), bgPaint)

        // Inner Card Container (margin 48px, rounded 36px)
        val margin = 48f * scale
        val cardRect = RectF(margin, margin, widthPx - margin, widthPx - margin)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                margin, margin, margin, widthPx - margin,
                Color.parseColor("#21252D"),
                Color.parseColor("#171A20"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(cardRect, 36f * scale, 36f * scale, cardPaint)

        // Card stroke subtle border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
            color = Color.argb(40, 255, 255, 255)
        }
        canvas.drawRoundRect(cardRect, 36f * scale, 36f * scale, borderPaint)

        // Header Pill: "TRACKME" with emerald dot
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676") // Emerald active dot
        }
        canvas.drawCircle(margin + 44f * scale, margin + 56f * scale, 7f * scale, dotPaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E1E2E8")
            textSize = 22f * scale
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("TRACKME", margin + 64f * scale, margin + 64f * scale, brandPaint)

        // Week Label (top right): e.g. "2026-W45"
        val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8E919A")
            textSize = 20f * scale
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(recap.weekKey, widthPx - margin - 44f * scale, margin + 64f * scale, weekPaint)

        // Subhead: "WEEKLY RECAP"
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9CA3AF")
            textSize = 26f * scale
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        canvas.drawText(strings.weeklyRecapTitle.uppercase(Locale.getDefault()), margin + 44f * scale, margin + 140f * scale, titlePaint)

        // Hero distance value (e.g. "42.8 km")
        val distanceStr = UnitFormatter.distance(recap.distanceMeters, imperial, decimals = 1)
        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = 92f * scale
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        canvas.drawText(distanceStr, margin + 44f * scale, margin + 250f * scale, heroPaint)

        val heroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8E919A")
            textSize = 24f * scale
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
        }
        canvas.drawText(strings.weeklyRecapDistanceLabel.uppercase(Locale.getDefault()), margin + 44f * scale, margin + 296f * scale, heroLabelPaint)

        // Subtle Divider Line
        val dividerPaint = Paint().apply {
            color = Color.argb(30, 255, 255, 255)
            strokeWidth = 2f * scale
        }
        canvas.drawLine(margin + 44f * scale, margin + 340f * scale, widthPx - margin - 44f * scale, margin + 340f * scale, dividerPaint)

        // Bottom Stats Grid: Rides (Col 1) and Streak (Col 2)
        val col1X = margin + 44f * scale
        val col2X = margin + 440f * scale

        // Rides Stat
        val statValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = 56f * scale
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        canvas.drawText(recap.rideCount.toString(), col1X, margin + 430f * scale, statValuePaint)

        val statLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8E919A")
            textSize = 22f * scale
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
        }
        canvas.drawText(strings.weeklyRecapRidesLabel.uppercase(Locale.getDefault()), col1X, margin + 472f * scale, statLabelPaint)

        // Streak Stat
        val streakText = when {
            recap.streakWeeks >= 2 -> String.format(Locale.getDefault(), "%d WEEKS", recap.streakWeeks)
            recap.streakWeeks == 1 -> "ACTIVE"
            else -> "—"
        }
        canvas.drawText(streakText, col2X, margin + 430f * scale, statValuePaint)
        canvas.drawText("ACTIVE STREAK", col2X, margin + 472f * scale, statLabelPaint)

        // Bottom Brand / Privacy statement & Link
        val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280")
            textSize = 20f * scale
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
        }
        canvas.drawText("TrackMe • Privacy-first GPS tracker", margin + 44f * scale, widthPx - margin - 52f * scale, taglinePaint)

        val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 235, 235, 235)
            textSize = 22f * scale
            this.typeface = Typeface.create(typeface, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("trackme.shvms.in", widthPx - margin - 44f * scale, widthPx - margin - 52f * scale, linkPaint)

        return bitmap
    }

    fun writePng(context: Context, bitmap: Bitmap, weekKey: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "trackme-recap-$weekKey.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun shareRecapCard(
        context: Context,
        recap: WeeklyRecap,
        strings: AppStrings,
        imperial: Boolean,
    ) {
        val bitmap = render(context, recap, strings, imperial)
        val file = writePng(context, bitmap, recap.weekKey)
        shareExportedArtifact(
            context = context,
            file = file,
            kind = ExportArtifactKind.IMAGE,
            chooserTitle = strings.weeklyRecapTitle,
        )
    }
}
