package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.history.PausedMarkerLocation
import `in`.shvms.trackme.ui.history.explicitPauseMarkerLocations

/**
 * An immutable, pure plan for rendering a route map.
 * 
 * TASK-271: Ensures detail map, preview, and export all draw exactly the same
 * geometry and bounds. Privacy trim must be applied to the points BEFORE creating this plan.
 */
data class RouteRenderPlan(
    val solidRuns: List<List<Coordinate>>,
    val dottedJoins: List<List<Coordinate>>,
    val pauseMarkers: List<PausedMarkerLocation>,
    val boundsLimits: List<Coordinate>
) {
    data class Coordinate(val latitude: Double, val longitude: Double)
    
    val isEmpty: Boolean get() = solidRuns.isEmpty()

    companion object {
        fun build(points: List<GPSPointEntity>, persona: RidePersona): RouteRenderPlan {
            if (points.isEmpty()) {
                return RouteRenderPlan(emptyList(), emptyList(), emptyList(), emptyList())
            }

            val runs = RideGaps.recordedRuns(points, persona)
            
            val solidRuns = runs.map { run -> 
                run.map { Coordinate(it.latitude, it.longitude) } 
            }
            
            val dottedJoins = mutableListOf<List<Coordinate>>()
            for (i in 0 until runs.size - 1) {
                val lastOfCurrent = runs[i].last()
                val firstOfNext = runs[i + 1].first()
                dottedJoins.add(
                    listOf(
                        Coordinate(lastOfCurrent.latitude, lastOfCurrent.longitude),
                        Coordinate(firstOfNext.latitude, firstOfNext.longitude)
                    )
                )
            }
            
            val markers = explicitPauseMarkerLocations(points)
            
            val boundsLimits = mutableListOf<Coordinate>()
            solidRuns.forEach { run -> boundsLimits.addAll(run) }
            dottedJoins.forEach { join -> boundsLimits.addAll(join) }
            markers.forEach { boundsLimits.add(Coordinate(it.latitude, it.longitude)) }

            return RouteRenderPlan(
                solidRuns = solidRuns,
                dottedJoins = dottedJoins,
                pauseMarkers = markers,
                boundsLimits = boundsLimits
            )
        }
    }
}
