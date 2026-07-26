package `in`.shvms.trackme.domain.replay

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayExportConfigTest {
    @Test
    fun `defaults match the shared 9 by 16 contract`() {
        val config = ReplayExportConfig(persona = RidePersona.CYCLING)

        assertEquals(1080, config.width)
        assertEquals(1920, config.height)
        assertEquals(30, config.fps)
        assertEquals(20, config.targetDurationSeconds)
        assertTrue(config.applyPrivacyTrim)
        assertEquals(200.0, config.privacyTrimDistanceMeters, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duration outside fifteen to thirty seconds is rejected`() {
        ReplayExportConfig(persona = RidePersona.RUN, targetDurationSeconds = 31)
    }
}
