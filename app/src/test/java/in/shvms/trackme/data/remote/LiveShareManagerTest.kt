package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveShareManagerTest {
    @Test
    fun formatGracefulErrorExplainsReauthenticationForUnauthorizedResponse() {
        val error = LiveShareHttpException(401)

        assertEquals(
            "Your sign-in expired. Please sign in again to share your location.",
            LiveShareManager.formatGracefulError(error)
        )
        assertEquals(true, LiveShareManager.isAuthenticationError(error))
    }

    @Test
    fun nonUnauthorizedResponsesAreNotTreatedAsAuthenticationFailures() {
        val error = LiveShareHttpException(500)

        assertEquals(false, LiveShareManager.isAuthenticationError(error))
    }
}
