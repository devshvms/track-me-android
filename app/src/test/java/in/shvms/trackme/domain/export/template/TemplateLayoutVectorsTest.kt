package `in`.shvms.trackme.domain.export.template

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.8.9 §11 gate 2 (parity), as something a machine can check.
 *
 * The gate asks that Android and iOS make the same layout decisions for the same template and ride,
 * expressed as frame fractions. A side-by-side screenshot proves that once, for the ride and the
 * moment it was taken. `template-layout-v1.json` proves it on every run for the decisions that are
 * actually shared: the design space, what each template declares, and where the route box sits in
 * its frame. The per-template type tables stay private to each renderer — the render tests cover
 * those — and this file covers the contract between the two.
 */
class TemplateLayoutVectorsTest {

    private val vectors = JSONObject(File("src/test/resources/template-layout-v1.json").readText())

    private fun canvas(name: String): TemplateCanvas = when (name) {
        "story" -> TemplateCanvas.STORY
        "portrait" -> TemplateCanvas.PORTRAIT
        "square" -> TemplateCanvas.SQUARE
        "card" -> TemplateCanvas.CARD
        else -> error("unknown canvas: $name")
    }

    private fun scope(name: String): TemplateScope = TemplateScope.valueOf(name.uppercase())

    @Test
    fun `the design width is the one both renderers scale from`() {
        assertEquals(vectors.getDouble("design_width").toFloat(), TemplateRenderer.DESIGN_WIDTH)
    }

    @Test
    fun `every canvas is its destination's real size`() {
        val canvases = vectors.getJSONObject("canvases")
        canvases.keys().forEach { name ->
            val want = canvases.getJSONObject(name)
            val spec = canvas(name)
            assertEquals("$name width", want.getInt("width"), spec.widthPx)
            assertEquals("$name height", want.getInt("height"), spec.heightPx)
            assertEquals("$name aspect", want.getDouble("aspect"), spec.aspect.toDouble(), 1e-6)
            assertEquals("$name label", want.getString("label"), spec.label)
        }
    }

    @Test
    fun `every template declares what the contract says it declares`() {
        val templates = vectors.getJSONArray("templates")
        assertEquals("template count", templates.length(), ExportTemplates.all.size)
        (0 until templates.length()).forEach { index ->
            val want = templates.getJSONObject(index)
            val id = want.getString("id")
            // Order matters: it is the strip's order, and a rider learns where the cards are.
            val spec = ExportTemplates.all[index]
            assertEquals("declaration order at $index", id, spec.id.analyticsValue)
            assertEquals("$id scope", scope(want.getString("scope")), spec.scope)
            val canvases = want.getJSONArray("canvases")
            assertEquals("$id canvas count", canvases.length(), spec.canvases.size)
            (0 until canvases.length()).forEach { c ->
                assertEquals("$id canvas $c", canvas(canvases.getString(c)), spec.canvases[c])
            }
            assertEquals("$id default canvas", canvas(want.getString("default_canvas")), spec.defaultCanvas)
            assertEquals("$id transparent", want.getBoolean("transparent"), spec.transparent)
            assertEquals("$id map background", want.getBoolean("supports_map_background"), spec.supportsMapBackground)
        }
    }

    @Test
    fun `the trace route box sits where both platforms put it`() {
        val boxes = vectors.getJSONObject("trace_route_box")
        boxes.keys().forEach { name ->
            val want = boxes.getJSONObject(name)
            val box = traceRouteBoxDesign(canvas(name))
            assertEquals("$name left", want.getDouble("left"), box.left.toDouble(), 1e-6)
            assertEquals("$name top", want.getDouble("top"), box.top.toDouble(), 1e-6)
            assertEquals("$name right", want.getDouble("right"), box.right.toDouble(), 1e-6)
            assertEquals("$name bottom", want.getDouble("bottom"), box.bottom.toDouble(), 1e-6)
        }
    }

    /**
     * The gate's own unit. A client that changed a canvas size without moving the box would keep the
     * design-unit assertion above and fail here — which is the failure worth catching, because it is
     * the one that silently reframes the route.
     */
    @Test
    fun `the route box is the same fraction of every frame it is drawn in`() {
        val fractions = vectors.getJSONObject("trace_route_box_fractions")
        fractions.keys().asSequence().filter { it != "_note" }.forEach { name ->
            val want = fractions.getJSONObject(name)
            val spec = canvas(name)
            val box = traceRouteBoxDesign(spec)
            val designHeight = TemplateRenderer.DESIGN_WIDTH / spec.aspect
            assertEquals("$name left", want.getDouble("left"), (box.left / TemplateRenderer.DESIGN_WIDTH).toDouble(), 1e-6)
            assertEquals("$name right", want.getDouble("right"), (box.right / TemplateRenderer.DESIGN_WIDTH).toDouble(), 1e-6)
            assertEquals("$name top", want.getDouble("top"), (box.top / designHeight).toDouble(), 1e-6)
            assertEquals("$name bottom", want.getDouble("bottom"), (box.bottom / designHeight).toDouble(), 1e-6)
        }
    }
}
