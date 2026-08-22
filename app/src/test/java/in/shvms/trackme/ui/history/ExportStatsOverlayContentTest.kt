package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's contents and its height are now one decision, not two — SCOPE_1.8.4 §8.3.
 *
 * These assert the properties that actually broke in the field: a panel sized independently of what
 * it holds, and two renderers free to disagree about what that is.
 */
class ExportStatsOverlayContentTest {

    private fun content(
        title: String? = null,
        date: Boolean = false,
        duration: Boolean = false,
        distance: Boolean = false,
    ) = buildOverlayContent(
        rideTitle = title,
        showTitle = title != null,
        date = "Aug 22, 2026",
        duration = "17min",
        distance = "6.3 km",
        showDate = date,
        showDuration = duration,
        showDistance = distance,
    )

    @Test
    fun `blank title is not a line`() {
        assertNull(buildOverlayContent("   ", true, "d", "t", "x", false, false, false).title)
    }

    @Test
    fun `title is dropped when not shown`() {
        assertNull(content(title = "Evening BikeDrive").copy(title = null).title)
        assertNull(
            buildOverlayContent("Evening BikeDrive", false, "d", "t", "x", false, false, false).title
        )
    }

    @Test
    fun `figures keep date duration distance order`() {
        assertEquals(
            listOf("Aug 22, 2026", "17min", "6.3 km"),
            content(date = true, duration = true, distance = true).figures,
        )
    }

    @Test
    fun `a stacked card counts every figure as a line, a band counts one`() {
        val c = content(title = "Evening BikeDrive", date = true, duration = true, distance = true)
        assertEquals(4, c.lineCount(stacked = true))
        assertEquals(2, c.lineCount(stacked = false))
    }

    @Test
    fun `panel height grows with its contents`() {
        // The reported defect: height was a constant, so one figure and four lines drew the same box.
        val one = OverlayMetrics.panelHeightFraction(1, hasTitle = false)
        val four = OverlayMetrics.panelHeightFraction(4, hasTitle = true)
        assertTrue("four lines must be taller than one", four > one)
    }

    @Test
    fun `empty content yields no panel at all`() {
        val empty = content()
        assertTrue(empty.isEmpty)
        StatsOverlayStyle.entries.forEach { style ->
            assertNull("$style must not draw a panel for empty content", style.rect(empty))
        }
    }

    @Test
    fun `a one-figure card is far smaller than the old fixed 19 percent`() {
        val rect = StatsOverlayStyle.TopRight.rect(content(distance = true))!!
        assertTrue(
            "one-figure card was ${rect.heightFraction}, expected well under the old 0.19",
            rect.heightFraction < 0.12f,
        )
    }

    @Test
    fun `bottom band stays flush to the bottom edge whatever its height`() {
        val tall = StatsOverlayStyle.BottomBar.rect(
            content(title = "Evening BikeDrive", date = true, duration = true, distance = true)
        )!!
        assertEquals(1f, tall.bottom, 0.0001f)
        assertTrue(tall.top > 0f)
    }

    @Test
    fun `None never draws regardless of content`() {
        assertNull(StatsOverlayStyle.None.rect(content(date = true, duration = true)))
    }
}
