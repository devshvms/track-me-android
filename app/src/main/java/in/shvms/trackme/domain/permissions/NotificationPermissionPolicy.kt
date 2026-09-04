package `in`.shvms.trackme.domain.permissions

import android.os.Build

/**
 * TASK-284 — when TrackMe may ask for `POST_NOTIFICATIONS`.
 *
 * Both ride-start paths used to launch the request whenever the permission was not currently
 * granted, which is the "ask whenever not granted" shape the PRD's privacy principles name as a
 * bug. On Android 13+ it is worse than merely rude: the second denial is permanent, after which the
 * launcher silently does nothing. So the old code nagged a rider exactly twice, taught the system
 * to refuse on their behalf forever, and then went quiet — leaving the app with no way back and the
 * user with no idea they had spent their answer.
 *
 * The rule is therefore **ask once, ever**. If the rider says no, that is an answer, and Android's
 * own Settings is the way to change it — not a prompt that reappears every time they set off.
 *
 * A pure function so the rule can be tested without a device, an Activity or a launcher.
 */
object NotificationPermissionPolicy {

    /**
     * @param sdkInt the running platform level; below TIRAMISU there is no runtime permission to ask for
     * @param isGranted whether `POST_NOTIFICATIONS` is granted right now
     * @param hasAskedBefore whether TrackMe has ever launched this request on this install
     */
    fun shouldRequest(sdkInt: Int, isGranted: Boolean, hasAskedBefore: Boolean): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
        if (isGranted) return false
        return !hasAskedBefore
    }
}
