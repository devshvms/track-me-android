package `in`.shvms.trackme.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSampleRidePolicyTest {
    @Test
    fun freshInstall_isEligibleButDoesNotSeedBeforeCompletion() {
        val initial = initialOnboardingSampleSeedState(
            onboardingState = OnboardingState.PENDING,
            wasUpdated = false,
        )
        assertEquals(OnboardingSampleSeedState.ELIGIBLE, initial)
        assertFalse(shouldAttemptOnboardingSampleSeed(initial, OnboardingState.PENDING))

        val requested = requestedOnboardingSampleSeedState(initial)
        assertEquals(OnboardingSampleSeedState.PENDING, requested)
        assertTrue(shouldAttemptOnboardingSampleSeed(requested, OnboardingState.DONE))
    }

    @Test
    fun upgrade_isIneligibleEvenWhenOldOnboardingWasPending() {
        assertEquals(
            OnboardingSampleSeedState.INELIGIBLE,
            initialOnboardingSampleSeedState(
                onboardingState = OnboardingState.PENDING,
                wasUpdated = true,
            ),
        )
    }

    @Test
    fun seededState_isTerminalAfterDeletionAndRerun() {
        val state = requestedOnboardingSampleSeedState(OnboardingSampleSeedState.SEEDED)
        assertEquals(OnboardingSampleSeedState.SEEDED, state)
        assertFalse(shouldAttemptOnboardingSampleSeed(state, OnboardingState.DONE))
    }
}
