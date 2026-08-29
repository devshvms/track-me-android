package `in`.shvms.trackme.domain.gamification

data class GamificationFacts(
    val lifetimeActivityCount: Int,
    val lifetimeActiveDurationMillis: Long,
)

data class GamificationSnapshot(
    val currentLevelId: String,
    val nextLevelDurationThresholdMillis: Long?,
    val unlockedMilestoneIds: List<String>,
)
