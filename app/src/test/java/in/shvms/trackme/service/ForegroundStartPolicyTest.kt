package `in`.shvms.trackme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartPolicyTest {
    @Test
    fun securityExceptionIsPermissionRevoked() {
        val outcome = ForegroundStartPolicy.classify(SecurityException("permission"), 34)

        assertEquals(ForegroundStartOutcome.PERMISSION_REVOKED, outcome)
        assertTrue(ForegroundStartPolicy.shouldAbandonSession(outcome))
        assertTrue(ForegroundStartPolicy.shouldShowRevokedNotice(outcome))
        assertTrue(outcome.shouldShowLocationPermissionRevokedNotice)
    }

    @Test
    fun api31ForegroundStartNotAllowedExceptionIsBackgroundBlocked() {
        val outcome = ForegroundStartPolicy.classify(
            ForegroundServiceStartNotAllowedException(),
            34
        )

        assertEquals(ForegroundStartOutcome.BACKGROUND_START_BLOCKED, outcome)
        assertTrue(ForegroundStartPolicy.shouldAbandonSession(outcome))
        assertFalse(ForegroundStartPolicy.shouldShowRevokedNotice(outcome))
        assertFalse(outcome.shouldShowLocationPermissionRevokedNotice)
    }

    @Test
    fun illegalStateExceptionIsOther() {
        assertEquals(
            ForegroundStartOutcome.OTHER,
            ForegroundStartPolicy.classify(IllegalStateException("OEM"), 34)
        )
        assertTrue(ForegroundStartPolicy.shouldAbandonSession(ForegroundStartOutcome.OTHER))
        assertFalse(ForegroundStartPolicy.shouldShowRevokedNotice(ForegroundStartOutcome.OTHER))
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
