package `in`.shvms.trackme.ui.localization

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.8.4 §5.2 / TASK-196: **no spoken path may read a glanceable string.**
 *
 * `groupAgeSeconds` is `"%1$ds ago"`. That is right on a roster row a rider glances at and wrong in
 * a sentence a TTS engine pronounces — engines read "45s" as "forty-five ess", or spell it out, and
 * it varies by engine and locale. The visual and spoken vocabularies are therefore separate
 * families that happen to describe the same `PresenceAge.Bucket`.
 *
 * The TASK-138/139 guard checks key *presence*, so it cannot see this class of defect: both keys
 * exist and both are translated. Only the *consumer* is wrong. Hence a source-level guard.
 */
class VoiceCopyGuardTest {

    // `voice/` (the App Actions adapter) was removed in TASK-201 when Play refused the bundle.
    // The pure controller under `domain/voice` remains and is still the thing that must never read
    // a glanceable key — it is what Android voice would be rebuilt on.
    private val voiceSourceDirs = listOf(
        File("src/main/java/in/shvms/trackme/domain/voice"),
    )

    /** Glanceable families that must never be read from a spoken path. */
    private val glanceableKeys = listOf("groupAge", "groupDirectionsWithAge")

    @Test
    fun `no voice source reads a glanceable string key`() {
        val offenders = mutableListOf<String>()
        voiceSourceDirs.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                glanceableKeys.forEach { key ->
                    if (text.contains(key)) offenders += "${file.name} reads $key"
                }
            }
        }
        assertTrue(
            "Spoken copy must not reuse glanceable strings: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the voice source directories actually exist`() {
        // A guard that silently scans nothing is worse than no guard: it reports success forever.
        assertTrue(
            "no voice source directory found — this guard would pass vacuously",
            voiceSourceDirs.any { it.isDirectory },
        )
    }
}
