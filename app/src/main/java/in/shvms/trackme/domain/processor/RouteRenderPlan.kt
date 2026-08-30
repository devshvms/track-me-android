package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.model.RidePersona

internal data class RouteCoordinate(val latitude: Double, val longitude: Double)

/**
 * An immutable, pure plan for rendering a route map.
 * 
 * TASK-271: Ensures detail map, preview, and export all draw exactly the same
 * geometry and bounds. Privacy trim must be applied to the points BEFORE creating this plan.
 */
internal data class RouteRenderPlan(
    val solidRuns: List<List<RouteCoordinate>>,
    val dottedJoins: List<List<RouteCoordinate>>,
    val pauseMarkers: List<RouteCoordinate>,
    val boundsLimits: List<RouteCoordinate>,
) {
    /** A lone point has bounds but no drawable route geometry. */
    val isEmpty: Boolean get() = solidRuns.none { it.size >= 2 } && dottedJoins.isEmpty()

    companion object {
        fun build(points: List<GPSPointEntity>, persona: RidePersona): RouteRenderPlan {
            if (points.isEmpty()) {
                return RouteRenderPlan(emptyList(), emptyList(), emptyList(), emptyList())
            }

            val runs = RideGaps.recordedRuns(points, persona)
            
            val solidRuns = runs.map { run -> 
                run.map { RouteCoordinate(it.latitude, it.longitude) }
            }
            
            val dottedJoins = mutableListOf<List<RouteCoordinate>>()
            for (i in 0 until runs.size - 1) {
                val lastOfCurrent = runs[i].last()
                val firstOfNext = runs[i + 1].first()
                dottedJoins.add(
                    listOf(
                        RouteCoordinate(lastOfCurrent.latitude, lastOfCurrent.longitude),
                        RouteCoordinate(firstOfNext.latitude, firstOfNext.longitude)
                    )
                )
            }
            
            val markers = autoPauseMarkerLocations(points)

            val boundsLimits = mutableListOf<RouteCoordinate>()
            solidRuns.forEach { run -> boundsLimits.addAll(run) }
            dottedJoins.forEach { join -> boundsLimits.addAll(join) }
            boundsLimits.addAll(markers)

            return RouteRenderPlan(
                solidRuns = solidRuns,
                dottedJoins = dottedJoins,
                pauseMarkers = markers,
                boundsLimits = boundsLimits
            )
        }
    }
}
