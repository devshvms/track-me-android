package `in`.shvms.trackme.service

/** Which location subscription [TrackingService] should have open right now. */
enum class LocationStreamMode {
    /** Nothing running. No ride, no group. */
    NONE,

    /** The ride's 2s `PRIORITY_HIGH_ACCURACY` stream. Feeds the recorder **and** group presence. */
    RIDE_HIGH_ACCURACY,

    /** `PRIORITY_BALANCED_POWER_ACCURACY` at the group cadence. Presence only — no ride is running. */
    PRESENCE_BALANCED,
}

/**
 * SCOPE_1.7.0 §6.1 **B1** — *"Location only flows while a ride is recording."*
 *
 * The audit calls this the blocker that breaks the core promise: the live-share push sits inside
 * `if (currentState == TrackingState.TRACKING)`, and the service only requests location at all once
 * a ride starts. A member who joins a group and hasn't set off yet broadcasts nothing, so they are
 * invisible to the people they are trying to meet.
 *
 * §4.6's fix is presence as an **orthogonal flag**, not a new `TrackingState` value — presence and
 * recording compose rather than exclude each other. This is that composition, as a pure function so
 * the one rule that actually matters is testable without a service, a device, or a GPS fix:
 *
 * > **When a ride is running, presence rides on the stream the ride already opened.**
 *
 * §4.6 is explicit about why: *"No second location subscription, no doubled GPS cost — this is the
 * reason to extend the service rather than add one."* Two subscriptions would double the GPS draw
 * for a feature whose whole battery budget (§7.4) is "< 1.5 pp/hour above the solo baseline,
 * because GPS is already running and the marginal cost is network only."
 */
object PresenceStreamPolicy {

    /**
     * @param state the ride recorder's state
     * @param presenceMode true while the user is in a live group
     */
    fun streamFor(state: TrackingState, presenceMode: Boolean): LocationStreamMode = when {
        // A ride session of any kind means the high-accuracy stream is already open — including
        // PAUSED, which does not tear it down today, and the GPS_LOST/GPS_DISABLED states that are
        // still actively trying. Presence takes what is already there.
        hasRideSession(state) -> LocationStreamMode.RIDE_HIGH_ACCURACY

        // No ride, but in a group: this is the case B1 says does not exist today, and it is the
        // whole point of presence mode.
        presenceMode -> LocationStreamMode.PRESENCE_BALANCED

        else -> LocationStreamMode.NONE
    }

    /**
     * Whether a location fix should be pushed to the group.
     *
     * Deliberately **not** gated on `TrackingState.TRACKING`. That gate is exactly B1, and every
     * state below is one where the member is still in the group and still expects to be seen:
     * paused at a café, searching for GPS at the bottom of a valley, out of storage, or simply not
     * riding yet. §2.6 puts it plainly — *"Stopping a ride does not leave the group… the person who
     * got a flat tyre is exactly the person the group most needs to see."*
     */
    fun shouldPushPresence(state: TrackingState, presenceMode: Boolean): Boolean = presenceMode

    /**
     * Whether a fix is accurate enough to show someone as a marker.
     *
     * **Looser than the recorder's filter, and that is the point.** The ride path discards anything
     * worse than 22 m to keep indoor multipath out of a saved track. Presence-only mode runs at
     * `PRIORITY_BALANCED_POWER_ACCURACY`, which routinely returns 20–100 m — so reusing the
     * recorder's threshold would silently discard almost every presence fix and make the feature
     * look broken rather than fail.
     *
     * A marker that is 80 m out still answers "roughly where is everyone", which is what the map is
     * for. Anything past [PRESENCE_MAX_ACCURACY_METERS] is not a position, it is a guess.
     */
    fun isAccurateEnoughForPresence(accuracyMeters: Float?): Boolean =
        accuracyMeters == null || accuracyMeters <= PRESENCE_MAX_ACCURACY_METERS

    /**
     * Whether the foreground service may stop.
     *
     * Ending a ride used to mean stopping the service. With presence, it must not: a member who
     * finishes their ride stays in the group until they leave it (§2.6), and killing the service
     * would take their presence with it.
     */
    fun canStopService(state: TrackingState, presenceMode: Boolean): Boolean =
        !presenceMode && !hasRideSession(state)

    private fun hasRideSession(state: TrackingState): Boolean =
        state != TrackingState.IDLE

    /** Ride recorder's strict filter, for contrast. Unchanged. */
    const val RIDE_MAX_ACCURACY_METERS = 22.0f

    const val PRESENCE_MAX_ACCURACY_METERS = 150.0f

    /**
     * Presence-only sampling interval. The relay decides the *upload* cadence via `nextSyncInSec`
     * (§7.1); this is only how often the device is asked for a fix when no ride is running. Matching
     * the fastest group cadence means the sync loop always has something recent without waking GPS
     * more often than the group could use it.
     */
    const val PRESENCE_INTERVAL_MS = 10_000L
    const val PRESENCE_MIN_INTERVAL_MS = 5_000L
}
