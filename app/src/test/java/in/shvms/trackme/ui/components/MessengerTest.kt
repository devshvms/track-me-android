package `in`.shvms.trackme.ui.components

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the behaviour that distinguishes [TrackMeMessenger] from a naive
 * `scope.launch { host.showSnackbar(...) }`.
 *
 * `showSnackbar` suspends until its snackbar is dismissed, and `SnackbarHostState` serialises on a
 * mutex. So launching twice without cancelling makes the second message wait for the first to time
 * out — the user reads a stale message, then watches a queue drain. `Toast` appeared not to do
 * that, which is part of why call sites kept reaching for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessengerTest {

  @Test
  fun `a second message replaces the first instead of queueing behind it`() = runTest {
    val host = SnackbarHostState()
    val messenger = TrackMeMessenger(host, backgroundScope)

    messenger.show("first")
    runCurrent()
    assertEquals("first", host.currentSnackbarData?.visuals?.message)

    messenger.show("second")
    runCurrent()
    assertEquals(
      "the newest message must win — if this is still 'first', the messenger is queueing and " +
        "the user reads a message they have already seen while the current one waits",
      "second",
      host.currentSnackbarData?.visuals?.message,
    )
  }

  @Test
  fun `an action message carries its label through to the host`() = runTest {
    val host = SnackbarHostState()
    val messenger = TrackMeMessenger(host, backgroundScope)

    messenger.show(message = "Ride deleted", actionLabel = "Undo") { /* no-op */ }
    runCurrent()

    assertEquals("Ride deleted", host.currentSnackbarData?.visuals?.message)
    assertEquals(
      "a destructive result without a reachable action is the exact gap Toast left",
      "Undo",
      host.currentSnackbarData?.visuals?.actionLabel,
    )
  }

  @Test
  fun `nothing is showing before the first message`() = runTest {
    val host = SnackbarHostState()
    TrackMeMessenger(host, backgroundScope)
    runCurrent()
    assertNull(host.currentSnackbarData)
  }
}
