package `in`.shvms.trackme.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window breakpoints decide whether navigation sits at the bottom or the side, so an
 * off-by-one at a boundary silently moves the whole layout by one device class. These pin the
 * boundaries themselves rather than a value comfortably inside each band.
 */
class WindowClassTest {

    @Test
    fun belowSixHundred_isCompact() {
        assertEquals(TrackMeWindowClass.Compact, windowClassFor(599))
    }

    @Test
    fun exactlySixHundred_isMedium() {
        assertEquals(TrackMeWindowClass.Medium, windowClassFor(600))
    }

    @Test
    fun belowEightForty_isMedium() {
        assertEquals(TrackMeWindowClass.Medium, windowClassFor(839))
    }

    @Test
    fun exactlyEightForty_isExpanded() {
        assertEquals(TrackMeWindowClass.Expanded, windowClassFor(840))
    }

    @Test
    fun typicalPhonePortrait_isCompact() {
        // Pixel-class portrait width. The overwhelmingly common case, and the one that must keep
        // the bottom bar it has always had.
        assertEquals(TrackMeWindowClass.Compact, windowClassFor(412))
    }

    @Test
    fun onlyCompactUsesBottomNavigation() {
        assertFalse(TrackMeWindowClass.Compact.usesNavigationRail)
        assertTrue(TrackMeWindowClass.Medium.usesNavigationRail)
        assertTrue(TrackMeWindowClass.Expanded.usesNavigationRail)
    }
}
