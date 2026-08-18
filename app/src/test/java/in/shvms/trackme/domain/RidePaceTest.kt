package `in`.shvms.trackme.domain

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.model.usesPace
import `in`.shvms.trackme.ui.history.effortValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pace is the metric on foot, and it had two separate holes: ride detail showed speed for
 * everything, and the tracking HUD checked for WALK only — so running, the persona that cares most
 * about pace, showed km/h on both screens.
 */
class RidePaceTest {

    @Test
    fun onlyFootPersonasUsePace() {
        assertTrue(RidePersona.WALK.usesPace)
        assertTrue("running is the persona that cares most about pace", RidePersona.RUN.usesPace)
        assertTrue(!RidePersona.CYCLING.usesPace)
        assertTrue(!RidePersona.BIKE_DRIVE.usesPace)
        assertTrue(!RidePersona.CAR_DRIVE.usesPace)
        // AUTO covers whatever the classifier has not decided; km/h stays sensible across all of it.
        assertTrue(!RidePersona.AUTO.usesPace)
    }

    @Test
    fun paceIsMinutesAndSecondsPerUnit() {
        // 10 km/h = 2.7778 m/s = 6:00 per km. A brisk run, and an easy number to check by hand.
        assertEquals("6:00 /km", UnitFormatter.pace(10.0 / 3.6, imperial = false, locale = Locale.US))
        // 5 km/h = a walk, 12:00 per km.
        assertEquals("12:00 /km", UnitFormatter.pace(5.0 / 3.6, imperial = false, locale = Locale.US))
    }

    @Test
    fun imperialPaceIsPerMile() {
        val perMile = UnitFormatter.pace(10.0 / 3.6, imperial = true, locale = Locale.US)
        assertTrue("expected a per-mile label, got $perMile", perMile.endsWith("/mi"))
        // A mile is 1.609 km, so the same speed must give a proportionally longer pace.
        assertEquals("9:39 /mi", perMile)
    }

    @Test
    fun stoppedSamplesDoNotPrintInfinity() {
        // The failure this guards: seconds-per-km is 1/speed, so a stopped sample runs away.
        for (stopped in listOf(0.0, 0.05, -1.0, Double.NaN)) {
            val text = UnitFormatter.pace(stopped, imperial = false, locale = Locale.US)
            assertTrue("$stopped produced '$text'", text.startsWith("--:--"))
        }
    }

    @Test
    fun absurdlySlowSamplesAreAlsoGuarded() {
        // A barely-shuffling sample is arithmetically valid and still not information.
        val text = UnitFormatter.pace(0.2, imperial = false, locale = Locale.US)
        assertTrue("expected a placeholder, got '$text'", text.startsWith("--:--"))
    }

    @Test
    fun chartValueIsSpeedOnWheelsAndPaceOnFoot() {
        val mps = 10f / 3.6f
        assertEquals(10f, effortValue(mps, usesPace = false, imperial = false), 0.01f)
        assertEquals(6f, effortValue(mps, usesPace = true, imperial = false), 0.01f)
    }

    @Test
    fun chartPaceIsClampedSoOneStopDoesNotFlattenTheRide() {
        // Without the clamp a single stopped sample plots at infinity, and every other point in
        // the ride collapses onto the bottom edge of the chart.
        val stopped = effortValue(0f, usesPace = true, imperial = false)
        assertEquals(UnitFormatter.PACE_MAX_MINUTES.toFloat(), stopped, 0.01f)
        assertTrue(stopped.isFinite())
    }

    @Test
    fun fasterMeansALowerPaceValue() {
        // Speed and pace are mirror images, which is what makes a pace chart dip when you speed
        // up. Recorded here so nobody "fixes" the inversion later.
        val slow = effortValue(2f, usesPace = true, imperial = false)
        val fast = effortValue(4f, usesPace = true, imperial = false)
        assertTrue("faster must plot lower on a pace chart", fast < slow)
    }
}
