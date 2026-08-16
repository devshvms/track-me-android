package `in`.shvms.trackme.service

/**
 * SCOPE_1.7.3 §0 contract 2 — **"A ride recording is always visible."**
 *
 * > *No path may leave the app recording without the UI reflecting it — including after an
 * > automatic split, if a split still exists.*
 *
 * ### The defect this exists for (§2(b))
 *
 * `splitRide()` called [TrackingManager.reset], which sets the observed `trackingState` to
 * [TrackingState.IDLE]. The service's own `currentState` stayed `TRACKING` and the location
 * callback kept writing points into the freshly-inserted Part 2 ride. Nothing ever published
 * `TRACKING` again, so the two halves of the same fact disagreed until the user pressed start —
 * at which point `onStartCommand` routed to `resumeTracking()`, republished `TRACKING`, and
 * revealed a ride already ten minutes and 300 m along.
 *
 * That is the worst available outcome: **the app is recording a ride the user cannot see, cannot
 * pause, and cannot stop.** They believe they have stopped. Every second after that is location
 * data collected without visible consent, which is a bigger problem than any camera irritation and
 * squarely against the honesty principle the 1.7.x line is built on (1.7.0 §8: no silent failures).
 *
 * ### Why this is a policy object and not a one-line fix at the call site
 *
 * The split was one way to reach the invisible-recording state; it is not the only conceivable one.
 * 1.7.2 shipped a bug where the policy was right and the wiring was not, and 537 passing tests
 * missed it. So the invariant is stated once, here, as something a test can execute — and
 * [TrackingService] is asserted to route its state changes through it, rather than being trusted to.
 *
 * This deliberately outlives the auto-split. §2(a) removes the split entirely (chunked upload makes
 * it unnecessary), but the rule "a recording ride is visible" is about the recorder, not about the
 * split, and is exactly the kind of thing that regresses quietly once the reason for it is gone.
 */
object RecordingVisibilityPolicy {

    /**
     * The state the UI must observe, given what the recorder is actually doing.
     *
     * [hasActiveRide] is the honest question — *is a ride id currently being written to* — not
     * "did someone intend to stop". A ride id is held from `insertRide` until `stopTracking` clears
     * it, which is precisely the window in which points can land in the database.
     *
     * The only correction this makes is refusing to publish [TrackingState.IDLE] while a ride is
     * open. It does not invent activity: every other state ([TrackingState.PAUSED],
     * [TrackingState.GPS_LOST], [TrackingState.GPS_DISABLED], [TrackingState.STORAGE_LOW]) is a
     * visible, honest description of a live ride and passes through untouched.
     */
    fun observedStateFor(serviceState: TrackingState, hasActiveRide: Boolean): TrackingState =
        if (isInvisibleRecording(serviceState, hasActiveRide)) TrackingState.TRACKING else serviceState

    /**
     * Whether an (observed state, active ride) pair breaches contract 2.
     *
     * Exposed separately from [observedStateFor] so tests can assert the violation directly, and so
     * the service can treat "we were about to do this" as the reportable event it is.
     */
    fun isInvisibleRecording(observedState: TrackingState, hasActiveRide: Boolean): Boolean =
        hasActiveRide && observedState == TrackingState.IDLE
}
