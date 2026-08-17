package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.google.maps.android.compose.MapType
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

/**
 * The option sets that replaced three booleans.
 *
 * Each of these was a switch whose only answer was yes or no, where the interesting question was
 * *which* — which marker, how much of the basemap's text, where the panel sits.
 */
class ExportOptionStyleTest {

    @Test
    fun markerStylesCoverTheStatesTheyClaim() {
        assertTrue(!ExportMarkerStyle.None.marksStart && !ExportMarkerStyle.None.marksFinish)
        assertTrue(ExportMarkerStyle.StartFinish.marksStart && ExportMarkerStyle.StartFinish.marksFinish)
        // The whole point of this one: the start is not marked, the finish is.
        assertTrue(!ExportMarkerStyle.FinishOnly.marksStart && ExportMarkerStyle.FinishOnly.marksFinish)
        assertTrue(ExportMarkerStyle.Mono.marksStart && ExportMarkerStyle.Mono.marksFinish)
        assertTrue(ExportMarkerStyle.Pin.marksStart && ExportMarkerStyle.Pin.marksFinish)
    }

    @Test
    fun onlyTheFullStyleDrawsPauseRings() {
        // The reductions are reductions. A monochrome or finish-only picture that still sprinkled
        // amber rings down the route would not be either of those things.
        for (style in ExportMarkerStyle.entries) {
            assertEquals(style == ExportMarkerStyle.StartFinish, style.marksPauses)
        }
    }

    @Test
    fun mapLabelStylesOnlyApplyToTheNormalBasemap() {
        // The Maps SDK ignores styling on satellite and terrain, so returning a style there would
        // be a control that silently does nothing.
        for (style in MapLabelStyle.entries) {
            assertEquals(null, style.styleFor(MapType.SATELLITE))
            assertEquals(null, style.styleFor(MapType.TERRAIN))
            assertEquals(null, style.styleFor(MapType.HYBRID))
        }
        assertEquals(null, MapLabelStyle.All.styleFor(MapType.NORMAL))
        assertTrue(MapLabelStyle.NoPlaces.styleFor(MapType.NORMAL) != null)
        assertTrue(MapLabelStyle.NoLabels.styleFor(MapType.NORMAL) != null)
    }

    @Test
    fun everyPanelRectStaysInsideTheFrame() {
        for (style in StatsOverlayStyle.entries) {
            val rect = style.rect() ?: continue
            assertTrue("$style left", rect.left in 0f..1f)
            assertTrue("$style top", rect.top in 0f..1f)
            assertTrue("$style right", rect.right in 0f..1f)
            assertTrue("$style bottom", rect.bottom in 0f..1f)
            assertTrue("$style has width", rect.widthFraction > 0f)
            assertTrue("$style has height", rect.heightFraction > 0f)
        }
    }

    @Test
    fun onlyNoneDrawsNothing() {
        assertEquals(null, StatsOverlayStyle.None.rect())
        for (style in StatsOverlayStyle.entries.filter { it != StatsOverlayStyle.None }) {
            assertTrue("$style should draw a panel", style.rect() != null)
        }
    }

    @Test
    fun bottomBarKeepsItsHistoricalGeometry() {
        // Every export anyone has already made looks like this. Changing it would silently alter
        // the appearance of a picture someone chose deliberately.
        val rect = StatsOverlayStyle.BottomBar.rect()!!
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(1f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
        assertEquals(0.20f, rect.heightFraction, 0.0001f)
        assertEquals(0f, rect.inset, 0.0001f)
    }

    @Test
    fun bottomHalfClearsTheAttributionCorner() {
        // The Google logo sits bottom-left and must stay visible. A half-width band anchored left
        // would cover it; anchored right it does not.
        val rect = StatsOverlayStyle.BottomHalf.rect()!!
        assertTrue("half panel must not reach the left edge", rect.left >= 0.5f)
        assertEquals(1f, rect.right, 0.0001f)
    }

    @Test
    fun cornerCardsAreInsetAndRounded() {
        for (style in listOf(StatsOverlayStyle.TopLeft, StatsOverlayStyle.TopRight)) {
            val rect = style.rect()!!
            assertTrue("$style should be inset from the top", rect.top > 0f)
            assertTrue("$style should be rounded", rect.inset > 0f)
            assertTrue("$style should not span the full width", rect.widthFraction < 0.75f)
            assertTrue("$style is a card", style.isCard)
        }
    }

    @Test
    fun panelPixelsScaleWithTheFrame() {
        // Same placement, two resolutions: the panel must occupy the same proportion of each.
        val rect = StatsOverlayStyle.TopRight.rect()!!
        val small = rect.rightPx(540) - rect.leftPx(540)
        val large = rect.rightPx(1080) - rect.leftPx(1080)
        assertEquals(2f, large / small, 0.001f)
    }

    @Test
    fun cornerRadiusComesFromTheShorterEdge() {
        // So the curve looks the same on a tall story as on a wide landscape frame.
        val rect = StatsOverlayStyle.TopLeft.rect()!!
        assertEquals(
            rect.cornerRadiusPx(1080, 1920),
            rect.cornerRadiusPx(1920, 1080),
            0.0001f,
        )
    }
}
