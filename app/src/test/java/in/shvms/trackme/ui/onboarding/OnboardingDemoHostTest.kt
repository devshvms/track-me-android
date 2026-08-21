package `in`.shvms.trackme.ui.onboarding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDemoHostTest {
    @Test
    fun `four-step progress advances and then completes`() {
        assertEquals(1, nextOnboardingDemoStep(currentStep = 0, stepCount = 4))
        assertEquals(2, nextOnboardingDemoStep(currentStep = 1, stepCount = 4))
        assertEquals(3, nextOnboardingDemoStep(currentStep = 2, stepCount = 4))
        assertNull(nextOnboardingDemoStep(currentStep = 3, stepCount = 4))
    }

    @Test
    fun `scrub requires meaningful travel`() {
        assertFalse(isMeaningfulOnboardingScrub(startIndex = 0, currentIndex = 1, pointCount = 31))
        assertFalse(isMeaningfulOnboardingScrub(startIndex = 0, currentIndex = 6, pointCount = 31))
        assertTrue(isMeaningfulOnboardingScrub(startIndex = 0, currentIndex = 7, pointCount = 31))
    }

    @Test
    fun `guided demos have no service network map or clipboard dependency`() {
        val source = File(
            "src/main/java/in/shvms/trackme/ui/onboarding/OnboardingGuidedDemos.kt"
        ).readText()

        listOf(
            "TrackingService.",
            "LiveShareManager(",
            "GoogleMap(",
            "ClipboardManager",
            "MediaStore",
            "Firestore",
        ).forEach { forbidden ->
            assertFalse("Demo source must not contain $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("RoutePreviewThumbnail("))
        assertTrue(source.contains("ExportPreviewDialog("))
    }
}
