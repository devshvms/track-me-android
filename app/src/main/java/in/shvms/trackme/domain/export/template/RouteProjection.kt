package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.domain.processor.RouteCoordinate
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/** A rectangle in pixels. Plain floats, so the domain layer does not depend on `android.graphics`. */
internal data class PixelBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class PixelPoint(val x: Float, val y: Float)

/**
 * Where a route lands inside a box when there is **no map under it** — the VECTOR mode of the
 * export templates (SCOPE_1.8.9 §4).
 *
 * Two properties, both of which the replay video's old `project()` lacked:
 *
 * 1. **One scale for both axes.** Longitude and latitude were each stretched to fill the frame
 *    independently, so any route that was not exactly the frame's shape came out distorted — an
 *    out-and-back along a coast became a shape nobody rode. A design whose hero is the route
 *    shape cannot ship on that.
 * 2. **Mercator, not raw degrees.** A degree of longitude is `cos(latitude)` of a degree of
 *    latitude on the ground, so plotting raw degrees stretches every route east–west by
 *    `1/cos(lat)` — 3 % at Bengaluru, 60 % in London, double in Oslo. Web-Mercator is conformal,
 *    so shapes survive, and it is the projection every map the rider has seen this route on uses.
 *
 * **Never use this over a map bitmap.** `EXPORT_SHARE_CONTRACTS.md` "Never re-derive the map
 * projection": when a basemap is present, the route's positions come from the map SDK's own
 * projection. This class exists only for the case where there is no map to ask.
 *
 * Routes crossing the antimeridian are not handled; nobody records a ride across it.
 */
internal class RouteProjection private constructor(
    private val minX: Double,
    private val maxY: Double,
    private val scale: Double,
    private val offsetX: Float,
    private val offsetY: Float,
) {
    fun project(coordinate: RouteCoordinate): PixelPoint = PixelPoint(
        x = offsetX + ((mercatorX(coordinate.longitude) - minX) * scale).toFloat(),
        y = offsetY + ((maxY - mercatorY(coordinate.latitude)) * scale).toFloat(),
    )

    fun project(coordinates: List<RouteCoordinate>): List<PixelPoint> = coordinates.map(::project)

    companion object {
        /**
         * Fits every coordinate inside [box], centred, with one scale for both axes.
         *
         * Null when there is nothing to fit or nowhere to fit it. A single point lands in the
         * centre: a route with no extent on an axis constrains nothing on that axis, so a due-north
         * line is as tall as the box allows rather than being forced to its width as well.
         */
        fun fit(coordinates: List<RouteCoordinate>, box: PixelBox): RouteProjection? {
            if (coordinates.isEmpty() || box.width <= 0f || box.height <= 0f) return null
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (coordinate in coordinates) {
                val x = mercatorX(coordinate.longitude)
                val y = mercatorY(coordinate.latitude)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val spanX = maxX - minX
            val spanY = maxY - minY
            val scaleX = if (spanX > EPSILON) box.width / spanX else Double.POSITIVE_INFINITY
            val scaleY = if (spanY > EPSILON) box.height / spanY else Double.POSITIVE_INFINITY
            val scale = min(scaleX, scaleY).takeIf { it.isFinite() } ?: 1.0
            val drawnWidth = (spanX * scale).toFloat()
            val drawnHeight = (spanY * scale).toFloat()
            return RouteProjection(
                minX = minX,
                maxY = maxY,
                scale = scale,
                offsetX = box.left + (box.width - drawnWidth) / 2f,
                offsetY = box.top + (box.height - drawnHeight) / 2f,
            )
        }

        private const val EPSILON = 1e-12

        /** Mercator is undefined at the poles; a corrupt point must not produce infinity. */
        private const val MAX_LATITUDE = 85.0

        internal fun mercatorX(longitude: Double): Double = Math.toRadians(longitude)

        internal fun mercatorY(latitude: Double): Double {
            val radians = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
            return ln(tan(PI / 4 + radians / 2))
        }
    }
}

/**
 * Drops projected points closer than [minStep] pixels to the last one kept, always keeping both
 * ends. A long ride carries thousands of fixes, and at share-image scale most of them land within a
 * pixel of their neighbour — drawing each as its own segment costs time and buys nothing visible.
 * Same indices come back alongside, so a per-point value (pace) can follow the thinned line.
 */
internal fun decimate(points: List<PixelPoint>, minStep: Float): List<Int> {
    if (points.size <= 2) return points.indices.toList()
    val kept = ArrayList<Int>(points.size)
    kept += 0
    var last = points[0]
    for (index in 1 until points.lastIndex) {
        val point = points[index]
        if (hypot((point.x - last.x).toDouble(), (point.y - last.y).toDouble()) >= minStep) {
            kept += index
            last = point
        }
    }
    kept += points.lastIndex
    return kept
}
