package `in`.shvms.trackme.ui.components

import androidx.compose.ui.graphics.Color

/**
 * One colour per member, everywhere they appear.
 *
 * §3.3 wants a per-member deterministic tint *"so two members are visually separable at a
 * glance"*, and §3.6 requires that identity is **never conveyed by colour alone** — hence initials
 * and names on every touchpoint. Both only work if the colour is the *same* colour: the roster is
 * how you learn that Priya is the teal one, and the map is where you use it.
 *
 * This exists because it was not. The roster and the marker each had their own ramp, so the same
 * member rendered in two different colours and the roster taught you nothing about the map.
 *
 * Shades within the locked cyan/navy accent (§3.1), never a rainbow that would fight the brand.
 */
object GroupMemberTint {

    /** ARGB, because the map needs `android.graphics.Color` ints and Compose needs `Color`. */
    val RAMP_ARGB = intArrayOf(
        0xFF29B6F6.toInt(), // cyan/bright
        0xFF0277B6.toInt(), // cyan/deep
        0xFF4FC3F7.toInt(),
        0xFF01579B.toInt(),
        0xFF039BE5.toInt(),
        0xFF00ACC1.toInt(),
    )

    /**
     * Stable for the whole session and across a reconnect — a member whose colour changed mid-ride
     * would undo the one thing the tint is for.
     *
     * The mask matters: `hashCode` is signed, and a negative index would crash the map on some
     * uids and not others.
     */
    fun argbFor(uid: String): Int =
        RAMP_ARGB[((uid.hashCode().toLong() and 0xFFFFFFFFL) % RAMP_ARGB.size).toInt()]

    fun colorFor(uid: String): Color = Color(argbFor(uid))
}
