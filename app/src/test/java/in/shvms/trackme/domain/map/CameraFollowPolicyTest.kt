package `in`.shvms.trackme.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.3 §1 and §0 contract 1 — **follow is a mode, not a behaviour.**
 *
 * The defect: `HomeScreen.kt:317` re-fired on every GPS fix and animated to the newest point at
 * zoom 17 unconditionally, overriding both a pan and a zoom within a second or two. 1.7.0 §3.4
 * claimed a free-look mode that has never existed.
 *
 * The rule tests below are cheap. The ones under "the wiring" are the ones that matter: the whole
 * failure mode of 1.7.2 was a correct policy with incorrect wiring, and the two ways this feature
 * can silently regress are (a) the follow move going back to `newLatLngZoom`, which re-breaks zoom
 * for a rider who deliberately zoomed out, and (b) the recentre control forgetting to re-arm, which
 * leaves follow permanently off with no way back.
 */
class CameraFollowPolicyTest {

    // --- Arming ---------------------------------------------------------------------------------

    @Test
    fun `follow arms when a ride starts`() {
        assertTrue(CameraFollowPolicy.armsOnRecordingStart(wasRecording = false, isRecording = true))
    }

    @Test
    fun `follow does not re-arm on every recomposition while recording`() {
        // Edge-triggered, not level-triggered. A rider who pans away mid-ride must stay panned
        // away; "recording implies following" would re-arm on the next frame and reproduce the
        // original defect with extra steps.
        assertFalse(CameraFollowPolicy.armsOnRecordingStart(wasRecording = true, isRecording = true))
    }

    @Test
    fun `ending a ride does not arm follow`() {
        assertFalse(CameraFollowPolicy.armsOnRecordingStart(wasRecording = true, isRecording = false))
        assertFalse(CameraFollowPolicy.armsOnRecordingStart(wasRecording = false, isRecording = false))
    }

    // --- Release --------------------------------------------------------------------------------

    @Test
    fun `any user gesture drops the camera into free-look`() {
        // The behaviour 1.7.0 §3.4 asserted and that has never existed until now.
        assertTrue(CameraFollowPolicy.releasesFollow(CameraMoveCause.USER_GESTURE))
    }

    @Test
    fun `our own animation never releases follow`() {
        // THE SELF-CANCELLING LOOP. The follow move is itself a camera move, reported by Maps
        // Compose as DEVELOPER_ANIMATION. Treating it as a gesture would switch follow off on its
        // first move and read as a flaky map rather than a bug.
        assertFalse(CameraFollowPolicy.releasesFollow(CameraMoveCause.APP_ANIMATION))
        assertFalse(CameraFollowPolicy.releasesFollow(CameraMoveCause.OTHER))
    }

    // --- What a follow update does --------------------------------------------------------------

    @Test
    fun `following a recording ride moves the target and keeps the zoom`() {
        assertEquals(
            CameraFollowPolicy.FollowMove.KeepZoom,
            CameraFollowPolicy.moveFor(following = true, isRecording = true, hasTarget = true),
        )
    }

    @Test
    fun `nothing moves once the rider has taken the camera`() {
        // The entire complaint: zooming out to see the group and being yanked back.
        assertEquals(
            CameraFollowPolicy.FollowMove.Stay,
            CameraFollowPolicy.moveFor(following = false, isRecording = true, hasTarget = true),
        )
    }

    @Test
    fun `nothing moves when there is no ride or no fix yet`() {
        assertEquals(
            CameraFollowPolicy.FollowMove.Stay,
            CameraFollowPolicy.moveFor(following = true, isRecording = false, hasTarget = true),
        )
        // Moving to nothing would fling the camera to the (0,0) world view.
        assertEquals(
            CameraFollowPolicy.FollowMove.Stay,
            CameraFollowPolicy.moveFor(following = true, isRecording = true, hasTarget = false),
        )
    }

    // --- Re-arming ------------------------------------------------------------------------------

    @Test
    fun `the recentre control is what re-arms follow`() {
        assertTrue(CameraFollowPolicy.onRecentrePressed())
    }

