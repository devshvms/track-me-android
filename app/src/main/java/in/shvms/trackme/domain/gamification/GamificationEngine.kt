package `in`.shvms.trackme.domain.gamification

object GamificationEngine {
    val levels: List<GamificationLevel> = listOf(
        GamificationLevel("level_1", "Starter", 0L),
        GamificationLevel("level_2", "Moving", 120L),
        GamificationLevel("level_3", "Regular", 600L),
        GamificationLevel("level_4", "Explorer", 1_800L),
        GamificationLevel("level_5", "Enduring", 4_500L),
        GamificationLevel("level_6", "Pathfinder", 9_000L),
    )

    val milestones: List<GamificationMilestone> =
        listOf(1, 10, 25, 50, 100, 250, 500, 1_000).map { count ->
            GamificationMilestone("milestone_$count", count)
        }

    fun deriveSnapshot(facts: GamificationFacts): GamificationSnapshot {
        val currentMinutes = facts.lifetimeActiveDurationMillis.coerceAtLeast(0L) / 60_000L
        val activityCount = facts.lifetimeActivityCount.coerceAtLeast(0)
        val currentIndex = levels.indexOfLast { currentMinutes >= it.thresholdMinutes }
            .coerceAtLeast(0)
        val current = levels[currentIndex]
        val next = levels.getOrNull(currentIndex + 1)
        val unlocked = milestones.takeWhile { activityCount >= it.activityCount }
        val progressDenominator = next?.let { it.thresholdMinutes - current.thresholdMinutes } ?: 0L
        val progressNumerator = next?.let {
            (currentMinutes - current.thresholdMinutes).coerceIn(0L, progressDenominator)
        } ?: 0L

        return GamificationSnapshot(
            currentLevelId = current.id,
            currentLevelNameKey = current.nameKey,
            currentMinutes = currentMinutes,
            currentActivityCount = activityCount,
            currentThresholdMinutes = current.thresholdMinutes,
            nextThresholdMinutes = next?.thresholdMinutes,
            progressNumeratorMinutes = progressNumerator,
            progressDenominatorMinutes = progressDenominator,
            latestUnlockedMilestoneId = unlocked.lastOrNull()?.id,
            unlockedMilestoneIds = unlocked.map { it.id },
            unlockedMilestoneCount = unlocked.size,
        )
    }
}
