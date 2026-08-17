package `in`.shvms.trackme.domain.map

/**
 * Why the camera started moving. Mirrors the subset of Maps Compose's `CameraMoveStartedReason`
 * that the follow rule cares about, deliberately as our own type.
 *
 * SCOPE_1.7.3 §7 makes this a cross-platform seam: iOS detects gestures through a completely
 * different MapKit signal, but *"the observable rule is identical"*. Keeping the policy off the
 * Maps SDK's enum means the rule is stated once, in terms both clients can implement, and stays
 * unit-testable without a map.
 */
enum class CameraMoveCause {
    /** The rider panned, pinched, or rotated the map. The only thing that releases follow. */
    USER_GESTURE,

    /** One of our own `animateSafely` calls — including the follow move itself. Must not release. */
    APP_ANIMATION,

    /** Anything else the SDK reports, including "no movement yet". Treated as not a gesture. */
    OTHER,
}

/**
 * SCOPE_1.7.3 §1 and §0 contract 1 — **follow is a mode, not a behaviour.**
 *
 * > *It is on while recording, cleared by any user gesture, and re-armed only by the recentre
 * > control. While following, the target moves and the zoom level is left alone.*
 *
 * ### What was there before
 *
 * `HomeScreen.kt:317` re-fired on **every** GPS fix and unconditionally animated to the newest
 * point **at zoom 17**, so it overrode both a pan *and* a zoom within a second or two, every time.
 * There was no manual-gesture detection anywhere in the file. 1.7.0 §3.4 asserted that a manual pan
 * *"drops both modes into free-look, exactly as today"* — free-look has never existed.
 *
 * This is worst exactly where it was hit: in a group, zooming out to see where everyone is, and
 * being yanked back to yourself before the screen can be read.
 *
 * ### The two decisions that shape this
 *
 * - **Q1.1 — button only.** Follow never re-arms on a timer. Auto-re-arm is friendlier solo and
 *   actively hostile in a group: it would recreate this exact complaint every 30 seconds.
 * - **Q1.2 — follow survives backgrounding.** The rider's last explicit intent is the best guess at
 *   their next one, so the flag is remembered rather than reset when Home comes back.
 */
object CameraFollowPolicy {

    /**
     * The zoom a deliberate recentre snaps to. Applied **only** when follow is re-armed or when the
     * rider explicitly asks to recentre — never on a follow update, which is the whole point of
     * [FollowMove.KeepZoom].
     */
    const val RECENTRE_ZOOM = 17f

    /** What the camera should do for one path-point update. */
    enum class FollowMove {
        /** Move the target to the newest point, leaving zoom exactly where the rider put it. */
        KeepZoom,

        /** Leave the camera alone — not following, not recording, or nothing to follow yet. */
        Stay,
    }

    /**
     * Whether follow should be armed as recording begins.
     *
     * Arming is edge-triggered on *entering* a ride rather than held true throughout it, because a
     * rider who pans away mid-ride must stay panned away — a level-triggered "recording implies
     * following" would re-arm on the next recomposition and reproduce the original defect with
     * extra steps.
     */
    fun armsOnRecordingStart(wasRecording: Boolean, isRecording: Boolean): Boolean =
        isRecording && !wasRecording

    /**
     * Whether this camera move releases follow into free-look.
     *
     * Only a real gesture counts. Our own follow animation reports [CameraMoveCause.APP_ANIMATION],
     * and treating that as a release would make follow switch itself off on its first move — the
     * kind of self-cancelling loop that looks like a flaky map rather than a bug.
     */
    fun releasesFollow(cause: CameraMoveCause): Boolean = cause == CameraMoveCause.USER_GESTURE

    /**
     * What to do with a new position while a ride is recording.
     *
     * [hasTarget] is false until the first fix lands; moving to nothing would fling the camera to
     * the (0,0) world view.
     */
    fun moveFor(following: Boolean, isRecording: Boolean, hasTarget: Boolean): FollowMove =
        if (following && isRecording && hasTarget) FollowMove.KeepZoom else FollowMove.Stay

    /**
     * Follow state after the rider taps the recentre control.
     *
     * The control's real job. §1: *"The existing recentre control re-arms it, which gives the
     * button a real job rather than a redundant one."* This is the **only** thing that re-arms —
     * see Q1.1.
     */
    fun onRecentrePressed(): Boolean = true

    /**
     * Follow state after the app itself moves the camera somewhere deliberate — focusing a roster
     * member (§4).
     *
     * §4: *"focusing a member is a camera move that must **not** be immediately undone by follow-me.
     * It should put the camera into free-look, exactly as a manual pan would."* Without this, tapping
     * a roster row while recording would show that member for a fraction of a second before the next
     * fix dragged the camera back to the rider — the §1 defect, wearing a different hat.
     */
    fun onFocusedElsewhere(): Boolean = false
}
