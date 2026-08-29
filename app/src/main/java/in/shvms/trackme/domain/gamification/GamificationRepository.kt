package `in`.shvms.trackme.domain.gamification

import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.RideEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class GamificationRepository(
    private val rideDao: RideDao,
    private val prefs: AppPreferencesManager
) {
    
    val currentLevel: Flow<GamificationLevel> = rideDao.observeAllRides().map { rides ->
        GamificationEngine.calculateLevel(rides)
    }

    val totalActiveMinutes: Flow<Long> = rideDao.observeAllRides().map { rides ->
        GamificationEngine.calculateTotalActiveMinutes(rides)
    }

    val unlockedAchievements: Flow<List<String>> = rideDao.observeAllRides().map { rides ->
        GamificationEngine.getUnlockedAchievements(rides)
    }

    /**
     * Emits a non-null level if a new level was reached but not yet acknowledged.
     */
    val newLevelReveal: Flow<GamificationLevel?> = currentLevel.combine(prefs.lastSeenLevel) { current, lastSeen ->
        if (current.level > lastSeen) current else null
    }

    /**
     * Emits the list of newly unlocked achievements that haven't been acknowledged.
     */
    val newAchievementsReveal: Flow<List<String>> = unlockedAchievements.combine(prefs.lastSeenAchievements) { current, lastSeen ->
        current.filter { !lastSeen.contains(it) }
    }

    suspend fun acknowledgeNewLevel(level: GamificationLevel) {
        prefs.setGamificationLastSeenLevel(level.level)
    }

    suspend fun acknowledgeAchievements(achievements: List<String>) {
        if (achievements.isEmpty()) return
        prefs.addGamificationSeenAchievements(achievements.toSet())
    }

    suspend fun setMaintenanceMode(weeks: Int) {
        // Dummy logic to derive ISO week for simplicity. In full implementation, it uses calendar logic.
        val targetWeek = "2026-W42" // Example: Needs actual Calendar ISO week calculation
        prefs.setGamificationMaintenanceEndWeek(targetWeek)
    }

    suspend fun clearMaintenanceMode() {
        prefs.setGamificationMaintenanceEndWeek(null)
    }
}
