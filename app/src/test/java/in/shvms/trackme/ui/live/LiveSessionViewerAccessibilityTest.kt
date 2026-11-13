package `in`.shvms.trackme.ui.live

import `in`.shvms.trackme.ui.localization.SUPPORTED_LANGUAGE_CODES
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LiveSessionViewerAccessibilityTest {

    @Test
    fun `all supported languages provide valid non-empty live viewer accessibility strings`() {
        SUPPORTED_LANGUAGE_CODES.forEach { lang ->
            val strings = getAppStrings(lang)
            val locale = Locale.forLanguageTag(lang)

            // Delayed badge a11y description
            assertFalse(
                "Delayed badge a11y empty in $lang",
                strings.liveViewerDelayedBadgeA11y.isBlank()
            )

            // Recenter and Fit Route map button descriptions
            assertFalse(
                "Recenter a11y empty in $lang",
                strings.liveViewerRecenterA11y.isBlank()
            )
            assertFalse(
                "Fit route a11y empty in $lang",
                strings.liveViewerFitRouteA11y.isBlank()
            )

            // Formatted battery description
            val batteryFormatted = String.format(locale, strings.liveViewerBatteryA11y, 78)
            assertTrue(
                "Battery a11y formatted output should contain '78' in $lang",
                batteryFormatted.contains("78")
            )

            // Formatted speed description
            val speedFormatted = String.format(locale, strings.liveViewerSpeedA11y, "25 km/h")
            assertTrue(
                "Speed a11y formatted output should contain '25 km/h' in $lang",
                speedFormatted.contains("25 km/h")
            )

            // Speed unavailable description
            assertFalse(
                "Speed unavailable a11y empty in $lang",
                strings.liveViewerSpeedUnavailableA11y.isBlank()
            )

            // Destination description
            val destinationFormatted = String.format(locale, strings.liveViewerDestinationA11y, "City Hall")
            assertTrue(
                "Destination a11y formatted output should contain 'City Hall' in $lang",
                destinationFormatted.contains("City Hall")
            )

            // Open in browser description
            assertFalse(
                "Open in browser a11y empty in $lang",
                strings.liveViewerOpenInBrowserA11y.isBlank()
            )
        }
    }

    @Test
    fun `english accessibility strings match expected human-readable standards`() {
        val strings = getAppStrings("en")
        assertEquals("Location updates delayed", strings.liveViewerDelayedBadgeA11y)
        assertEquals("Re-center map on rider's current location", strings.liveViewerRecenterA11y)
        assertEquals("Fit entire route in view", strings.liveViewerFitRouteA11y)
        assertEquals("Destination: %1\$s", strings.liveViewerDestinationA11y)
        assertEquals("Rider speed: %1\$s", strings.liveViewerSpeedA11y)
        assertEquals("Rider speed unavailable", strings.liveViewerSpeedUnavailableA11y)
        assertEquals("Rider battery: %1\$d percent", strings.liveViewerBatteryA11y)
        assertEquals("Open live tracking session in web browser", strings.liveViewerOpenInBrowserA11y)
        assertEquals("Close", strings.close)
    }
}
