package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `plain tap honors onboarding persona preselection`() {
        assertEquals(
            RidePersona.CYCLING,
            selectedPersonaForRelease(
                hoveredPersona = null,
                didExceedTouchSlop = false,
                releasedInsideCenter = true,
                centerPersona = RidePersona.CYCLING,
            ),
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

    @Test
    fun `tap launch waits for an explicit persona choice`() {
        val launch = PendingRideLaunch(
            token = 7L,
            persona = RidePersona.RUN,
            awaitsPersonaChoice = true,
        )

        assertTrue(launch.awaitsPersonaChoice)
        assertTrue(canCommitPendingLaunch(launch, expectedLaunchToken = launch.token))
    }

    @Test
    fun `direct persona launch remains identity guarded`() {
        val launch = PendingRideLaunch(token = 8L, persona = RidePersona.RUN)

        assertFalse(launch.awaitsPersonaChoice)
        assertTrue(canCommitPendingLaunch(launch, expectedLaunchToken = launch.token))
        assertFalse(canCommitPendingLaunch(launch, expectedLaunchToken = 7L))
    }

    @Test
    fun `pending launch commits only while its identity is current`() {
        val launch = PendingRideLaunch(token = 7L, persona = RidePersona.RUN)

        assertTrue(canCommitPendingLaunch(launch, expectedLaunchToken = 7L))
        assertFalse(canCommitPendingLaunch(launch, expectedLaunchToken = 8L))
        assertFalse(canCommitPendingLaunch(null, expectedLaunchToken = 7L))
    }

    @Test
    fun `abort reset clears every interaction field`() {
        val reset = resetRadialInteractionState()

        assertFalse(reset.isPressed)
        assertNull(reset.hoveredPersona)
        assertNull(reset.lastVibratedPersona)
        assertFalse(reset.didExceedTouchSlop)
        assertNull(reset.pendingLaunch)
    }
}
