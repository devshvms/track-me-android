package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E9 — the replay-export button moved into the export preview and was redesigned so its progress is
 * painted inside its own bounds. These cover the state machine behind that button; the risk surface
 * is "can this button ever be tappable when it shouldn't be, or report a progress value that a
 * layout could act on", not pixel rendering.
 */
class ReplayExportButtonStateTest {

    @Test
    fun `idle ride with enough points is enabled and shows no fill`() {
        val state = replayExportButtonState(exporting = false, progress = 0f, hasEnoughPoints = true)

        assertTrue(state.enabled)
        assertFalse(state.isExporting)
        assertEquals(ReplayExportLabel.IDLE, state.label)
        assertEquals(0f, state.fillFraction, 0.0001f)
    }

    @Test
    fun `ride without enough gps points is disabled and reports the unavailable label`() {
        val state = replayExportButtonState(exporting = false, progress = 0f, hasEnoughPoints = false)

        assertFalse(state.enabled)
        assertEquals(ReplayExportLabel.UNAVAILABLE, state.label)
    }

    @Test
    fun `export in progress stays enabled so the tap can cancel it`() {
        val state = replayExportButtonState(exporting = true, progress = 0.42f, hasEnoughPoints = true)

        assertTrue(state.enabled)
        assertTrue(state.isExporting)
        assertEquals(0.42f, state.fillFraction, 0.0001f)
        assertEquals(42, state.percent)
    }

    @Test
    fun `progress is clamped so a renderer can never be asked for an out-of-bounds fill`() {
        val over = replayExportButtonState(exporting = true, progress = 1.4f, hasEnoughPoints = true)
        val under = replayExportButtonState(exporting = true, progress = -0.3f, hasEnoughPoints = true)

        assertEquals(1f, over.fillFraction, 0.0001f)
        assertEquals(100, over.percent)
        assertEquals(0f, under.fillFraction, 0.0001f)
        assertEquals(0, under.percent)
    }

    @Test
    fun `percent is rounded rather than truncated`() {
        assertEquals(
            67,
            replayExportButtonState(exporting = true, progress = 0.666f, hasEnoughPoints = true).percent
        )
    }

    @Test
    fun `an in-flight export remains cancellable even if the point count check would fail`() {
        // Defensive: `exporting` is the authority once a job is running. The pre-E9 button used
        // `!exporting || hasEnoughPoints`, which disabled the control — and therefore cancellation —
        // in exactly this state.
        val state = replayExportButtonState(exporting = true, progress = 0.1f, hasEnoughPoints = false)

        assertTrue(state.enabled)
        assertTrue(state.isExporting)
    }
}
