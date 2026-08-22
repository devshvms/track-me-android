package `in`.shvms.trackme.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingClipPolicyTest {
    @Test
    fun `reduced motion and decode failures both force vector fallback`() {
        assertTrue(shouldRenderOnboardingClip(reduceMotion = false, playerFailed = false))
        assertFalse(shouldRenderOnboardingClip(reduceMotion = true, playerFailed = false))
        assertFalse(shouldRenderOnboardingClip(reduceMotion = false, playerFailed = true))
    }
}
