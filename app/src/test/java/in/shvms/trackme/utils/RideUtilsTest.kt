package `in`.shvms.trackme.utils

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RideUtilsTest {
    private val afternoonStart = Calendar.getInstance(TimeZone.getDefault()).apply {
        set(2026, Calendar.JULY, 18, 14, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `manual persona determines generated title`() {
        assertEquals(
            "Afternoon BikeDrive",
            RideUtils.getDefaultTitle(afternoonStart, RidePersona.BIKE_DRIVE)
        )
    }

    @Test
    fun `legacy generated title remains eligible for persona correction`() {
        assertTrue(
            RideUtils.isGeneratedTitle(
                RideUtils.getDefaultTitle(afternoonStart),
                afternoonStart,
                RidePersona.BIKE_DRIVE
            )
        )
    }
}
