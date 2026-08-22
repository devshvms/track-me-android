package `in`.shvms.trackme.ui.onboarding

import android.content.Context
import androidx.room.withTransaction
import `in`.shvms.trackme.data.local.AppDatabase

/** Durable state machine for the first-run sample. `SEEDED` is terminal even after deletion. */
internal enum class OnboardingSampleSeedState(val stored: String) {
    ELIGIBLE("eligible"),
    PENDING("pending"),
    SEEDED("seeded"),
    INELIGIBLE("ineligible");

    companion object {
        fun fromStored(value: String?): OnboardingSampleSeedState? =
            entries.firstOrNull { it.stored == value }
    }
}

internal fun initialOnboardingSampleSeedState(
    onboardingState: OnboardingState,
    wasUpdated: Boolean,
): OnboardingSampleSeedState = when {
    wasUpdated -> OnboardingSampleSeedState.INELIGIBLE
    onboardingState == OnboardingState.PENDING -> OnboardingSampleSeedState.ELIGIBLE
    else -> OnboardingSampleSeedState.INELIGIBLE
}

internal fun requestedOnboardingSampleSeedState(
    current: OnboardingSampleSeedState,
): OnboardingSampleSeedState = if (current == OnboardingSampleSeedState.ELIGIBLE) {
    OnboardingSampleSeedState.PENDING
} else {
    current
}

internal fun shouldAttemptOnboardingSampleSeed(
    state: OnboardingSampleSeedState,
    onboardingState: OnboardingState,
): Boolean = state == OnboardingSampleSeedState.PENDING && onboardingState == OnboardingState.DONE

/**
 * Seeds the canonical ride only after a genuinely fresh install finishes onboarding.
 *
 * State is persisted outside Room so deleting the sample cannot make it eligible again. Room's
 * transaction independently checks for an existing sample, closing the insert→preference-write
 * crash window without relying on a title or timestamp as identity.
 */
internal object OnboardingSampleRideSeeder {
    private const val PREFS = "trackme_prefs"
    internal const val STATE_KEY = "onboarding_sample_seed_state"

    fun initialize(
        context: Context,
        onboardingState: OnboardingState,
        wasUpdated: Boolean,
    ): OnboardingSampleSeedState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        OnboardingSampleSeedState.fromStored(prefs.getString(STATE_KEY, null))?.let { return it }
        val initial = initialOnboardingSampleSeedState(onboardingState, wasUpdated)
        prefs.edit().putString(STATE_KEY, initial.stored).commit()
        return initial
    }

    fun request(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = OnboardingSampleSeedState.fromStored(prefs.getString(STATE_KEY, null))
            ?: return false
        val requested = requestedOnboardingSampleSeedState(current)
        if (requested == current) return false
        return prefs.edit().putString(STATE_KEY, requested.stored).commit()
    }

    suspend fun seedIfNeeded(
        context: Context,
        database: AppDatabase,
        onboardingState: OnboardingState,
        title: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = OnboardingSampleSeedState.fromStored(prefs.getString(STATE_KEY, null))
            ?: return false
        if (!shouldAttemptOnboardingSampleSeed(state, onboardingState)) return false

        val startTime = (nowMillis - OnboardingDemoFixture.DURATION_MILLIS).coerceAtLeast(1L)
        val fixture = OnboardingDemoFixture.create(startTimeMillis = startTime, title = title)
        database.withTransaction {
            val rideDao = database.rideDao()
            if (rideDao.getSampleRideId() == null) {
                val rideId = rideDao.insertRide(fixture.ride.copy(id = 0L, isSample = true))
                rideDao.insertGPSPoints(fixture.points.map { it.copy(rideId = rideId) })
            }
        }
        prefs.edit().putString(STATE_KEY, OnboardingSampleSeedState.SEEDED.stored).commit()
        return true
    }
}
