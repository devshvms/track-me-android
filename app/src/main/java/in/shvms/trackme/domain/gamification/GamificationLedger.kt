package `in`.shvms.trackme.domain.gamification

/**
 * TASK-276: when each level was reached, and what the rider was doing to reach it.
 *
 * **Derived, never stored.** An "achieved on" date looks like something to persist at unlock time,
 * and persisting it would be wrong: `GAMIFICATION.md` §7 requires that deleting a ride recomputes
 * progress, and a stored date would survive the deletion of the very ride that earned it. Replaying
 * the qualifying rides in order costs one pass and cannot disagree with the ride table, because it
 * *is* the ride table.
 *
 * The consequence is deliberate and worth stating: delete rides until you fall below a threshold and
 * re-cross it later, and the displayed date moves. That is the truthful answer, and it is the same
 * behaviour §7 already requires of badges.
 */
object GamificationLedger {

    /** One qualifying, recorded ride reduced to what this derivation needs. */
    data class RideFact(
        val atEpochMillis: Long,
        val personaRaw: String,
        val activeDurationMillis: Long,
        val distanceMeters: Double,
    )

    /** What one persona contributed up to the moment a level was reached. */
    data class PersonaContribution(
        val personaRaw: String,
        val activeDurationMillis: Long,
        val distanceMeters: Double,
    )

    data class LevelAchievement(
        val levelId: String,
        /** Null while the level is still ahead, and for level 1 before the first ride. */
        val achievedAtEpochMillis: Long?,
        /** Everything that counted toward it, largest contribution first. Empty until achieved. */
        val personaSplit: List<PersonaContribution>,
    )

    /**
     * Returns one entry per level, in level order.
     *
     * Level 1 is the joining date rather than a threshold crossing: its threshold is zero, so every
     * rider satisfies it before they have done anything, and "reached at 0 minutes" says nothing. The
     * first recorded ride is the honest answer to when the journey started.
     */
    fun derive(rides: List<RideFact>): List<LevelAchievement> {
        val ordered = rides.sortedBy { it.atEpochMillis }
        val result = mutableListOf<LevelAchievement>()

        // Minutes accumulate in milliseconds and divide once, matching GamificationEngine. Dividing
        // per ride and summing would round each ride down and drift below the engine's own total.
        var millis = 0L
        val byPersona = linkedMapOf<String, PersonaContribution>()
        var index = 0

        GamificationEngine.levels.forEachIndexed { levelIndex, level ->
            if (levelIndex == 0) {
                val first = ordered.firstOrNull()
                if (first != null) {
                    millis += first.activeDurationMillis
                    accumulate(byPersona, first)
                    index = 1
                    result += LevelAchievement(level.id, first.atEpochMillis, snapshot(byPersona))
                } else {
                    result += LevelAchievement(level.id, null, emptyList())
                }
                return@forEachIndexed
            }

            while (millis / 60_000L < level.thresholdMinutes && index < ordered.size) {
                val ride = ordered[index]
                millis += ride.activeDurationMillis
                accumulate(byPersona, ride)
                index++
            }

            val reached = millis / 60_000L >= level.thresholdMinutes && ordered.isNotEmpty()
            result += if (reached) {
                LevelAchievement(level.id, ordered[index - 1].atEpochMillis, snapshot(byPersona))
            } else {
                LevelAchievement(level.id, null, emptyList())
            }
        }
        return result
    }

    private fun accumulate(into: MutableMap<String, PersonaContribution>, ride: RideFact) {
        val existing = into[ride.personaRaw]
        into[ride.personaRaw] = PersonaContribution(
            personaRaw = ride.personaRaw,
            activeDurationMillis = (existing?.activeDurationMillis ?: 0L) + ride.activeDurationMillis,
            distanceMeters = (existing?.distanceMeters ?: 0.0) + ride.distanceMeters,
        )
    }

    /**
     * Copies the running totals so a later level cannot mutate an earlier level's answer. Ties break
     * on persona name so two personas with identical time do not reorder between reads.
     */
    private fun snapshot(byPersona: Map<String, PersonaContribution>): List<PersonaContribution> =
        byPersona.values.map { it.copy() }.sortedWith(
            compareByDescending<PersonaContribution> { it.activeDurationMillis }
                .thenBy { it.personaRaw }
        )
}
