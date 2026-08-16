package `in`.shvms.trackme.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import `in`.shvms.trackme.LocalSnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fire-and-forget messaging, so a `Snackbar` is as cheap to reach for as a `Toast` was.
 *
 * ### Why this exists
 * The app carried 40 `Toast` call sites against 6 `Snackbar` ones, and the reason was ergonomic,
 * not architectural: `SnackbarHostState.showSnackbar` is a `suspend` function, so every call site
 * needed a `CoroutineScope` and a `launch`. `Toast.makeText(...).show()` is one line. When the
 * correct surface costs four lines and the wrong one costs one, the wrong one wins.
 *
 * This makes the correct surface one line too.
 *
 * ### Why Snackbar rather than Toast
 * A `Toast` renders outside the app's theme, cannot be dismissed, cannot carry an action, and on
 * Android 12+ is rate-limited and always stamped with the app icon and name. A `Snackbar` is themed,
 * swipe-dismissable, and can carry the **undo** that destructive actions actually need — "Ride
 * deleted" is not useful without it.
 *
 * ### Replace, don't queue
 * `showSnackbar` suspends until its snackbar is dismissed, so naively launching several stacks them
 * into a queue and the user watches messages they have already read. [show] cancels the in-flight
 * one first, so the newest message wins — matching what `Toast` appeared to do, without the
 * queue-behind behaviour it actually had.
 *
 * See `docs/DESIGN_SYSTEM_1.8.md` — feedback ladder, rung 2.
 */
@Stable
class TrackMeMessenger(
  private val hostState: SnackbarHostState,
  private val scope: CoroutineScope,
) {
  private var current: Job? = null

  /** A plain notice. Replaces anything currently showing. */
  fun show(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
    current?.cancel()
    current = scope.launch {
      hostState.showSnackbar(message = message, duration = duration, withDismissAction = false)
    }
  }

  /**
   * A notice with an action — the shape a destructive result should always use.
   *
   * @param onAction invoked only when the user actually taps the action, not on timeout.
   */
  fun show(
    message: String,
    actionLabel: String,
    duration: SnackbarDuration = SnackbarDuration.Long,
    onAction: () -> Unit,
  ) {
    current?.cancel()
    current = scope.launch {
      val result =
        hostState.showSnackbar(
          message = message,
          actionLabel = actionLabel,
          duration = duration,
          withDismissAction = false,
        )
      if (result == SnackbarResult.ActionPerformed) onAction()
    }
  }
}

/**
 * The messenger for the current screen, bound to the app-level host in `Navigation.kt`.
 *
 * Scoped to the composition that calls it, so a screen leaving the back stack takes its pending
 * message with it rather than firing into whatever replaced it.
 */
@Composable
fun rememberMessenger(): TrackMeMessenger {
  val hostState = LocalSnackbarHostState.current
  val scope = rememberCoroutineScope()
  return remember(hostState, scope) { TrackMeMessenger(hostState, scope) }
}
