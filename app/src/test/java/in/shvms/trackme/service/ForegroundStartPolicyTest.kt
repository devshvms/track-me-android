package `in`.shvms.trackme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartPolicyTest {
    // Abandonment is unconditional for every failure class; see
    // TrackMeApp.abandonPersistedTrackingSession, which takes no decision.

    @Test
    fun securityExceptionIsPermissionRevoked() {
        val outcome = ForegroundStartPolicy.classify(SecurityException("permission"), 34)

        assertEquals(ForegroundStartOutcome.PERMISSION_REVOKED, outcome)
        assertTrue(outcome.shouldShowLocationPermissionRevokedNotice)
    }

    @Test
    fun api31ForegroundStartNotAllowedExceptionIsBackgroundBlocked() {
        val outcome = ForegroundStartPolicy.classify(
            ForegroundServiceStartNotAllowedException(),
            34
        )

        assertEquals(ForegroundStartOutcome.BACKGROUND_START_BLOCKED, outcome)
        assertFalse(outcome.shouldShowLocationPermissionRevokedNotice)
    }

    @Test
    fun illegalStateExceptionIsOther() {
        val outcome = ForegroundStartPolicy.classify(IllegalStateException("OEM"), 34)

        assertEquals(
            ForegroundStartOutcome.OTHER,
            outcome
        )
        assertFalse(outcome.shouldShowLocationPermissionRevokedNotice)
    }

    @Test
    fun api24DoesNotClassifyApi31Failure() {
        assertEquals(
            ForegroundStartOutcome.OTHER,
            ForegroundStartPolicy.classify(ForegroundServiceStartNotAllowedException(), 24)
        )
    }

    @Test
    fun classificationIsIdempotent() {
        val exception = SecurityException("permission")

        assertEquals(
            ForegroundStartPolicy.classify(exception, 34),
            ForegroundStartPolicy.classify(exception, 34)
        )
    }

    /** Same simple name as the API 31 exception, without loading that API class in the test. */
    private class ForegroundServiceStartNotAllowedException : IllegalStateException() {
        override fun getStackTrace(): Array<StackTraceElement> = emptyArray()
        override fun fillInStackTrace(): Throwable = this
    }
}
