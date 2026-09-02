package `in`.shvms.trackme.ui.components

import android.util.Log
import com.google.android.gms.maps.CameraUpdate
import com.google.maps.android.compose.CameraPositionState
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MapCamera"

/**
 * Camera moves that tolerate a Maps SDK which has not finished loading.
 *
 * `CameraUpdateFactory` is a static façade over a delegate the Maps SDK installs when it loads.
 * Touch it before that happens and it throws
 * `NullPointerException: CameraUpdateFactory is not initialized` — a **fatal** crash, raised by a
 * factory call, on a path that only wanted to pan a map.
 *
 * It is a race, which is why it reproduces badly and reached production anyway: every camera move
 * in this app is fired from a `LaunchedEffect` or a tap, and either can win against map
 * initialisation when the first location fix arrives quickly or the device is slow.
 *
 * `TrackMeApp` now calls `MapsInitializer.initialize()` at startup, which closes the window in the
 * normal case; these helpers close it in the abnormal one. The two are deliberately separate
 * defences — the initialiser can itself fail (no Play Services, or a renderer the device will not
 * load) and it would then fail silently, leaving every camera call unguarded.
 *
 * **The update is built inside the guard, not passed in.** The throwing call is
 * `CameraUpdateFactory.newLatLngZoom(...)` *itself*, so a helper taking an already-constructed
 * [CameraUpdate] would be handed the exception instead of catching it — which is the entire bug.
 *
 * Failing means the camera does not move. Every use in this app is a convenience — recentre, fit
 * to bounds, reset bearing — and none of them is worth killing the process for.
 */
suspend fun CameraPositionState.animateSafely(durationMs: Int? = null, update: () -> CameraUpdate) {
    val cameraUpdate = buildUpdate(update) ?: return
    try {
        if (durationMs == null) animate(cameraUpdate) else animate(cameraUpdate, durationMs)
    } catch (cancellation: CancellationException) {
        // Leaving composition mid-animation is normal, not a failure. Swallowing this would break
        // structured concurrency — the caller's scope needs to see its own cancellation.
        throw cancellation
    } catch (e: Exception) {
        Log.w(TAG, "Camera animation failed; leaving the camera where it is", e)
    }
}

/**
 * The non-suspending counterpart, for fit-to-bounds calls on history screens.
 *
 * The result lets measured/map-loaded preview code retry a move that still lost an SDK readiness
 * race instead of silently accepting the estimated initial camera as the final frame.
 */
fun CameraPositionState.moveSafely(update: () -> CameraUpdate): Boolean {
    val cameraUpdate = buildUpdate(update) ?: return false
    return try {
        move(cameraUpdate)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Camera move failed; leaving the camera where it is", e)
        false
    }
}

/**
 * Builds the update, or null if the factory is not usable yet.
 *
 * Catches [Exception] rather than the documented [NullPointerException] alone: `newLatLngBounds`
 * additionally throws when the map has no measured size, which is the same race arriving through
 * a different door and equally not worth a crash.
 */
private inline fun buildUpdate(update: () -> CameraUpdate): CameraUpdate? =
    try {
        update()
    } catch (e: Exception) {
        Log.w(TAG, "CameraUpdateFactory unavailable; skipping this camera move", e)
        null
    }
