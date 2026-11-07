package `in`.shvms.trackme.domain.quickstart

import `in`.shvms.trackme.domain.model.RidePersona

/**
 * TASK-357. A request to start a ride from a launcher shortcut or the Quick Settings tile.
 *
 * ### Why a request instead of starting the service directly
 * Android 12+ forbids starting a location foreground service from the background, and a tile or
 * shortcut tap is not a guarantee the app is foregrounded. So every surface does the same thing:
 * open [`MainActivity`] with [ACTION] and hand Home a short-lived request. Home then runs the
 * *existing* start path (`beginDashboardStart`) — which already owns the location-permission
 * primer, the notification-permission ask, the 1.5 s undo grace (TASK-349) and start analytics —
 * so a shortcut can never bypass a safeguard that a normal tap honours.
 *
 * ### Guarantees (all unit-tested; pure and Android-free)
 * - Unknown/missing persona never starts anything (a stale or hand-crafted intent is ignored).
 * - A request is only honoured while fresh ([TTL_MILLIS]); a tap before onboarding finishes must
 *   not fire a ride minutes later when Home finally composes.
 * - It starts nothing while a ride is already active.
 */
data class QuickStartRequest(
    val persona: RidePersona,
    val source: Source,
    val createdAtMillis: Long,
) {
    enum class Source(val analyticsValue: String) {
        SHORTCUT("shortcut"),
        TILE("tile");

        companion object {
            fun fromStored(value: String?): Source? = entries.firstOrNull { it.name == value }
        }
    }

    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - createdAtMillis in 0..TTL_MILLIS

    companion object {
        const val ACTION = "in.shvms.trackme.action.QUICK_START"
        const val EXTRA_PERSONA = "in.shvms.trackme.extra.QUICK_START_PERSONA"
        const val EXTRA_SOURCE = "in.shvms.trackme.extra.QUICK_START_SOURCE"
        const val TTL_MILLIS: Long = 30_000L

        /** Personas offered as launcher shortcuts (Android shows ~4): the top real-usage modes. */
        val SHORTCUT_PERSONAS: List<RidePersona> = listOf(
            RidePersona.CAR_DRIVE,
            RidePersona.BIKE_DRIVE,
            RidePersona.WALK,
            RidePersona.CYCLING,
        )

        /** Returns null for any intent that is not a well-formed quick-start request. */
        fun parse(action: String?, personaName: String?, sourceName: String?, nowMillis: Long): QuickStartRequest? {
            if (action != ACTION) return null
            val persona = RidePersona.entries.firstOrNull { it.name == personaName } ?: return null
            val source = Source.fromStored(sourceName) ?: return null
            return QuickStartRequest(persona, source, nowMillis)
        }

        /** Whether Home should act on [request] right now. */
        fun shouldStart(request: QuickStartRequest?, nowMillis: Long, isTrackingIdle: Boolean): Boolean =
            request != null && isTrackingIdle && request.isFresh(nowMillis)
    }
}
