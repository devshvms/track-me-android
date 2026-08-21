package `in`.shvms.trackme.domain.voice

import `in`.shvms.trackme.domain.group.PresenceAge

/**
 * Platform-neutral voice contract for SCOPE_1.8.4 TASK-192.
 *
 * The platform intent adapters own invocation and spoken rendering. This layer only evaluates an
 * immutable snapshot that the caller already holds in memory. It performs no I/O, reads no clock,
 * and returns structures rather than catalogue text.
 */
enum class VoiceAction(val intent: VoiceIntent) {
    START(VoiceIntent.START),
    PAUSE(VoiceIntent.PAUSE),
    RESUME(VoiceIntent.RESUME),
    END(VoiceIntent.END),
}

enum class VoicePersonalQuery(
    val intent: VoiceIntent,
    val queryIntent: VoiceQueryIntent,
) {
    DISTANCE(VoiceIntent.PERSONAL_DISTANCE, VoiceQueryIntent.PERSONAL_DISTANCE),
    PACE_OR_SPEED(VoiceIntent.PERSONAL_PACE_OR_SPEED, VoiceQueryIntent.PERSONAL_PACE_OR_SPEED),
    DURATION(VoiceIntent.PERSONAL_DURATION, VoiceQueryIntent.PERSONAL_DURATION),
}

enum class VoiceGroupQuery(
    val intent: VoiceIntent,
    val queryIntent: VoiceQueryIntent,
) {
    MEMBER_LOCATION(VoiceIntent.GROUP_MEMBER_LOCATION, VoiceQueryIntent.GROUP_MEMBER_LOCATION),
    ROSTER(VoiceIntent.GROUP_ROSTER, VoiceQueryIntent.GROUP_ROSTER),
    SAFETY_STATUS(VoiceIntent.GROUP_SAFETY_STATUS, VoiceQueryIntent.GROUP_SAFETY_STATUS),
}

/** Values are the shared Android/iOS telemetry vocabulary, not user utterances. */
enum class VoiceIntent(val analyticsValue: String) {
    START("start"),
    PAUSE("pause"),
    RESUME("resume"),
    END("end"),
    PERSONAL_DISTANCE("personal_distance"),
    PERSONAL_PACE_OR_SPEED("personal_pace_or_speed"),
    PERSONAL_DURATION("personal_duration"),
    GROUP_MEMBER_LOCATION("group_member_location"),
    GROUP_ROSTER("group_roster"),
    GROUP_SAFETY_STATUS("group_safety_status"),
}

/** The query-only subset accepted by `voice_query_answered`. */
enum class VoiceQueryIntent(val analyticsValue: String) {
    PERSONAL_DISTANCE("personal_distance"),
    PERSONAL_PACE_OR_SPEED("personal_pace_or_speed"),
    PERSONAL_DURATION("personal_duration"),
    GROUP_MEMBER_LOCATION("group_member_location"),
    GROUP_ROSTER("group_roster"),
    GROUP_SAFETY_STATUS("group_safety_status"),
}

enum class VoiceSurface(val analyticsValue: String) {
    ASSISTANT("assistant"),
    APP_SHORTCUT("app_shortcut"),
}

/** Closed failure vocabulary. Never replace this with exception or assistant prose. */
enum class VoiceFailureReason(val analyticsValue: String) {
    NO_ACTIVE_RIDE("no_active_ride"),
    INVALID_RIDE_STATE("invalid_ride_state"),
    NO_ACTIVE_GROUP("no_active_group"),
    EMPTY_CACHE("empty_cache"),
    DEGRADED("degraded"),
    LOCKED("locked"),
    MEMBER_NOT_FOUND("member_not_found"),
    AMBIGUOUS_MEMBER("ambiguous_member"),
    UNAVAILABLE("unavailable"),
}

enum class VoiceFreshnessBucket(val analyticsValue: String) {
    NOW("now"),
    SECONDS("seconds"),
    MINUTES("minutes"),
    HOURS("hours"),
    UNKNOWN("unknown"),
}

sealed interface VoiceFreshness {
    val bucket: VoiceFreshnessBucket

    data object Now : VoiceFreshness {
        override val bucket = VoiceFreshnessBucket.NOW
    }

    data class Seconds(val value: Int) : VoiceFreshness {
        override val bucket = VoiceFreshnessBucket.SECONDS
    }

    data class Minutes(val value: Int) : VoiceFreshness {
        override val bucket = VoiceFreshnessBucket.MINUTES
    }

    data class Hours(val value: Int) : VoiceFreshness {
        override val bucket = VoiceFreshnessBucket.HOURS
    }

    data object Unknown : VoiceFreshness {
        override val bucket = VoiceFreshnessBucket.UNKNOWN
    }
}

enum class VoiceGroupConnection {
    CURRENT,
    DEGRADED,
}

/**
 * The smallest member cache shape TASK-192 needs. The key is local routing identity and is never
 * admitted to telemetry. Position/direction/status facts are added by TASK-195 without changing
 * the availability and freshness contract established here.
 */
data class VoiceGroupMemberCache(
    val cacheKey: String,
    val displayName: String?,
    val positionAgeAnchor: PresenceAge.Anchor?,
)

