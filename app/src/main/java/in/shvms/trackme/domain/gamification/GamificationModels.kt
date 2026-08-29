package in.shvms.trackme.domain.gamification

data class GamificationLevel(
    val level: Int,
    val name: String,
    val requiredActiveMinutes: Long
)

object GamificationDefinitions {
    val CURRENT_VERSION = 1

    val LEVELS = listOf(
        GamificationLevel(1, "Starter", 0),
        GamificationLevel(2, "Moving", 120),
        GamificationLevel(3, "Regular", 600),
        GamificationLevel(4, "Explorer", 1800),
        GamificationLevel(5, "Enduring", 4500),
        GamificationLevel(6, "Pathfinder", 9000)
    ).sortedBy { it.level }

    fun getLevelForMinutes(activeMinutes: Long): GamificationLevel {
        return LEVELS.lastOrNull { activeMinutes >= it.requiredActiveMinutes } ?: LEVELS.first()
    }
}
