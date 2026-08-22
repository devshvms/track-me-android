package `in`.shvms.trackme.domain.voice

import `in`.shvms.trackme.domain.group.RiderStatus
import java.util.Locale

/**
 * What voice is allowed to say about another rider — SCOPE_1.8.4 §4.3–§4.6, TASK-195.
 *
 * ### The rule this file exists to enforce
 *
 * **A spoken answer may never claim more confidence than the underlying fix supports.** The map
 * already refuses these claims twice, in shipped code: `MemberDirections` makes the directions
 * action *absent* for a stale member — *"a two-minute-old fix is 2.7 km wrong… not a degraded
 * feature, it is a wrong answer"* — and `HeadingTail.shouldDraw` returns false when a member is
 * stale, stationary, or auto-paused, because *"a fading trail is a claim about recent motion, and we
 * must only make that claim when we can vouch for it."*
 *
 * Voice has to refuse *harder* than the map, because it has nowhere to show doubt. A greyed chip
 * beside a distance tells the reader the number is old; a sentence spoken over road noise does not.
 * This is why `ARCHITECTURE.md`'s illustrative line — *"Alice's last known location from 2 minutes
 * ago was 500 meters ahead"* — is not implementable as written: it pairs a two-minute-old fix with a
 * direction word. §4.4 replaced it, and [discloseMember] is that table.
 *
 * ### Purity
 *
 * No Android, no clock, no network, no formatting. Freshness arrives already bucketed by
 * `PresenceAge` (never re-derived from a device clock — that is amendment A32), distance and bearing
 * arrive already computed, and the caller renders the result through the `voice*` catalogue. What
 * lives here is only the decision about **what may be said**.
 */

/** Where a member is relative to the listener, when that may be claimed at all. */
enum class VoiceDirection {
    AHEAD,
    BEHIND,
    /** Close enough that "ahead" and "behind" would both be noise. */
    NEARBY,
}

/**
 * How much may be disclosed about one member's position.
 *
 * Ordered most to least confident. Nothing here carries a coordinate: §4 forbids voice ever speaking
 * one, and the disclosure types make that a property of the type rather than of a call site.
 */
sealed interface VoiceMemberDisclosure {

    /** Fresh fix and a vouchable heading: distance *and* direction. */
    data class DistanceAndDirection(
        val roundedMeters: Int,
        val direction: VoiceDirection,
    ) : VoiceMemberDisclosure

    /** Fresh enough for a distance, not for a direction. The age is spoken with it. */
    data class DistanceWithAge(
        val roundedMeters: Int,
        val freshness: VoiceFreshness,
    ) : VoiceMemberDisclosure

    /**
     * Too old for a number at all — the age alone.
     *
     * An hour-old position is not "where Alice is"; at any plausible speed it names a place she has
     * left, and saying a distance invites the rider to act on it.
     */
    data class AgeOnly(val freshness: VoiceFreshness) : VoiceMemberDisclosure

    /**
     * The member is in the group but nothing about their position may be claimed.
     *
     * Covers `PresenceAge.Bucket.Unknown` — the sender rebooted and the age is unrecoverable — and a
     * member with no cached position at all. Never a guessed age, never a distance.
     */
    data object PresenceOnly : VoiceMemberDisclosure
}

/** The result of matching a spoken name against the roster — §4.6. */
sealed interface VoiceNameMatch {
    data class Matched(val member: VoiceGroupMemberFact) : VoiceNameMatch

    /** Two or more plausible riders. Voice asks rather than picking one. */
    data class Ambiguous(val candidates: List<VoiceGroupMemberFact>) : VoiceNameMatch

    data object NoMatch : VoiceNameMatch
}

/**
 * What "who's in my group?" / "is everyone okay?" may report.
 *
 * [alerts] is derived **only** from declared [RiderStatus] severity. There is deliberately no
 * "everyone looks fine" signal computed from movement: inferring safety from four moving dots is a
 * claim the product cannot support, on the question where being wrong costs the most.
 */
data class VoiceRosterAnswer(
    val memberCount: Int,
    /** Members who have declared a severity-1 status, with how old that declaration is. */
    val alerts: List<VoiceGroupMemberFact>,
    /** Members whose position is fresh enough to have been heard from recently. */
    val recentlyHeardCount: Int,
    /** Members we cannot vouch for at all — stale, or age-unknown. */
    val notHeardFrom: List<VoiceGroupMemberFact>,
    val connection: VoiceGroupConnection,
)

object VoiceGroupAnswers {

    /** Under a kilometre reads in fifty-metre steps; "four hundred and eighty-seven" is a machine talking. */
    const val NEAR_ROUNDING_METERS = 50

    /** Above a kilometre, one decimal — the caller renders "six point three kilometres". */
    const val KILOMETRE = 1_000

