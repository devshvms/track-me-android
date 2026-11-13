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

            // Formatted low battery description (CIE-008)
            val batteryLowFormatted = String.format(locale, strings.liveViewerBatteryLowA11y, 12)
            assertTrue(
                "Low battery a11y formatted output should contain '12' in $lang",
                batteryLowFormatted.contains("12")
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

            // Rider ended notice
            assertFalse(
                "Rider ended notice empty in $lang",
                strings.liveViewerRiderEndedNotice.isBlank()
            )

            // Stale signal badge, banner, and a11y description (CIE-011)
            assertFalse(
                "Signal paused badge empty in $lang",
                strings.liveViewerSignalPausedBadge.isBlank()
            )
            assertFalse(
                "Signal paused banner empty in $lang",
                strings.liveViewerSignalPausedBanner.isBlank()
            )
            assertFalse(
                "Signal paused a11y empty in $lang",
                strings.liveViewerSignalPausedA11y.isBlank()
            )

            // Duration label formatted output
            val durationFormatted = String.format(locale, strings.liveViewerDurationLabel, "42m")
            assertTrue(
                "Duration label formatted output should contain '42m' in $lang",
                durationFormatted.contains("42m")
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
        assertEquals("The rider has ended this live trip.", strings.liveViewerRiderEndedNotice)
        assertEquals("Signal paused", strings.liveViewerSignalPausedBadge)
        assertEquals("GPS signal paused, waiting for rider location", strings.liveViewerSignalPausedA11y)
        assertEquals("Signal paused · Waiting for rider GPS", strings.liveViewerSignalPausedBanner)
        assertEquals("Duration: %1\$s", strings.liveViewerDurationLabel)
        assertEquals("Close", strings.close)
    }
}
