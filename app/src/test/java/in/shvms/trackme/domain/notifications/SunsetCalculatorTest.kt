package `in`.shvms.trackme.domain.notifications

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * SCOPE_1.8.7 §6.1.6 scenario 28 — sunset, computed on device.
 *
 * Asserted against **real sunsets for real places on real dates**, not against the implementation's
 * own output. A solar algorithm that is subtly wrong still produces plausible-looking times all
 * year round, so the only test worth having is one that could have been written before the code.
 *
 * Published times are used as the reference with a few minutes of tolerance, which is far tighter
 * than the surface needs — it says "in about 40 minutes" — but loose enough not to fail on the
 * difference between civil and true sunset definitions.
 */
class SunsetCalculatorTest {

    private fun assertSunsetNear(
        description: String,
        latitude: Double,
        longitude: Double,
        dayOfYear: Int,
        utcOffsetMinutes: Int,
        expectedHour: Int,
        expectedMinute: Int,
        toleranceMinutes: Int = 6,
    ) {
        val actual = SunsetCalculator.sunsetMinutesAfterMidnight(
            latitude, longitude, dayOfYear, utcOffsetMinutes
        )
        assertNotNull("$description: the sun does set here", actual)
        val expected = expectedHour * 60 + expectedMinute
        assertTrue(
            "$description: expected ~${expectedHour}:${"%02d".format(expectedMinute)}, " +
                "got ${actual!! / 60}:${"%02d".format(actual % 60)}",
            abs(actual - expected) <= toleranceMinutes,
        )
    }

    @Test
    fun `Bengaluru in September`() {
        // 12.97N 77.59E, IST (+330). Sunset around 18:26 in early September.
        assertSunsetNear("Bengaluru, 7 Sep", 12.9716, 77.5946, dayOfYear = 250, utcOffsetMinutes = 330, 18, 26)
    }

    @Test
    fun `London at midsummer and midwinter`() {
        // The widest spread any populated place gives, and the case that catches an algorithm that
        // has flattened the seasonal variation.
        assertSunsetNear("London, 21 Jun (BST)", 51.5074, -0.1278, dayOfYear = 172, utcOffsetMinutes = 60, 21, 21)
        assertSunsetNear("London, 21 Dec (GMT)", 51.5074, -0.1278, dayOfYear = 355, utcOffsetMinutes = 0, 15, 53)
    }

    @Test
    fun `the southern hemisphere runs the other way`() {
        // Sydney: December is midsummer. An algorithm with a sign error in the declination gets
        // every northern city right and every southern one exactly backwards.
        assertSunsetNear("Sydney, 21 Dec", -33.8688, 151.2093, dayOfYear = 355, utcOffsetMinutes = 660, 20, 5)
        assertSunsetNear("Sydney, 21 Jun", -33.8688, 151.2093, dayOfYear = 172, utcOffsetMinutes = 600, 16, 53)
    }

    @Test
    fun `near the equator the year is almost flat`() {
        // Singapore's sunset moves by under half an hour across the whole year. A calculator that
        // over-swings the season passes the London case and fails this one.
        val june = SunsetCalculator.sunsetMinutesAfterMidnight(1.3521, 103.8198, 172, 480)!!
        val december = SunsetCalculator.sunsetMinutesAfterMidnight(1.3521, 103.8198, 355, 480)!!
        assertTrue(
            "Singapore should vary by well under an hour, varied by ${abs(june - december)} minutes",
            abs(june - december) < 60,
        )
    }

    @Test
    fun `above the Arctic circle in June the sun does not set`() {
        // Null is a real answer, not a failure. A caller that treats it as "unknown" and shows
        // nothing is behaving correctly for someone in Tromsø in midsummer.
        assertNull(SunsetCalculator.sunsetMinutesAfterMidnight(78.2232, 15.6267, dayOfYear = 172, utcOffsetMinutes = 120))
    }

    @Test
    fun `nonsense input is refused rather than answered`() {
        assertNull(SunsetCalculator.sunsetMinutesAfterMidnight(91.0, 0.0, 100, 0))
        assertNull(SunsetCalculator.sunsetMinutesAfterMidnight(0.0, 181.0, 100, 0))
        assertNull(SunsetCalculator.sunsetMinutesAfterMidnight(0.0, 0.0, 0, 0))
        assertNull(SunsetCalculator.sunsetMinutesAfterMidnight(0.0, 0.0, 367, 0))
    }

    @Test
    fun `every answer is a real time of day`() {
        // Wrapping rather than clamping: a sunset can land on the adjacent calendar day in local
        // time near a date line or a large offset, and clamping would report midnight.
        listOf(-720, -330, 0, 330, 780).forEach { offset ->
            (1..366 step 29).forEach { day ->
                val minutes = SunsetCalculator.sunsetMinutesAfterMidnight(35.0, 139.0, day, offset)
                if (minutes != null) {
                    assertTrue("offset=$offset day=$day gave $minutes", minutes in 0..1439)
                }
            }
        }
    }

    // --- what the app actually asks -------------------------------------------------------------

    @Test
    fun `sunset already past says nothing`() {
        // 20:00 in Bengaluru, well after sunset. "Sunset was two hours ago" is not a fact anyone
        // setting off needs.
        assertNull(
            SunsetCalculator.minutesUntilSunset(
                12.9716, 77.5946, dayOfYear = 250,
                minutesAfterLocalMidnightNow = 20 * 60, utcOffsetMinutes = 330,
            )
        )
    }

    @Test
    fun `a distant sunset says nothing either`() {
        // Ten in the morning. The fact is true and saying it is the app filling silence.
        assertNull(
            SunsetCalculator.minutesUntilSunset(
                12.9716, 77.5946, dayOfYear = 250,
                minutesAfterLocalMidnightNow = 10 * 60, utcOffsetMinutes = 330,
            )
        )
    }

    @Test
    fun `a sunset within the window is reported with the minutes remaining`() {
        // 17:45, sunset around 18:26 — the exact case §6.1.6 describes.
        val remaining = SunsetCalculator.minutesUntilSunset(
            12.9716, 77.5946, dayOfYear = 250,
            minutesAfterLocalMidnightNow = 17 * 60 + 45, utcOffsetMinutes = 330,
        )
        assertNotNull(remaining)
        assertTrue("expected roughly 40 minutes, got $remaining", remaining!! in 30..50)
    }

    @Test
    fun `the boundary of the window is honoured exactly`() {
        val sunset = SunsetCalculator.sunsetMinutesAfterMidnight(12.9716, 77.5946, 250, 330)!!
        val justInside = sunset - SunsetCalculator.MAX_MINUTES_WORTH_MENTIONING
        assertNotNull(
            SunsetCalculator.minutesUntilSunset(12.9716, 77.5946, 250, justInside, 330)
        )
        assertNull(
            SunsetCalculator.minutesUntilSunset(12.9716, 77.5946, 250, justInside - 1, 330)
        )
    }
}
