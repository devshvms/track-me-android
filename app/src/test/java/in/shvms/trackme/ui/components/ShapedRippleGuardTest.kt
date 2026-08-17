package `in`.shvms.trackme.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A shaped `Surface` must take its click through the `onClick` overload, not a `.clickable` in the
 * modifier handed to it.
 *
 * ### The bug this prevents
 *
 * `Surface` builds its node as `[caller modifier] → shadow → background → clip(shape) → content`.
 * A `.clickable` at the end of the caller's modifier therefore sits *above* that `clip`, so the
 * ripple and press highlight are drawn against the un-clipped layout bounds: a square flash behind
 * a circular map button, a rectangle behind a rounded chip.
 *
 * The obvious fix — adding `.clip(shape)` before the `.clickable` — is wrong in the other
 * direction. That clip node also sits above `shadow`, so it clips the shadow away and the button
 * loses its elevation. Only the `onClick` / `selected` / `checked` overloads put the indication
 * between the two.
 *
 * ### Why a source test
 *
 * Nothing about `Surface(shape = CircleShape, modifier = Modifier.clickable { })` looks wrong. It
 * compiles, it is the shape it says it is, and the defect only appears while a finger is down —
 * which is exactly when nobody is looking at a screenshot. It shipped for the life of the app.
 */
class ShapedRippleGuardTest {

    private val componentSources = listOf(
        "ui/home/components/MapControlButtons.kt",
        "ui/home/components/InteractiveShareLocationButton.kt",
        "ui/home/components/ActiveRideHudPanel.kt",
        "ui/community/StatusPickerSheet.kt",
    )

    /**
     * A `Surface(` call whose argument list contains a `shape =` and, before the call closes, a
     * `.clickable` / `.selectable` / `.toggleable` in a modifier.
     *
     * Deliberately scoped to the argument list: the same modifiers *inside* a Surface's content
     * lambda are correct, because content is already below the clip. `GroupPresenceHost` does
     * exactly that and must not be flagged.
     */
    private val shapedSurfaceCall =
        Regex("""Surface\((?:[^()]|\([^()]*\))*?shape\s*=(?:[^()]|\([^()]*\))*?\)""", RegexOption.DOT_MATCHES_ALL)

    private val interactionModifier = Regex("""\.(clickable|selectable|toggleable)\s*[({]""")

    @Test
    fun `no shaped Surface takes its click from the modifier chain`() {
        val offenders = mutableListOf<String>()
        for (path in componentSources) {
            val source = stripComments(sourceFile(path).readText())
            for (call in shapedSurfaceCall.findAll(source)) {
                if (interactionModifier.containsMatchIn(call.value)) {
                    offenders += "$path: ${call.value.lineSequence().first().trim()}"
                }
            }
        }
        assertTrue(
            "These pass a click modifier to a shaped Surface, so the press indication is drawn " +
                "on the square layout bounds instead of inside the shape. Use the Surface " +
                "onClick / selected / checked overloads — adding .clip() instead would clip the " +
                "shadow away. Offenders:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the guard can actually see a violation`() {
        // A green source-reading test is worthless if the regex silently matches nothing. This
        // feeds it the exact shape of the bug and asserts it is caught.
        val violation = """
            Surface(
                shape = CircleShape,
                color = Color.Red,
                modifier = Modifier.size(52.dp).clickable { doThing() }
            ) { Icon() }
        """.trimIndent()
        val call = shapedSurfaceCall.find(violation)
        assertTrue("the regex no longer matches a shaped Surface call", call != null)
        assertTrue(
            "the regex no longer sees the click modifier inside it",
            interactionModifier.containsMatchIn(call!!.value),
        )
    }

    @Test
    fun `the guard does not flag a click inside the content lambda`() {
        // The correct pattern: content sits below Surface's own clip, so a clickable there is
        // already shaped. Flagging it would push people back toward the broken form.
        val correct = """
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accent
            ) {
                Row(modifier = Modifier.clickable(onClick = onOpen)) { Text("hi") }
            }
        """.trimIndent()
        val call = shapedSurfaceCall.find(correct)
        assertTrue("expected the Surface call itself to still match", call != null)
        assertTrue(
            "content-lambda clicks must not be reported",
            !interactionModifier.containsMatchIn(call!!.value),
        )
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//.*"), "")

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
