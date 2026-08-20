package `in`.shvms.trackme.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * How much horizontal room the app has, in the only three sizes it makes decisions about.
 *
 * This is the app's own abstraction over Material 3's width breakpoints, for the same reason the
 * colour and motion layers have one: screens depend on *this*, never on a windowing library. The
 * adaptive APIs that would replace the implementation below (`material3-window-size-class`,
 * `material3-adaptive`) are a dependency this app does not otherwise need, and pulling one in to
 * read a single number would put a third-party type in the signature of every layout decision.
 * When there is a second reason to add it, only [rememberWindowClass] changes.
 *
 * Breakpoints follow the M3 window size classes: compact below 600dp, medium to 840dp, expanded
 * above. `screenWidthDp` is the width available to the app, so a multi-window or freeform app that
 * is only half the screen wide is correctly treated as compact.
 */
enum class TrackMeWindowClass {
    /** Phone in portrait, or any window narrower than 600dp. Bottom navigation. */
    Compact,

    /** Large phone in landscape, small tablet, unfolded foldable. Navigation rail. */
    Medium,

    /** Tablet in landscape and larger. Navigation rail. */
    Expanded;

    /**
     * Whether navigation belongs at the side rather than the bottom.
     *
     * A bottom bar spends vertical space, which is exactly what is scarce on a wide, short window,
     * and it puts the destinations far from the hand on a tablet. Both are the rail's case.
     */
    val usesNavigationRail: Boolean get() = this != Compact
}

@Composable
@ReadOnlyComposable
fun rememberWindowClass(): TrackMeWindowClass = windowClassFor(LocalConfiguration.current.screenWidthDp)

/**
 * The breakpoint mapping, kept separate from the composable so the boundaries are testable.
 *
 * Both boundaries are inclusive-lower — 600dp is medium, not compact — which is the one detail
 * worth a test, since getting it backwards moves the whole navigation layout by one device class.
 */
internal fun windowClassFor(widthDp: Int): TrackMeWindowClass = when {
    widthDp < 600 -> TrackMeWindowClass.Compact
    widthDp < 840 -> TrackMeWindowClass.Medium
    else -> TrackMeWindowClass.Expanded
}
