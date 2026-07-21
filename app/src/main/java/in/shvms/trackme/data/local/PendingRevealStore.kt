package `in`.shvms.trackme.data.local

import android.content.Context
import android.content.SharedPreferences
import `in`.shvms.trackme.domain.stats.Reveal
import `in`.shvms.trackme.domain.stats.RevealKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Durable one-shot holder for the B1 post-ride [Reveal].
 *
 * Why persist (not just a SharedFlow): the reveal is produced in [TrackingService.finalizeRide]
 * — a foreground service — while Home may be backgrounded or the process about to die. The
 * reveal must survive that and be shown exactly once when Home is next foreground, then be
 * acknowledged. A transient SharedFlow would drop it on a background/kill race (B1 spec pitfall).
 *
 * Contract:
 *  - [put] persists the pending reveal (overwrites any prior unconsumed one — the newest ride
 *    wins; reveals are not queued, matching a single "you just finished a ride" moment).
 *  - [pending] is a hot [StateFlow] the UI observes; seeded from disk at construction so a
 *    reveal saved before process death is still delivered.
 *  - [consume] clears it only if the acknowledged ride ID matches, so a newer reveal written
 *    between show and acknowledge is never lost.
 *
 * Presentation and analytics live in the UI layer; this store stays free of both.
 */
class PendingRevealStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pending = MutableStateFlow(load())
    val pending: StateFlow<Reveal?> = _pending.asStateFlow()

    fun put(reveal: Reveal) {
        persist(reveal)
        _pending.value = reveal
    }

    /** Acknowledge the reveal for [rideId]; no-op if a newer reveal has since replaced it. */
    fun consume(rideId: Long) {
        if (_pending.value?.rideId != rideId) return
        prefs.edit().remove(KEY_BLOB).apply()
        _pending.value = null
    }

    private fun load(): Reveal? {
        val raw = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val json = JSONObject(raw)
            Reveal(
                rideId = json.getLong("rideId"),
                kind = RevealKind.valueOf(json.getString("kind")),
                totalRides = json.optInt("totalRides", 0),
                distanceMeters = json.optDouble("distanceMeters", 0.0),
                durationMillis = json.optLong("durationMillis", 0L),
                milestoneRideCount = if (json.isNull("milestoneRideCount")) null
                    else json.optInt("milestoneRideCount")
            )
        } catch (t: Throwable) {
            // A corrupt/unknown blob must never surface a broken reveal — drop it.
            prefs.edit().remove(KEY_BLOB).apply()
            null
        }
    }

    private fun persist(reveal: Reveal) {
        val json = JSONObject().apply {
            put("rideId", reveal.rideId)
            put("kind", reveal.kind.name)
            put("totalRides", reveal.totalRides)
            put("distanceMeters", reveal.distanceMeters)
            put("durationMillis", reveal.durationMillis)
            if (reveal.milestoneRideCount != null) put("milestoneRideCount", reveal.milestoneRideCount)
            else put("milestoneRideCount", JSONObject.NULL)
        }
        prefs.edit().putString(KEY_BLOB, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "trackme_pending_reveal"
        private const val KEY_BLOB = "pending_reveal_v1"
    }
}
