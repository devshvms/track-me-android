package `in`.shvms.trackme.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.maps.android.compose.MapType
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "OffscreenMapCapture"

/** Default ceiling on how long to wait for tiles. Generous: cold tile fetches on mobile are slow. */
private const val DEFAULT_TIMEOUT_MS = 8_000L

/**
 * Renders a map at an exact pixel size, off-screen, and hands back its snapshot.
 *
 * ### Why this exists
 *
 * `GoogleMap.snapshot()` returns a bitmap the size of the view it is called on. Exporting by
 * snapshotting the *preview* map therefore produced an image whose resolution was whatever the
 * preview happened to measure — which depended on the device's screen density and on a layout
 * constant. A "1080×1920 story" came out around 708×1260 on one phone and 472×840 on another.
 * Rendering here instead makes the requested size the actual size.
 *
 * ### The part that is not obvious
 *
 * The view must be **attached to a real window**. Maps' underlying GL surface only renders once it
 * joins the view hierarchy; a [MapView] that is merely measured and laid out calls back from
 * `snapshot()` perfectly happily and hands you blank tiles. So it is added to the activity's decor
 * view and pushed outside the visible frame with `translationX`. Not `alpha = 0` and not
 * `visibility = GONE` — both stop the surface rendering on some devices, which is the same blank
 * snapshot by a different route.
 *
 * @param configure called once the map is ready and before tiles are awaited: add overlays and set
 *   the camera here.
 * @param onResult called exactly once, on the main thread, with the snapshot or null on failure or
 *   timeout. **The map passed alongside is valid only for the duration of this call** — the view is
 *   detached and destroyed the moment it returns, so read anything you need from `projection` now.
 */
internal fun captureOffscreenMap(
    context: Context,
    widthPx: Int,
    heightPx: Int,
    mapType: MapType,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    configure: (GoogleMap) -> Unit,
    onResult: (Bitmap?, GoogleMap?) -> Unit,
) {
    if (widthPx <= 0 || heightPx <= 0) {
        onResult(null, null)
        return
    }

    val rootView = context.findHostActivity()?.window?.decorView as? ViewGroup
    val mapView = MapView(context, GoogleMapOptions().mapType(googleMapType(mapType)))
    mapView.layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
    if (rootView != null) {
        mapView.translationX = -(widthPx * 2).toFloat()
        rootView.addView(mapView)
    }
    mapView.measure(
        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
    )
    mapView.layout(0, 0, widthPx, heightPx)
    mapView.onCreate(null)
    mapView.onStart()
    mapView.onResume()

    val completed = AtomicBoolean(false)
    val mainHandler = Handler(Looper.getMainLooper())

    fun detach() {
        runCatching {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            rootView?.removeView(mapView)
        }.onFailure { Log.w(TAG, "Offscreen map teardown failed", it) }
    }

    val timeout = Runnable {
        if (completed.compareAndSet(false, true)) {
            Log.w(TAG, "Offscreen map timed out after ${timeoutMs}ms")
            onResult(null, null)
            detach()
        }
    }
    mainHandler.postDelayed(timeout, timeoutMs)

    // Ordering matters: the caller is promised a live map, so onResult runs before detach().
    fun finish(bitmap: Bitmap?, map: GoogleMap?) {
        if (!completed.compareAndSet(false, true)) {
            if (bitmap?.isRecycled == false) bitmap.recycle()
            return
        }
        mainHandler.removeCallbacks(timeout)
        runCatching { onResult(bitmap, map) }
            .onFailure { Log.w(TAG, "Offscreen map consumer threw", it) }
        detach()
    }

    mapView.getMapAsync { map ->
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        runCatching {
            configure(map)
            map.setOnMapLoadedCallback {
                runCatching { map.snapshot { bitmap -> finish(bitmap, map) } }
                    .onFailure { finish(null, null) }
            }
        }.onFailure {
            Log.w(TAG, "Offscreen map configuration failed", it)
            finish(null, null)
        }
    }
}

/** Walks the `ContextWrapper` chain to find the hosting Activity, if any. */
internal fun Context.findHostActivity(): android.app.Activity? {
    var ctx: Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

internal fun googleMapType(mapType: MapType): Int = when (mapType) {
    MapType.SATELLITE -> GoogleMap.MAP_TYPE_SATELLITE
    MapType.TERRAIN -> GoogleMap.MAP_TYPE_TERRAIN
    MapType.HYBRID -> GoogleMap.MAP_TYPE_HYBRID
    else -> GoogleMap.MAP_TYPE_NORMAL
}

/**
 * The framing a live map is currently showing, as geographic bounds.
 *
 * Bounds rather than [GoogleMap.getCameraPosition] because zoom is only meaningful alongside a
 * viewport size — the same zoom level on a larger surface shows more ground. Bounds reproduce the
 * same framing at any resolution, which is exactly what re-rendering an export needs. Valid only
 * while the map has been laid out; returns null before that.
 */
internal fun com.google.android.gms.maps.GoogleMap.visibleBounds():
    com.google.android.gms.maps.model.LatLngBounds? =
    runCatching { projection.visibleRegion.latLngBounds }.getOrNull()
