package `in`.shvms.trackme.domain.permissions

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-284 — the rule is "ask once, ever".
 *
 * The defect this replaces had no test, which is part of why it survived into production: nothing
 * anywhere asserted how often TrackMe is allowed to ask, so "whenever not granted" looked like a
 * permission check rather than a policy decision.
 */
class NotificationPermissionPolicyTest {

    private val tiramisu = Build.VERSION_CODES.TIRAMISU

    @Test
    fun `asks once on a fresh install that has not granted`() {
        assertTrue(
            NotificationPermissionPolicy.shouldRequest(
                sdkInt = tiramisu,
                isGranted = false,
                hasAskedBefore = false,
            )
        )
    }

    @Test
    fun `never asks a second time`() {
        // The whole defect. Android 13+ makes the second denial permanent, so a re-ask does not
        // merely annoy — it spends the rider's answer and then goes silently inert.
        assertFalse(
            NotificationPermissionPolicy.shouldRequest(
                sdkInt = tiramisu,
                isGranted = false,
                hasAskedBefore = true,
            )
        )
    }

    @Test
    fun `does not ask when already granted`() {
        assertFalse(
            NotificationPermissionPolicy.shouldRequest(
                sdkInt = tiramisu,
                isGranted = true,
                hasAskedBefore = false,
            )
        )
        assertFalse(
            NotificationPermissionPolicy.shouldRequest(
                sdkInt = tiramisu,
                isGranted = true,
                hasAskedBefore = true,
            )
        )
    }

    @Test
    fun `does not ask below Android 13, where there is no runtime permission`() {
        for (sdk in listOf(Build.VERSION_CODES.N, Build.VERSION_CODES.S, tiramisu - 1)) {
            assertFalse(
                "sdk=$sdk",
                NotificationPermissionPolicy.shouldRequest(
                    sdkInt = sdk,
                    isGranted = false,
                    hasAskedBefore = false,
                )
            )
        }
    }

    @Test
    fun `the only state that asks is exactly one combination`() {
        // Exhaustive over the decision's whole input space at and above Tiramisu, so a later edit
        // cannot quietly widen it back towards "ask whenever not granted".
        var asked = 0
        for (granted in listOf(true, false)) {
            for (before in listOf(true, false)) {
                if (NotificationPermissionPolicy.shouldRequest(tiramisu, granted, before)) asked++
            }
        }
        org.junit.Assert.assertEquals(1, asked)
    }
}
