package `in`.shvms.trackme.domain.home

import `in`.shvms.trackme.data.local.dao.HomeDashboardRideProjection
import `in`.shvms.trackme.domain.model.RidePersona
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import `in`.shvms.trackme.data.local.entity.RideSource

object HomeDashboardSelector {
    private const val CHART_WEEKS = 8
    private const val INSIGHT_ACTIVE_WEEKS = 8
    private const val STABLE_DEADBAND_PERCENT = 10.0

    fun select(
        rides: List<HomeDashboardRideProjection>,
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): HomeDashboardSummary {
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val currentWeekStart = weekStart(today)
        val sorted = rides.sortedWith(
            compareByDescending<HomeDashboardRideProjection> { it.startedAtEpochMillis }
                .thenByDescending { it.localId }
        )
        val grouped = sorted.groupBy { weekStart(dateOf(it, zoneId)) }
        val chartBuckets = (CHART_WEEKS - 1 downTo 0).map { weeksAgo ->
            val start = currentWeekStart.minusWeeks(weeksAgo.toLong())
            weeklyBucket(start, grouped[start].orEmpty())
        }
        val currentWeek = chartBuckets.last()
        val activeWeekStarts = grouped.keys.sortedDescending()
        val recentActiveStarts = activeWeekStarts.take(INSIGHT_ACTIVE_WEEKS).toSet()
        val recentPersonaRides = sorted.filter {
            weekStart(dateOf(it, zoneId)) in recentActiveStarts
        }
        val personaCounts = RidePersona.entries.mapNotNull { persona ->
            val count = recentPersonaRides.count { personaOf(it.personaRaw) == persona }
            count.takeIf { it > 0 }?.let { PersonaCount(persona, it) }
        }.sortedWith(
            compareByDescending<PersonaCount> { it.count }
                .thenBy { RidePersona.entries.indexOf(it.persona) }
        )
        val earned = sorted.filter { RideSource.earnsProgress(it.sourceRaw) }
        val latest = sorted.firstOrNull()?.toRecent()
        return HomeDashboardSummary(
            currentWeek = currentWeek,
            lifetimeActivityCount = sorted.size,
            lifetimeDistanceMeters = sorted.sumOf { it.distanceMeters },
            lifetimeActiveDurationMillis = sorted.sumOf { it.activeDurationMillis },
            gamificationActivityCount = earned.size,
            gamificationActiveDurationMillis = earned.sumOf { it.activeDurationMillis },
            displayStreakWeeks = displayStreak(activeWeekStarts, currentWeekStart),
            latestActivity = latest,
            weeklyBuckets = chartBuckets,
            personaCounts = personaCounts,
            insight = selectInsight(
                rides = sorted,
                grouped = grouped,
                activeWeekStarts = activeWeekStarts,
                currentWeekStart = currentWeekStart,
                today = today,
                zoneId = zoneId,
            ),
        )
    }

    private fun selectInsight(
        rides: List<HomeDashboardRideProjection>,
        grouped: Map<LocalDate, List<HomeDashboardRideProjection>>,
        activeWeekStarts: List<LocalDate>,
        currentWeekStart: LocalDate,
        today: LocalDate,
        zoneId: ZoneId,
    ): HomeInsight? {
        if (rides.size < 3) return null
        returnAfterInactivity(rides, zoneId)?.let { return it }
        previousActiveWeekComparison(grouped, activeWeekStarts, currentWeekStart, today, zoneId)
            ?.let { return it }
        fourWeekComparison(grouped, activeWeekStarts, currentWeekStart)?.let { return it }
        return dominantPersona(rides, activeWeekStarts, zoneId)
    }

    private fun returnAfterInactivity(
        rides: List<HomeDashboardRideProjection>,
        zoneId: ZoneId,
    ): HomeInsight.Return? {
        val latestDate = dateOf(rides[0], zoneId)
        val previousDate = dateOf(rides[1], zoneId)
        val inactiveDays = java.time.temporal.ChronoUnit.DAYS.between(previousDate, latestDate)
        return if (inactiveDays >= 14) {
            HomeInsight.Return(personaOf(rides[0].personaRaw), inactiveDays)
        } else null
    }

    private fun previousActiveWeekComparison(
        grouped: Map<LocalDate, List<HomeDashboardRideProjection>>,
        activeWeekStarts: List<LocalDate>,
        currentWeekStart: LocalDate,
        today: LocalDate,
        zoneId: ZoneId,
    ): HomeInsight.PeriodComparison? {
        val current = grouped[currentWeekStart].orEmpty()
        if (current.isEmpty()) return null
        val comparisonStart = activeWeekStarts.firstOrNull {
            it < currentWeekStart && java.time.temporal.ChronoUnit.DAYS.between(it, currentWeekStart) <= 28
        } ?: return null
        val elapsedDay = java.time.temporal.ChronoUnit.DAYS.between(currentWeekStart, today)
        val comparisonEndInclusive = comparisonStart.plusDays(elapsedDay)
        val comparison = grouped[comparisonStart].orEmpty().filter {
            !dateOf(it, zoneId).isAfter(comparisonEndInclusive)
        }
        return comparison(
            currentValue = current.sumOf { it.distanceMeters },
            comparisonValue = comparison.sumOf { it.distanceMeters },
            current = InsightPeriod(currentWeekStart.toEpochDay(), today.toEpochDay()),
            previous = InsightPeriod(comparisonStart.toEpochDay(), comparisonEndInclusive.toEpochDay()),
        )
    }

