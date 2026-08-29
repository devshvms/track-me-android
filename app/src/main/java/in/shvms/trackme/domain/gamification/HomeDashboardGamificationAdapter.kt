package `in`.shvms.trackme.domain.gamification

import `in`.shvms.trackme.domain.home.HomeDashboardSummary

fun HomeDashboardSummary.toGamificationFacts(): GamificationFacts {
    return GamificationFacts(
        lifetimeActivityCount = this.lifetimeActivityCount,
        lifetimeActiveDurationMillis = this.lifetimeActiveDurationMillis,
    )
}