data class VoiceGroupCacheSnapshot(
    val isActive: Boolean,
    val isDegraded: Boolean,
    val syncIntervalSec: Int,
    val members: List<VoiceGroupMemberCache>,
) {
    companion object {
        val NO_ACTIVE_GROUP = VoiceGroupCacheSnapshot(
            isActive = false,
            isDegraded = false,
            syncIntervalSec = 1,
            members = emptyList(),
        )
    }
}

data class VoiceGroupMemberFact(
    val cacheKey: String,
    val displayName: String?,
    val freshness: VoiceFreshness,
)

sealed interface VoiceGroupCacheResult {
    data object NoActiveGroup : VoiceGroupCacheResult

    data class Empty(val connection: VoiceGroupConnection) : VoiceGroupCacheResult

    data class Available(
        val connection: VoiceGroupConnection,
        val members: List<VoiceGroupMemberFact>,
    ) : VoiceGroupCacheResult
}

sealed interface VoiceCommand {
    val intent: VoiceIntent

    data class Action(val action: VoiceAction) : VoiceCommand {
        override val intent = action.intent
    }

    data class PersonalQuery(val query: VoicePersonalQuery) : VoiceCommand {
        override val intent = query.intent
    }

    data class GroupQuery(val query: VoiceGroupQuery) : VoiceCommand {
        override val intent = query.intent
    }
}

sealed interface VoiceCommandResult {
    data class ActionReady(
        val action: VoiceAction,
        /** END always confirms; adapters may not override this. */
        val requiresConfirmation: Boolean,
    ) : VoiceCommandResult

    data class PersonalQueryReady(val query: VoicePersonalQuery) : VoiceCommandResult

    data class GroupQueryReady(
        val query: VoiceGroupQuery,
        val cache: VoiceGroupCacheResult,
    ) : VoiceCommandResult
}

object VoiceCommandController {
    /**
     * Synchronous by construction. [nowElapsedMillis] is supplied by the platform adapter alongside
     * the in-memory snapshot; this layer never reaches for device wall time or monotonic time.
     */
    fun evaluate(
        command: VoiceCommand,
        groupSnapshot: VoiceGroupCacheSnapshot = VoiceGroupCacheSnapshot.NO_ACTIVE_GROUP,
        nowElapsedMillis: Long = 0L,
    ): VoiceCommandResult = when (command) {
        is VoiceCommand.Action -> VoiceCommandResult.ActionReady(
            action = command.action,
            requiresConfirmation = command.action == VoiceAction.END,
        )
        is VoiceCommand.PersonalQuery -> VoiceCommandResult.PersonalQueryReady(command.query)
        is VoiceCommand.GroupQuery -> VoiceCommandResult.GroupQueryReady(
            query = command.query,
            cache = evaluateGroupCache(groupSnapshot, nowElapsedMillis),
        )
    }

    fun evaluateGroupCache(
        snapshot: VoiceGroupCacheSnapshot,
        nowElapsedMillis: Long,
    ): VoiceGroupCacheResult {
        if (!snapshot.isActive) return VoiceGroupCacheResult.NoActiveGroup
        val connection = if (snapshot.isDegraded) {
            VoiceGroupConnection.DEGRADED
        } else {
            VoiceGroupConnection.CURRENT
        }
        if (snapshot.members.isEmpty()) return VoiceGroupCacheResult.Empty(connection)
        return VoiceGroupCacheResult.Available(
            connection = connection,
            members = snapshot.members.map { member ->
                VoiceGroupMemberFact(
                    cacheKey = member.cacheKey,
                    displayName = member.displayName,
                    freshness = member.positionAgeAnchor?.let { anchor ->
                        PresenceAge.bucket(anchor, nowElapsedMillis, snapshot.syncIntervalSec).toVoiceFreshness()
                    } ?: VoiceFreshness.Unknown,
                )
            },
        )
    }

    private fun PresenceAge.Bucket.toVoiceFreshness(): VoiceFreshness = when (this) {
        PresenceAge.Bucket.Now -> VoiceFreshness.Now
        is PresenceAge.Bucket.Seconds -> VoiceFreshness.Seconds(value)
        is PresenceAge.Bucket.Minutes -> VoiceFreshness.Minutes(value)
        is PresenceAge.Bucket.Hours -> VoiceFreshness.Hours(value)
        PresenceAge.Bucket.Unknown -> VoiceFreshness.Unknown
    }
}

/** A pure event descriptor; AnalyticsManager/PostHog integration belongs to the platform cards. */
data class VoiceTelemetryEvent(
    val name: String,
    val properties: Map<String, String>,
)

object VoiceTelemetryContract {
    fun commandInvoked(intent: VoiceIntent, surface: VoiceSurface) = VoiceTelemetryEvent(
        name = "voice_command_invoked",
        properties = mapOf(
            "intent" to intent.analyticsValue,
            "surface" to surface.analyticsValue,
        ),
    )

    fun commandFailed(intent: VoiceIntent, reason: VoiceFailureReason) = VoiceTelemetryEvent(
        name = "voice_command_failed",
        properties = mapOf(
            "intent" to intent.analyticsValue,
            "reason" to reason.analyticsValue,
        ),
    )

    fun queryAnswered(intent: VoiceQueryIntent, freshness: VoiceFreshnessBucket) = VoiceTelemetryEvent(
        name = "voice_query_answered",
        properties = mapOf(
            "intent" to intent.analyticsValue,
            "freshness_bucket" to freshness.analyticsValue,
        ),
    )
}
