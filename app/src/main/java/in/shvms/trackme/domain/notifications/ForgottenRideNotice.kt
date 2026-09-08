package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.1.1 scenario 4 — *"Still recording — 3h 42m. No movement since 15:10."*
 *
 * People forget to stop. The ride ends, the phone goes in a pocket, and the recording runs until
 * the battery does — producing a ride whose duration is wrong, whose average speed is meaningless,
 * and which quietly poisons every aggregate it lands in.
 *
 * ### It must never stop the ride
 *
 * §6.1.1 is explicit and P4 says the same: this **must not** auto-stop. The app cannot tell "forgot
 * to stop" from "waiting out a thunderstorm", "stopped for lunch on a long tour", or "sitting at a
 * level crossing in a very long queue". Stopping someone's ride for them is destroying data they
 * cannot get back; asking is free. So this produces a notification and nothing else.
 *
 * ### Why auto-pause is not enough on its own
 *
 * Auto-pause already exists per persona, and a forgotten ride does get paused. But a paused ride is
 * still a *running* ride: it holds the foreground service, it keeps the notification, and it is
 * still open when the user next looks. Auto-pause solves the metrics; it does not solve the fact
 * that nobody told the rider.
 *
 * Pure so the thresholds can be reasoned about and tested without a service.
 */
object ForgottenRideNotice {

    /**
     * How long without movement before asking.
     *
     * Forty-five minutes. Long enough to sit out a downpour, eat lunch, or wait for a group to
     * regroup without being nagged; short enough that a ride forgotten at the end of a commute is
     * caught before it has doubled its own duration.
     *
     * Deliberately far longer than any `PersonaAutoPauseConfig` stillness threshold, which are
     * seconds — those decide whether the *clock* runs, this decides whether to speak.
     */
    const val STILLNESS_BEFORE_NOTICE_MILLIS: Long = 45L * 60 * 1000

    /**
     * Whether to ask "are you still riding?".
     *
     * @param stillnessMillis how long the rider has been stationary.
     * @param alreadyAsked whether this ride has already been asked about. Once per ride, ever —
     *   someone who has decided to keep recording while stationary has answered, and asking again
     *   is the app arguing with them.
     * @param isTracking false while paused-by-user or stopped. A ride the user paused deliberately
     *   is not a forgotten ride, it is a ride someone is managing.
     */
    fun shouldAsk(
        stillnessMillis: Long,
        alreadyAsked: Boolean,
        isTracking: Boolean,
    ): Boolean {
        if (!isTracking) return false
        if (alreadyAsked) return false
        return stillnessMillis >= STILLNESS_BEFORE_NOTICE_MILLIS
    }
}
