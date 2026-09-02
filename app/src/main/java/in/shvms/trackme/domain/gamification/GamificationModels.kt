package `in`.shvms.trackme.domain.gamification

data class GamificationFacts(
    val lifetimeActivityCount: Int,
    val lifetimeActiveDurationMillis: Long,
)

data class GamificationLevel(
    val id: String,
    val nameKey: String,
    val thresholdMinutes: Long,
)

data class GamificationMilestone(
    val id: String,
    val activityCount: Int,
)

data class GamificationSnapshot(
    val currentLevelId: String,
    val currentLevelNameKey: String,
    val currentMinutes: Long,
    /** TASK-276: the real qualifying activity count, so the rail can state it rather than
     *  showing the last milestone's threshold and calling it "recorded". */
    val currentActivityCount: Int,
    val currentThresholdMinutes: Long,
    val nextThresholdMinutes: Long?,
    val progressNumeratorMinutes: Long,
    val progressDenominatorMinutes: Long,
    val latestUnlockedMilestoneId: String?,
    val unlockedMilestoneIds: List<String>,
    val unlockedMilestoneCount: Int,
)
