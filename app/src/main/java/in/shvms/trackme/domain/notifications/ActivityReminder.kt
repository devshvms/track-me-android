package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a — a reminder the user set, at a time the user chose.
 *
 * ### This is Class B, and that is the whole design
 *
 * §6.0's interruption budget caps Class C at one per seven days across every source. This does not
 * touch that budget, and the reason is not generosity: a Class B notification is one the user asked
 * for, on a cadence they picked, and spending their weekly proactive allowance on their own alarm
 * clock would mean the app punishing them for using a feature. The budget exists to limit what the
 * *app* decides to say. This is not the app deciding.
 *
 * The corollary is the part that has to hold: if this can ever fire without the user having set it,
 * the exemption becomes a loophole. So [Settings.enabled] defaults to false, there is no code path
 * that enables it except a user action, and the suggestion in [RideHistoryProfile] is offered as
 * pre-filled form values rather than as a reminder that already exists.
 *
 * ### Weekly, not daily
 *
 * A daily reminder for an activity almost nobody does daily is a notification that is wrong six
 * times a week, and being wrong on a channel the user opted into is how an opt-in channel becomes
 * one they turn off. One weekday, one time.
 */
object ActivityReminder {

    /**
     * A reminder as the user has it configured.
     *
     * @param dayOfWeek ISO-8601: 1 = Monday … 7 = Sunday, matching [RideHistoryProfile.Sample].
     * @param persona what the reminder is for. Carried so the copy can name the activity rather
     *   than say "time to exercise", which is the tone §4.2 N2 rules out.
     */
    data class Settings(
        val enabled: Boolean = false,
        val dayOfWeek: Int = DEFAULT_DAY,
        val hour: Int = DEFAULT_HOUR,
        val minute: Int = 0,
        val persona: String = DEFAULT_PERSONA,
    ) {
        /** Whether these settings describe something schedulable. */
        val isValid: Boolean
            get() = dayOfWeek in 1..7 && hour in 0..23 && minute in 0..59

        companion object {
            /**
             * Saturday morning. Only ever seen by someone who opened the screen with no history to
             * suggest from and did not touch the pickers — the least presumptuous slot available,
             * not a recommendation.
             */
            const val DEFAULT_DAY = 6
            const val DEFAULT_HOUR = 8
            const val DEFAULT_PERSONA = "AUTO"
        }
    }

    /**
     * The settings to pre-fill the form with, given whatever history exists.
     *
     * Returns [Settings] with `enabled = false` in every case. Nothing here schedules anything;
     * that requires the user pressing the toggle, which is the entire distinction between 12a and
     * the cut scenario 12.
     */
    fun prefill(suggestion: RideHistoryProfile.SuggestedSlot?): Settings {
        if (suggestion == null) return Settings()
        return Settings(
            enabled = false,
            dayOfWeek = suggestion.dayOfWeek,
            hour = suggestion.hour,
            minute = 0,
            persona = suggestion.persona,
        )
    }

    /**
     * Whether a reminder is due to fire, evaluated against the slot the user picked.
     *
     * @param nowDayOfWeek ISO weekday of the moment being tested.
     * @param lastFiredDayStamp a stable per-day marker (an epoch day) for the last time this fired,
     *   or null. Weekly schedulers on both platforms can wake more than once inside the firing
     *   window — a device rebooted at 08:59 re-registers its work and runs it immediately — and a
     *   reminder that arrives twice on the same morning reads as a bug in a way a missed one does
     *   not.
     */
    fun shouldFire(
        settings: Settings,
        nowDayOfWeek: Int,
        nowEpochDay: Long,
        lastFiredEpochDay: Long?,
    ): Boolean {
        if (!settings.enabled) return false
        if (!settings.isValid) return false
        if (nowDayOfWeek != settings.dayOfWeek) return false
        if (lastFiredEpochDay != null && lastFiredEpochDay >= nowEpochDay) return false
        return true
    }
}
