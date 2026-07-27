package `in`.shvms.trackme

import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.replay.formatReplayDistance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class UnitFormatterTest {
    private lateinit var originalLocale: Locale

    // These assertions encode a decimal-point locale ("1.00 km"). Pin it so the suite does not
    // depend on the machine or emulator it happens to run on.
    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test fun metricDistance() = assertEquals("1.00 km", UnitFormatter.distance(1000.0, false))
    @Test fun imperialDistance() = assertEquals("1.00 mi", UnitFormatter.distance(1609.344, true))
    @Test fun marathonImperialDistance() = assertEquals("26.22 mi", UnitFormatter.distance(42195.0, true))
    @Test fun zeroDistancePreservesUnit() {
        assertEquals("0.00 km", UnitFormatter.distance(0.0, false))
        assertEquals("0.00 mi", UnitFormatter.distance(0.0, true))
    }
    @Test fun metricSpeed() = assertEquals("36.0 km/h", UnitFormatter.speed(10.0, false))
    @Test fun imperialSpeed() = assertEquals("22.4 mph", UnitFormatter.speed(10.0, true))

    // --- Ride-summary distance (TASK-109) ---------------------------------------------------
    // The ride detail screen used the `decimals = 2` default while the replay MP4 it launches
    // rendered `decimals = 1`, so the same ride read "12.35 km" on screen and "12.3 km" in the
    // file the user shared. `rideDistance` is the single canonical spelling for both.

    @Test fun rideDistanceUsesOneDecimal() {
        assertEquals("12.3 km", UnitFormatter.rideDistance(12345.0, false))
        assertEquals("7.7 mi", UnitFormatter.rideDistance(12345.0, true))
    }

    @Test fun rideDistanceZeroPreservesUnit() {
        assertEquals("0.0 km", UnitFormatter.rideDistance(0.0, false))
        assertEquals("0.0 mi", UnitFormatter.rideDistance(0.0, true))
    }

    @Test fun rideDistanceDropsTheSecondDecimalRatherThanTruncating() {
        // The second decimal is rounded away, not chopped. Exact `x.y5` midpoints are deliberately
        // not asserted: they are IEEE-754 representation trivia (12350.0/1000.0 is 12.34999…, so it
        // rounds down), and pinning that behaviour would test the JDK rather than the product.
        assertEquals("12.4 km", UnitFormatter.rideDistance(12360.0, false))
        assertEquals("12.3 km", UnitFormatter.rideDistance(12340.0, false))
    }

    @Test fun rideDistanceIsLocaleAware() {
        assertEquals("12,3 km", UnitFormatter.rideDistance(12345.0, false, Locale.GERMANY))
    }

    @Test fun rideDistanceMatchesTheDecimalsArgumentItReplaces() {
        listOf(0.0, 950.0, 12345.0, 42195.0).forEach { meters ->
            listOf(false, true).forEach { imperial ->
                assertEquals(
                    UnitFormatter.distance(meters, imperial, decimals = 1, locale = Locale.US),
                    UnitFormatter.rideDistance(meters, imperial, Locale.US)
                )
            }
        }
    }

    // The regression this task exists to prevent: the number on the ride detail screen and the
    // number burned into the MP4 shared from that screen must be the same string.
    @Test fun rideDistanceMatchesTheBurnedInReplayOverlay() {
        listOf(0.0, 950.0, 12345.0, 12350.0, 42195.0).forEach { meters ->
            listOf(false, true).forEach { imperial ->
                assertEquals(
                    formatReplayDistance(meters, imperial),
                    UnitFormatter.rideDistance(meters, imperial)
                )
            }
        }
    }
}
