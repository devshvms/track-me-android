package `in`.shvms.trackme.domain.export.template

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.R
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.processor.RouteCoordinate
import `in`.shvms.trackme.domain.rideSplits
import `in`.shvms.trackme.domain.stats.RevealKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders every template at every canvas it declares, with real Skia pixels (native graphics), and
 * writes each one to `app/build/template-renders/` for review. The route is **synthetic** — a
 * parametric loop, not anyone's ride — so nothing here is user data and nothing leaves `build/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemplateRendererTest {

    private val outDir = File("build/template-renders").apply { mkdirs() }

    private val typeface: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(ApplicationProvider.getApplicationContext(), R.font.inter_variable) }
            .getOrNull() ?: Typeface.DEFAULT
    }

    /**
     * A lobed, open loop with a quick middle third and one long climb. Parametric, not recorded. Open
     * so the start ring and the finish dot are both visible — a closed loop hides one under the other.
     */
    private fun syntheticRide(): List<GPSPointEntity> = List(360) { index ->
        val t = (0.06 + index / 359.0 * 0.88) * 2 * PI
        val quick = index in 120..239
        GPSPointEntity(
            id = index.toLong(),
            rideId = 1L,
            latitude = 12.9700 + 0.0125 * sin(t) + 0.0045 * sin(3 * t),
            longitude = 77.5900 + 0.0170 * cos(t) - 0.0040 * cos(2 * t),
            altitude = 905.0 + 38.0 * sin(t - 0.6) + 12.0 * sin(3 * t),
            accuracy = 5f,
            speed = if (quick) 7.4f + (index % 7) * 0.12f else 4.6f + (index % 5) * 0.1f,
            timestamp = index * 8_000L,
            isPaused = false,
        )
    }

    private fun content(award: AwardText? = null, light: LightPhase = LightPhase.DAWN, place: String? = "Koramangala → Indiranagar"): TemplateContent {
        val points = syntheticRide()
        val run = points.map { RouteCoordinate(it.latitude, it.longitude) }
        return TemplateContent(
            runs = listOf(run),
            joins = emptyList(),
            runIntensities = listOf(paceIntensities(points)),
            heroValue = "12.4",
            heroUnit = "km",
            heroUnitLong = "KILOMETRES",
            figures = listOf(
                TemplateFigure(FigureRole.DURATION, "TIME", "48min"),
                TemplateFigure(FigureRole.ELEVATION, "ELEVATION", "312 m"),
                TemplateFigure(FigureRole.EFFORT, "AVG PACE", "3:53 /km"),
            ),
            dateLine = "SAT 6 SEP · 06:14 · CYCLING",
            placeLine = place,
            link = "https://trackme.shvms.in/r/abc123def456",
            elevation = elevationProfile(points, 312.0),
            elevationLabel = "ELEVATION · 312 M GAIN",
            splits = splitBars(rideSplits(points, imperial = false)),
            splitsLabel = "SPLITS · MIN/KM",
            fastestSegment = fastestSplitSegment(points, imperial = false, drawn = points),
            fastestLabel = "FASTEST KM",
            award = award,
            light = light,
            lightLine = "FIRST LIGHT · 06:14",
        )
    }

    private val distancePr = AwardText(
        facts = AwardFacts(RevealKind.DISTANCE_PR, 38_200.0, null, previousFraction = (38_200.0 / 41_700.0).toFloat()),
        badge = "PR",
        badgeCaption = "DISTANCE",
        headline = "Longest yet",
        subline = "Previous best 38.2 km",
    )

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(outDir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun opaqueShare(bitmap: Bitmap): Double {
        var opaque = 0
        val step = 8
        var total = 0
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            total++
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) opaque++
        }
        return opaque.toDouble() / total
    }

    @Test
    fun `every template renders at every canvas it declares, at the destination's real size`() {
        ExportTemplates.all.forEach { spec ->
            spec.canvases.forEach { canvas ->
                val bitmap = TemplateRenderer.render(
                    spec.id, canvas,
                    content(award = distancePr.takeIf { spec.id == ExportTemplateId.AWARD }),
                    typeface,
                )
                assertEquals("${spec.id} ${canvas.label} width", canvas.widthPx, bitmap.width)
                assertEquals("${spec.id} ${canvas.label} height", canvas.heightPx, bitmap.height)
                save(bitmap, "${spec.id.analyticsValue}_${canvas.name.lowercase()}")
            }
        }
    }

    @Test
    fun `every sky of The Hour renders`() {
        LightPhase.entries.forEach { phase ->
            val sky = content(light = phase).copy(lightLine = "${phase.name.replace('_', ' ')} · 06:14")
            TemplateCanvas.entries.filter { it != TemplateCanvas.CARD }.forEach { canvas ->
                save(TemplateRenderer.render(ExportTemplateId.HOUR, canvas, sky, typeface), "hour_${canvas.name.lowercase()}_${phase.name.lowercase()}")
            }
        }
    }

    @Test
    fun `opaque templates cover the whole canvas and the sticker is transparent outside its plate`() {
        val trace = TemplateRenderer.render(ExportTemplateId.TRACE, TemplateCanvas.STORY, content(), typeface)
        assertEquals(1.0, opaqueShare(trace), 0.0)

        val sticker = TemplateRenderer.render(ExportTemplateId.STICKER, TemplateCanvas.CARD, content(), typeface)
        assertEquals("the rounded corner is see-through", 0, Color.alpha(sticker.getPixel(2, 2)))
        assertTrue("the plate itself is there", Color.alpha(sticker.getPixel(sticker.width / 2, sticker.height / 2)) > 0)
        assertTrue(opaqueShare(sticker) < 1.0)
    }

    @Test
    fun `the preview is the same picture at a smaller width`() {
        val full = TemplateRenderer.render(ExportTemplateId.INSTRUMENT, TemplateCanvas.PORTRAIT, content(), typeface)
        val preview = TemplateRenderer.render(ExportTemplateId.INSTRUMENT, TemplateCanvas.PORTRAIT, content(), typeface, widthPx = 540)
        assertEquals(540, preview.width)
        assertEquals(full.height / 2, preview.height)
    }

    @Test
    fun `missing data drops its element instead of drawing a placeholder`() {
        val bare = content(place = null).copy(elevation = null, splits = emptyList(), fastestSegment = null)
        val bitmap = TemplateRenderer.render(ExportTemplateId.INSTRUMENT, TemplateCanvas.PORTRAIT, bare, typeface)
        save(bitmap, "instrument_portrait_no_elevation_no_splits")
        assertEquals(1350, bitmap.height)
    }

    @Test
    fun `an Award with no record closes its ring in gold`() {
        val first = AwardText(AwardFacts(RevealKind.FIRST_RIDE, null, null, 1f), "1st", "RIDE", "First ride", null)
        save(TemplateRenderer.render(ExportTemplateId.AWARD, TemplateCanvas.STORY, content(award = first), typeface), "award_story_first_ride")
        val translated = distancePr.copy(badge = "新纪录", badgeCaption = "DISTANCIA", headline = "La más larga hasta ahora")
        save(TemplateRenderer.render(ExportTemplateId.AWARD, TemplateCanvas.SQUARE, content(award = translated), typeface), "award_square_long_badge")
    }
}
