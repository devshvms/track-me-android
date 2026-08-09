package `in`.shvms.trackme.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The walkthrough's gating logic, which fails silently in both directions if it is wrong: show the
 * tour to everyone who upgrades, or show it to nobody at all.
 */
class OnboardingStateTest {

    @Test
    fun `a genuinely fresh install gets the walkthrough`() {
        assertEquals(
            OnboardingState.PENDING,
            resolveOnboardingState(stored = null, hasExistingPreferences = false, wasUpdated = false),
        )
    }

    @Test
    fun `existing preferences mean this is an upgrade`() {
        assertEquals(
            OnboardingState.LEGACY,
            resolveOnboardingState(stored = null, hasExistingPreferences = true, wasUpdated = false),
        )
    }

    @Test
    fun `having ever been updated means this is an upgrade`() {
        // Covers the upgrader who never opened Settings, so left no preferences behind.
        assertEquals(
            OnboardingState.LEGACY,
            resolveOnboardingState(stored = null, hasExistingPreferences = false, wasUpdated = true),
        )
    }

    @Test
    fun `either signal alone is enough to withhold the tour`() {
        // Deliberately asymmetric. Wrongly showing the tour to an existing user is a worse failure
        // than wrongly withholding it, so both signals must look fresh before it is offered.
        for (prefs in listOf(true, false)) {
            for (updated in listOf(true, false)) {
                val expected =
                    if (!prefs && !updated) OnboardingState.PENDING else OnboardingState.LEGACY
                assertEquals(
                    "prefs=$prefs updated=$updated",
                    expected,
                    resolveOnboardingState(null, prefs, updated),
                )
            }
        }
    }

    @Test
    fun `a stored decision is never re-derived`() {
        // Once resolved the answer is fixed, whatever the signals look like later — preferences
        // fill up during normal use, and re-deriving would flip a PENDING install to LEGACY on its
        // second launch, hiding the tour from someone mid-way through it.
        for (state in OnboardingState.entries) {
            assertEquals(
                state,
                resolveOnboardingState(state.stored, hasExistingPreferences = true, wasUpdated = true),
            )
        }
    }

    @Test
    fun `an unrecognised stored value falls back to the signals`() {
        assertEquals(
            OnboardingState.PENDING,
            resolveOnboardingState("something-else", hasExistingPreferences = false, wasUpdated = false),
        )
    }

    @Test
    fun `the hint pill belongs only to upgraders who have not dismissed it`() {
        assertTrue(shouldShowStartRideHint(OnboardingState.LEGACY, hintAlreadySeen = false))
        assertFalse(shouldShowStartRideHint(OnboardingState.LEGACY, hintAlreadySeen = true))
        // A fresh install was taught the gesture by the walkthrough; the pill would repeat it.
        assertFalse(shouldShowStartRideHint(OnboardingState.DONE, hintAlreadySeen = false))
        assertFalse(shouldShowStartRideHint(OnboardingState.PENDING, hintAlreadySeen = false))
    }

    @Test
    fun `the gate resolves before anything else writes to preferences`() {
        // The one failure that cannot be caught by testing the pure function. SosStateCleanup
        // commits a flag into trackme_prefs near the top of onCreate, so if resolution ever moves
        // below it, `hasExistingPreferences` is true on every install, every install resolves to
        // LEGACY, and the walkthrough silently ships to nobody. Read from source because the
        // symptom is an absence — nothing crashes, nothing logs, a screen just never appears.
        val onCreate = source("TrackMeApp.kt")
            .substringAfter("override fun onCreate()")
            .substringBefore("\n    }")

        val gateAt = onCreate.indexOf("OnboardingGate.resolve")
        val cleanupAt = onCreate.indexOf("SosStateCleanup.clearOnce")
        assertTrue("TrackMeApp.onCreate no longer resolves the onboarding gate", gateAt >= 0)
        assertTrue("SosStateCleanup.clearOnce not found — did it move?", cleanupAt >= 0)
        assertTrue(
            "OnboardingGate.resolve must run before SosStateCleanup.clearOnce writes to trackme_prefs",
            gateAt < cleanupAt,
        )

        val preferenceWrites = Regex("""getSharedPreferences\("trackme_prefs"""").findAll(onCreate)
            .map { it.range.first }
            .filter { it < gateAt }
            .toList()
        assertTrue(
            "something touches trackme_prefs before the onboarding gate resolves",
            preferenceWrites.isEmpty(),
        )
    }

    private fun source(name: String): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$name"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("$name not found")
    }
}
