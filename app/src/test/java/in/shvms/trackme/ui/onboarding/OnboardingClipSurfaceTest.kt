package `in`.shvms.trackme.ui.onboarding

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the surface `OnboardingClip` renders video into.
 *
 * 1.8.2 shipped `setBackgroundColor(Color.TRANSPARENT)` on the `TextureView`. That call is not
 * merely ineffective — `TextureView.setBackgroundDrawable` throws `UnsupportedOperationException`
 * unconditionally, and every `setBackground*` overload routes into it. The crash fired the instant
 * Compose constructed the view, so onboarding killed the app on page 0 and no first-run user could
 * get past the first screen.
 *
 * A JVM test cannot instantiate a real `TextureView`, and an instrumentation test would not have
 * caught it either without a device that actually reached onboarding — which is precisely what was
 * missing. So this reads the source, in the same spirit as `PlayReleaseNotesTest` reading
 * `build.gradle.kts`. It is a cheap, deterministic check for a defect whose entire cost was that
 * nothing cheap was checking.
 */
class OnboardingClipSurfaceTest {

    private val source: String by lazy {
        resolve("app/src/main/java/in/shvms/trackme/ui/onboarding/OnboardingClip.kt").readText()
    }

    @Test
    fun `the video surface never sets a background`() {
        val offenders = Regex("""setBackground\w*\s*\(""").findAll(source).map { it.value }.toList()
        assertTrue(
            "OnboardingClip.kt calls ${offenders.joinToString()} — TextureView throws " +
                "UnsupportedOperationException from every setBackground* overload. Use " +
                "`isOpaque = false` for transparency instead.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `transparency still comes from isOpaque`() {
        // If someone removes this while removing a background call, the clip goes opaque and
        // silently boxes the video against the page — the thing the background call was reaching for.
        assertTrue(
            "OnboardingClip.kt should set `isOpaque = false` on the TextureView",
            source.contains("isOpaque = false"),
        )
    }

    @Test
    fun `the unused graphics Color import does not come back with it`() {
        assertFalse(
            "android.graphics.Color is only ever needed here for the forbidden background call",
            source.contains("import android.graphics.Color"),
        )
    }

    private fun resolve(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${File("").absolutePath}")
    }
}
