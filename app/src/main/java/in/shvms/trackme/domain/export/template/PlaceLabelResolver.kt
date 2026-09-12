package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.export.trimGpsPointsForExport

/**
 * SCOPE_1.8.9 §7 — names for the route's two ends, resolved **once**, and only after the user turned
 * the place reference on.
 *
 * Reverse geocoding sends two coordinates to the platform's geocoder (Google on Android, Apple on
 * iOS). That is a new data flow for an app whose whole position is privacy, so it happens for nobody
 * who did not ask for place names, and it is never on the export's critical path: the result is
 * cached on the ride and the export reads the cache.
 *
 * The coordinates asked about are always the **trimmed** ends — 200 m in from the true start and
 * finish, whatever the privacy toggle says — so the lookup itself never involves the front door, and
 * [PlaceLabelPolicy] keeps the answer to neighbourhood-or-coarser.
 */
internal class PlaceLabelResolver(
    private val geocode: suspend (latitude: Double, longitude: Double) -> PlaceParts?,
) {
    suspend fun resolve(points: List<GPSPointEntity>): Pair<String?, String?> {
        val (start, finish) = parts(points)
        return start?.let(PlaceLabelPolicy::label) to finish?.let(PlaceLabelPolicy::label)
    }

    /**
     * The same lookup, keeping the administrative components rather than flattening to one name.
     *
     * Part 1 needed a label; Part 2's coverage counts need the district and state *behind* it, and
     * `label` throws those away by design — it picks the finest name and discards the rest.
     */
    suspend fun parts(points: List<GPSPointEntity>): Pair<PlaceParts?, PlaceParts?> {
        val trimmed = trimGpsPointsForExport(points, AppConfig.PRIVACY_TRIM_METERS)
        val start = trimmed.firstOrNull() ?: return null to null
        val finish = trimmed.last()
        return lookup(start) to lookup(finish)
    }

    private suspend fun lookup(point: GPSPointEntity): PlaceParts? =
        runCatching { geocode(point.latitude, point.longitude) }.getOrNull()

}
