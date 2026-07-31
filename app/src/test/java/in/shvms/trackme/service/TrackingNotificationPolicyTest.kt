package `in`.shvms.trackme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingNotificationPolicyTest {
    @Test
    fun movementThresholdUsesCumulativeDistanceSinceLastPost() {
        var snapshot = TrackingNotificationThrottle(
            lastNotifyElapsedMs = 1_000L,
            lastNotifyDistanceMeters = 0f,
            lastNotifyState = TrackingState.TRACKING
        )
        var posts = 0

        listOf(5f, 10f, 30f).forEachIndexed { index, distanceMeters ->
            val shouldPost = shouldUpdateTrackingNotification(
                nowElapsedMs = 2_000L + index * 1_000L,
                distanceMeters = distanceMeters,
                state = TrackingState.TRACKING,
                previous = snapshot
            )
            if (shouldPost) {
                posts += 1
                snapshot = snapshot.copy(
                    lastNotifyElapsedMs = 2_000L + index * 1_000L,
                    lastNotifyDistanceMeters = distanceMeters
                )
            }
        }

        assertEquals(1, posts)
    }

    @Test
    fun stateTransitionPostsBeforeTimeOrDistanceThreshold() {
        val snapshot = TrackingNotificationThrottle(
            lastNotifyElapsedMs = 10_000L,
            lastNotifyDistanceMeters = 100f,
            lastNotifyState = TrackingState.TRACKING
        )

        assertTrue(
            shouldUpdateTrackingNotification(
                nowElapsedMs = 10_001L,
                distanceMeters = 100f,
                state = TrackingState.PAUSED,
                previous = snapshot
            )
        )
        assertTrue(
            shouldUpdateTrackingNotification(
                nowElapsedMs = 10_002L,
                distanceMeters = 100f,
                state = TrackingState.GPS_LOST,
                previous = snapshot
            )
        )
        assertFalse(
            shouldUpdateTrackingNotification(
                nowElapsedMs = 10_003L,
                distanceMeters = 100f,
                state = TrackingState.TRACKING,
                previous = snapshot
            )
        )
    }

    @Test
    fun elapsedTimeThresholdPostsAfterFifteenSeconds() {
        val snapshot = TrackingNotificationThrottle(
            lastNotifyElapsedMs = 10_000L,
            lastNotifyDistanceMeters = 100f,
            lastNotifyState = TrackingState.TRACKING
        )

        assertFalse(
            shouldUpdateTrackingNotification(
                nowElapsedMs = 24_999L,
                distanceMeters = 100f,
                state = TrackingState.TRACKING,
                previous = snapshot
            )
        )
        assertTrue(
            shouldUpdateTrackingNotification(
                nowElapsedMs = 25_000L,
                distanceMeters = 100f,
                state = TrackingState.TRACKING,
                previous = snapshot
            )
        )
    }
}
