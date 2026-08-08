package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.data.crypto.GroupCrypto
import org.json.JSONObject

/**
 * The typed layer between the relay's JSON and the rest of the app — SCOPE_1.7.0 §6.2 H9.
 *
 * H9: *"No API models, no serializer — every endpoint is hand-written `JSONObject` parsing… a
 * malformed parse on the hot path is a crash risk."* Sync runs every ~10s for hours, so a single
 * unguarded `getString` on a field the server omitted takes down the map mid-ride.
 *
 * Everything here is pure: JSON in, data class out, no network and no Android. That is what makes
 * the hot-path parser testable, which is the entire point of separating it.
 *
 * Decryption failures are **skipped, not thrown** (§8, "Decryption failure on a member's
 * envelope": *"Skip that member, log, don't crash the map — that member appears absent rather than
 * the whole map failing"*).
 */
object GroupWire {

    /** A member's decrypted position. `serverTs` is stamped by the relay, never by a device. */
    data class MemberPosition(
        val uid: String,
        val lat: Double,
        val lng: Double,
        val speedMps: Float?,
        val headingDeg: Float?,
        val batteryPercent: Int?,
        val moving: Boolean,
        /**
         * Whether this member has actually started recording a ride, as opposed to having joined
         * and not set off yet.
         *
         * Lives inside the encrypted position payload, not beside it: the relay must not learn who
         * is riding any more than it learns where they are. It rides on the position rather than
         * the roster because the roster is rev-gated and only re-sent when membership changes — a
         * "started riding" that took until the next join to appear would be useless.
         *
         * Distinct from [moving]: a member stopped at a junction is riding but not moving, and a
         * member driving to the meetup is moving but not riding.
         */
        val riding: Boolean,
        val serverTsMillis: Long,
    )

    data class RosterEntry(
        val uid: String,
        val displayName: String?,
        val initials: String?,
        val photoUrl: String?,
    )

    data class GroupMeta(val name: String?, val ownerDisplayName: String?)

    /** The relay's sync response, after decryption. */
    data class SyncResult(
        val state: String,
        val expiresAtMillis: Long,
        val rev: Int,
        val maxMembers: Int,
        val nextSyncInSec: Int,
        val positions: List<MemberPosition>,
        /** Present only when the client's `rev` was stale. */
        val roster: List<RosterEntry>?,
        val meta: GroupMeta?,
        /** Members whose envelope would not open. Surfaced so it can be logged, never silently dropped. */
        val undecryptable: List<String>,
    )

    data class CreateResult(
        val groupId: String,
        val joinCode: String,
        val state: String,
        val expiresAtMillis: Long,
        val maxMembers: Int,
        val syncIntervalSec: Int,
        val rev: Int,
    )

    data class JoinResult(
        val groupId: String,
        val state: String,
        val expiresAtMillis: Long,
        val maxMembers: Int,
        val syncIntervalSec: Int,
        val memberCount: Int,
        val rev: Int,
        val rejoined: Boolean,
        val meta: GroupMeta?,
    )

    /** The unauthenticated pre-join lookup. `wrappedToken` is present only on the code path. */
    data class ResolveResult(
        val groupId: String,
        val state: String,
        val memberCount: Int,
        val maxMembers: Int,
        val expiresAtMillis: Long,
        val wrappedToken: String?,
        val encryptedMeta: String?,
    )

    class WireException(message: String) : Exception(message)

    // --- Parsing ----------------------------------------------------------------------------

    fun parseCreate(body: String): CreateResult {
        val json = obj(body)
        return CreateResult(
            groupId = requireString(json, "groupId"),
            joinCode = requireString(json, "joinCode"),
            state = json.optString("state", "PREPARING"),
            expiresAtMillis = requireLong(json, "expiresAt"),
            maxMembers = json.optInt("maxMembers", 5),
            syncIntervalSec = json.optInt("syncIntervalSec", GroupBackoff.DEFAULT_SYNC_INTERVAL_SEC),
            rev = json.optInt("rev", 1),
        )
    }

    fun parseJoin(body: String, key: ByteArray): JoinResult {
        val json = obj(body)
        return JoinResult(
            groupId = requireString(json, "groupId"),
            state = json.optString("state", "PREPARING"),
            expiresAtMillis = requireLong(json, "expiresAt"),
            maxMembers = json.optInt("maxMembers", 5),
            syncIntervalSec = json.optInt("syncIntervalSec", GroupBackoff.DEFAULT_SYNC_INTERVAL_SEC),
            memberCount = json.optInt("memberCount", 1),
            rev = json.optInt("rev", 0),
            rejoined = json.optBoolean("rejoined", false),
            meta = json.optString("meta").takeIf { it.isNotEmpty() }?.let { openMeta(key, it) },
        )
    }

