package `in`.shvms.trackme.data.local

import android.content.Context
import android.content.SharedPreferences
import `in`.shvms.trackme.domain.stats.GoodRideSummary
import `in`.shvms.trackme.domain.stats.RideStats
import `in`.shvms.trackme.domain.stats.RideStatsReducer
import `in`.shvms.trackme.domain.stats.RideStatsTransition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.ZoneId

/**
 * Android persistence adapter for the shared A1 stats layer.
 *
 * Responsibilities (only these):
 *  - Serialize/deserialize the versioned [RideStats] blob to a dedicated SharedPreferences
 *    file, as ONE record (never many independent keys).
 *  - Serialize all mutations through a [Mutex] so an overlap between normal finalization and
 *    orphaned-ride recovery can never lose an increment.
 *  - Persist FIRST, then expose the [RideStatsTransition] to callers. Telemetry is emitted by
 *    the feature layer (B1–B4), never here — the store stays free of analytics/UI concerns.
 *  - Fail closed: an unreadable/corrupt/older blob resets to a fresh versioned store rather
 *    than crashing ride saving.
 *
 * Stored separately from `trackme_prefs` so retention state is easy to reason about and clear.
 */
class RideStatsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    private val _stats = MutableStateFlow(load())

    /** Latest aggregate snapshot for UI (B2 recap, B3 streak badge, etc.). */
    val stats: StateFlow<RideStats> = _stats.asStateFlow()

    /**
     * Fold one good ride into the store and return the resulting [RideStatsTransition].
     *
     * Idempotent by ride ID: recording the same ride twice (retry / recovery after a normal
     * finalize) persists nothing and returns a transition with `alreadyProcessed = true`.
     *
     * @param zone injected for testability; defaults to the device zone.
     */
    suspend fun recordGoodRide(
        summary: GoodRideSummary,
        zone: ZoneId = ZoneId.systemDefault()
    ): RideStatsTransition = mutex.withLock {
        val old = _stats.value
        val (new, transition) = RideStatsReducer.reduce(old, summary, zone)
        if (!transition.alreadyProcessed) {
            persist(new)
            _stats.value = new
        }
        transition
    }

    // --- persistence ---------------------------------------------------------------------

    private fun load(): RideStats {
        val raw = prefs.getString(KEY_BLOB, null) ?: return RideStats()
        return try {
            val json = JSONObject(raw)
            val version = json.optInt("schemaVersion", 0)
            // Only v1 is known; anything else fails closed to a fresh store.
            if (version != RideStats.CURRENT_SCHEMA_VERSION) return RideStats()
            RideStats(
                schemaVersion = version,
                totalRides = json.optInt("totalRides", 0),
                totalDistanceMeters = json.optDouble("totalDistanceMeters", 0.0),
                longestDistanceMeters = json.optDouble("longestDistanceMeters", 0.0),
                longestDurationMillis = json.optLong("longestDurationMillis", 0L),
                lastRideFinishedAtMillis = json.optLong("lastRideFinishedAtMillis", 0L),
                currentWeekStartEpochDay = json.optLong("currentWeekStartEpochDay", 0L),
                currentWeekRideCount = json.optInt("currentWeekRideCount", 0),
                currentWeekDistanceMeters = json.optDouble("currentWeekDistanceMeters", 0.0),
                streakWeeks = json.optInt("streakWeeks", 0),
                lastStreakWeekStartEpochDay = json.optLong("lastStreakWeekStartEpochDay", 0L),
                processedRideIds = json.optJSONArray("processedRideIds")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optLong(i)) }
                } ?: emptyList()
            )
        } catch (t: Throwable) {
            // Corruption must never take down ride saving.
            RideStats()
        }
    }

    private fun persist(stats: RideStats) {
        val json = JSONObject().apply {
            put("schemaVersion", stats.schemaVersion)
            put("totalRides", stats.totalRides)
            put("totalDistanceMeters", stats.totalDistanceMeters)
            put("longestDistanceMeters", stats.longestDistanceMeters)
            put("longestDurationMillis", stats.longestDurationMillis)
            put("lastRideFinishedAtMillis", stats.lastRideFinishedAtMillis)
            put("currentWeekStartEpochDay", stats.currentWeekStartEpochDay)
            put("currentWeekRideCount", stats.currentWeekRideCount)
            put("currentWeekDistanceMeters", stats.currentWeekDistanceMeters)
            put("streakWeeks", stats.streakWeeks)
            put("lastStreakWeekStartEpochDay", stats.lastStreakWeekStartEpochDay)
            put("processedRideIds", org.json.JSONArray().apply {
                stats.processedRideIds.forEach { put(it) }
            })
        }
        prefs.edit().putString(KEY_BLOB, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "trackme_stats"
        private const val KEY_BLOB = "ride_stats_v1"
    }
}
