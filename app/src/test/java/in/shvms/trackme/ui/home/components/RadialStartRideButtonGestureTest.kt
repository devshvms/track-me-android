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
    fun `touch inside center aborts current launch and consumes gesture`() {
        val launch = PendingRideLaunch(token = 7L, persona = RidePersona.RUN)

        val decision = pendingLaunchAbortDecision(
            pendingLaunch = launch,
            observedLaunchToken = launch.token,
            pressedInsideCenter = true
        )

        assertTrue(decision.shouldAbort)
        assertTrue(decision.consumeGesture)
    }

    @Test
    fun `touch after launch window is a no-op and is not consumed`() {
        val decision = pendingLaunchAbortDecision(
            pendingLaunch = null,
            observedLaunchToken = 7L,
            pressedInsideCenter = true
        )

        assertFalse(decision.shouldAbort)
        assertFalse(decision.consumeGesture)
    }

    @Test
    fun `stale touch cannot abort a newer launch`() {
        val decision = pendingLaunchAbortDecision(
            pendingLaunch = PendingRideLaunch(token = 8L, persona = RidePersona.RUN),
            observedLaunchToken = 7L,
            pressedInsideCenter = true
        )

        assertFalse(decision.shouldAbort)
        assertFalse(decision.consumeGesture)
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
        assertFalse(reset.isAbortGestureActive)
    }
}
