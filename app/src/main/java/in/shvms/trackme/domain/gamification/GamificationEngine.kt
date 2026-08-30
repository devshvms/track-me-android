package `in`.shvms.trackme.domain.gamification

object GamificationEngine {

    private val LEVEL_THRESHOLDS = listOf(
        7_200_000L to "level_2",
        36_000_000L to "level_3",
        108_000_000L to "level_4",
        270_000_000L to "level_5",
        540_000_000L to "level_6",
    )

    private val MILESTONES = listOf(
        1 to "milestone_1",
        10 to "milestone_10",
        25 to "milestone_25",
        50 to "milestone_50",
        100 to "milestone_100",
        250 to "milestone_250",
        500 to "milestone_500",
        1000 to "milestone_1000",
    )

    fun deriveSnapshot(facts: GamificationFacts): GamificationSnapshot {
        val duration = facts.lifetimeActiveDurationMillis
        
        var currentLevelId = "level_1"
        var nextThreshold: Long? = LEVEL_THRESHOLDS.first().first

        for (threshold in LEVEL_THRESHOLDS) {
            if (duration >= threshold.first) {
                currentLevelId = threshold.second
                // Find next threshold if available
                val nextIndex = LEVEL_THRESHOLDS.indexOf(threshold) + 1
                nextThreshold = if (nextIndex < LEVEL_THRESHOLDS.size) {
                    LEVEL_THRESHOLDS[nextIndex].first
                } else {
                    null
                }
            } else {
                break
            }
        }

        val unlockedMilestones = MILESTONES
            .filter { facts.lifetimeActivityCount >= it.first }
            .map { it.second }
            .sorted()

        return GamificationSnapshot(
            currentLevelId = currentLevelId,
            nextLevelDurationThresholdMillis = nextThreshold,
            unlockedMilestoneIds = unlockedMilestones,
        )
    }
}
