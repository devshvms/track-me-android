package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.export.artifactDeepLink
import `in`.shvms.trackme.domain.export.template.AwardEligibility
import `in`.shvms.trackme.domain.export.template.AwardFacts
import `in`.shvms.trackme.domain.export.template.AwardText
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.export.template.ExportTemplates
import `in`.shvms.trackme.domain.export.template.FigureRole
import `in`.shvms.trackme.domain.export.template.LightPhase
import `in`.shvms.trackme.domain.export.template.PlaceLabelPolicy
import `in`.shvms.trackme.domain.export.template.PlaceReference
import `in`.shvms.trackme.domain.export.template.SolarPhase
import `in`.shvms.trackme.domain.export.template.TemplateContent
import `in`.shvms.trackme.domain.export.template.TemplateFigure
import `in`.shvms.trackme.domain.export.template.TemplateScope
import `in`.shvms.trackme.domain.export.template.elevationProfile
import `in`.shvms.trackme.domain.export.template.fastestSplitSegment
import `in`.shvms.trackme.domain.export.template.paceIntensities
import `in`.shvms.trackme.domain.export.template.splitBars
import `in`.shvms.trackme.domain.export.trimGpsPointsForExport
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.model.usesPace
import `in`.shvms.trackme.domain.processor.RideGaps
import `in`.shvms.trackme.domain.processor.RouteRenderPlan
import `in`.shvms.trackme.domain.rideSplits
import `in`.shvms.trackme.domain.stats.RevealKind
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale

/** The Templates tab's own choices — everything that shapes the picture beyond the ride itself. */
internal data class TemplateOptions(
    val privacyTrim: Boolean = true,
    val place: PlaceReference = PlaceReference.OFF,
    /** Null means the light the ride actually happened in. */
    val lightOverride: LightPhase? = null,
)

/**
 * Turns a ride into [TemplateContent] — the one place that decides what a template *says*.
 *
 * It formats with the ride detail screen's own helpers and arguments, because the parity target for
 * a shared artifact is the screen the share action lives on (EXPORT_SHARE_CONTRACTS §1–2): the
 * distance through `UnitFormatter`'s ride precision, the duration through `displayExportDuration`
 * (active time, as the History card), the elevation through `UnitFormatter.elevation`.
 */
internal object ExportTemplateContent {

    /** The templates this ride can honestly fill, in strip order — single-ride surfaces only (§9.3). */
    fun available(ride: RideWithPoints, imperial: Boolean): List<ExportTemplateId> {
        val hasAward = awardFacts(ride) != null
        val hasInstrumentData = rideSplits(ride.points, imperial).count { !it.isPartial } >= 2 ||
            elevationProfile(ride.points, ride.ride.postRideCalculation?.elevationGainMeters) != null
        return ExportTemplates.all
            .filter { it.scope != TemplateScope.AGGREGATE }
            .map { it.id }
            .filter { id ->
                when (id) {
                    ExportTemplateId.AWARD -> hasAward
                    ExportTemplateId.INSTRUMENT -> hasInstrumentData
                    else -> true
                }
            }
    }

