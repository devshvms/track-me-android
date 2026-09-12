package `in`.shvms.trackme.domain.export.template

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import `in`.shvms.trackme.domain.processor.RouteCoordinate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * SCOPE_1.8.9 Part 2 — the aggregate Trace: N routes on one ground, one colour per ride (§9.3).
 *
 * The claim worth testing is not that something was drawn but that the *palette reached the canvas*.
 * `TemplateContent.runPalette` travels through the content builder, the renderer's `route()` helper
 * and a decimation pass before it becomes pixels, and a per-run colour that was quietly dropped
 * anywhere along that path would leave a picture that still looks like a Trace — N lines in one
 * colour — while making the legend beside it a lie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AggregateTraceRenderTest {

    private val outDir = File("build/template-renders").apply { mkdirs() }

    private val cyan = 0xFF00A6C7.toInt()
    private val purple = 0xFF7557B5.toInt()

    /** Two rides far enough apart on the canvas that neither line can be mistaken for the other. */
    private fun content(palette: List<Int>?) = TemplateContent(
        runs = listOf(
            (0..20).map { RouteCoordinate(12.90 + it * 0.002, 77.55 + it * 0.002) },
            (0..20).map { RouteCoordinate(13.10 + it * 0.002, 77.55 + it * 0.002) },
        ),
        joins = emptyList(),
        runIntensities = null,
        heroValue = "480",
        heroUnit = "km",
        heroUnitLong = "KILOMETRES",
        figures = emptyList(),
        dateLine = "2 RIDES · MAR 2026",
        placeLine = "1 states · 3 districts",
        link = "https://trackme.shvms.in/r/abc123",
        elevation = null,
        elevationLabel = null,
        splits = emptyList(),
        splitsLabel = null,
        fastestSegment = null,
        fastestLabel = null,
        award = null,
        light = LightPhase.DAY,
        lightLine = null,
        runPalette = palette,
    )

    private fun render(palette: List<Int>?, name: String): Bitmap {
        val bitmap = TemplateRenderer.render(
            ExportTemplateId.TRACE, TemplateCanvas.SQUARE, content(palette), Typeface.DEFAULT,
        )
        FileOutputStream(File(outDir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    fun `each ride keeps its own colour`() {
        val bitmap = render(listOf(cyan, purple), "aggregate-trace-palette")
        assertTrue("ride 1's colour is missing", has(bitmap, cyan))
        assertTrue("ride 2's colour is missing — the palette was dropped on the way to the canvas", has(bitmap, purple))
    }

    /**
     * Without a palette the same content is a single ride's Trace, and its line has to stay on the
     * pace gradient — this is what stops the aggregate field leaking into the single-ride look.
     */
    @Test
    fun `no palette leaves the pace colours alone`() {
        val bitmap = render(null, "aggregate-trace-no-palette")
        assertTrue("the second ride's colour appeared without a palette", !has(bitmap, purple))
    }

    /** Antialiasing means the exact value appears only in the middle of a stroke; near enough is enough. */
    private fun has(bitmap: Bitmap, color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val p = bitmap.getPixel(x, y)
            val dr = kotlin.math.abs(((p shr 16) and 0xFF) - r)
            val dg = kotlin.math.abs(((p shr 8) and 0xFF) - g)
            val db = kotlin.math.abs((p and 0xFF) - b)
            if (dr + dg + db <= 24) return true
        }
        return false
    }
}
