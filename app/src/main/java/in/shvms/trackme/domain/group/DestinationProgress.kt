package `in`.shvms.trackme.domain.group

/**
 * Tracks this member's progress toward the group's destination, and produces the one thing that
 * justifies building ETA a release before it is shown — SCOPE_1.7.0 §2.9.
 *
 * > *"Ship it dark, but measure it. This is the actual payoff… On arrival, each client emits one
 * > anonymous event: predicted-vs-actual duration… After one release that gives a real error
 * > distribution, and 1.8's display can either ship with an honest confidence range or, if the
 * > estimator turns out to be poor, be redesigned before anyone ever saw a wrong number. **This is
 * > the difference between deferring a feature and wasting a release.**"*
 *
 * Without a caller, [EtaEstimate] is exactly the dead code §2.9 warns about, and 1.8 would arrive
 * with nothing to calibrate against. This is that caller.
 *
 * Pure: positions in, decisions out. No clock, no location API, no analytics — the caller supplies
 * `nowMillis` and emits the sample.
 */
class DestinationProgress(
    private val destLat: Double,
    private val destLng: Double,
    private val persona: String?,
) {
    /** Recent speeds, reduced by median so one bad fix cannot swing the prediction. */
    private val recentSpeeds = ArrayDeque<Double>()

    private var lastDistanceMeters: Double? = null

    /** The first usable prediction and when it was made — the "predicted" half of the sample. */
    private var firstPredictionSeconds: Long? = null
    private var firstPredictionAtMillis: Long = 0L

    private var arrived = false

    /** Latest estimate. Computed always, displayed never in 1.7.x ([GroupFeatureFlags.SHOW_ETA]). */
    var currentEstimate: EtaEstimate = EtaEstimate.None
        private set

    /**
     * Feed one fix.
     *
     * @return the calibration sample, exactly once, on the update where arrival is first detected.
     *   Null every other time — including every subsequent update after arrival, so a member
     *   loitering at the destination cannot emit the same measurement repeatedly and skew the
     *   distribution 1.8 depends on.
     */
    fun onPosition(
        lat: Double,
        lng: Double,
        speedMps: Double?,
        nowMillis: Long,
    ): EtaCalibration.Sample? {
        if (arrived) return null

        val distance = haversineMeters(lat, lng, destLat, destLng)
        // "Not closing" needs the distance to grow by more than GPS noise, not merely to fail to
        // shrink. A strict `distance < last` flips to "moving away" on ordinary jitter — a member
        // riding steadily toward the meeting point would drop to no-estimate every few fixes, and
        // the calibration data would be full of holes for no real reason.
        val closing = lastDistanceMeters?.let { distance < it + NOISE_TOLERANCE_METERS } ?: true
        lastDistanceMeters = distance

        if (speedMps != null && speedMps.isFinite() && speedMps >= 0) {
            recentSpeeds.addLast(speedMps)
            while (recentSpeeds.size > SPEED_WINDOW) recentSpeeds.removeFirst()
        }
        val rolling = medianSpeed()

        currentEstimate = EtaEstimate.from(distance, rolling, closing)

        // Record the first prediction that was actually a prediction. A Stopped or None estimate
        // says nothing about how long the trip will take, so calibrating against one would measure
        // the wrong thing.
        val estimate = currentEstimate
        if (firstPredictionSeconds == null && estimate is EtaEstimate.Eta && estimate.secondsRemaining > 0) {
            firstPredictionSeconds = estimate.secondsRemaining
            firstPredictionAtMillis = nowMillis
        }

        if (!ArrivalPolicy.hasArrived(distance, persona)) return null

        arrived = true
        val predicted = firstPredictionSeconds ?: return null
        val actual = (nowMillis - firstPredictionAtMillis) / 1000L
        return EtaCalibration.sampleFor(predicted, actual, persona)
    }

    /**
     * Median, not mean.
     *
     * A single 40 m/s glitch — ordinary enough on consumer GPS in a tunnel or under trees — pulls
     * a 5-sample mean far enough to halve the estimate. The median ignores it entirely while still
     * tracking a real change of pace within a couple of fixes. Found by the smoothing test, which
     * the mean failed.
     */
    private fun medianSpeed(): Double {
        if (recentSpeeds.isEmpty()) return 0.0
        val sorted = recentSpeeds.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** §8: arrival is detected and logged in 1.7.x, not rendered. Exposed for 1.8. */
    fun hasArrived(): Boolean = arrived

    companion object {
        /** Enough samples to smooth a bad fix, few enough to still react to a real change of pace. */
        const val SPEED_WINDOW = 5

        /**
         * How much the distance may grow before we call it "moving away".
         *
         * Sized for consumer GPS jitter at rest, not for a detour: a real change of direction
         * moves you much further than this within one sync interval.
         */
        const val NOISE_TOLERANCE_METERS = 15.0

        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Straight-line distance. Deliberately not routed: §2.9 keeps Places and any metered API
         * out of 1.7.x, and a straight line is honest about being an approximation in a way a
         * fake road distance would not be.
         */
        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
            return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }
}
