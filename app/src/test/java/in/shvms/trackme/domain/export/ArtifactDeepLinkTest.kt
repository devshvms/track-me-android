package `in`.shvms.trackme.domain.export

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtifactDeepLinkTest {
    @Test
    fun `cloud rides use only the stable public suffix`() {
        val ride = ride(id = 41, firestoreId = "private-prefix-abcdefghijkl")
        assertEquals(AppConfig.REPLAY_DEEP_LINK_BASE_URL + "abcdefghijkl", artifactDeepLink(ride))
    }

    @Test
    fun `local rides have a deterministic fallback`() {
        assertEquals(AppConfig.REPLAY_DEEP_LINK_BASE_URL + "41", artifactDeepLink(ride(id = 41)))
    }

    @Test
    fun `only nonblank TrackMe routes are trusted for rendering`() {
        assertTrue(isTrackMeArtifactDeepLink(AppConfig.REPLAY_DEEP_LINK_BASE_URL + "abc123"))
        assertFalse(isTrackMeArtifactDeepLink(null))
        assertFalse(isTrackMeArtifactDeepLink(AppConfig.REPLAY_DEEP_LINK_BASE_URL))
        assertFalse(isTrackMeArtifactDeepLink(AppConfig.REPLAY_DEEP_LINK_BASE_URL + "bad id"))
        assertFalse(isTrackMeArtifactDeepLink("https://example.com/r/abc123"))
    }

    @Test
    fun `both still and video production call sites pass the shared link`() {
        val rideDetail = source("ui/history/RideDetailScreen.kt")
        val replay = source("ui/history/ReplayExportAction.kt")
        assertTrue(rideDetail.contains("deepLink = artifactLink"))
        assertTrue(rideDetail.contains("rideWithPoints?.ride?.let(::artifactDeepLink)"))
        assertTrue(replay.contains("deepLink = artifactDeepLink(rideWithPoints.ride)"))
    }

    private fun ride(id: Long, firestoreId: String? = null) = RideEntity(
        id = id,
        startTime = 1_000,
        firestoreId = firestoreId,
    )

    private fun source(relative: String): String {
        var directory: File? = File("").absoluteFile
        val path = "app/src/main/java/in/shvms/trackme/$relative"
        while (directory != null) {
            File(directory, path).takeIf(File::exists)?.let { return it.readText() }
            File(directory, path.removePrefix("app/")).takeIf(File::exists)?.let { return it.readText() }
            directory = directory.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
