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
        // The one failure that cannot be caught by testing the pure function. onCreate opens
        // preference files, and if resolution ever moves below any of them then
        // `hasExistingPreferences` is true on every install, every install resolves to LEGACY, and
        // the walkthrough silently ships to nobody. Read from source because the symptom is an
        // absence — nothing crashes, nothing logs, a screen just never appears.
        //
        // TASK-309 removed SosStateCleanup, which used to be the specific offender this test
        // named. The rule it protected did not go with it: the assertion below is deliberately
        // about *any* preference access, so the next thing to creep above the gate is caught
        // whatever it happens to be called.
        val onCreate = source("TrackMeApp.kt")
            .substringAfter("override fun onCreate()")
            .substringBefore("\n    }")

        val gateAt = onCreate.indexOf("OnboardingGate.resolve")
        assertTrue("TrackMeApp.onCreate no longer resolves the onboarding gate", gateAt >= 0)

        val preferenceAccesses = Regex("getSharedPreferences\\(").findAll(onCreate)
            .map { it.range.first }
            .filter { it < gateAt }
            .toList()
        assertTrue(
            "something opens a preference file before the onboarding gate resolves",
            preferenceAccesses.isEmpty(),
        )
    }

    @Test
    fun `consent is written before the funnel event is captured`() {
        // AnalyticsManager drops everything while the flag is off, so emitting first would
        // silently discard the one event describing the screen the user just answered — and it
        // would be right to, because at that instant they had not agreed to anything. Ordering is
        // the whole mechanism, and swapping two lines breaks it with no visible symptom.
        val body = source("TrackMeApp.kt")
            .substringAfter("fun completeOnboarding(")
            .substringBefore("\n    }")

        val consentAt = body.indexOf("updateLocalConsent")
        val captureAt = body.indexOf("trackOnboardingCompleted")
        assertTrue("completeOnboarding no longer sets consent", consentAt >= 0)
        assertTrue("completeOnboarding no longer emits the funnel event", captureAt >= 0)
        assertTrue(
            "consent must be applied before trackOnboardingCompleted, or the event is dropped",
            consentAt < captureAt,
        )
    }

    @Test
    fun `the walkthrough transmits nothing before the consent screen is answered`() {
        // The funnel is one terminal event by design. A page-view stream would be reporting
        // progress through the very screens that ask permission to report anything.
        val screen = source("ui/onboarding/OnboardingScreen.kt")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
        assertFalse(
            "OnboardingScreen captures analytics directly — progress would leave the device " +
                "before the user has answered the consent question",
            screen.contains("AnalyticsManager"),
        )
    }

    @Test
    fun `notification permission is deferred until the first ride starts`() {
        val screen = source("ui/onboarding/OnboardingScreen.kt")
            .substringAfter("fun OnboardingScreen(")
            .substringBefore("private fun OnboardingChrome(")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
        assertFalse(
            "onboarding must not spend the once-only notification prompt before it is earned",
            screen.contains("notificationLauncher") ||
                screen.contains("Manifest.permission.POST_NOTIFICATIONS"),
        )
    }

    @Test
    fun `the funnel carries primitives and keeps persona out of telemetry`() {
        val outcome = source("ui/onboarding/OnboardingState.kt")
            .substringAfter("data class OnboardingOutcome(")
            .substringBefore(")")
        val types = Regex("""val \w+: (\w+)""").findAll(outcome).map { it.groupValues[1] }.toList()
        assertTrue("no fields found — did OnboardingOutcome move?", types.isNotEmpty())
        assertTrue(
            "OnboardingOutcome gained an unexpected field type ($types)",
            types.all { it == "Int" || it == "Boolean" || it == "RidePersona" },
        )
        assertEquals(1, types.count { it == "RidePersona" })

        val analytics = source("analytics/AnalyticsManager.kt")
            .substringAfter("fun trackOnboardingCompleted(")
            .substringBefore("\n    }")
        assertFalse("the local persona handoff must not enter telemetry", analytics.contains("selectedPersona"))
    }

    @Test
    fun `dwell distinguishes skipped pages and excludes background time`() {
        var now = 0L
        val dwell = OnboardingDwellAccumulator(pageCount = 6, nowMillis = { now })

        dwell.enter(0)
        now = 1_500L
        dwell.enter(1)
        now = 1_900L
        assertEquals(listOf(1, 0, -1, -1, -1, -1), dwell.snapshotSeconds())

        dwell.pause()
        now = 20_000L
        dwell.enter(2)
        now = 50_000L
        assertEquals(listOf(1, 0, 0, -1, -1, -1), dwell.snapshotSeconds())

        dwell.resume(2)
        now = 51_200L
        assertEquals(listOf(1, 0, 1, -1, -1, -1), dwell.snapshotSeconds())
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
