package `in`.shvms.trackme.domain.sync

/**
 * SCOPE_1.7.3 §2(a) and §0 contracts 5–6 — **what happens when a ride is deleted.**
 *
 * > *Deletion is client-side and atomic: all chunks plus the parent in one batched write. Order is
 * > `pendingDelete` locally → cloud batch → local delete.*
 * >
 * > *Offline deletion is not an error. Three states: acknowledged, queued, rejected. Only rejected
 * > is an error, and only rejected restores the local row.*
 */
object RideDeletion {

    /**
     * The outcome of a cloud delete.
     *
     * **Three states, not two, and this is the trap most likely to produce a false message.**
     * Firestore's offline persistence is on by default on both platforms. When offline, a batch is
     * queued locally and applied on reconnect — and the completion callback *does not fire* until
     * the server acknowledges it. So awaiting the batch and treating a timeout as failure would
     * show *"couldn't delete, try again"* for a deletion that is queued and will succeed, and a
     * retry would then attempt to delete documents already pending deletion.
     */
    sealed interface Outcome {
        /** The server has it. Nothing to say to the user. */
        object Acknowledged : Outcome

        /**
         * Queued in Firestore's local persistence, which is durable across app restart. Not an
         * error, and not silent either — the user is told it will happen when they reconnect.
         */
        object Queued : Outcome

        /** Genuinely refused: permissions, invalid state. The only state that is an error. */
        data class Rejected(val cause: Cause, val error: Throwable?) : Outcome
    }

    /**
     * Why a delete was refused, bucketed.
     *
     * §2(a)'s telemetry section allows the cause bucket and nothing else: *"no coordinates, no ride
     * identifiers, no point counts that could fingerprint a specific ride."* A delete is the one
     * action where the user has said "stop holding this", and instrumenting it in detail would be
     * the wrong lesson to draw from having good telemetry.
     */
    enum class Cause {
        PERMISSION,
        NETWORK,
        UNKNOWN;

        /** Lowercase bucket name for the `ride_delete_failed` property. */
        val bucket: String get() = name.lowercase()
    }

    /**
     * Whether the local row may now be removed.
     *
     * Acknowledged and Queued both mean the cloud copy is gone or will be. Only a rejection leaves
     * the cloud row live, and keeping the local row is what stops the two disagreeing.
     */
    fun mayDeleteLocally(outcome: Outcome): Boolean = outcome !is Outcome.Rejected

    /**
     * Whether the local row must be **restored** — its `pendingDelete` flag cleared.
     *
     * §2(a): *"only rejected restores the local row."* A ride left flagged after a failure is
     * invisible to the uploader (which refuses to upload anything carrying the flag) and still
     * present in History — the worst of both, and it would never resolve itself.
     */
    fun mustRestoreLocally(outcome: Outcome): Boolean = outcome is Outcome.Rejected

    /** Whether this outcome should reach Crashlytics. Only a genuine rejection is an error. */
    fun isError(outcome: Outcome): Boolean = outcome is Outcome.Rejected

    /**
     * Whether the user should be told something.
     *
     * Acknowledged is deliberately silent: the row disappearing *is* the feedback, and a
     * confirmation toast for the expected outcome of an explicit action is noise.
     */
    fun needsUserNotice(outcome: Outcome): Boolean = outcome !is Outcome.Acknowledged

    /**
     * Buckets a Firestore exception.
     *
     * Matched on the SDK's own status code where possible rather than on message text, which is
     * localised and version-dependent. [firestoreStatusName] is passed in rather than read here so
     * this object stays free of the Firebase SDK and testable without it.
     */
    fun causeOf(firestoreStatusName: String?): Cause = when (firestoreStatusName) {
        "PERMISSION_DENIED", "UNAUTHENTICATED" -> Cause.PERMISSION
        "UNAVAILABLE", "DEADLINE_EXCEEDED", "ABORTED" -> Cause.NETWORK
        else -> Cause.UNKNOWN
    }

    /**
     * How long to wait for a server acknowledgement before calling it queued.
     *
     * Not a network timeout — the batch is already durably queued by the time this expires, and
     * cancelling the wait does not cancel the write. It is only how long the UI blocks before
     * telling the user the honest thing. Long enough that a slow-but-working connection is not
     * mislabelled as offline; short enough that the History screen does not appear frozen.
     */
    const val ACK_TIMEOUT_MS = 6_000L
}
