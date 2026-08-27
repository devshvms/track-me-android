package `in`.shvms.trackme.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-226. The objection to double-tap was that it taxes every single tap with a disambiguation
 * timeout. It does not here, and these are the cases that prove the rule stayed cheap and did not
 * start firing on taps a rider did not mean as a pair.
 */
class TabDoubleTapDetectorTest {

    private fun detector() = TabDoubleTapDetector(windowMillis = 300L)

    @Test fun `the first tap is never a double-tap`() {
        assertFalse(detector().tap("history", 1_000L).isDoubleTap)
    }

    @Test fun `two taps on the same tab inside the window are a double-tap`() {
        val first = detector().tap("history", 1_000L)
        assertTrue(first.detector.tap("history", 1_200L).isDoubleTap)
    }

    @Test fun `two taps just outside the window are two single taps`() {
        val first = detector().tap("history", 1_000L)
        assertFalse(first.detector.tap("history", 1_301L).isDoubleTap)
    }

    @Test fun `the boundary counts as a double-tap`() {
        val first = detector().tap("history", 1_000L)
        assertTrue(first.detector.tap("history", 1_300L).isDoubleTap)
    }

    @Test fun `a fast tap on a different tab is not a double-tap`() {
        // Moving quickly between two tabs is ordinary use and must not pop either one.
        val first = detector().tap("history", 1_000L)
        assertFalse(first.detector.tap("community", 1_050L).isDoubleTap)
    }

    @Test fun `three fast taps are one double-tap and one single, not two`() {
        val first = detector().tap("home", 1_000L)
        val second = first.detector.tap("home", 1_100L)
        assertTrue(second.isDoubleTap)
        assertFalse("the pair was consumed", second.detector.tap("home", 1_200L).isDoubleTap)
    }

    @Test fun `a fourth tap can start a new pair`() {
        val d = detector().tap("home", 1_000L).detector
            .tap("home", 1_100L).detector
            .tap("home", 1_200L).detector
        assertTrue(d.tap("home", 1_300L).isDoubleTap)
    }
}
