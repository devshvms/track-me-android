package `in`.shvms.trackme.domain.group

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Destination links and coordinate formatting.
 *
 * These strings leave the app — into Maps, into a calendar entry, into whatever someone forwards
 * them to — so a malformed one fails somewhere we will never see a stack trace. Locale is the
 * quiet trap and most of this file is about it.
 */
class GroupDestinationLinksTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private val lat = 12.9716
    private val lng = 77.5946

    @Test
    fun `coordinates use a dot even in a comma-decimal locale`() {
        // THE BUG THIS PREVENTS. String.format with the default locale renders 12.9716 as
        // "12,9716" across most of Europe. Pasted into a maps URL that is either a different
        // place or nothing at all — and it would work perfectly in every test run in en-GB.
        Locale.setDefault(Locale.GERMANY)
        val text = GroupDestinationLinks.formatCoordinates(lat, lng)
        assertTrue("comma decimal separator leaked into coordinates: $text", text.startsWith("12.971600"))
        assertFalse(text.contains("12,9716"))
    }

    @Test
    fun `the geo uri survives a comma-decimal locale`() {
        Locale.setDefault(Locale.FRANCE)
        val uri = GroupDestinationLinks.geoUri(lat, lng, "Sunday Riders")
        assertTrue(uri, uri.startsWith("geo:12.971600,77.594600"))
        assertFalse("a comma decimal would split the coordinate pair", uri.contains("12,9716"))
    }

    @Test
    fun `the web url survives a comma-decimal locale`() {
        Locale.setDefault(Locale.ITALY)
        val url = GroupDestinationLinks.webMapUrl(lat, lng)
        assertTrue(url, url.contains("query=12.971600,77.594600"))
    }

    @Test
    fun `the label is carried so a pin is named in the maps app`() {
        val uri = GroupDestinationLinks.geoUri(lat, lng, "Sunday Riders")
        assertTrue(uri.contains("(Sunday Riders)"))
    }

    @Test
    fun `parentheses in a group name cannot break the uri`() {
        // "geo:...?q=12,77(Ride (Sunday))" closes the label early and swallows the rest.
        val uri = GroupDestinationLinks.geoUri(lat, lng, "Ride (Sunday)")
        assertEquals("unbalanced parentheses in $uri", 1, uri.count { it == '(' })
        assertEquals(1, uri.count { it == ')' })
    }

    @Test
    fun `a missing label still produces a usable uri`() {
        for (label in listOf(null, "", "   ")) {
            val uri = GroupDestinationLinks.geoUri(lat, lng, label)
            assertTrue("$label produced $uri", uri.startsWith("geo:12.971600,77.594600?q=12.971600,77.594600"))
            assertFalse("empty label left dangling parens", uri.contains("()"))
        }
    }

    @Test
    fun `the web url is platform-neutral`() {
        // It ends up in a calendar entry, and calendar entries travel. Android opens it in Maps,
        // iOS offers Apple Maps or Google Maps, a desktop browser just shows the place. A
        // platform-specific URL would strand whichever platform it was not written for.
        val url = GroupDestinationLinks.webMapUrl(lat, lng)
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("api=1"))
    }

    @Test
    fun `southern and western coordinates keep their sign`() {
        // A dropped minus sign is a hemisphere.
        val url = GroupDestinationLinks.webMapUrl(-33.8688, -151.2093)
        assertTrue(url, url.contains("query=-33.868800,-151.209300"))
    }

    @Test
    fun `no destination means no link rather than a link to nowhere`() {
        // (0, 0) is a real place in the Gulf of Guinea, and sending a group there would be worse
        // than sending them nothing.
        assertEquals("", GroupDestinationLinks.calendarDescription(null, null))
        assertEquals("", GroupDestinationLinks.calendarDescription(lat, null))
        assertEquals("", GroupDestinationLinks.calendarDescription(null, lng))
        assertTrue(GroupDestinationLinks.calendarDescription(lat, lng).startsWith("https://"))
    }

    @Test
    fun `precision is bounded to what GPS can justify`() {
        // Six decimals is ~0.1m. More would be noise dressed as accuracy.
        // Six decimals, trailing zeros kept: a fixed width reads as a coordinate rather than a
        // number someone rounded, and it is what every mapping tool emits.
        assertEquals("1.123457, 2.987654", GroupDestinationLinks.formatCoordinates(1.123456789, 2.987654321))
        assertEquals("12.971600, 77.594600", GroupDestinationLinks.formatCoordinates(12.9716, 77.5946))
    }
}
