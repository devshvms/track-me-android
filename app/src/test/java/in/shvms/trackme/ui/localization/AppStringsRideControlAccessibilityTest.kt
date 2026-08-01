package `in`.shvms.trackme.ui.localization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsRideControlAccessibilityTest {
    private val accessibilityLabels = listOf(
        AppStrings::stopRideAction,
        AppStrings::rideStopped,
        AppStrings::recenterMap,
        AppStrings::compassNorth,
        AppStrings::send,
        AppStrings::copy,
        AppStrings::sharePin,
        AppStrings::timelineScrubberAccessibility,
        AppStrings::newVersionAvailable,
        AppStrings::updateAvailable,
        AppStrings::info,
        AppStrings::expand
    )

    @Test
    fun `stop control strings are translated in every supported locale`() {
        val english = getAppStrings("en")
        listOf("es", "fr", "de", "hi", "ja", "zh").forEach { language ->
            val strings = getAppStrings(language)
            assertTrue("$language stop action is blank", strings.stopRideAction.isNotBlank())
            assertTrue("$language stopped state is blank", strings.rideStopped.isNotBlank())
            assertFalse("$language stop action fell back to English", strings.stopRideAction == english.stopRideAction)
            assertFalse("$language stopped state fell back to English", strings.rideStopped == english.rideStopped)
        }
    }

    @Test
    fun `accessibility labels are translated in every supported locale`() {
        val english = getAppStrings("en")
        listOf("es", "fr", "de", "hi", "ja", "zh").forEach { language ->
            val strings = getAppStrings(language)
            accessibilityLabels.forEach { label ->
                val localized = label.get(strings)
                assertTrue("$language accessibility label is blank", localized.isNotBlank())
                assertFalse("$language accessibility label fell back to English", localized == label.get(english))
            }
        }
    }
}
