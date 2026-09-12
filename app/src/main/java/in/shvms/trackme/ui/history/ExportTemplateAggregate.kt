package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.processor.RouteCoordinate
import `in`.shvms.trackme.domain.processor.RouteRenderPlan
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

    /**
     * Where the selection went: "Karnataka · Goa", or "5 regions" when there are too many to name.
     *
     * Naming beats counting, and not only because it reads better — a count has to choose a noun,
     * and the first render of this line said "1 states". Up to [NAMED_REGIONS] the line names them
     * in the order ridden and the grammar problem disappears with the noun; past that the count is
     * always plural, so the fallback is safe in every catalogue.
     *
     * Districts are deliberately absent: the Itinerary already prints one under every stop, and the
     * aggregate Trace has one line, which the regions have the better claim to.
     */
    fun coverageLine(legs: List<SelectionLeg>, strings: AppStrings, locale: Locale): String? {
        val regions = AggregateSelection.regions(legs, AdminLevel.STATE).keys.toList()
        if (regions.isEmpty()) return null
        return if (regions.size <= NAMED_REGIONS) {
            regions.joinToString(" · ")
        } else {
            String.format(locale, strings.itineraryRegions, regions.size)
        }
    }

    /** Three names is roughly the width of the Trace's place line at its design size. */
    private const val NAMED_REGIONS = 3

    /**
     * "3 RIDES · MAR 2026", or "3 RIDES · MAR – APR 2026" when the selection crosses a month.
     *
     * The month is formatted by the caller for the same reason [ExportTemplateContent] takes its
     * date formatters: a `SimpleDateFormat` built here would use the JVM default pattern rather
     * than the device's, and the export's parity target is the screen it was shared from.
     */
    fun dateLine(
        routes: List<ComparisonRoute>,
        strings: AppStrings,
        locale: Locale,
        formatMonth: (Long) -> String,
    ): String {
        val rides = String.format(locale, strings.itineraryRides, routes.size)
        val times = routes.map { it.ride.ride.startTime }.filter { it > 0L }.sorted()
        if (times.isEmpty()) return rides.uppercase(locale)
        val first = formatMonth(times.first())
        val last = formatMonth(times.last())
        val span = if (first == last) first else "$first – $last"
        return "$rides · $span".uppercase(locale)
    }

    /**
     * The selection's geometry, one entry per ride, in the order the strip shows them.
     *
     * Each ride goes through the same [RouteRenderPlan] a single-ride export uses, so a gap in a
     * recording is a dotted join here exactly as it is there — and then the plans are concatenated
     * rather than merged, which is what keeps "run 3 belongs to ride 2" true for the palette.
     */
    private fun geometry(routes: List<ComparisonRoute>): Triple<List<List<RouteCoordinate>>, List<List<RouteCoordinate>>, List<Int>> {
        val runs = mutableListOf<List<RouteCoordinate>>()
        val joins = mutableListOf<List<RouteCoordinate>>()
        val palette = mutableListOf<Int>()
        routes.forEachIndexed { index, route ->
            if (route.points.size < 2) return@forEachIndexed
            val persona = runCatching { RidePersona.valueOf(route.ride.ride.persona) }.getOrDefault(RidePersona.AUTO)
            val plan = RouteRenderPlan.build(route.points, persona)
            val colour = comparisonRouteColors[index % comparisonRouteColors.size]
            plan.solidRuns.forEach { run ->
                runs += run
                palette += colour
            }
            joins += plan.dottedJoins
        }
        return Triple(runs, joins, palette)
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
        val (runs, joins, palette) = geometry(routes)
        val coverage = coverageLine(legs, strings, locale)
        // The same formatter the single-ride hero uses: an aggregate total must not read in a
        // different precision from the figure it is the sum of.
        return TemplateContent(
            runs = runs,
            joins = joins,
            // Pace is a single ride's story. Across a selection the line says which ride it is, and
            // [TemplateContent.runPalette] is the channel that says so.
            runIntensities = null,
            heroValue = UnitFormatter.rideDistanceValue(totalMeters, imperial, locale),
            heroUnit = UnitFormatter.distanceUnitLabel(imperial),
            heroUnitLong = (if (imperial) strings.templateUnitMiles else strings.templateUnitKilometres).uppercase(locale),
            figures = emptyList(),
            dateLine = dateLine,
            // The aggregate Trace has one line of room above its hero, and what a selection has to
            // say there is where it went — the same sentence the Itinerary puts in its footer.
            placeLine = coverage,
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
            coverageLine = coverage,
            runPalette = palette,
        )
    }
}
