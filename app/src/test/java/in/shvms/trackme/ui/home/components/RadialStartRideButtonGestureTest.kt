package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadialStartRideButtonGestureTest {
    private val touchSlopPx = 16f

    @Test
    fun `plain tap starts auto`() {
        assertEquals(
            RidePersona.AUTO,
            selectedPersonaForRelease(
                hoveredPersona = null,
                didExceedTouchSlop = false,
                releasedInsideCenter = true
            )
        )
    }

    @Test
    fun `movement at touch slop boundary is still treated as a tap`() {
        val didExceedTouchSlop = hasExceededTouchSlop(
            previouslyExceeded = false,
            distanceFromDownPx = touchSlopPx,
            touchSlopPx = touchSlopPx
        )

        assertEquals(
            RidePersona.AUTO,
            selectedPersonaForRelease(
                hoveredPersona = null,
                didExceedTouchSlop = didExceedTouchSlop,
                releasedInsideCenter = true
            )
        )
    }

    @Test
    fun `movement beyond touch slop remains a drag after returning to center`() {
        val exceededOnDrag = hasExceededTouchSlop(
            previouslyExceeded = false,
            distanceFromDownPx = touchSlopPx + 1f,
            touchSlopPx = touchSlopPx
        )

        assertEquals(
            true,
            hasExceededTouchSlop(
                previouslyExceeded = exceededOnDrag,
                distanceFromDownPx = 0f,
                touchSlopPx = touchSlopPx
            )
        )
    }

    @Test
    fun `drag released outside persona circles cancels`() {
        assertNull(
            selectedPersonaForRelease(
                hoveredPersona = null,
                didExceedTouchSlop = true,
                releasedInsideCenter = false
            )
        )
    }

    @Test
    fun `drag returned to center cancels`() {
        assertNull(
            selectedPersonaForRelease(
                hoveredPersona = null,
                didExceedTouchSlop = true,
                releasedInsideCenter = true
            )
        )
    }

    @Test
    fun `dragged persona selections are preserved`() {
        val selectablePersonas = RidePersona.entries.filterNot { it == RidePersona.AUTO }

        selectablePersonas.forEach { persona ->
            assertEquals(
                persona,
                selectedPersonaForRelease(
                    hoveredPersona = persona,
                    didExceedTouchSlop = true,
                    releasedInsideCenter = false
                )
            )
        }
    }
}
