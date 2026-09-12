package `in`.shvms.trackme.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** SCOPE_1.8.9 — the templates split the distance from its unit; the split must rejoin exactly. */
class UnitFormatterTemplateParityTest {

    @Test
    fun `the hero number and its unit are the ride distance, taken apart`() {
        listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("hi-IN")).forEach { locale ->
            listOf(0.0, 12_437.0, 41_700.0, 999.9).forEach { meters ->
                listOf(false, true).forEach { imperial ->
                    assertEquals(
                        UnitFormatter.rideDistance(meters, imperial, locale),
                        "${UnitFormatter.rideDistanceValue(meters, imperial, locale)} ${UnitFormatter.distanceUnitLabel(imperial)}",
                    )
                }
            }
        }
    }

    @Test
    fun `elevation reads as the ride detail screen always printed it`() {
        assertEquals("312 m", UnitFormatter.elevation(312.4, imperial = false, locale = Locale.US))
        assertEquals("1025 ft", UnitFormatter.elevation(312.4, imperial = true, locale = Locale.US))
    }
}
