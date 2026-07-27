package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadialStartRideButtonGestureTest {
    private val touchSlopPx = 16f

    @Test
    fun `plain tap does not select a persona`() {
        assertNull(selectedPersonaForRelease(null, dragDistancePx = 0f, touchSlopPx))
    }

    @Test
    fun `movement at touch slop boundary is still treated as a tap`() {
        assertNull(selectedPersonaForRelease(null, dragDistancePx = touchSlopPx, touchSlopPx))
    }

    @Test
    fun `drag released outside persona circles keeps auto fallback`() {
        assertEquals(
            RidePersona.AUTO,
            selectedPersonaForRelease(null, dragDistancePx = touchSlopPx + 1f, touchSlopPx)
        )
    }

    @Test
    fun `dragged persona selection is preserved`() {
        assertEquals(
            RidePersona.CYCLING,
            selectedPersonaForRelease(RidePersona.CYCLING, touchSlopPx + 1f, touchSlopPx)
        )
    }
}
