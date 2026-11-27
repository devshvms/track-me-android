package `in`.shvms.trackme.domain.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.data.local.PendingRevealStore
import `in`.shvms.trackme.data.local.RideStatsStore
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.stats.RevealKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OrphanedRideRecoveryManagerTest {

    private fun point(lat: Double, lng: Double, speed: Float, timestampMs: Long) = GPSPointEntity(
        rideId = 1L,
        latitude = lat,
        longitude = lng,
        altitude = 0.0,
        accuracy = 5f,
        speed = speed,
        timestamp = timestampMs,
        isPaused = false,
    )

    @Test
    fun `trailing dwell cutoff trims long stationary tail after real movement`() {
        val base = 1_780_000_000_000L
        val points = listOf(
            point(28.5000, 77.2000, 5.0f, base),
            point(28.5025, 77.2000, 6.0f, base + 60_000L),
            point(28.5045, 77.2000, 0.4f, base + 120_000L),
            point(28.50455, 77.20005, 0.2f, base + 420_000L),
            point(28.50452, 77.20002, 0.0f, base + 120_000L + 15 * 60_000L),
        )
        val cutoff = OrphanedRideRecoveryManager.findTrailingDwellCutoff(points)
        assertNotNull(cutoff)
        assertEquals(2, cutoff!!.keepEndIndexInclusive)
        assertEquals(base + 120_000L, cutoff.cutoffTimestampMillis)
    }

    @Test
    fun `trailing dwell cutoff preserves brief pauses and stationary tests`() {
        val base = 1_780_000_000_000L
        val briefPause = listOf(
            point(28.5000, 77.2000, 5.0f, base),
            point(28.5045, 77.2000, 0.2f, base + 120_000L),
            point(28.50452, 77.20002, 0.0f, base + 120_000L + 9 * 60_000L),
        )
        assertNull(OrphanedRideRecoveryManager.findTrailingDwellCutoff(briefPause))

        val indoorOnly = listOf(
            point(28.5000, 77.2000, 0.2f, base),
            point(28.5001, 77.2000, 0.1f, base + 15 * 60_000L),
        )
        assertNull(OrphanedRideRecoveryManager.findTrailingDwellCutoff(indoorOnly))
    }

    @Test
    fun `creditRecoveredRides folds qualifying rides once and stages first ride reveal`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val statsStore = RideStatsStore(context)
        val revealStore = PendingRevealStore(context)

        val summary = OrphanedRideRecoveryManager.RecoverySummary(
            recoveredCount = 2,
            discardedCount = 0,
            recovered = listOf(
                OrphanedRideRecoveryManager.RecoveredRide(
                    rideId = 10L,
                    endTimeMillis = 1_780_000_000_000L,
                    distanceMeters = 4.0,
                    activeDurationMillis = 20_000L,
                    qualifiesForStats = false,
                ),
                OrphanedRideRecoveryManager.RecoveredRide(
                    rideId = 11L,
                    endTimeMillis = 1_780_001_000_000L,
                    distanceMeters = 2_500.0,
                    activeDurationMillis = 450_000L,
                    qualifiesForStats = true,
                ),
            ),
        )

        var streakEvents = 0
        OrphanedRideRecoveryManager.creditRecoveredRides(
            summary = summary,
            rideStatsStore = statsStore,
            pendingRevealStore = revealStore,
            rideDao = null,
            onFirstRideOfWeek = { _, _ -> streakEvents++ },
        )

        assertEquals(1, statsStore.stats.value.totalRides)
        assertEquals(2_500.0, statsStore.stats.value.totalDistanceMeters, 0.01)
        assertEquals(RevealKind.FIRST_RIDE, revealStore.pending.value?.kind)
        assertEquals(11L, revealStore.pending.value?.rideId)
        assertEquals(1, streakEvents)

        // Idempotent replay does not double-count or re-emit reveal/streak
        revealStore.consume(11L)
        OrphanedRideRecoveryManager.creditRecoveredRides(
            summary = summary,
            rideStatsStore = statsStore,
            pendingRevealStore = revealStore,
            rideDao = null,
            onFirstRideOfWeek = { _, _ -> streakEvents++ },
        )
        assertEquals(1, statsStore.stats.value.totalRides)
        assertNull(revealStore.pending.value)
        assertEquals(1, streakEvents)
    }
}
