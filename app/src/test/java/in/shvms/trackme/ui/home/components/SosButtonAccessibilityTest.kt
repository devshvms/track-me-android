package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosButtonAccessibilityTest {
    private val supportedLanguages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    @Test
    fun `button exposes an actionable label and state in every language`() {
        val english = getAppStrings("en")

        supportedLanguages.forEach { language ->
            val strings = getAppStrings(language)
            val trigger = SosButtonAccessibility.contentDescription(
                isReady = true,
                isActive = false,
                strings = strings
            )
            val stop = SosButtonAccessibility.contentDescription(
                isReady = true,
                isActive = true,
                strings = strings
            )
            val readyState = SosButtonAccessibility.stateDescription(
                isReady = true,
                isActive = false,
                strings = strings
            )
            val activeState = SosButtonAccessibility.stateDescription(
                isReady = true,
                isActive = true,
                strings = strings
            )
            val unavailable = SosButtonAccessibility.contentDescription(
                isReady = false,
                isActive = false,
                strings = strings
            )

            listOf(trigger, stop, readyState, activeState, unavailable).forEach { value ->
                assertTrue("$language has blank SOS accessibility copy", value.isNotBlank())
            }
            assertFalse("$language does not distinguish trigger and stop", trigger == stop)
            assertFalse("$language does not distinguish ready and active", readyState == activeState)

            if (language != "en") {
                assertFalse("$language fell back for stop label", stop == english.stopEmergencyBroadcast)
                assertFalse("$language fell back for trigger label", trigger == english.triggerEmergencyAccessibility)
                assertFalse("$language fell back for ready state", readyState == english.emergencySosReady)
                assertFalse("$language fell back for active state", activeState == english.emergencySosActive)
                assertFalse("$language fell back for unavailable state", unavailable == english.emergencySosUnavailable)
            }
        }
    }
}
