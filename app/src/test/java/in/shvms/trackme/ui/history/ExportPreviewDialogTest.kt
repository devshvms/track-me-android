package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPreviewDialogTest {
    @Test
    fun portraitPreviewIsBoundedWithoutChangingAspectRatio() {
        val size = boundedPreviewSize(maxWidth = 360f, maxHeight = 420f, ratio = 9f / 16f)

        assertTrue(size.width <= 360f)
        assertTrue(size.height <= 420f)
        assertEquals(9f / 16f, size.width / size.height, 0.0001f)
    }

    @Test
    fun landscapePreviewUsesAvailableWidth() {
        val size = boundedPreviewSize(maxWidth = 360f, maxHeight = 420f, ratio = 16f / 9f)

        assertEquals(360f, size.width, 0.0001f)
        assertEquals(16f / 9f, size.width / size.height, 0.0001f)
    }
}

/**
 * The export used to be a screenshot of the on-screen preview, so its resolution was whatever the
 * preview view measured — a function of the device's screen density and a layout constant, and
 * always below the size the caller asked for. These pin the replacement: a size derived from the
 * ratio alone, identical on every device.
 */
class ExportPixelSizeTest {

    @Test
    fun squareIsTheShortEdgeBothWays() {
        assertEquals(1080 to 1080, exportPixelSize(1f))
    }

    @Test
    fun portraitStoryReachesFullSocialSize() {
        // The default for single-ride export, and the case that motivated the change: this was
        // coming out around 708×1260 on a 3x phone.
        assertEquals(1080 to 1920, exportPixelSize(9f / 16f))
    }

    @Test
    fun landscapeIsCappedOnTheLongEdge() {
        assertEquals(1920 to 1080, exportPixelSize(16f / 9f))
    }

    @Test
    fun fourThreeKeepsTheShortEdge() {
        assertEquals(1440 to 1080, exportPixelSize(4f / 3f))
    }

    @Test
    fun everyRatioRoundTripsThroughTheSize() {
        // The property that actually matters: what you asked for is the shape you get.
        for (ratio in listOf(1f, 4f / 3f, 16f / 9f, 9f / 16f, 3f / 4f, 1080f / 1920f)) {
            val (width, height) = exportPixelSize(ratio)
            assertEquals(
                "ratio $ratio produced ${width}x$height",
                ratio,
                width.toFloat() / height.toFloat(),
                0.005f,
            )
        }
    }

    @Test
    fun neverExceedsTheLongEdgeCap() {
        // A pathological ratio must not ask the allocator for an unbounded bitmap.
        for (ratio in listOf(50f, 0.02f, 1000f)) {
            val (width, height) = exportPixelSize(ratio)
            assertTrue("$width x $height exceeds the cap", maxOf(width, height) <= 1920)
            assertTrue("$width x $height is degenerate", minOf(width, height) >= 1)
        }
    }

    @Test
    fun degenerateRatiosFallBackToSquare() {
        assertEquals(1080 to 1080, exportPixelSize(0f))
        assertEquals(1080 to 1080, exportPixelSize(-3f))
        assertEquals(1080 to 1080, exportPixelSize(Float.NaN))
        assertEquals(1080 to 1080, exportPixelSize(Float.POSITIVE_INFINITY))
    }
}

class ExportRenderScaleTest {

    @Test
    fun strokeScalesWithRenderWidth() {
        // The point of the whole change: preview and export draw the same picture, so the stroke
        // has to be a fraction of the surface rather than a fixed pixel count.
        val preview = ExportRenderScale.routeStroke(540)
        val export = ExportRenderScale.routeStroke(1080)
        assertEquals(2f, export / preview, 0.001f)
    }

    @Test
    fun strokeHasAFloorSoTinySurfacesStillDrawALine() {
        assertTrue(ExportRenderScale.routeStroke(10) >= 4f)
    }

    @Test
    fun markerScalesWithRenderWidth() {
        assertEquals(2 * ExportRenderScale.markerSize(540), ExportRenderScale.markerSize(1080))
    }

    @Test
    fun fitPaddingComesFromTheShorterEdge() {
        // Fixed pixel padding is a different proportion of a tall frame than of a wide one, which
        // is why the same route sat tighter in one ratio than another. Mirrored dimensions must
        // produce identical padding.
        assertEquals(
            ExportRenderScale.fitPadding(1080, 1920),
            ExportRenderScale.fitPadding(1920, 1080),
        )
    }

    @Test
    fun fitPaddingHasAFloor() {
        assertTrue(ExportRenderScale.fitPadding(20, 20) >= 8)
    }
}

class ExportControlCategoryTest {

    @Test
    fun legendGroupIsAggregateOnly() {
        // Legend and sequence links describe several rides against each other; on a single ride
        // they would be a group that is present and inert.
        assertTrue(
            ExportControlCategory.Legend in exportCategoriesFor(showAggregateControls = true)
        )
        assertTrue(
            ExportControlCategory.Legend !in exportCategoriesFor(showAggregateControls = false)
        )
    }

    @Test
    fun everyOtherGroupIsAlwaysOffered() {
        val single = exportCategoriesFor(showAggregateControls = false)
        val aggregate = exportCategoriesFor(showAggregateControls = true)
        for (entry in ExportControlCategory.entries) {
            if (entry == ExportControlCategory.Legend) continue
            assertTrue("$entry missing from single-ride rail", entry in single)
            assertTrue("$entry missing from aggregate rail", entry in aggregate)
        }
    }

    @Test
    fun railOrderIsStable() {
        // The rail is muscle memory. Reordering it is a decision, not a refactor.
        assertEquals(
            listOf(
                ExportControlCategory.Ratio,
                ExportControlCategory.MapStyle,
                ExportControlCategory.Privacy,
                ExportControlCategory.Markers,
                ExportControlCategory.Stats,
                ExportControlCategory.Legend,
            ),
            ExportControlCategory.entries.toList(),
        )
    }
}
