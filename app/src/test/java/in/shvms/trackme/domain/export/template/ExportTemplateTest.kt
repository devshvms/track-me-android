package `in`.shvms.trackme.domain.export.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SCOPE_1.8.9 §5, §9.2, §9.3 — the template declarations are the contract; these pin it. */
class ExportTemplateTest {

    @Test
    fun `the strip order is stable with the Award last so it never shifts the others`() {
        // The contract is about the single-ride strip: those five must not reshuffle under a rider
        // who has learned where they are. Part 2's aggregate templates are appended after them and
        // never appear in that strip, so they cannot shift it — which is why the assertion is now
        // "the single-ride ids, in this order, first" rather than "these are all of them".
        assertEquals(
            listOf(ExportTemplateId.TRACE, ExportTemplateId.INSTRUMENT, ExportTemplateId.STICKER, ExportTemplateId.HOUR, ExportTemplateId.AWARD),
            ExportTemplates.all.filter { it.scope != TemplateScope.AGGREGATE }.map { it.id },
        )
        assertEquals(
            "aggregate templates belong after the single-ride ones",
            listOf(ExportTemplateId.ITINERARY),
            ExportTemplates.all.filter { it.scope == TemplateScope.AGGREGATE }.map { it.id },
        )
    }

    @Test
    fun `the three templates built on one ride's data are single-ride only`() {
        // §9.3: one reveal keyed by ride id, one continuous split table, one hour of light.
        listOf(ExportTemplateId.AWARD, ExportTemplateId.INSTRUMENT, ExportTemplateId.HOUR).forEach {
            assertEquals(it.name, TemplateScope.SINGLE, ExportTemplates.spec(it).scope)
        }
        assertEquals(TemplateScope.BOTH, ExportTemplates.spec(ExportTemplateId.TRACE).scope)
    }

    @Test
    fun `the Instrument opens at 4 by 5 and the Sticker is a transparent card`() {
        assertEquals(TemplateCanvas.PORTRAIT, ExportTemplates.spec(ExportTemplateId.INSTRUMENT).defaultCanvas)
        val sticker = ExportTemplates.spec(ExportTemplateId.STICKER)
        assertTrue(sticker.transparent)
        assertEquals(listOf(TemplateCanvas.CARD), sticker.canvases)
    }

    @Test
    fun `only the Trace offers a basemap`() {
        assertEquals(listOf(ExportTemplateId.TRACE), ExportTemplates.all.filter { it.supportsMapBackground }.map { it.id })
    }

    @Test
    fun `a chosen ratio is kept where the template supports it and replaced where it does not`() {
        assertEquals(TemplateCanvas.SQUARE, ExportTemplates.canvasFor(ExportTemplateId.TRACE, TemplateCanvas.SQUARE))
        assertEquals(TemplateCanvas.CARD, ExportTemplates.canvasFor(ExportTemplateId.STICKER, TemplateCanvas.STORY))
        assertEquals(TemplateCanvas.PORTRAIT, ExportTemplates.canvasFor(ExportTemplateId.INSTRUMENT, null))
        assertFalse(TemplateCanvas.CARD in ExportTemplates.spec(ExportTemplateId.TRACE).canvases)
    }

    @Test
    fun `the ratio chips keep one order, not each template's native-first list`() {
        val fixed = listOf(TemplateCanvas.STORY, TemplateCanvas.PORTRAIT, TemplateCanvas.SQUARE)
        // The Instrument lists 4:5 first; the chips must not follow it.
        assertEquals(fixed, ExportTemplates.canvasChoices(ExportTemplateId.INSTRUMENT))
        assertEquals(fixed, ExportTemplates.canvasChoices(ExportTemplateId.TRACE))
        assertEquals(listOf(TemplateCanvas.CARD), ExportTemplates.canvasChoices(ExportTemplateId.STICKER))
    }

    @Test
    fun `every canvas is drawn at the destination's real width`() {
        TemplateCanvas.entries.forEach { assertEquals(it.name, 1080, it.widthPx) }
        assertEquals(1920, TemplateCanvas.STORY.heightPx)
        assertEquals(1350, TemplateCanvas.PORTRAIT.heightPx)
    }
}
