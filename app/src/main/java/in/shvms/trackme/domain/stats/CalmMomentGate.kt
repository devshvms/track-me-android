package `in`.shvms.trackme.domain.stats

/**
 * Pure gate (TASK-119): may a *non-urgent celebration* surface interrupt the user right now?
 *
 * Prompt 09 (`release-hq/parity/claude-code-prompts/09-weekly-recap.md`, "Trigger") is explicit:
 * the weekly recap must not fire "during an active/paused ride, a storage-low or GPS-lost state,
 * or any mid-task modal. The recap waits until the app is calmly idle on Home." Neither platform
 * enforced that — Android gated only on a pending B1 reveal.
 *
 * Skipping is free and is the correct behaviour: the recap is *not* consumed when it is skipped,
 * so it stays eligible for its whole week and surfaces on the next calm moment. It is deliberately
 * expressed as booleans rather than [in.shvms.trackme.service.TrackingState] so `domain` stays free
 * of any `service`/Android dependency and this stays a plain JVM unit test — the single
 * `TrackingState.IDLE` mapping lives at the two app-layer construction sites.
 *
 * This is the same defect class as TASK-116 (a ride earning a celebratory reveal it had not
 * earned) with a different trigger, so the rule is named once here rather than inlined per call
 * site.
 */
object CalmMomentGate {

    /**
     * A snapshot of everything that makes "now" a bad time to celebrate.
     *
     * @param isTrackingIdle tracking state is `IDLE` — i.e. NOT tracking, paused, GPS-lost,
     *   GPS-disabled or storage-low. Anything other than idle means the user is mid-task.
     * @param hasPendingReveal a B1 post-ride reveal is queued or on screen; the two celebrations
     *   must never stack (pre-existing rule, folded in here so there is one gate, not two).
     */
    data class AppMoment(
        val isTrackingIdle: Boolean = true,
        val hasPendingReveal: Boolean = false
    )

    /** True only when every condition above is calm. Defaults make the happy path explicit. */
    fun isCalm(moment: AppMoment): Boolean =
        moment.isTrackingIdle && !moment.hasPendingReveal
}
