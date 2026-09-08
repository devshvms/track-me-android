package `in`.shvms.trackme.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a.
 *
 * The tests that matter here are the ones asserting that nothing schedules itself. 12a is exempt
 * from the Class C budget because the user chose the cadence; if a reminder can ever exist without
 * a user action, that exemption becomes a way for the app to notify weekly outside the budget it
 * was written to obey.
 */
class ActivityReminderTest {

    @Test
    fun `a reminder is off until someone turns it on`() {
        assertFalse(ActivityReminder.Settings().enabled)
    }

    @Test
    fun `prefilling from a suggestion still does not enable it`() {
        val suggestion = RideHistoryProfile.SuggestedSlot(
            dayOfWeek = 6, hour = 8, persona = "RUN", rideCount = 12,
        )
        val prefilled = ActivityReminder.prefill(suggestion)

        assertFalse(
            "a suggestion that arrives already enabled is scenario 12, which was cut",
            prefilled.enabled,
        )
        assertEquals(6, prefilled.dayOfWeek)
        assertEquals(8, prefilled.hour)
        assertEquals("RUN", prefilled.persona)
    }

    @Test
    fun `prefilling with no suggestion yields the neutral default`() {
        val prefilled = ActivityReminder.prefill(null)
        assertFalse(prefilled.enabled)
        assertEquals(ActivityReminder.Settings.DEFAULT_DAY, prefilled.dayOfWeek)
        assertEquals(ActivityReminder.Settings.DEFAULT_HOUR, prefilled.hour)
    }

    @Test
    fun `a disabled reminder never fires`() {
        assertFalse(
            ActivityReminder.shouldFire(
                settings = ActivityReminder.Settings(enabled = false, dayOfWeek = 6),
                nowDayOfWeek = 6,
                nowEpochDay = 100,
                lastFiredEpochDay = null,
            )
        )
    }

    @Test
    fun `an enabled reminder fires on its day`() {
        assertTrue(
            ActivityReminder.shouldFire(
                settings = ActivityReminder.Settings(enabled = true, dayOfWeek = 6),
                nowDayOfWeek = 6,
                nowEpochDay = 100,
                lastFiredEpochDay = null,
            )
        )
    }

    @Test
    fun `it does not fire on any other day`() {
        (1..7).filter { it != 6 }.forEach { day ->
            assertFalse(
                "fired on day $day for a Saturday reminder",
                ActivityReminder.shouldFire(
                    settings = ActivityReminder.Settings(enabled = true, dayOfWeek = 6),
                    nowDayOfWeek = day,
                    nowEpochDay = 100,
                    lastFiredEpochDay = null,
                )
            )
        }
    }

    /**
     * A device rebooted inside the firing window re-registers its work and runs it immediately.
     * Arriving twice on the same morning reads as a bug in a way that a missed one does not.
     */
    @Test
    fun `it fires once per day even when the scheduler wakes twice`() {
        val settings = ActivityReminder.Settings(enabled = true, dayOfWeek = 6)
        assertTrue(
            ActivityReminder.shouldFire(settings, nowDayOfWeek = 6, nowEpochDay = 100, lastFiredEpochDay = null)
        )
        assertFalse(
            "a second wake on the same day must not notify again",
            ActivityReminder.shouldFire(settings, nowDayOfWeek = 6, nowEpochDay = 100, lastFiredEpochDay = 100)
        )
    }

    @Test
    fun `it fires again the following week`() {
        assertTrue(
            ActivityReminder.shouldFire(
                settings = ActivityReminder.Settings(enabled = true, dayOfWeek = 6),
                nowDayOfWeek = 6,
                nowEpochDay = 107,
                lastFiredEpochDay = 100,
            )
        )
    }

    /**
     * A restore from backup, or a timezone edit, can leave a last-fired stamp in the future.
     * Treating that as "not yet fired" would emit a reminder on every scheduler wake until real
     * time caught up — the same failure the proactive budget guards against.
     */
    @Test
    fun `a last-fired stamp in the future suppresses rather than repeats`() {
        assertFalse(
            ActivityReminder.shouldFire(
                settings = ActivityReminder.Settings(enabled = true, dayOfWeek = 6),
                nowDayOfWeek = 6,
                nowEpochDay = 100,
                lastFiredEpochDay = 500,
            )
        )
    }

    @Test
    fun `an invalid slot never fires`() {
        listOf(
            ActivityReminder.Settings(enabled = true, dayOfWeek = 0),
            ActivityReminder.Settings(enabled = true, dayOfWeek = 8),
            ActivityReminder.Settings(enabled = true, dayOfWeek = 6, hour = 24),
            ActivityReminder.Settings(enabled = true, dayOfWeek = 6, minute = 60),
        ).forEach { settings ->
            assertFalse(
                "invalid settings fired: $settings",
                ActivityReminder.shouldFire(settings, nowDayOfWeek = 6, nowEpochDay = 100, lastFiredEpochDay = null)
            )
        }
    }
}