    /**
     * The §4.4 disclosure table, and the only place it is decided.
     *
     * @param distanceMeters great-circle distance to the member's cached position, or null when
     *   there is no cached position.
     * @param direction the computed bearing relation, or null when one could not be derived.
     * @param headingIsVouchable the caller's `HeadingTail.shouldDraw` for this member. False when
     *   they are stationary, auto-paused, stale, or have too few samples to imply a direction.
     */
    fun discloseMember(
        freshness: VoiceFreshness,
        distanceMeters: Double?,
        direction: VoiceDirection?,
        headingIsVouchable: Boolean,
    ): VoiceMemberDisclosure {
        // Age we cannot vouch for outranks everything: without a known age, a distance is a number
        // with no scale on it.
        if (freshness is VoiceFreshness.Unknown) return VoiceMemberDisclosure.PresenceOnly
        if (freshness is VoiceFreshness.Hours) return VoiceMemberDisclosure.AgeOnly(freshness)
        if (distanceMeters == null || distanceMeters.isNaN() || distanceMeters < 0) {
            return VoiceMemberDisclosure.PresenceOnly
        }

        val rounded = roundDistanceMeters(distanceMeters)
        // A direction word requires BOTH a fix from this sync interval and a heading the map itself
        // would draw. Either alone is not enough — see the file header.
        val mayClaimDirection = freshness is VoiceFreshness.Now && headingIsVouchable && direction != null
        return if (mayClaimDirection) {
            VoiceMemberDisclosure.DistanceAndDirection(rounded, direction!!)
        } else {
            VoiceMemberDisclosure.DistanceWithAge(rounded, freshness)
        }
    }

    /**
     * Rounds the way a rider thinks: fifty-metre steps under a kilometre, one decimal above.
     *
     * Returns metres in both cases; the catalogue decides whether to speak "six point three
     * kilometres" or "five hundred metres", because that choice is locale-shaped and this is not.
     */
    fun roundDistanceMeters(meters: Double): Int {
        if (meters < KILOMETRE) {
            return (Math.round(meters / NEAR_ROUNDING_METERS.toDouble()) * NEAR_ROUNDING_METERS).toInt()
        }
        // One decimal kilometre, expressed back in metres so the unit choice stays with the renderer.
        return (Math.round(meters / 100.0) * 100).toInt()
    }

    /**
     * Matches a spoken name against the roster — §4.6.
     *
     * Display names are user data: non-Latin scripts, emoji, duplicates, and nicknames an assistant
     * will never transcribe cleanly. Exact match first, then a *unique* prefix; two candidates ask
     * rather than guess. **Never silently answer about the wrong rider** — that is worse than
     * admitting the name was not understood.
     */
    fun matchName(spoken: String, members: List<VoiceGroupMemberFact>): VoiceNameMatch {
        val needle = normalise(spoken)
        if (needle.isEmpty()) return VoiceNameMatch.NoMatch

        val named = members.filter { !it.displayName.isNullOrBlank() }
        val exact = named.filter { normalise(it.displayName!!) == needle }
        if (exact.size == 1) return VoiceNameMatch.Matched(exact.single())
        if (exact.size > 1) return VoiceNameMatch.Ambiguous(exact)

        val prefixed = named.filter { normalise(it.displayName!!).startsWith(needle) }
        return when {
            prefixed.size == 1 -> VoiceNameMatch.Matched(prefixed.single())
            prefixed.size > 1 -> VoiceNameMatch.Ambiguous(prefixed)
            else -> VoiceNameMatch.NoMatch
        }
    }

    /**
     * Case- and diacritic-insensitive comparison key.
     *
     * `Locale.ROOT` deliberately: a Turkish device lowercasing "I" to "ı" would stop a rider called
     * Ian from matching, which is the same class of trap `GroupDestinationLinks` documents for
     * decimal separators.
     */
    private fun normalise(value: String): String =
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)

    /**
     * Builds the roster answer.
     *
     * @param statusFor the member's declared status, or null. **Only** a declared severity-1 status
     *   becomes an alert; nothing is inferred from position or movement.
     */
    fun roster(
        result: VoiceGroupCacheResult.Available,
        statusFor: (VoiceGroupMemberFact) -> RiderStatus?,
    ): VoiceRosterAnswer {
        val members = result.members
        val alerts = members.filter { statusFor(it)?.isAlert == true }
        val heardRecently = members.count {
            it.freshness is VoiceFreshness.Now || it.freshness is VoiceFreshness.Seconds
        }
        val notHeard = members.filter {
            it.freshness is VoiceFreshness.Hours || it.freshness is VoiceFreshness.Unknown
        }
        return VoiceRosterAnswer(
            memberCount = members.size,
            alerts = alerts,
            recentlyHeardCount = heardRecently,
            notHeardFrom = notHeard,
            connection = result.connection,
        )
    }
}