    fun build(
        ride: RideWithPoints,
        options: TemplateOptions,
        strings: AppStrings,
        imperial: Boolean,
        formatDate: (Long) -> String,
        formatTime: (Long) -> String,
        locale: Locale = Locale.getDefault(),
    ): TemplateContent {
        val entity = ride.ride
        val persona = runCatching { RidePersona.valueOf(entity.persona) }.getOrDefault(RidePersona.AUTO)
        val points = ride.points
        val drawn = if (options.privacyTrim) trimGpsPointsForExport(points, AppConfig.PRIVACY_TRIM_METERS) else points
        val plan = RouteRenderPlan.build(drawn, persona)
        // The plan owns the geometry (TASK-271). Pace is read from the same recorded runs the plan is
        // built from; if the two ever disagree in shape, the line falls back to one colour rather
        // than colouring the wrong stretch of road.
        val recorded = RideGaps.recordedRuns(drawn, persona)
        val intensities = recorded
            .takeIf { runs -> runs.size == plan.solidRuns.size && runs.indices.all { runs[it].size == plan.solidRuns[it].size } }
            ?.map(::paceIntensities)

        val calc = entity.postRideCalculation
        val elevationGain = calc?.elevationGainMeters
        val elevationText = elevationGain?.let { UnitFormatter.elevation(it, imperial, locale) }
        val averageMps = (calc?.avgSpeed ?: 0f).toDouble()
        val figures = buildList {
            displayExportDuration(entity)?.let {
                add(TemplateFigure(FigureRole.DURATION, strings.duration.uppercase(locale), it))
            }
            elevationText?.let {
                add(TemplateFigure(FigureRole.ELEVATION, strings.templateElevation.uppercase(locale), it))
            }
            if (averageMps > 0.0) {
                add(
                    if (persona.usesPace) {
                        TemplateFigure(FigureRole.EFFORT, strings.avgPace.uppercase(locale), UnitFormatter.pace(averageMps, imperial, locale))
                    } else {
                        TemplateFigure(FigureRole.EFFORT, strings.avgSpeed.uppercase(locale), UnitFormatter.speed(averageMps, imperial, locale))
                    }
                )
            }
        }

        val start = entity.startTime
        val time = formatTime(start)
        // Coarse position for the light only — read, never drawn, and never leaves the device.
        val light = options.lightOverride
            ?: points.firstOrNull()?.let { SolarPhase.phase(it.latitude, it.longitude, start) }
            ?: LightPhase.DAY

        return TemplateContent(
            runs = plan.solidRuns,
            joins = plan.dottedJoins,
            runIntensities = intensities,
            heroValue = UnitFormatter.rideDistanceValue(calc?.distance ?: 0.0, imperial, locale),
            heroUnit = UnitFormatter.distanceUnitLabel(imperial),
            heroUnitLong = (if (imperial) strings.templateUnitMiles else strings.templateUnitKilometres).uppercase(locale),
            figures = figures,
            dateLine = listOf(formatDate(start), time, strings.personaLabel(persona)).joinToString(" · ").uppercase(locale),
            placeLine = PlaceLabelPolicy.line(options.place, entity.placeLabelStart, entity.placeLabelEnd),
            link = artifactDeepLink(entity),
            elevation = elevationProfile(points, elevationGain),
            elevationLabel = elevationText?.let { strings.templateElevationLine.format(it).uppercase(locale) },
            splits = splitBars(rideSplits(points, imperial)),
            splitsLabel = strings.templateSplitsLine.format("min${UnitFormatter.paceUnitLabel(imperial)}").uppercase(locale),
            fastestSegment = fastestSplitSegment(points, imperial, drawn),
            fastestLabel = (if (imperial) strings.templateFastestMi else strings.templateFastestKm).uppercase(locale),
            award = awardFacts(ride)?.let { awardText(it, strings, imperial, locale) },
            light = light,
            lightLine = "${lightLabel(light, strings).uppercase(locale)} · $time",
        )
    }

    /** What the ride earned and still honestly supports — null for everything else (§6.4). */
    fun awardFacts(ride: RideWithPoints): AwardFacts? {
        val entity = ride.ride
        return AwardEligibility.facts(
            reveal = AwardEligibility.parse(entity.revealKind, entity.revealPreviousBest, entity.revealMilestoneCount),
            source = entity.source,
            storedDistanceMeters = entity.postRideCalculation?.distance ?: 0.0,
            storedActiveMillis = displayActiveDurationMillis(entity),
        )
    }

    fun lightLabel(phase: LightPhase, strings: AppStrings): String = when (phase) {
        LightPhase.DAWN -> strings.lightDawn
        LightPhase.GOLDEN_MORNING, LightPhase.GOLDEN_EVENING -> strings.lightGolden
        LightPhase.DAY -> strings.lightDay
        LightPhase.DUSK -> strings.lightDusk
        LightPhase.NIGHT -> strings.lightNight
    }

    private fun awardText(facts: AwardFacts, strings: AppStrings, imperial: Boolean, locale: Locale): AwardText? = when (facts.kind) {
        RevealKind.DISTANCE_PR -> AwardText(
            facts, strings.awardBadgePr, strings.distance.uppercase(locale), strings.awardHeadlineDistance,
            facts.previousBest?.let { strings.awardPreviousBest.format(UnitFormatter.rideDistance(it, imperial, locale)) },
        )
        RevealKind.DURATION_PR -> AwardText(
            facts, strings.awardBadgePr, strings.duration.uppercase(locale), strings.awardHeadlineDuration,
            facts.previousBest?.let { strings.awardPreviousBest.format(compactDuration(it.toLong())) },
        )
        RevealKind.FIRST_RIDE -> AwardText(
            facts, strings.awardBadgeFirst, strings.awardCaptionRide.uppercase(locale), strings.awardHeadlineFirst, null,
        )
        RevealKind.MILESTONE -> facts.milestoneCount?.let { count ->
            AwardText(facts, count.toString(), strings.awardCaptionRides.uppercase(locale), strings.awardHeadlineMilestone.format(count), null)
        }
        // Unreachable — AwardEligibility never returns facts for DEFAULT — but a share sheet is no
        // place to find that out with a crash.
        RevealKind.DEFAULT -> null
    }
}
