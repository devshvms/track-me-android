package `in`.shvms.trackme.domain.gamification

import `in`.shvms.trackme.domain.home.HomeDashboardSummary

fun HomeDashboardSummary.toGamificationFacts(): GamificationFacts {
    return GamificationFacts(
        // TASK-275: deliberately the gamification pair, not the dashboard pair. Imported rides
        // appear everywhere else and earn nothing here.
        lifetimeActivityCount = this.gamificationActivityCount,
        lifetimeActiveDurationMillis = this.gamificationActiveDurationMillis,
    )
}
