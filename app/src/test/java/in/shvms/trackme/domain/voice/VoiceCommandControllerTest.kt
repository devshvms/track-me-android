package `in`.shvms.trackme.domain.voice

import `in`.shvms.trackme.domain.group.PresenceAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceCommandControllerTest {
    @Test
    fun `all PresenceAge buckets map to defined structured freshness`() {
        val members = listOf(
            member("now", PresenceAge.Anchor(0L, 10_000L)),
            member("seconds", PresenceAge.Anchor(40_000L, 10_000L)),
            member("minutes", PresenceAge.Anchor(120_000L, 10_000L)),
            member("hours", PresenceAge.Anchor(3_600_000L, 10_000L)),
            member("unknown", PresenceAge.Anchor.unknown(10_000L)),
        )
        val result = VoiceCommandController.evaluateGroupCache(
            snapshot = activeSnapshot(members = members),
            nowElapsedMillis = 10_000L,
        ) as VoiceGroupCacheResult.Available

        assertEquals(
            listOf(
                VoiceFreshness.Now,
                VoiceFreshness.Seconds(40),
                VoiceFreshness.Minutes(2),
                VoiceFreshness.Hours(1),
                VoiceFreshness.Unknown,
            ),
            result.members.map { it.freshness },
        )
    }

    @Test
    fun `missing age anchor is unknown rather than guessed`() {
        val result = VoiceCommandController.evaluateGroupCache(
            snapshot = activeSnapshot(members = listOf(member("missing", null))),
            nowElapsedMillis = Long.MAX_VALUE,
        ) as VoiceGroupCacheResult.Available

        assertEquals(VoiceFreshness.Unknown, result.members.single().freshness)
    }

    @Test
    fun `no group empty cache and degraded cache stay distinct`() {
        assertEquals(
            VoiceGroupCacheResult.NoActiveGroup,
            VoiceCommandController.evaluateGroupCache(
                VoiceGroupCacheSnapshot.NO_ACTIVE_GROUP,
                nowElapsedMillis = 0L,
            ),
        )
        assertEquals(
            VoiceGroupCacheResult.Empty(VoiceGroupConnection.CURRENT),
            VoiceCommandController.evaluateGroupCache(
                activeSnapshot(members = emptyList()),
                nowElapsedMillis = 0L,
            ),
        )
        assertEquals(
            VoiceGroupCacheResult.Empty(VoiceGroupConnection.DEGRADED),
            VoiceCommandController.evaluateGroupCache(
                activeSnapshot(members = emptyList(), degraded = true),
                nowElapsedMillis = 0L,
            ),
        )
    }

    @Test
    fun `degraded state remains attached to usable cached members`() {
        val result = VoiceCommandController.evaluateGroupCache(
            snapshot = activeSnapshot(
                members = listOf(member("member-1", PresenceAge.Anchor(0L, 0L))),
                degraded = true,
            ),
            nowElapsedMillis = 0L,
        ) as VoiceGroupCacheResult.Available

        assertEquals(VoiceGroupConnection.DEGRADED, result.connection)
        assertEquals("member-1", result.members.single().cacheKey)
    }

    @Test
    fun `end is the only action requiring confirmation`() {
        VoiceAction.entries.forEach { action ->
            val result = VoiceCommandController.evaluate(VoiceCommand.Action(action))
                as VoiceCommandResult.ActionReady
            assertEquals(action == VoiceAction.END, result.requiresConfirmation)
        }
    }

    @Test
    fun `command evaluation returns typed query results`() {
        val personal = VoiceCommandController.evaluate(
            VoiceCommand.PersonalQuery(VoicePersonalQuery.DISTANCE),
        )
        assertEquals(
            VoiceCommandResult.PersonalQueryReady(VoicePersonalQuery.DISTANCE),
            personal,
        )

        val group = VoiceCommandController.evaluate(
            VoiceCommand.GroupQuery(VoiceGroupQuery.ROSTER),
        )
        assertEquals(
            VoiceCommandResult.GroupQueryReady(
                VoiceGroupQuery.ROSTER,
                VoiceGroupCacheResult.NoActiveGroup,
            ),
            group,
        )
    }

    @Test
    fun `telemetry names keys and values are closed and cross-platform stable`() {
        val invoked = VoiceTelemetryContract.commandInvoked(VoiceIntent.START, VoiceSurface.ASSISTANT)
        assertEquals("voice_command_invoked", invoked.name)
        assertEquals(mapOf("intent" to "start", "surface" to "assistant"), invoked.properties)

        val failed = VoiceTelemetryContract.commandFailed(
            VoiceIntent.GROUP_MEMBER_LOCATION,
            VoiceFailureReason.LOCKED,
        )
        assertEquals("voice_command_failed", failed.name)
        assertEquals(
            mapOf("intent" to "group_member_location", "reason" to "locked"),
            failed.properties,
        )

        val answered = VoiceTelemetryContract.queryAnswered(
            VoiceQueryIntent.GROUP_MEMBER_LOCATION,
            VoiceFreshnessBucket.UNKNOWN,
        )
        assertEquals("voice_query_answered", answered.name)
        assertEquals(
            mapOf("intent" to "group_member_location", "freshness_bucket" to "unknown"),
            answered.properties,
        )

        assertEquals(
            listOf(
                "start", "pause", "resume", "end", "personal_distance",
                "personal_pace_or_speed", "personal_duration", "group_member_location",
                "group_roster", "group_safety_status",
            ),
            VoiceIntent.entries.map { it.analyticsValue },
        )
        assertEquals(
            listOf(
                "personal_distance", "personal_pace_or_speed", "personal_duration",
                "group_member_location", "group_roster", "group_safety_status",
            ),
            VoiceQueryIntent.entries.map { it.analyticsValue },
        )
        assertEquals(
            listOf(
                "no_active_ride", "invalid_ride_state", "no_active_group", "empty_cache",
                "degraded", "locked", "member_not_found", "ambiguous_member", "unavailable",
            ),
            VoiceFailureReason.entries.map { it.analyticsValue },
        )
    }

    @Test
    fun `telemetry payload cannot carry private voice or group data`() {
        val events = buildList {
            VoiceIntent.entries.forEach { intent ->
                VoiceSurface.entries.forEach { surface ->
                    add(VoiceTelemetryContract.commandInvoked(intent, surface))
                }
                VoiceFailureReason.entries.forEach { reason ->
                    add(VoiceTelemetryContract.commandFailed(intent, reason))
                }
            }
            VoiceQueryIntent.entries.forEach { intent ->
                VoiceFreshnessBucket.entries.forEach { freshness ->
                    add(VoiceTelemetryContract.queryAnswered(intent, freshness))
                }
            }
        }
        val allowedKeys = setOf("intent", "surface", "reason", "freshness_bucket")
        val forbidden = listOf(
            "utterance", "transcript", "name", "uid", "member", "group_id", "token",
            "lat", "lng", "coordinate", "distance",
        )

        events.forEach { event ->
            assertTrue(event.properties.keys.all(allowedKeys::contains))
            event.properties.keys.forEach { key ->
                assertFalse(forbidden.any { key.contains(it, ignoreCase = true) })
            }
        }
    }

    @Test
    fun `controller source is synchronous memory-only and has no renderer`() {
        val source = productionSource()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
        val forbidden = listOf(
            "System.currentTimeMillis", "SystemClock", "Clock.system", "Instant.now", "Date(",
            "suspend fun", ".await(", "delay(", "CoroutineScope", "Flow<", "URL(", "HttpURLConnection",
            "Retrofit", "Room", "DataStore", "SharedPreferences", "File(", "PostHog", "AnalyticsManager",
            "android.app", "android.content", "AppIntent", "IntentDialog",
        )
        forbidden.forEach { token ->
            assertFalse("VoiceCommandController must not contain $token", source.contains(token))
        }
        assertFalse(
            "spoken formatting belongs to the voice catalogue, not the controller",
            Regex("fun\\s+\\w+\\([^)]*\\)\\s*:\\s*String").containsMatchIn(source),
        )
    }

    private fun member(key: String, anchor: PresenceAge.Anchor?) = VoiceGroupMemberCache(
        cacheKey = key,
        displayName = "Rider",
        positionAgeAnchor = anchor,
    )

    private fun activeSnapshot(
        members: List<VoiceGroupMemberCache>,
        degraded: Boolean = false,
    ) = VoiceGroupCacheSnapshot(
        isActive = true,
        isDegraded = degraded,
        syncIntervalSec = 10,
        members = members,
    )

    private fun productionSource(): String {
        var directory: File? = File("").absoluteFile
        val relative = "app/src/main/java/in/shvms/trackme/domain/voice/VoiceCommandController.kt"
        while (directory != null) {
            File(directory, relative).takeIf { it.exists() }?.let { return it.readText() }
            File(directory, relative.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            directory = directory.parentFile
        }
        throw AssertionError("VoiceCommandController.kt not found")
    }
}