    @Test
    fun `focusing another member leaves the camera in free-look`() {
        // §4: "focusing a member is a camera move that must not be immediately undone by follow-me.
        // It should put the camera into free-look, exactly as a manual pan would." Without this,
        // tapping a roster row mid-ride shows that member for a fraction of a second before the
        // next fix drags the camera home.
        assertFalse(CameraFollowPolicy.onFocusedElsewhere())
    }

    // --- The wiring ------------------------------------------------------------------------------

    @Test
    fun `the follow move never forces a zoom level`() {
        // Q1's sharpest edge, and the easiest thing to reintroduce by reflex: "a rider who zoomed
        // out to 14 to see the group and is still following should stay at 14." newLatLngZoom in
        // the follow effect would silently re-break exactly the complaint this item exists for.
        val follow = bodyOf(homeScreenSource(), "LaunchedEffect(uiState.pathPoints, isFollowingRider, isRecording)")
        assertFalse(
            "the follow effect forces a zoom level — see SCOPE_1.7.3 §1, zoom is left alone while following",
            follow.contains("newLatLngZoom"),
        )
        assertTrue(
            "the follow effect must move the target with newLatLng",
            follow.contains("newLatLng("),
        )
    }

    @Test
    fun `the recentre control re-arms follow, and nothing re-arms it on a timer`() {
        // Q1.1: "button only, never on a timer." Auto-re-arm is friendlier solo and actively
        // hostile in a group — it would recreate this exact complaint every 30 seconds. So the
        // control must re-arm, and the only other place the flag is raised must be the
        // edge-triggered ride-start arming, never a delay().
        val source = homeScreenSource()
        assertTrue(
            "the recentre control must re-arm follow via onRecentrePressed()",
            source.contains("isFollowingRider = CameraFollowPolicy.onRecentrePressed()"),
        )
        val bareArming = Regex("""isFollowingRider\s*=\s*true""").findAll(source).count()
        assertEquals(
            "follow should be raised by exactly one bare assignment (the ride-start arming); " +
                "every other re-arm must go through CameraFollowPolicy",
            1,
            bareArming,
        )
        val armingEffect = bodyOf(source, "LaunchedEffect(isRecording)")
        assertTrue(
            "the one bare arming must sit behind armsOnRecordingStart, not behind a timer (Q1.1)",
            armingEffect.contains("CameraFollowPolicy.armsOnRecordingStart(") &&
                !armingEffect.contains("delay("),
        )
    }

    @Test
    fun `follow survives backgrounding`() {
        // Q1.2. rememberSaveable, not remember: the rider's last explicit intent is the best guess
        // at their next one.
        assertTrue(
            "isFollowingRider must be rememberSaveable so follow survives backgrounding (Q1.2)",
            homeScreenSource().contains("var isFollowingRider by rememberSaveable"),
        )
    }

    @Test
    fun `a gesture is read from the SDK signal rather than intercepted`() {
        // §1: "cameraPositionState.cameraMoveStartedReason == GESTURE is the signal Maps Compose
        // already provides, so this needs no touch interception."
        val source = homeScreenSource()
        assertTrue(
            "the gesture release must read cameraMoveStartedReason",
            source.contains("cameraPositionState.cameraMoveStartedReason.toMoveCause()"),
        )
        assertTrue(
            "DEVELOPER_ANIMATION must map to APP_ANIMATION, or follow cancels its own first move",
            source.contains("CameraMoveStartedReason.DEVELOPER_ANIMATION"),
        )
    }

    /**
     * Source with comments stripped, the same way [in.shvms.trackme.ui.components.MapCameraGuardTest]
     * does it — the comments explaining these rules necessarily name the calls the rules forbid.
     */
    private fun homeScreenSource(): String = rawHomeScreenSource()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//.*"), "")

    private fun rawHomeScreenSource(): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/ui/home/HomeScreen.kt"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("HomeScreen.kt not found")
    }

    /** Brace-matched body of the named declaration. */
    private fun bodyOf(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        require(start >= 0) { "\"$declaration\" not found — did it get renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces in $declaration")
    }
}
