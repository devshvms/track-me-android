package `in`.shvms.trackme.domain.export.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * SCOPE_1.8.9 §6.5 — The Hour's palette follows the sun, so the sun's real behaviour on real dates
 * is the test. Bengaluru on the June solstice: sunrise about 05:53 IST, sunset about 18:47 IST.
 * Every case sits at least 1.5° inside its bucket, far more than the calculation's error.
 */
class SolarPhaseTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val bengaluru = 12.97 to 77.59

    private fun ist(month: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, month, 21, hour, minute, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    private fun phaseIst(hour: Int, minute: Int): LightPhase =
        SolarPhase.phase(bengaluru.first, bengaluru.second, ist(6, hour, minute))

    @Test
    fun `the sun is overhead at noon on the equinox at the equator`() {
        assertTrue(SolarPhase.elevationDegrees(0.0, 0.0, utc(2026, 3, 20, 12, 7)) > 85.0)
    }

    @Test
    fun `midnight in a London winter is night`() {
        assertEquals(LightPhase.NIGHT, SolarPhase.phase(51.5, -0.12, utc(2026, 12, 21, 0, 0)))
    }

    @Test
    fun `a Bengaluru day runs dawn, golden, day, golden, dusk, night`() {
        assertEquals(LightPhase.NIGHT, phaseIst(4, 30))
        assertEquals(LightPhase.DAWN, phaseIst(5, 40))
        assertEquals(LightPhase.GOLDEN_MORNING, phaseIst(6, 15))
        assertEquals(LightPhase.DAY, phaseIst(12, 0))
        assertEquals(LightPhase.GOLDEN_EVENING, phaseIst(18, 20))
        assertEquals(LightPhase.DUSK, phaseIst(19, 0))
        assertEquals(LightPhase.NIGHT, phaseIst(20, 30))
    }

    @Test
    fun `the same clock time is dark in winter and light in summer`() {
        val delhi = 28.61 to 77.21
        // 05:50 IST: −16.9° in December, +4.4° in June (NOAA and Meeus agree within 0.1°).
        val winter = SolarPhase.phase(delhi.first, delhi.second, ist(12, 5, 50))
        val summer = SolarPhase.phase(delhi.first, delhi.second, ist(6, 5, 50))
        assertEquals(LightPhase.NIGHT, winter)
        assertEquals(LightPhase.GOLDEN_MORNING, summer)
    }
}
