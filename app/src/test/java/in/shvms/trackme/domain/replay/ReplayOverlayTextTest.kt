package `in`.shvms.trackme.domain.replay

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Covers the text burned into every exported replay frame. This text ships inside a file the user
 * shares publicly, so it cannot be wrong, untranslated, or in the wrong unit system.
 */
class ReplayOverlayTextTest {
    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // --- Duration -----------------------------------------------------------------------------

    /**
     * The defect: `"%02d:%02d"` had no hours field, so a 2h44m47s ride rendered as `"164:47"`.
     */
    @Test
    fun `durations over an hour render an hours field`() {
        assertEquals("02:44:47", formatReplayDuration(9_887_000L))
    }

    @Test
    fun `durations under an hour keep a zero hours field like the rest of the app`() {
        assertEquals("00:12:30", formatReplayDuration(750_000L))
    }

    @Test
    fun `non positive durations render zero rather than a negative clock`() {
        assertEquals("00:00:00", formatReplayDuration(0L))
        assertEquals("00:00:00", formatReplayDuration(-5_000L))
    }

    @Test
    fun `duration rolls over correctly at exactly one hour`() {
        assertEquals("01:00:00", formatReplayDuration(3_600_000L))
        assertEquals("00:59:59", formatReplayDuration(3_599_000L))
    }

    // --- Distance -----------------------------------------------------------------------------

    @Test
    fun `distance honours the metric preference`() {
        assertEquals("12.3 km", formatReplayDistance(12_345.0, imperial = false))
    }

    /** The defect: the overlay was hardcoded to km regardless of the user's unit setting. */
    @Test
    fun `distance honours the imperial preference`() {
        assertEquals("7.7 mi", formatReplayDistance(12_345.0, imperial = true))
    }

    @Test
    fun `metric and imperial never render the same string for the same ride`() {
        assertNotEquals(
            formatReplayDistance(12_345.0, imperial = false),
            formatReplayDistance(12_345.0, imperial = true)
        )
    }

    @Test
    fun `negative distance is clamped rather than rendering a minus sign`() {
        assertEquals("0.0 km", formatReplayDistance(-1.0, imperial = false))
    }

    // --- Persona ------------------------------------------------------------------------------

    /**
     * The defect: the overlay drew `RidePersona.displayName`, so a shared video read "BikeDrive" /
     * "CarDrive" in every language.
     */
    @Test
    fun `every persona has a localized label in every supported language`() {
        val languages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

        languages.forEach { language ->
            val strings = getAppStrings(language)
            RidePersona.entries.forEach { persona ->
                val label = strings.personaLabel(persona)
                assertTrue(
                    "$language/$persona resolved to a blank label",
                    label.isNotBlank()
                )
                if (language != "en") {
                    assertNotEquals(
                        "$language/$persona still renders the English enum label",
                        persona.displayName,
                        label
                    )
                }
            }
        }
    }

    /** No user-facing string should ever be the raw camel-case enum name. */
    @Test
    fun `english persona labels are human readable, not enum names`() {
        val strings = getAppStrings("en")

        assertEquals("Motorbike", strings.personaLabel(RidePersona.BIKE_DRIVE))
        assertEquals("Car", strings.personaLabel(RidePersona.CAR_DRIVE))
    }

    @Test
    fun `overlay defaults keep existing call sites compiling with english metric output`() {
        val overlay = ReplayOverlay()

        assertEquals(null, overlay.personaLabel)
        assertEquals(false, overlay.imperialUnits)
    }
}
