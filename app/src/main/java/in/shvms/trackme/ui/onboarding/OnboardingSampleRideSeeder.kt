package `in`.shvms.trackme.ui.onboarding

import android.content.Context
import androidx.room.withTransaction
import `in`.shvms.trackme.data.local.AppDatabase
import `in`.shvms.trackme.data.local.dashboardActiveDurationFromPoints
import `in`.shvms.trackme.data.local.dashboardRoutePolylineFromPoints
import `in`.shvms.trackme.data.local.withDashboardMetadata
import `in`.shvms.trackme.data.local.withUnavailableDashboardMetadata
import `in`.shvms.trackme.domain.processor.calculateElevationGainMeters
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.`import`.RideContentHash

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
        val seedRide = sampleRideWithMetadata(fixture)

        database.withTransaction {
            val rideDao = database.rideDao()
            if (rideDao.getSampleRideId() == null) {
                val rideId = rideDao.insertRide(seedRide)
                rideDao.insertGPSPoints(fixture.points.map { it.copy(rideId = rideId) })
            }
        }
        prefs.edit().putString(STATE_KEY, OnboardingSampleSeedState.SEEDED.stored).commit()
        return true
    }
}

/**
 * TASK-248: builds the sample ride with the dashboard metadata every other ride gets.
 *
 * It used to be inserted raw, at metadata version 0, and nothing ever reconciled it: the only
 * backfill sweep runs at application start, and the sample is seeded later, after that sweep has
 * drained. So the one ride every new rider opens first showed "Unknown" where its duration belongs
 * and no elevation cell at all — while its average speed, which comes off the aggregate rather than
 * the metadata, displayed a real number derived from the very duration the grid claimed not to know.
 *
 * This was the sixth write path found creating a ride without populating its metadata, after the
 * five in TASK-246. The compiler could not catch this one, because it never called the helper.
 *
 * Extracted from the seeder so the invariant can be tested without a database.
 */
internal fun sampleRideWithMetadata(fixture: RideWithPoints): RideEntity {
    val points = fixture.points
    val activeDurationMillis = dashboardActiveDurationFromPoints(points)
    val routePolyline = dashboardRoutePolylineFromPoints(points)
    // Measured rather than absent: the track carries an altitude on every point, so the answer is a
    // real 0 m on flat terrain, not the "we never knew" that §5.2 reserves the empty cell for. The
    // fixture's own header records that the scenario supplies no terrain.
    val elevationGainMeters = calculateElevationGainMeters(points)
    val ride = fixture.ride.copy(
        id = 0L,
        isSample = true,
        postRideCalculation = fixture.ride.postRideCalculation?.copy(
            elevationGainMeters = elevationGainMeters,
        ),
    )
    return if (activeDurationMillis != null) {
        withDashboardMetadata(
            ride,
            activeDurationMillis,
            points.size,
            routePolyline,
            RideContentHash.of(points),
        )
    } else {
        withUnavailableDashboardMetadata(ride, points.size, routePolyline)
    }
}
