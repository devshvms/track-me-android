package `in`.shvms.trackme.analytics

import android.app.Application
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import `in`.shvms.trackme.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.posthog.PostHog

object AnalyticsManager {
    private const val TAG = "AnalyticsManager"
    private const val PREFS_NAME = "trackme_prefs"
    private const val PREF_KEY_LOCAL_CONSENT = "telemetry_enabled"
    private var isInitialized = false
    private var configListener: ListenerRegistration? = null

    // Local consent is read synchronously from the same store Settings writes. The remote flag
    // remains an emergency kill switch, but it can never grant consent that the user did not give.
    private val _localConsent = MutableStateFlow(false)
    private val _remoteAllowed = MutableStateFlow(true)
    private val _isTelemetryEnabled = MutableStateFlow(false)
    val isTelemetryEnabled: StateFlow<Boolean> = _isTelemetryEnabled.asStateFlow()

    fun init(application: Application) {
        if (isInitialized) return

        val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        _localConsent.value = prefs.getBoolean(PREF_KEY_LOCAL_CONSENT, false)
        recomputeEffective()

        // 1. Setup Firestore Config Listener
        val firestore = FirebaseFirestore.getInstance()
        configListener = firestore.collection("config").document("telemetry_settings")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Listen failed for telemetry_settings.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _remoteAllowed.value = snapshot.getBoolean("isTelemetryEnabled") ?: true
                    recomputeEffective()
                    Log.d(TAG, "Remote telemetry allow-state updated: ${_remoteAllowed.value}")
                } else {
                    Log.d(TAG, "telemetry_settings document does not exist, remote allow-state defaults to true")
                    _remoteAllowed.value = true
                    recomputeEffective()
                }
            }

        // 2. Initialize PostHog SDK
        val config = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_API_KEY,
            host = "https://eu.i.posthog.com"
        ).apply {
            captureApplicationLifecycleEvents = true
            captureScreenViews = false // We handle this manually in Compose
        }
        
        PostHogAndroid.setup(application, config)
        isInitialized = true
        applyOptState()
    }

    /** Update local consent immediately after the Settings toggle changes. */
    fun updateLocalConsent(enabled: Boolean) {
        _localConsent.value = enabled
        recomputeEffective()
    }

    private fun recomputeEffective() {
        _isTelemetryEnabled.value = TelemetryConsentState(
            localConsent = _localConsent.value,
            remoteAllowed = _remoteAllowed.value
        ).isEnabled
        applyOptState()
    }

    private fun applyOptState() {
        if (!isInitialized) return
        if (_isTelemetryEnabled.value) PostHog.optIn() else PostHog.optOut()
    }

    // Authentication
    fun identifyUser(userId: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.identify(userId)
    }
    
    fun trackUserLoggedIn() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("user_logged_in")
    }

    fun trackUserSignedUp() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("user_signed_up")
    }

    // Account Management
    fun trackAccountDeletionRequested(reason: String? = null) {
        if (!_isTelemetryEnabled.value) return
        val props = mutableMapOf<String, Any>()
        reason?.let { props["reason"] = it }
        PostHog.capture("account_deletion_requested", properties = props)
    }

    fun trackDataDownloadRequested() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("data_download_requested")
    }

    // App Performance & Errors — taxonomy parity with iOS. These remain unwired until a
    // crash handler/ANR watchdog is deliberately introduced on both platforms.
    fun trackAppCrashDetected(errorMessage: String, errorStack: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "app_crash_detected",
            properties = mapOf(
                "error_message" to errorMessage,
                "error_stack" to errorStack
            )
        )
    }

    fun trackScreenStuckDetected(screenName: String, stuckDurationSeconds: Long) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "screen_stuck_detected",
            properties = mapOf(
                "screen_name" to screenName,
                "stuck_duration_seconds" to stuckDurationSeconds
            )
        )
    }

    // Background Tracking Reliability — Android's GPS-staleness watchdog is the equivalent
    // of iOS's CLLocationManager pause/resume callbacks. These have no event properties.
    fun trackLocationUpdatesPaused() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("location_updates_paused")
    }

    fun trackLocationUpdatesResumed() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("location_updates_resumed")
    }

    fun trackHelpOpened() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("help_opened")
    }

    fun trackSupportContactStarted(faqExpandedCount: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("support_contact_started", properties = mapOf("faq_expanded_count" to faqExpandedCount))
    }

    // Core Rides
    fun trackRideStarted(rideId: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "ride_started",
            properties = mapOf(
                "ride_id" to rideId
            )
        )
    }

    // distance_km is Double for cross-platform type parity (decision_log 2026-07-20 — no
    // Float/Double fragmentation in PostHog).
    fun trackRideCompleted(rideId: String, durationSeconds: Long, distanceKm: Double) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "ride_completed",
            properties = mapOf(
                "ride_id" to rideId,
                "duration_seconds" to durationSeconds,
                "distance_km" to distanceKm
            )
        )
    }

    fun trackRideStartAborted(method: RideStartAbortMethod) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "ride_start_aborted",
            properties = mapOf(
                "method" to method.analyticsValue
            )
        )
    }

    // Live Sharing
    // --- Group Ride (§9) ------------------------------------------------------------------
    //
    // §9's funnel, and its counter-metrics, which it insists are tracked "with equal
    // seriousness":
    //
    //   group_created -> invite_sent -> member_joined -> group_started -> co_presence_minutes
    //
    // What is deliberately NOT collected, per §9: "any coordinate, any group name, any member
    // relationship, any inference about who rides with whom. Aggregate counts only. This is a
    // constraint on the analytics, not just on the product." No uid is ever a property here — the
    // groupId is ephemeral and dies with the session, which is why it is safe to correlate on.

    fun trackGroupCreated(durationMinutes: Int, maxMembers: Int, hasDestination: Boolean, hasStartTime: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_created",
            properties = mapOf(
                "duration_minutes" to durationMinutes,
                "max_members" to maxMembers,
                "has_destination" to hasDestination,
                "has_start_time" to hasStartTime,
            )
        )
    }

    /**
     * The growth loop's first step (§2.5). Records that a share sheet opened, never to whom.
     *
     * Carries no `via_code`: §2.3's share message contains the code *and* the link together, so at
     * send time the channel is genuinely unknowable — the recipient picks it. The property was
     * previously passed a hardcoded `true`, which implied a distinction the data could not make.
     * The channel is recorded where it is actually known, on [trackGroupMemberJoined].
     */
    fun trackGroupInviteSent() {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_invite_sent")
    }

    /**
     * An invite reached the app — a link opened, or a code was submitted.
     *
     * Sits between `invite_sent` and `member_joined`, which previously ran straight into each
     * other. Without it a link that opens the app and then fails is indistinguishable from a link
     * nobody ever tapped, so the growth loop cannot tell a distribution problem from a join
     * problem.
     */
    fun trackGroupInviteOpened(viaCode: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_invite_opened", properties = mapOf("via_code" to viaCode))
    }

    /**
     * Why a join attempt did not become a member.
     *
     * [reason] is a fixed vocabulary — the relay's own error codes plus the client-side ones — and
     * never the exception message, which is prose and could carry server text. §9's constraint
     * holds: no code, no token, no group identity, just the category.
     */
    fun trackGroupJoinFailed(reason: GroupJoinFailure, viaCode: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_join_failed",
            properties = mapOf("reason" to reason.analyticsValue, "via_code" to viaCode),
        )
    }

    /**
     * The leader removed a member — a safety control, counted for the same reason as
     * [trackGroupLeft]: to prove it is reachable. Never who was removed.
     */
    fun trackGroupMemberRemoved(memberCount: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_member_removed", properties = mapOf("member_count" to memberCount))
    }

    /**
     * A destination or start time was set after creation.
     *
     * `group_created` records both as they stood at creation, so without this a group that gains a
     * destination later is counted forever as one that never had one — and §2.9's ETA work is
     * sized off exactly that number. Booleans only: no coordinates, no times.
     */
    fun trackGroupMetaUpdated(hasDestination: Boolean, hasStartTime: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_meta_updated",
            properties = mapOf(
                "has_destination" to hasDestination,
                "has_start_time" to hasStartTime,
            ),
        )
    }

    fun trackGroupMemberJoined(memberCount: Int, viaCode: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_member_joined",
            properties = mapOf("member_count" to memberCount, "via_code" to viaCode)
        )
    }

    fun trackGroupStarted(memberCount: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_started", properties = mapOf("member_count" to memberCount))
    }

    /**
     * §9's north-star input: "co-presence retention — do members of a live group return more than
     * solo users?" Minutes only, no route, no coordinates.
     */
    fun trackGroupCoPresence(minutes: Int, memberCount: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_co_presence_minutes",
            properties = mapOf("minutes" to minutes, "member_count" to memberCount)
        )
    }

    /**
     * A SAFETY counter-metric, not a growth one.
     *
     * §9 is explicit and it is easy to get backwards: "heavy use of the exit controls is a healthy
     * signal, not a problem. Near-zero leave usage most likely means the control is
     * undiscoverable, which is a red flag. **Nobody should be tasked with reducing the leave
     * rate.**" This event exists to prove the exit is findable, and should never be optimised down.
     */
    fun trackGroupLeft(secondsInGroup: Int, wasLeader: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_left",
            properties = mapOf(
                "seconds_in_group" to secondsInGroup,
                "was_leader" to wasLeader,
            )
        )
    }

    fun trackGroupEnded(secondsAlive: Int, memberCount: Int, reason: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_ended",
            properties = mapOf(
                "seconds_alive" to secondsAlive,
                "member_count" to memberCount,
                "reason" to reason,
            )
        )
    }

    /**
     * §8's degraded state, so the ops metrics in §9 ("503 rate", "position staleness p95") have a
     * client-side counterpart. A relay outage the clients absorbed silently is still an outage.
     */
    /**
     * §7 — status usage, at the coarsest resolution that can answer the question.
     *
     * **The 4-character code deliberately never leaves the device.** The draft argued four
     * characters carry no PII; that was wrong. "Crashed", "Need help" and "Tired" are health- and
     * safety-adjacent information about a person, and the code *is* that information in compressed
     * form. Unlinked sensitive-category data is still sensitive-category data, and logging it would
     * also drag a health disclosure into Play Data Safety and the App Store privacy label —
     * directly against §5.1's posture.
     *
     * Severity tier is retained because it is the one aggregate that answers a question we must be
     * able to answer: is the alert tier being used, and is it being muted?
     */
    fun trackGroupStatusSet(severityDigit: Char) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_status_set", properties = mapOf("severity" to severityDigit.toString()))
    }

    fun trackGroupStatusCleared(byUser: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_status_cleared", properties = mapOf("by_user" to byUser))
    }

    /**
     * The alert-fatigue signal, and §7's **named falsifier**: if the mute rate climbs, E3 was the
     * wrong call and a later release demotes alerting to passive. Stated up front so it cannot be
     * rationalised away later.
     */
    fun trackGroupAlert(event: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_status_alert_${'$'}event")
    }

    /** §7 — if most taps route to a stale age, §5.3's freshness gate is too loose. */
    fun trackGroupDirectionsOpened(ageBucket: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_directions_opened", properties = mapOf("age_bucket" to ageBucket))
    }

    /**
     * SCOPE_1.7.3 §2(a) — a cascade delete the cloud genuinely refused.
     *
     * **Two properties, and deliberately no more.** Not which ride, not when it was ridden, not
     * where, and not the point count — that would fingerprint a specific ride. A delete is the one
     * action where the user has said "stop holding this", and instrumenting it in detail would be
     * the wrong lesson to draw from having good telemetry.
     *
     * Only a rejection is reported. Offline-queued is a normal outcome (§0 contract 6) and firing
     * this for it would make the dashboard read as a permanent outage every time someone deletes a
     * ride in a tunnel.
     */
    fun trackRideDeleteFailed(cause: String, bulk: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "ride_delete_failed",
            properties = mapOf("cause" to cause, "bulk" to bulk),
        )
    }

    /**
     * §7 — finally distinguishes our outages from riders' dead zones, which we cannot tell apart
     * today. No uid, no group, no coordinates.
     */
    fun trackGroupPresencePaused(cause: String, durationBucket: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_presence_paused",
            properties = mapOf("cause" to cause, "duration_bucket" to durationBucket),
        )
    }

    fun trackGroupDegraded(consecutiveFailures: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("group_degraded", properties = mapOf("consecutive_failures" to consecutiveFailures))
    }

    /** SCOPE_1.8.4 §5.3 — method only; never ride, route, or group identity. */
    fun trackPiPEntered(trigger: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture("pip_entered", properties = mapOf("trigger" to trigger))
    }

    /** Bucketed visible-session length; exact timestamps and exact ride duration stay local. */
    fun trackPiPSession(durationBucket: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "pip_session_seconds",
            properties = mapOf("duration_bucket" to durationBucket),
        )
    }

    /**
     * §2.9's calibration event — the reason ETA is built a release before it is shown.
     *
     * "No coordinates, no destination, no group identity — just two durations and a persona."
     */
    fun trackGroupEtaCalibration(sample: `in`.shvms.trackme.domain.group.EtaCalibration.Sample) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "group_eta_calibration",
            properties = mapOf(
                "predicted_seconds" to sample.predictedSeconds,
                "actual_seconds" to sample.actualSeconds,
                "absolute_error_seconds" to sample.absoluteErrorSeconds,
                "percentage_error" to sample.percentageError,
                "persona" to (sample.persona ?: "unknown"),
            )
        )
    }

    fun trackLiveShareStarted(shareId: String, recipientCount: Int) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "live_share_started",
            properties = mapOf(
                "share_id" to shareId,
                "recipient_count" to recipientCount
            )
        )
    }

    fun trackLiveShareEnded(shareId: String, durationSeconds: Long) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "live_share_ended",
            properties = mapOf(
                "share_id" to shareId,
                "duration_seconds" to durationSeconds
            )
        )
    }

    // Screen tracking
    fun trackScreenViewed(screenName: String, durationSeconds: Long) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "screen_viewed",
            properties = mapOf(
                "screen_name" to screenName,
                "duration_seconds" to durationSeconds
            )
        )
    }

    // ------------------------------------------------------------------------------------
    // v1.6.0 retention taxonomy (A1). snake_case names + typed props, NO PII (no lat/lng,
    // no names/emails/titles). iOS MUST emit identical event names + property keys/types.
    // These are emitted by the feature layer (B1–B4), only when the surface is actually
    // shown/acted on — never speculatively.
    // ------------------------------------------------------------------------------------

    /** B1 — reveal_type in {"pr","first_ride","milestone","default"}. */
    fun trackPostRideRevealShown(revealType: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "post_ride_reveal_shown",
            properties = mapOf(
                "reveal_type" to revealType
            )
        )
    }

    /** B2 — weekly gain-framed recap surfaced. distance_km is Double (parity). */
    fun trackWeeklyRecapShown(weekKey: String, rideCount: Int, distanceKm: Double) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "weekly_recap_shown",
            properties = mapOf(
                "week_key" to weekKey,
                "ride_count" to rideCount,
                "distance_km" to distanceKm
            )
        )
    }

    /** B3 — active-week streak advanced. `froze` reserved for the freeze/tolerance path. */
    fun trackWeeklyStreakUpdated(streakWeeks: Int, froze: Boolean) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "weekly_streak_updated",
            properties = mapOf(
                "streak_weeks" to streakWeeks,
                "froze" to froze
            )
        )
    }

    /** B4 — in-app review prompt requested (system may or may not show it). */
    fun trackReviewPromptRequested(platform: String = "android") {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "review_prompt_requested",
            properties = mapOf(
                "platform" to platform
            )
        )
    }

    /**
     * The first-run walkthrough's funnel — one event, emitted when the walkthrough ends.
     *
     * Deliberately a single terminal event rather than a stream of page events. Everything before
     * the last screen happens before the user has answered the consent question, and transmitting
     * progress through the very screens that ask for consent would contradict the thing being
     * asked. [attempts] carries most of what a stream would have said: a tour abandoned and
     * re-entered arrives as `attempts > 1`, with nothing having left the device in between.
     *
     * **Read [analyticsOptIn] with care.** It can only ever arrive `true`, because a user who
     * declines is a user whose events are not sent — that is what declining means. It is recorded
     * so the event is self-describing, not so the opt-in *rate* can be read off it. That rate has
     * to come from install counts against completions, and anyone reading this property as a rate
     * will conclude everybody opts in.
     */
    fun trackOnboardingCompleted(
        attempts: Int,
        furthestPage: Int,
        usedSkip: Boolean,
        seconds: Int,
        welcomeDwellSeconds: Int,
        rideDwellSeconds: Int,
        historyDwellSeconds: Int,
        togetherDwellSeconds: Int,
        permissionsDwellSeconds: Int,
        readyDwellSeconds: Int,
        analyticsOptIn: Boolean,
        locationGranted: Boolean,
        notificationsGranted: Boolean,
    ) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "onboarding_completed",
            properties = mapOf(
                "attempts" to attempts,
                "furthest_page" to furthestPage,
                "used_skip" to usedSkip,
                "seconds" to seconds,
                "dwell_welcome_seconds" to welcomeDwellSeconds,
                "dwell_ride_seconds" to rideDwellSeconds,
                "dwell_history_seconds" to historyDwellSeconds,
                "dwell_together_seconds" to togetherDwellSeconds,
                "dwell_permissions_seconds" to permissionsDwellSeconds,
                "dwell_ready_seconds" to readyDwellSeconds,
                "analytics_opt_in" to analyticsOptIn,
                "location_granted" to locationGranted,
                "notifications_granted" to notificationsGranted,
            )
        )
    }

    /** Age-signal compliance outcome. Category and decision are coarse, non-PII values. */
    fun trackAgeSignalChecked(platform: String = "android", category: String, decision: String) {
        if (!_isTelemetryEnabled.value) return
        PostHog.capture(
            "age_signal_checked",
            properties = mapOf(
                "platform" to platform,
                "category" to category,
                "decision" to decision
            )
        )
    }
}

