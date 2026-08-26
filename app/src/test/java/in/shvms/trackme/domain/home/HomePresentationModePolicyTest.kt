package `in`.shvms.trackme.domain.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePresentationModePolicyTest {
    @Test fun `idle defaults to dashboard`() = assertEquals(
        HomePresentationMode.IDLE_DASHBOARD,
        HomePresentationModePolicy.resolve(isTrackingIdle = true, explicitGroupMap = false),
    )

    @Test fun `explicit group map outranks idle dashboard`() = assertEquals(
        HomePresentationMode.EXPLICIT_GROUP_MAP,
        HomePresentationModePolicy.resolve(isTrackingIdle = true, explicitGroupMap = true),
    )

    @Test fun `recording always outranks explicit group map`() = assertEquals(
        HomePresentationMode.ACTIVE_TRACKING_MAP,
        HomePresentationModePolicy.resolve(isTrackingIdle = false, explicitGroupMap = true),
    )
}
