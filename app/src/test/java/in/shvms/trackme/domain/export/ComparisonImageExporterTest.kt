package `in`.shvms.trackme.domain.export

import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonImageExporterTest {
    @Test
    fun `legend layout keeps eight rows inside bitmap`() {
        val layout = comparisonLegendLayout(bitmapWidth = 1080, bitmapHeight = 1920, rowCount = 8)!!
        val lastBaseline = layout.verticalPadding + layout.textSize + 7 * layout.lineHeight

        assertTrue(lastBaseline + layout.textSize <= layout.panelHeight)
        assertTrue(layout.panelHeight <= 1920)
    }

    @Test
    fun `legend layout caps row count at supported maximum`() {
        val capped = comparisonLegendLayout(bitmapWidth = 1080, bitmapHeight = 1920, rowCount = 20)!!
        val maximum = comparisonLegendLayout(bitmapWidth = 1080, bitmapHeight = 1920, rowCount = 8)!!

        assertTrue(capped.panelHeight == maximum.panelHeight)
    }
}
