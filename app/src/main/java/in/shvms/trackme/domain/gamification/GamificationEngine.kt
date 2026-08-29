package in.shvms.trackme.domain.gamification

import `in`.shvms.trackme.data.local.entity.RideEntity

object GamificationEngine {
    
    // Core qualification rule from GAMIFICATION.md SS3
    fun isQualifyingRide(ride: RideEntity): Boolean {
        if (ride.isSample) return false
        if (ride.pendingDelete) return false
        if (ride.dashboardActiveDurationMillis < 5 * 60 * 1000) return false
        
        // qualifiesForStats already checks the distance/movement rules for dashboard
        if (!ride.qualifiesForStats) return false
        
        return true
    }

    fun isQualifyingGroupRide(ride: RideEntity): Boolean {
        if (!isQualifyingRide(ride)) return false
        if (!ride.wasGroupRide) return false
        if ((ride.groupRiderCount ?: 0) <= 1) return false
        return true
    }

    fun calculateTotalActiveMinutes(rides: List<RideEntity>): Long {
        return rides
            .filter { isQualifyingRide(it) }
            .sumOf { it.dashboardActiveDurationMillis } / 60000
    }

    fun calculateLevel(rides: List<RideEntity>): GamificationLevel {
        val minutes = calculateTotalActiveMinutes(rides)
        return GamificationDefinitions.getLevelForMinutes(minutes)
    }

    fun getUnlockedAchievements(rides: List<RideEntity>): List<String> {
        val unlocked = mutableListOf<String>()
        val qualifyingRides = rides.filter { isQualifyingRide(it) }
        
        if (qualifyingRides.isEmpty()) return unlocked

        unlocked.add("First Qualifying Activity")
        
        if (qualifyingRides.size >= 5) {
            unlocked.add("Getting Moving")
        }

        val totalMinutes = calculateTotalActiveMinutes(qualifyingRides)
        if (totalMinutes >= 6000) {
            unlocked.add("Hundred Hours")
        }

        val groupRides = qualifyingRides.filter { isQualifyingGroupRide(it) }
        if (groupRides.isNotEmpty()) {
            unlocked.add("Together")
        }
        if (groupRides.size >= 5) {
            unlocked.add("Social Five")
        }
        if (groupRides.any { (it.groupRiderCount ?: 0) >= 10 }) {
            unlocked.add("Full Crew")
        }
        val groupDistance = groupRides.sumOf { it.postRideCalculation?.distance ?: 0.0 }
        if (groupDistance >= 100000.0) { // 100km
            unlocked.add("Distance Together")
        }
        
        val distinctPersonas = qualifyingRides.map { it.persona }.filter { it != "AUTO" }.distinct()
        if (distinctPersonas.size >= 3) {
            unlocked.add("Multi-Move")
        }

        // More complex ones (Regular Rhythm, Four-Week Rhythm) require time zone grouping
        // Deferring to full implementation.

        return unlocked
    }
}