    private fun fourWeekComparison(
        grouped: Map<LocalDate, List<HomeDashboardRideProjection>>,
        activeWeekStarts: List<LocalDate>,
        currentWeekStart: LocalDate,
    ): HomeInsight.PeriodComparison? {
        val completedActive = activeWeekStarts.filter { it < currentWeekStart }
        if (completedActive.size < 8) return null
        val currentWeeks = completedActive.take(4)
        val precedingWeeks = completedActive.drop(4).take(4)
        val currentValue = currentWeeks.sumOf { week -> grouped[week].orEmpty().sumOf { it.distanceMeters } } / 4.0
        val previousValue = precedingWeeks.sumOf { week -> grouped[week].orEmpty().sumOf { it.distanceMeters } } / 4.0
        return comparison(
            currentValue = currentValue,
            comparisonValue = previousValue,
            current = InsightPeriod(currentWeeks.last().toEpochDay(), currentWeeks.first().plusDays(6).toEpochDay()),
            previous = InsightPeriod(precedingWeeks.last().toEpochDay(), precedingWeeks.first().plusDays(6).toEpochDay()),
        )
    }

    private fun dominantPersona(
        rides: List<HomeDashboardRideProjection>,
        activeWeekStarts: List<LocalDate>,
        zoneId: ZoneId,
    ): HomeInsight.DominantPersona? {
        val window = activeWeekStarts.take(INSIGHT_ACTIVE_WEEKS)
        if (window.isEmpty()) return null
        val candidates = rides.filter { weekStart(dateOf(it, zoneId)) in window }
        if (candidates.size < 3) return null
        val counts = candidates.groupingBy { personaOf(it.personaRaw) }.eachCount()
        val max = counts.values.maxOrNull() ?: return null
        val leaders = counts.filterValues { it == max }.keys
        if (leaders.size != 1) return null
        return HomeInsight.DominantPersona(
            persona = leaders.single(),
            personaCount = max,
            totalCount = candidates.size,
            windowStartEpochDay = window.last().toEpochDay(),
            windowEndEpochDay = window.first().plusDays(6).toEpochDay(),
        )
    }

    private fun comparison(
        currentValue: Double,
        comparisonValue: Double,
        current: InsightPeriod,
        previous: InsightPeriod,
    ): HomeInsight.PeriodComparison? {
        if (comparisonValue <= 0.0) return null
        val delta = ((currentValue - comparisonValue) / comparisonValue) * 100.0
        val direction = when {
            abs(delta) < STABLE_DEADBAND_PERCENT -> InsightDirection.STABLE
            delta > 0 -> InsightDirection.HIGHER
            else -> InsightDirection.LOWER
        }
        return HomeInsight.PeriodComparison(
            metric = InsightMetric.DISTANCE,
            direction = direction,
            currentPeriod = current,
            comparisonPeriod = previous,
            currentValue = currentValue,
            comparisonValue = comparisonValue,
            percentDelta = delta,
        )
    }

    private fun displayStreak(activeWeeks: List<LocalDate>, currentWeek: LocalDate): Int {
        if (activeWeeks.isEmpty()) return 0
        val newest = activeWeeks.first()
        if (newest != currentWeek && newest != currentWeek.minusWeeks(1)) return 0
        var streak = 1
        var cursor = newest
        for (week in activeWeeks.drop(1)) {
            if (week == cursor.minusWeeks(1)) {
                streak++
                cursor = week
            } else break
        }
        return streak
    }

    private fun weeklyBucket(
        start: LocalDate,
        rides: List<HomeDashboardRideProjection>,
    ) = WeeklyBucket(
        weekStartEpochDay = start.toEpochDay(),
        activityCount = rides.size,
        distanceMeters = rides.sumOf { it.distanceMeters },
        activeDurationMillis = rides.sumOf { it.activeDurationMillis },
        distanceByPersona = RidePersona.entries.mapNotNull { persona ->
            val matching = rides.filter { personaOf(it.personaRaw) == persona }
            matching.takeIf { it.isNotEmpty() }?.let {
                PersonaDistance(persona, it.sumOf(HomeDashboardRideProjection::distanceMeters))
            }
        },
    )

    private fun HomeDashboardRideProjection.toRecent() = RecentActivity(
        localId = localId,
        persona = personaOf(personaRaw),
        startedAtEpochMillis = startedAtEpochMillis,
        distanceMeters = distanceMeters,
        activeDurationMillis = activeDurationMillis,
        avgSpeedMps = avgSpeedMps,
        hasRoute = hasRoute,
    )

    private fun personaOf(raw: String): RidePersona =
        runCatching { RidePersona.valueOf(raw) }.getOrDefault(RidePersona.AUTO)

    private fun dateOf(ride: HomeDashboardRideProjection, fallbackZoneId: ZoneId): LocalDate {
        val rideZone = ride.startZoneId?.let { stored ->
            runCatching { ZoneId.of(stored) }.getOrNull()
        } ?: fallbackZoneId
        return Instant.ofEpochMilli(ride.startedAtEpochMillis).atZone(rideZone).toLocalDate()
    }

    private fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
