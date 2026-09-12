package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.export.template.AdminLevel
import `in`.shvms.trackme.domain.export.template.AggregateSelection
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.export.template.ExportTemplates
import `in`.shvms.trackme.domain.export.template.Itinerary
import `in`.shvms.trackme.domain.export.template.LightPhase
import `in`.shvms.trackme.domain.export.template.PlaceParts
import `in`.shvms.trackme.domain.export.template.RegionRole
import `in`.shvms.trackme.domain.export.template.SelectionLeg
import `in`.shvms.trackme.domain.export.template.SelectionShape
import `in`.shvms.trackme.domain.export.template.TemplateContent
import `in`.shvms.trackme.domain.export.template.TemplateScope
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.domain.UnitFormatter
import java.util.Locale

/**
 * SCOPE_1.8.9 Part 2 — turning a *selection* into something a template can draw.
 *
 * The single-ride twin of this is `ExportTemplateContent`, and the division is the same: this is
 * the one place that decides what an aggregate template *says*, so the renderer never formats and
 * never reaches for `AppStrings`.
 */
internal object ExportTemplateAggregate {

    /**
     * The legs of a selection, without geocoding.
     *
     * Shape detection needs only coordinates, so it must not wait on the network — a rider offline
     * still gets the right templates offered, they just carry no place names.
     */
    fun legs(routes: List<ComparisonRoute>): List<SelectionLeg> = routes.mapNotNull { route ->
        val start = route.points.firstOrNull() ?: return@mapNotNull null
        val finish = route.points.last()
        SelectionLeg(
            startLatitude = start.latitude, startLongitude = start.longitude,
            finishLatitude = finish.latitude, finishLongitude = finish.longitude,
            distanceMeters = route.ride.ride.postRideCalculation?.distance ?: 0.0,
            movingMillis = route.ride.ride.dashboardActiveDurationMillis,
        )
    }

    /**
     * The same legs with their ends resolved to places.
     *
     * Two lookups per leg, on the trimmed ends only — the same granularity Part 1 ships, so an
     * aggregate export can never disclose more finely than a single-ride one. Failures are not
     * fatal: a leg whose geocode came back empty simply contributes no place, and the itinerary
     * renders with a gap rather than refusing.
     */
    suspend fun legsWithPlaces(
        routes: List<ComparisonRoute>,
        geocode: suspend (Double, Double) -> PlaceParts?,
    ): List<SelectionLeg> = legs(routes).map { leg ->
        leg.copy(
            startPlace = runCatching { geocode(leg.startLatitude, leg.startLongitude) }.getOrNull(),
            finishPlace = runCatching { geocode(leg.finishLatitude, leg.finishLongitude) }.getOrNull(),
        )
    }

    /**
     * Which aggregate templates this selection can honestly fill.
     *
     * The Itinerary appears only for a TOUR: it draws a sequence, and a selection that is not a
     * journey has none to draw. Offering it anyway and rendering an empty frame would be the
     * failure §9.3 spent its whole argument avoiding.
     */
    fun available(legs: List<SelectionLeg>): List<ExportTemplateId> {
        val shape = AggregateSelection.shape(legs)
        return ExportTemplates.all
            .filter { it.scope == TemplateScope.AGGREGATE || it.scope == TemplateScope.BOTH }
            .map { it.id }
            .filter { id ->
                when (id) {
                    ExportTemplateId.ITINERARY -> shape == SelectionShape.TOUR
                    else -> true
                }
            }
    }

    /** "2 states · 5 districts", or null when nothing was geocoded. */
    fun coverageLine(legs: List<SelectionLeg>, strings: AppStrings, locale: Locale): String? {
        val states = AggregateSelection.regions(legs, AdminLevel.STATE)
        val districts = AggregateSelection.regions(legs, AdminLevel.DISTRICT)
        if (states.isEmpty() && districts.isEmpty()) return null
        return String.format(locale, strings.itineraryCoverage, states.size, districts.size)
    }

    fun build(
        routes: List<ComparisonRoute>,
        legs: List<SelectionLeg>,
        strings: AppStrings,
        imperial: Boolean,
        locale: Locale,
        dateLine: String,
        link: String?,
    ): TemplateContent {
        val itinerary: Itinerary? = AggregateSelection.itinerary(legs)
        val totalMeters = legs.sumOf { it.distanceMeters }
        // The same formatter the single-ride hero uses: an aggregate total must not read in a
        // different precision from the figure it is the sum of.
        return TemplateContent(
            runs = emptyList(),
            joins = emptyList(),
            runIntensities = null,
            heroValue = UnitFormatter.rideDistanceValue(totalMeters, imperial, locale),
            heroUnit = UnitFormatter.distanceUnitLabel(imperial),
            heroUnitLong = (if (imperial) strings.templateUnitMiles else strings.templateUnitKilometres).uppercase(locale),
            figures = emptyList(),
            dateLine = dateLine,
            placeLine = null,
            link = link,
            elevation = null,
            elevationLabel = null,
            splits = emptyList(),
            splitsLabel = null,
            fastestSegment = null,
            fastestLabel = null,
            award = null,
            light = LightPhase.DAY,
            lightLine = null,
            itinerary = itinerary,
            regions = AggregateSelection.regions(legs, AdminLevel.DISTRICT),
            coverageLine = coverageLine(legs, strings, locale),
        )
    }
}
