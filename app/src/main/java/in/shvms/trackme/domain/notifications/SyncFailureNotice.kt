package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.1.5 scenario 23 — telling someone their cloud backup has stopped working.
 *
 * §6.1.5 is blunt about why this matters: *"A user who enabled sync believes their data is safe.
 * Silent persistent failure is the worst class of bug in a data-ownership product."* Someone who
 * turned sync on has stopped worrying about their rides. If it quietly stops, they keep not
 * worrying, right up until the phone is lost.
 *
 * ### The rules, and why each one exists
 *
 * **Not on the first failure.** Sync fails constantly and harmlessly: a tunnel, a flaky café
 * network, airplane mode. An app that notified on every failure would be crying wolf within a day,
 * and the notification that mattered would arrive to someone who had already turned the channel
 * off.
 *
 * **Once per episode, not once per failure.** An episode is a run of failures ending in a success.
 * A device that has been offline for a week produces dozens of failures and exactly one problem.
 *
 * **Only when there is something to lose.** Zero unsynced rides means the backup is idle, not
 * broken — there is nothing waiting and nothing at risk. Notifying then would be the app reporting
 * its own internal state, which is not a fact about the user.
 *
 * Class A: this is about whether the user's data is safe, so it is never rationed by the proactive
 * budget. A backup that broke during a week when a recap went out is still a broken backup.
 */
object SyncFailureNotice {

    /**
     * How many consecutive failures before saying anything.
     *
     * Three, not one. With the periodic worker running daily, three consecutive failures is roughly
     * three days of a backup that is not working — long past a tunnel and well short of a month.
     */
    const val CONSECUTIVE_FAILURES_BEFORE_NOTICE = 3

    /**
     * Whether to tell the user their backup is failing.
     *
     * @param consecutiveFailures failures since the last success.
     * @param unsyncedRideCount rides that have not reached the cloud. The number in the message,
     *   and the reason there is a message at all.
     * @param alreadyNotifiedThisEpisode whether this run of failures has already been reported.
     *   Reset by a success, not by time.
     */
    fun shouldNotify(
        consecutiveFailures: Int,
        unsyncedRideCount: Int,
        alreadyNotifiedThisEpisode: Boolean,
    ): Boolean {
        if (alreadyNotifiedThisEpisode) return false
        if (consecutiveFailures < CONSECUTIVE_FAILURES_BEFORE_NOTICE) return false
        // Nothing waiting means nothing at risk. A failing sync with an empty queue is the app
        // reporting its own internal state, which is not a fact about the user (§4.2 N1).
        if (unsyncedRideCount <= 0) return false
        return true
    }

    /**
     * The episode state after an attempt.
     *
     * A success clears everything — including the notified flag, which is what makes "once per
     * episode" mean an episode rather than "once, ever". A user whose backup breaks twice in a year
     * should be told twice.
     */
    fun record(succeeded: Boolean, state: Episode): Episode = if (succeeded) {
        Episode()
    } else {
        state.copy(consecutiveFailures = state.consecutiveFailures + 1)
    }

    data class Episode(
        val consecutiveFailures: Int = 0,
        val notified: Boolean = false,
    )
}