    fun parseResolve(body: String): ResolveResult {
        val json = obj(body)
        return ResolveResult(
            groupId = requireString(json, "groupId"),
            state = json.optString("state", "PREPARING"),
            memberCount = json.optInt("memberCount", 0),
            maxMembers = json.optInt("maxMembers", 5),
            expiresAtMillis = requireLong(json, "expiresAt"),
            wrappedToken = json.optString("wrappedToken").takeIf { it.isNotEmpty() },
            encryptedMeta = json.optString("meta").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The hot path. [selfUid] is filtered out here rather than rendered — §4.1 has the client drop
     * its own entry, and the relay returns it deliberately so a client can confirm its push landed
     * (§15.4: the relay is opaque to us, so this is the only self-diagnostic there is).
     */
    fun parseSync(body: String, key: ByteArray, selfUid: String): SyncResult {
        val json = obj(body)
        val positions = mutableListOf<MemberPosition>()
        val undecryptable = mutableListOf<String>()

        val posJson = json.optJSONObject("positions")
        if (posJson != null) {
            for (uid in posJson.keys()) {
                if (uid == selfUid) continue
                val entry = posJson.optJSONObject(uid) ?: continue
                val envelope = entry.optString("e").takeIf { it.isNotEmpty() } ?: continue
                val ts = entry.optLong("ts", 0L)
                try {
                    val plain = JSONObject(
                        GroupCrypto.open(key, envelope, GroupCrypto.Purpose.Position(uid)),
                    )
                    positions += MemberPosition(
                        uid = uid,
                        lat = plain.getDouble("lat"),
                        lng = plain.getDouble("lng"),
                        speedMps = plain.optDouble("spd").takeIf { !it.isNaN() }?.toFloat(),
                        headingDeg = plain.optDouble("hdg").takeIf { !it.isNaN() }?.toFloat(),
                        batteryPercent = if (plain.has("bat")) plain.optInt("bat") else null,
                        moving = plain.optBoolean("moving", false),
                        riding = plain.optBoolean("riding", false),
                        serverTsMillis = ts,
                    )
                } catch (e: Exception) {
                    // One member we cannot read costs one absent marker, never the whole map (§8).
                    undecryptable += uid
                }
            }
        }

        val rosterJson = json.optJSONObject("roster")
        val roster = rosterJson?.let {
            val out = mutableListOf<RosterEntry>()
            for (uid in it.keys()) {
                val envelope = it.optString(uid).takeIf { s -> s.isNotEmpty() } ?: continue
                try {
                    val plain = JSONObject(
                        GroupCrypto.open(key, envelope, GroupCrypto.Purpose.Roster(uid)),
                    )
                    out += RosterEntry(
                        uid = uid,
                        displayName = plain.optString("displayName").takeIf { s -> s.isNotEmpty() },
                        initials = plain.optString("initials").takeIf { s -> s.isNotEmpty() },
                        photoUrl = plain.optString("photoUrl").takeIf { s -> s.isNotEmpty() },
                    )
                } catch (e: Exception) {
                    undecryptable += uid
                }
            }
            out
        }

        return SyncResult(
            state = json.optString("state", "LIVE"),
            expiresAtMillis = json.optLong("expiresAt", 0L),
            rev = json.optInt("rev", 0),
            maxMembers = json.optInt("maxMembers", 5),
            nextSyncInSec = json.optInt("nextSyncInSec", GroupBackoff.DEFAULT_SYNC_INTERVAL_SEC),
            positions = positions,
            roster = roster,
            meta = json.optString("meta").takeIf { it.isNotEmpty() }?.let { openMeta(key, it) },
            undecryptable = undecryptable,
        )
    }

    /** The position payload a member sends. Field names are fixed by the contract. */
    fun encodePosition(
        lat: Double,
        lng: Double,
        speedMps: Float?,
        headingDeg: Float?,
        batteryPercent: Int?,
        moving: Boolean,
        riding: Boolean = false,
    ): String = JSONObject().apply {
        put("lat", lat)
        put("lng", lng)
        if (speedMps != null) put("spd", speedMps.toDouble())
        if (headingDeg != null) put("hdg", headingDeg.toDouble())
        if (batteryPercent != null) put("bat", batteryPercent)
        put("moving", moving)
        put("riding", riding)
        // No timestamp: the relay stamps it, so a skewed device clock cannot poison freshness for
        // the whole group (§4.4, §8).
    }.toString()

    fun encodeRoster(displayName: String?, initials: String?, photoUrl: String?): String =
        JSONObject().apply {
            if (!displayName.isNullOrBlank()) put("displayName", displayName)
            if (!initials.isNullOrBlank()) put("initials", initials)
            if (!photoUrl.isNullOrBlank()) put("photoUrl", photoUrl)
        }.toString()

    fun encodeMeta(name: String, ownerDisplayName: String?): String =
        JSONObject().apply {
            put("name", name)
            if (!ownerDisplayName.isNullOrBlank()) put("ownerDisplayName", ownerDisplayName)
        }.toString()

    /** The relay's machine-readable error code, when it sent one. */
    fun errorCode(body: String?): String? = try {
        body?.let { obj(it).optString("code").takeIf { c -> c.isNotEmpty() } }
    } catch (e: Exception) {
        null
    }

    private fun openMeta(key: ByteArray, envelope: String): GroupMeta? = try {
        val plain = JSONObject(GroupCrypto.open(key, envelope, GroupCrypto.Purpose.Meta))
        GroupMeta(
            name = plain.optString("name").takeIf { it.isNotEmpty() },
            ownerDisplayName = plain.optString("ownerDisplayName").takeIf { it.isNotEmpty() },
        )
    } catch (e: Exception) {
        // A group whose name will not decrypt is still usable — the map, the roster and the
        // countdown all work. Losing the name is much better than losing the session.
        null
    }

    private fun obj(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw WireException("relay returned a malformed response")
    }

    private fun requireString(json: JSONObject, field: String): String =
        json.optString(field).takeIf { it.isNotEmpty() }
            ?: throw WireException("relay response is missing $field")

    private fun requireLong(json: JSONObject, field: String): Long =
        if (json.has(field)) json.optLong(field) else throw WireException("relay response is missing $field")
}