enum class RideStartAbortMethod(val analyticsValue: String) {
    PRE_COMMIT("pre_commit"),
    POST_COMMIT_UNDO("post_commit_undo")
}

/**
 * Why a join attempt failed, as a closed vocabulary.
 *
 * The relay's codes are reused verbatim rather than remapped, so a spike here can be read straight
 * against the server's own logs without a translation table in between.
 */
enum class GroupJoinFailure(val analyticsValue: String) {
    /** Failed [in.shvms.trackme.domain.group.GroupCrypto.normalizeJoinCode] — never reached the relay. */
    MALFORMED_CODE("malformed_code"),

    /** Resolved, but the relay held no token for it: expired, or already ended. */
    EXPIRED("expired"),

    GROUP_FULL("group_full"),
    GROUP_NOT_FOUND("group_not_found"),
    JOIN_RATE_LIMITED("join_rate_limited"),

    /** Signed out, so the roster could not be sealed. Distinct from a relay refusal. */
    SIGNED_OUT("signed_out"),

    /** Never reached the relay — no connectivity, DNS, or timeout. */
    NETWORK("network"),

    UNKNOWN("unknown"),
}

/** Pure consent contract used by [AnalyticsManager] and its JVM tests. */
internal data class TelemetryConsentState(
    val localConsent: Boolean,
    val remoteAllowed: Boolean
) {
    val isEnabled: Boolean get() = localConsent && remoteAllowed
}
