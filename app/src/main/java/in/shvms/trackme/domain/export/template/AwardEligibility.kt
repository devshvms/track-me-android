package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.data.local.entity.RideSource
import `in`.shvms.trackme.domain.stats.RevealKind

/**
 * What a ride earned at the moment it was saved, as persisted on its row (SCOPE_1.8.9 §13).
 *
 * [previousBest] is metres for a distance PR and active milliseconds for a duration PR — the record
 * it beat, read from the stats snapshot *before* this ride was folded in, which is the one value
 * that cannot be recovered afterwards.
 */
internal data class EarnedReveal(
    val kind: RevealKind,
    val previousBest: Double?,
    val milestoneCount: Int?,
)

/** Everything The Award draws, already checked against the ride's stored figures. */
internal data class AwardFacts(
    val kind: RevealKind,
    val previousBest: Double?,
    val milestoneCount: Int?,
    /** How much of the ring the old record covers; the rest is new ground. 1 when there is no record. */
    val previousFraction: Float,
)

/**
 * The one gate for The Award (SCOPE_1.8.9 §6.4). `RevealSelector` already chose the outcome when the
 * ride was saved; this decides whether that outcome is still **true** of the ride as stored.
 *
 * It exists because of a real gap: the reveal is chosen on the ride's *live* distance, and
 * post-processing runs afterwards and can shorten the ride. A PR claimed at 38.5 km against a
 * 38.2 km record is false once the stored ride reads 38.0 km, and gold on a false claim is exactly
 * the decoration the template's discipline forbids. So the claim is re-checked, and a template that
 * cannot say it truthfully does not render.
 */
internal object AwardEligibility {

    fun facts(
        reveal: EarnedReveal?,
        source: String,
        storedDistanceMeters: Double,
        storedActiveMillis: Long?,
    ): AwardFacts? {
        if (reveal == null || !RideSource.earnsProgress(source)) return null
        return when (reveal.kind) {
            RevealKind.DEFAULT -> null
            RevealKind.FIRST_RIDE -> AwardFacts(reveal.kind, null, null, previousFraction = 1f)
            RevealKind.MILESTONE -> reveal.milestoneCount?.let {
                AwardFacts(reveal.kind, null, it, previousFraction = 1f)
            }
            RevealKind.DISTANCE_PR -> {
                val previous = reveal.previousBest?.takeIf { it > 0.0 } ?: return null
                if (!(storedDistanceMeters > previous)) return null
                AwardFacts(reveal.kind, previous, null, (previous / storedDistanceMeters).toFloat())
            }
            RevealKind.DURATION_PR -> {
                val previous = reveal.previousBest?.takeIf { it > 0.0 } ?: return null
                val active = storedActiveMillis ?: return null
                if (!(active > previous)) return null
                AwardFacts(reveal.kind, previous, null, (previous / active).toFloat())
            }
        }
    }

    /** Reads the persisted columns. An unknown kind from a newer app version is treated as none. */
    fun parse(kind: String?, previousBest: Double?, milestoneCount: Int?): EarnedReveal? =
        kind?.let { runCatching { RevealKind.valueOf(it) }.getOrNull() }
            ?.let { EarnedReveal(it, previousBest, milestoneCount) }
}
