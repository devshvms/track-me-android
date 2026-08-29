package `in`.shvms.trackme.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Motion goes through the token scheme, not through hand-picked durations.
 *
 * A type cannot express this — `tween(220)` is a perfectly ordinary call, and every animated
 * component has a plausible reason to write one. So the rule is enforced by reading the source,
 * which is also the only thing that catches the *next* component someone adds.
 *
 * Two exemptions are real and are listed here rather than waived silently:
 *
 * 1. **`infiniteRepeatable`** requires a `DurationBasedAnimationSpec`. A spring has no duration,
 *    so it cannot be repeated — this is a type-level constraint, not a preference.
 * 2. **Duration-coupled pairs.** Two properties of one object that must resolve at the same
 *    instant cannot be springs: settle time depends on distance travelled, and two properties
 *    travelling different distances will not finish together at any stiffness. The launch pulse
 *    in `RadialStartRideButton` is the only such pair in the app.
 */
class MotionTokenAdoptionTest {

    /**
     * Files still permitted to call `tween`, and why. Adding to this list is the deliberate act
     * the test exists to force; converting the call site is usually the better answer.
     */
    private val exempt = mapOf(
        "ui/components/AchievementBadge.kt" to "infiniteRepeatable: glow pulse and shine sweep",
        "ui/home/components/InteractiveShareLocationButton.kt" to "infiniteRepeatable: STARTING blink",
        "ui/home/components/RadialStartRideButton.kt" to "duration-coupled launch pulse (scale + alpha)",
        "ui/home/components/ActiveRideHudPanel.kt" to
            "stop-slide is one timed sequence with the acknowledgement delay and the commit; a " +
            "spring settles as a function of screen width, so the ride would stop at a different " +
            "moment on a tablet than on a phone",
        "ui/home/HomeScreen.kt" to
            "HOME_DASHBOARD_MOTION is a cross-platform timed sequence: deck, scrim, controls and " +
            "opacity must resolve at the specified 420/300 ms boundaries",
    )

    private val tweenCall = Regex("""(^|[^A-Za-z])tween\s*\(""")

    @Test
    fun `only the documented exemptions still call tween`() {
        val offenders = uiSources()
            .filter { tweenCall.containsMatchIn(stripComments(it.readText())) }
            .map { it.relativeToUiRoot() }
            .filterNot { it in exempt }
            .sorted()

        assertEquals(
            "These call tween() directly. Use LocalTrackMeMotion — spatial* for things that move, " +
                "effects* for colour and opacity, spatialBounded for movement that is clamped. " +
                "If the call genuinely cannot be a spring, add it to `exempt` with the reason.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every exemption is still needed`() {
        // An exemption that no longer has a tween behind it is stale permission, and stale
        // permission is how a list like this stops meaning anything.
        for (path in exempt.keys) {
            assertTrue(
                "$path is exempt but no longer calls tween — remove it from `exempt`",
                tweenCall.containsMatchIn(stripComments(sourceFile(path).readText())),
            )
        }
    }

    @Test
    fun `effects and bounded tokens never overshoot`() {
        // The invariant the scheme exists to hold. Damping below 1 overshoots, and a value that
        // overshoots past a bound clips — a flash for alpha, a cut-off edge for a clamped offset.
        for (scheme in listOf(TrackMeMotionScheme.Standard, TrackMeMotionScheme.Expressive)) {
            for (token in listOf(
                scheme.effectsFast,
                scheme.effectsDefault,
                scheme.effectsSlow,
                scheme.spatialBounded,
            )) {
                assertTrue(
                    "damping ${token.dampingRatio} overshoots; must be >= 1.0",
                    token.dampingRatio >= 1.0f,
                )
            }
        }
    }

    @Test
    fun `spatial tokens are allowed to overshoot`() {
        // The other half: if these were critically damped too, the scheme would not be expressing
        // anything and the split would be decoration.
        for (scheme in listOf(TrackMeMotionScheme.Standard, TrackMeMotionScheme.Expressive)) {
            for (token in listOf(scheme.spatialFast, scheme.spatialDefault, scheme.spatialSlow)) {
                assertTrue(
                    "damping ${token.dampingRatio} is critically damped; spatial motion should settle with overshoot",
                    token.dampingRatio < 1.0f,
                )
            }
        }
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//.*"), "")

    private fun uiSources(): List<File> =
        uiRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.relativeToUiRoot(): String =
        relativeTo(uiRoot().parentFile!!).invariantSeparatorsPath

    private fun uiRoot(): File = sourceFile("ui")

    private fun sourceFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
