package `in`.shvms.trackme.ui.localization

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppStringsPersonaAccessibilityTest {
    private val supportedLanguages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    @Test
    fun `english auto label is distinct from the car persona`() {
        val strings = getAppStrings("en")

        assertEquals("Automatic", strings.personaLabel(RidePersona.AUTO))
        assertEquals("Car", strings.personaLabel(RidePersona.CAR_DRIVE))
        assertFalse(strings.personaLabel(RidePersona.AUTO) == strings.personaLabel(RidePersona.CAR_DRIVE))
    }

    @Test
    fun `persona accessibility templates resolve in every supported language`() {
        val english = getAppStrings("en")

        supportedLanguages.forEach { language ->
            val strings = getAppStrings(language)
            RidePersona.entries.forEach { persona ->
                val label = strings.personaLabel(persona)
                val starting = String.format(Locale.US, strings.startingPersona, label)
                val start = String.format(Locale.US, strings.startPersona, label)

                assertTrue("$language/$persona starting label is blank", starting.isNotBlank())
                assertTrue("$language/$persona start label is blank", start.isNotBlank())
                assertTrue("$language/$persona starting label omitted persona", starting.contains(label))
                assertTrue("$language/$persona start label omitted persona", start.contains(label))
                assertFalse("$language/$persona left a format placeholder", starting.contains("%1"))
                assertFalse("$language/$persona left a format placeholder", start.contains("%1"))
            }

            if (language != "en") {
                assertFalse("$language fell back for startingPersona", strings.startingPersona == english.startingPersona)
                assertFalse("$language fell back for startPersona", strings.startPersona == english.startPersona)
                assertFalse("$language fell back for personaAuto", strings.personaAuto == english.personaAuto)
                assertFalse("$language fell back for dragToSelect", strings.dragToSelect == english.dragToSelect)
                assertFalse("$language fell back for startRideAccessibility", strings.startRideAccessibility == english.startRideAccessibility)
                assertFalse("$language fell back for activitySelectionAvailable", strings.activitySelectionAvailable == english.activitySelectionAvailable)
                assertFalse("$language fell back for startRideAction", strings.startRideAction == english.startRideAction)
            }
        }
    }
}
