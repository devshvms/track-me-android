package `in`.shvms.trackme.domain.home

import `in`.shvms.trackme.domain.model.RidePersona

data class WeeklyBucket(
    val weekStartEpochDay: Long,
    val activityCount: Int,
    val distanceMeters: Double,
    val activeDurationMillis: Long,
)

data class RecentActivity(
    val localId: Long,
    val persona: RidePersona,
    val startedAtEpochMillis: Long,
    val distanceMeters: Double,
    val activeDurationMillis: Long,
    val avgSpeedMps: Double,
    val hasRoute: Boolean,
)

data class PersonaCount(
    val persona: RidePersona,
    val count: Int,
)

enum class InsightMetric { DISTANCE, ACTIVE_DURATION }
enum class InsightDirection { LOWER, STABLE, HIGHER }

data class InsightPeriod(
    val startEpochDay: Long,
    val endEpochDay: Long,
)

sealed interface HomeInsight {
    val analyticsValue: String

    data class PersonalBest(
        val persona: RidePersona,
        val metric: InsightMetric,
        val currentValue: Double,
        val previousBestValue: Double,
    ) : HomeInsight {
        override val analyticsValue: String = "personal_best"
    }

    data class Return(
        val persona: RidePersona,
        val inactiveDays: Long,
    ) : HomeInsight {
        override val analyticsValue: String = "return"
    }

    data class PeriodComparison(
        val metric: InsightMetric,
        val direction: InsightDirection,
        val currentPeriod: InsightPeriod,
        val comparisonPeriod: InsightPeriod,
        val currentValue: Double,
        val comparisonValue: Double,
        val percentDelta: Double,
    ) : HomeInsight {
        override val analyticsValue: String = "period_comparison"
    }

    data class DominantPersona(
        val persona: RidePersona,
        val personaCount: Int,
        val totalCount: Int,
        val windowStartEpochDay: Long,
        val windowEndEpochDay: Long,
    ) : HomeInsight {
        override val analyticsValue: String = "dominant_persona"
    }
}

data class HomeDashboardSummary(
    val currentWeek: WeeklyBucket,
    val lifetimeActivityCount: Int,
    val lifetimeDistanceMeters: Double,
    val lifetimeActiveDurationMillis: Long,
    val displayStreakWeeks: Int,
    val latestActivity: RecentActivity?,
    /** Oldest to newest, including zero weeks, so the four-bar chart never shifts meaning. */
    val weeklyBuckets: List<WeeklyBucket>,
    val personaCounts: List<PersonaCount>,
    val insight: HomeInsight?,
) {
    val historyBucket: String = when (lifetimeActivityCount) {
        0 -> "empty"
        1, 2 -> "early"
        else -> "established"
    }

    companion object {
        fun empty(currentWeekEpochDay: Long) = HomeDashboardSummary(
            currentWeek = WeeklyBucket(currentWeekEpochDay, 0, 0.0, 0L),
            lifetimeActivityCount = 0,
            lifetimeDistanceMeters = 0.0,
            lifetimeActiveDurationMillis = 0L,
            displayStreakWeeks = 0,
            latestActivity = null,
            weeklyBuckets = emptyList(),
            personaCounts = emptyList(),
            insight = null,
        )
    }
}

enum class HomePresentationMode {
    IDLE_DASHBOARD,
    ACTIVE_TRACKING_MAP,
    EXPLICIT_GROUP_MAP,
}

/** Pure precedence contract; process-restored and interrupted recordings are all non-IDLE. */
object HomePresentationModePolicy {
    fun resolve(isTrackingIdle: Boolean, explicitGroupMap: Boolean): HomePresentationMode = when {
        !isTrackingIdle -> HomePresentationMode.ACTIVE_TRACKING_MAP
        explicitGroupMap -> HomePresentationMode.EXPLICIT_GROUP_MAP
        else -> HomePresentationMode.IDLE_DASHBOARD
    }
}
