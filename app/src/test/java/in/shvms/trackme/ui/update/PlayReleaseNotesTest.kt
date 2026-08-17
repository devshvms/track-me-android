package `in`.shvms.trackme.ui.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every file in `distribution/whatsnew` must fit Google Play's release-notes limit.
 *
 * (Written without a glob on purpose: Kotlin block comments nest, so a literal slash-star inside
 * this KDoc opens a comment the closing star-slash then fails to close, and the whole file stops
 * parsing.)
 *
 * ### Why this is a test and not a comment
 *
 * Play rejects notes over **500 characters per language**, and it rejects them at the *very last
 * step* of the publish pipeline — after the signed AAB is built, after lint, after the unit suite,
 * and after the emulator smoke test. The 1.7.3 release notes came in at 521 characters and burned a
 * full ~13-minute CI run to discover a limit that a string length check answers instantly.
 *
 * Worse than the wasted run: the failure arrives *after* `auto_release_notes.yml` has already
 * pushed a GitHub release using the same copy, so the tag is public and the Play upload is not —
 * the two surfaces disagree until someone notices and re-runs.
 *
 * This file is also the in-app update dialog's copy (that workflow reads it verbatim), so it is
 * user-facing text on two channels and worth guarding on both counts.
 */
class PlayReleaseNotesTest {

    /** Google Play's hard limit, per language. */
    private val maxCharacters = 500

    @Test
    fun `every whatsnew file fits Play's release-notes limit`() {
        val files = whatsnewDir().listFiles()?.filter { it.isFile && !it.isHidden }.orEmpty()
        assertTrue("no whatsnew files found — did the directory move?", files.isNotEmpty())

        for (file in files) {
            val length = file.readText().length
            assertTrue(
                "${file.name} is $length characters; Google Play rejects anything over " +
                    "$maxCharacters and does so only at the final publish step, after the whole " +
                    "build and test pipeline has run",
                length <= maxCharacters,
            )
        }
    }

    @Test
    fun `every whatsnew file actually says something`() {
        // An empty or whitespace-only file would publish silently and leave users with a blank
        // "What's new" — the same class of dishonesty as a stale one, just quieter.
        for (file in whatsnewDir().listFiles()?.filter { it.isFile && !it.isHidden }.orEmpty()) {
            assertTrue("${file.name} is blank", file.readText().isNotBlank())
        }
    }

    @Test
    fun `the release notes name the version being shipped`() {
        // The 1.7.1/1.7.2 mix-up this repo already corrected once (see the docs commit on master):
        // notes that name the wrong version are worse than none, because they read as authoritative.
        // Not a raw string: a trailing \" inside """...""" runs the quotes together and Kotlin
        // parses the rest of the file as one unterminated literal.
        val versionName = Regex("versionName\\s*=\\s*\"([^\"]+)\"")
            .find(buildFile().readText())
            ?.groupValues?.get(1)
            ?: throw AssertionError("versionName not found in app/build.gradle.kts")

        val english = File(whatsnewDir(), "whatsnew-en-US")
        assertTrue("whatsnew-en-US is missing", english.exists())
        assertTrue(
            "whatsnew-en-US does not mention $versionName — the build and its notes disagree",
            english.readText().contains(versionName),
        )
    }

    private fun whatsnewDir(): File = resolve("distribution/whatsnew")

    private fun buildFile(): File = resolve("app/build.gradle.kts")

    private fun resolve(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${File("").absolutePath}")
    }
}
