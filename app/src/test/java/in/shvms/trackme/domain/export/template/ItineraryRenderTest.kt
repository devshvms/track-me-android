package `in`.shvms.trackme.domain.export.template

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * SCOPE_1.8.9 Part 2 — The Itinerary, rendered and written out for review.
 *
 * §15.5 of Part 1 records that every defect worth finding in these templates was found by *looking*
 * at the output, not by asserting on it: beading on the pace gradient, a footer overflowing, a
 * summary line ellipsised. So this writes a PNG per canvas and per selection size, and the
 * assertions only catch the failures a person would miss — a blank frame, or a chain that has
 * quietly run off the bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
// Without NATIVE, Robolectric's canvas is a no-op recorder: the bitmap comes back the size it
// should be and entirely empty, so every assertion about what was drawn passes or fails for the
// wrong reason. Part 1's screenshot tests set this for the same reason.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItineraryRenderTest {

    private val outDir = File("build/template-renders").apply { mkdirs() }

    /** The chain this work exists for, with its real districts. */
    private fun tour(stops: Int): Itinerary {
        val names = listOf(
            "Bengaluru" to "Bengaluru Urban",
            "Hampi" to "Vijayanagara",
            "Badami" to "Bagalkot",
            "Panaji" to "North Goa",
            "Sindhudurg" to "Sindhudurg",
            "Ratnagiri" to "Ratnagiri",
            "Mahabaleshwar" to "Satara",
            "Pune" to "Pune",
            "Nashik" to "Nashik",
        ).take(stops)
        return Itinerary(
            stops = names.map { ItineraryStop(it.first, it.second) },
            hops = List(stops - 1) { ItineraryHop(140_000.0 + it * 60_000.0, 12_000_000L + it * 3_000_000L) },
        )
    }

    private fun content(itinerary: Itinerary) = TemplateContent(
        runs = emptyList(),
        joins = emptyList(),
        runIntensities = null,
        heroValue = "710",
        heroUnit = "km",
        heroUnitLong = "KILOMETRES",
        figures = emptyList(),
        dateLine = "4 DAYS · MAR 2026",
        placeLine = null,
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
        itinerary = itinerary,
    )

    private fun render(canvas: TemplateCanvas, stops: Int, name: String): Bitmap {
        val bitmap = TemplateRenderer.render(
            ExportTemplateId.ITINERARY, canvas, content(tour(stops)), Typeface.DEFAULT,
        )
        FileOutputStream(File(outDir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    fun `every canvas renders the four-stop tour`() {
        listOf(
            TemplateCanvas.STORY to "itinerary-story-4",
            TemplateCanvas.PORTRAIT to "itinerary-portrait-4",
            TemplateCanvas.SQUARE to "itinerary-square-4",
        ).forEach { (canvas, name) ->
            val bitmap = render(canvas, 4, name)
            assertTrue("$name is blank", inkShare(bitmap) > 0.01)
        }
    }

    /**
     * The pitch is computed from the stop count rather than tabulated, so the case that breaks it is
     * the biggest selection the compare screen allows: MAX_COMPARISON_RIDES legs, nine stops. If the
     * squeeze does not hold, the last stop lands below the hero figure and the render is wrong in a
     * way no total would reveal.
     */
    @Test
    fun `the longest selection still fits above the hero`() {
        listOf(
            TemplateCanvas.STORY to "itinerary-story-9",
            TemplateCanvas.PORTRAIT to "itinerary-portrait-9",
            TemplateCanvas.SQUARE to "itinerary-square-9",
        ).forEach { (canvas, name) ->
            val bitmap = render(canvas, 9, name)
            assertTrue("$name is blank", inkShare(bitmap) > 0.01)
            assertTrue(
                "$name has ink in its bottom margin — the chain has run past the footer",
                bottomMarginIsClear(bitmap),
            )
            // The collision this caught on the first render: at nine stops the chain reached far
            // enough down that the last name was drawn through the hero figure. The hero band has
            // to belong to the hero alone, so the row just above it carries no chain text.
            assertTrue(
                "$name draws the chain into the hero band — the last stop collides with the total",
                heroBandIsClearOfChain(bitmap),
            )
        }
    }

    @Test
    fun `a selection that is not a tour does not draw a chain`() {
        val bitmap = TemplateRenderer.render(
            ExportTemplateId.ITINERARY, TemplateCanvas.SQUARE,
            content(tour(4)).copy(itinerary = null), Typeface.DEFAULT,
        )
        FileOutputStream(File(outDir, "itinerary-no-tour.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // It falls back to the hero figure alone rather than half a chain asserting a journey.
        assertTrue("the fallback should still show something", inkShare(bitmap) > 0.001)
    }

    /** Any pixel meaningfully lighter than the ground counts as ink. */
    private fun inkShare(bitmap: Bitmap): Double {
        var ink = 0
        var total = 0
        for (y in 0 until bitmap.height step 6) for (x in 0 until bitmap.width step 6) {
            val p = bitmap.getPixel(x, y)
            val luma = ((p shr 16 and 0xFF) * 0.299 + (p shr 8 and 0xFF) * 0.587 + (p and 0xFF) * 0.114)
            if (luma > 60) ink++
            total++
        }
        return ink.toDouble() / total
    }

    /**
     * No chain text may sit on or below the hairline that separates the chain from the total.
     *
     * The hairline is found rather than assumed, because its position moves with the canvas: it is
     * the lowest near-full-width row of faint pixels. The first version of this test guessed a band
     * by percentage and failed on a render that was correct — the band it picked was where the last
     * stop legitimately sits.
     */
    private fun heroBandIsClearOfChain(bitmap: Bitmap): Boolean {
        val hairlineY = (bitmap.height / 2 until bitmap.height).lastOrNull { y ->
            var run = 0
            for (x in (bitmap.width * 0.1).toInt() until (bitmap.width * 0.9).toInt() step 4) {
                val p = bitmap.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) * 0.299 + (p shr 8 and 0xFF) * 0.587 + (p and 0xFF) * 0.114)
                if (luma in 28.0..90.0) run++
            }
            run > (bitmap.width * 0.8 / 4) * 0.85
        } ?: return true

        // Bright text in the 24 px straddling the hairline is the chain having run into the total.
        for (y in (hairlineY - 12).coerceAtLeast(0) until (hairlineY + 12).coerceAtMost(bitmap.height)) {
            for (x in (bitmap.width * 0.18).toInt() until bitmap.width step 3) {
                val p = bitmap.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) * 0.299 + (p shr 8 and 0xFF) * 0.587 + (p and 0xFF) * 0.114)
                if (luma > 150) return false
            }
        }
        return true
    }

    /** The last 2% of the frame is margin; the link sits above it. */
    private fun bottomMarginIsClear(bitmap: Bitmap): Boolean {
        val from = (bitmap.height * 0.98).toInt()
        for (y in from until bitmap.height step 2) for (x in 0 until bitmap.width step 4) {
            val p = bitmap.getPixel(x, y)
            val luma = ((p shr 16 and 0xFF) * 0.299 + (p shr 8 and 0xFF) * 0.587 + (p and 0xFF) * 0.114)
            if (luma > 110) return false
        }
        return true
    }
}
