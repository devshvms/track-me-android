package `in`.shvms.trackme.domain.group

import kotlin.math.abs

/**
 * Destination ETA — SCOPE_1.7.0 §2.9, **computed and measured but never displayed in 1.7.x**.
 *
 * D7 was revised on 2026-08-07 to "structure in 1.7.x, display in 1.8", for one reason:
 *
 * > *"An ETA that is confidently wrong is worse than no ETA — a group that misses a meetup because
 * > the app said 20 minutes will not forgive it, and we currently have no idea what the error
 * > distribution of a pace-based estimator looks like on real rides."*
 *
 * §2.9 then names the hazard in building it anyway: *"this is how dead code gets made"*, with two
 * examples already in this codebase (iOS `ExportPreviewView` with no call sites and a broken
 * distance function; the Android GPS-signal-loss indicator). The three mandated mitigations are
 * all here:
 *
 * 1. **A pure, unit-tested policy type**, following `LocationStartDecision` / `RideSplitPolicy` /
 *    `AutoPausePreference` — *"testable to completion without a UI, which is what stops them
 *    rotting."*
 * 2. **A config flag, not `if (false)`** — [GroupFeatureFlags.SHOW_ETA].
 * 3. **Shipped dark but measured** — [EtaCalibration] emits predicted-vs-actual on arrival, which
 *    is the entire reason to build it a release early.
 */
sealed interface EtaEstimate {

    /** A usable estimate. Seconds, not a formatted string — formatting is 1.8's problem. */
    data class Eta(val secondsRemaining: Long, val distanceMeters: Double) : EtaEstimate

    /**
     * Moving too slowly to divide by. §8: *"Member is stationary → ETA divides by ~zero. Clamp to
     * a `Stopped` state in the estimator."*
     */
    data object Stopped : EtaEstimate

    /**
     * Moving away from the destination, or no usable inputs.
     *
     * §8: *"Member is moving away from the destination → Estimator returns 'no estimate' rather
     * than a growing one. Never editorialise ('wrong way') when this surfaces in 1.8 — a detour is
     * not an error."*
     */
    data object None : EtaEstimate

    companion object {

        /** Below this, speed is noise rather than travel. */
        const val MIN_SPEED_MPS = 0.5

        /** Inside this, you have arrived; an ETA is meaningless. */
        const val ARRIVED_WITHIN_METERS = 60.0

        /**
         * @param distanceMeters straight-line distance remaining
         * @param rollingSpeedMps smoothed speed, not an instantaneous fix
         * @param closing whether the last samples reduced the distance
         */
        fun from(
            distanceMeters: Double,
            rollingSpeedMps: Double,
            closing: Boolean = true,
        ): EtaEstimate {
            if (!distanceMeters.isFinite() || distanceMeters < 0) return None
            if (distanceMeters <= ARRIVED_WITHIN_METERS) return Eta(0L, distanceMeters)
            if (!rollingSpeedMps.isFinite()) return None
            if (rollingSpeedMps < MIN_SPEED_MPS) return Stopped
            if (!closing) return None
            return Eta((distanceMeters / rollingSpeedMps).toLong(), distanceMeters)
        }
    }
}

/**
 * Arrival — detected and measured in 1.7.x, **not rendered** (§2.9).
 *
 * Radius varies by persona because 60 m is generous on foot and tight in a car park.
 */
object ArrivalPolicy {

    fun radiusMetersFor(persona: String?): Double = when (persona?.uppercase()) {
        "WALK", "RUN" -> 40.0
        "BIKE", "CYCLE" -> 60.0
        else -> 80.0
    }

    fun hasArrived(distanceMeters: Double, persona: String?): Boolean =
        distanceMeters.isFinite() && distanceMeters >= 0 && distanceMeters <= radiusMetersFor(persona)
}

/**
 * The calibration event — §2.9's *"the reason to build it now at all"*.
 *
 * > *"On arrival, each client emits one anonymous event: predicted-vs-actual duration, and the
 * > absolute and percentage error. No coordinates, no destination, no group identity — just two
 * > durations and a persona."*
 *
 * Pure so the payload is testable, and so nothing can quietly add a coordinate to it later.
 */
object EtaCalibration {

    data class Sample(
        val predictedSeconds: Long,
        val actualSeconds: Long,
        val absoluteErrorSeconds: Long,
        val percentageError: Int,
        val persona: String?,
    )

    /** Null when there is nothing meaningful to learn from (no prediction, or no elapsed time). */
    fun sampleFor(predictedSeconds: Long, actualSeconds: Long, persona: String?): Sample? {
        if (predictedSeconds <= 0L || actualSeconds <= 0L) return null
        val error = abs(predictedSeconds - actualSeconds)
        return Sample(
            predictedSeconds = predictedSeconds,
            actualSeconds = actualSeconds,
            absoluteErrorSeconds = error,
            percentageError = ((error * 100.0) / actualSeconds).toInt(),
            persona = persona,
        )
    }
}

/**
 * §2.9's mitigation 2: *"A config flag, not an `if (false)`."*
 *
 * `SHOW_ETA` defaults off and is read from one place, so 1.8 turns the display on for a cohort
 * without a client release once the error distribution from [EtaCalibration] is known.
 */
object GroupFeatureFlags {
    /** 1.8 flips this, once the estimator's real error distribution is known. */
    const val SHOW_ETA = false

    /** §2.9: arrival is detected and measured in 1.7.x, not rendered. */
    const val SHOW_ARRIVAL = false
}
