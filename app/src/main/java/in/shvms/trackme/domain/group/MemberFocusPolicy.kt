package `in`.shvms.trackme.domain.group

/**
 * SCOPE_1.7.3 §4 — **tap a roster row, open Home focused on that member.**
 *
 * Today `onShowOnMap` exists for the group *destination* and simply switches tabs; there is no
 * per-member equivalent. This is the rule half of that plumbing.
 *
 * ### The two decisions it encodes
 *
 * - **Q4.2 — a member with no position stays tappable and says why**, rather than going inert. An
 *   un-tappable row answers nothing; a row that explains *"they haven't shared a position yet"* is
 *   the honest surface 1.7.0 §8 asks for. A dead row is indistinguishable from a broken one.
 * - **§4's interaction with §1** — focusing a member is a camera move that must **not** be undone
 *   by follow-me a second later. It drops the camera into free-look, exactly as a manual pan would.
 *   See [in.shvms.trackme.domain.map.CameraFollowPolicy.onFocusedElsewhere].
 */
object MemberFocusPolicy {

    /**
     * Where Home should point, carried by value rather than looked up again on arrival.
     *
     * The uid alone would have to be resolved against `groupSession.positions` on the other side of
     * a tab switch — a race with the sync loop, where a member going stale between the tap and the
     * composition turns a deliberate action into silence. The coordinate is what the rider asked to
     * see, so it travels with the request; the uid stays for identifying whose marker to open.
     */
    data class Focus(val uid: String, val lat: Double, val lng: Double)

    /** What a tap on a roster row should do. */
    sealed interface Outcome {
        /** Switch to Home, point the camera here, and open this member's marker. */
        data class ShowOnMap(val focus: Focus) : Outcome

        /**
         * Stay put and explain. Q4.2: the row is still tappable, because "nothing happens" reads as
         * a bug and teaches the rider that the roster is not to be trusted.
         */
        object ExplainNoPosition : Outcome
    }

    /**
     * @param uid the tapped member
     * @param lastKnownLat/[lastKnownLng] the last coordinate the relay still holds for them, fresh
     *   or not. Deliberately the *last known* position rather than a fresh one: §2.3 (revised) keeps
     *   routing to an explicitly-labelled stale point, because a rider searching for someone who
     *   stopped needs it most at exactly the moment it went stale.
     */
    fun onRowTapped(uid: String, lastKnownLat: Double?, lastKnownLng: Double?): Outcome =
        if (lastKnownLat != null && lastKnownLng != null) {
            Outcome.ShowOnMap(Focus(uid, lastKnownLat, lastKnownLng))
        } else {
            Outcome.ExplainNoPosition
        }

    /**
     * Whether a focus request should still be applied when Home reads it.
     *
     * A focus is a **one-shot**. §4: *"Then it must clear, or returning to Home later would
     * re-focus a member the rider has moved on from."* Home consumes the request as it applies it,
     * so this only ever guards the arrival, but stating it here keeps the one-shot rule testable
     * rather than resting on the order of two lines in a `LaunchedEffect`.
     */
    fun shouldApply(pending: Focus?): Boolean = pending != null

    /**
     * Whether this member has a marker on the map to open.
     *
     * Never true for yourself: §2.6 of 1.7.0 is explicit that we never draw ourselves twice — the
     * system blue dot is already there — so there is no self marker whose info window could open.
     * The camera still moves, because "show me on the map" is a coherent thing to ask for.
     */
    fun hasOpenableMarker(isSelf: Boolean): Boolean = !isSelf
}
