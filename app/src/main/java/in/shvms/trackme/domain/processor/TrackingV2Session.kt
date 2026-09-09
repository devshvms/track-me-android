package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona

/** Authoritative totals. Restore opens fresh anchors and retains the committed checkpoint. */
class TrackingV2Session {
    private val estimator = TrackingV2Estimator()
    private var distanceOffset = 0.0
    private var previousTime: Long? = null
    private var previousDistance = 0.0
    var distanceMeters = 0.0
        private set
    var movingDurationMillis = 0L
        private set
    var maxSpeedMps = 0.0
        private set
    var snapshot = TrackingV2Snapshot()
        private set

    fun reset(persona: RidePersona, distance: Double = 0.0, duration: Long = 0, peak: Double = 0.0) {
        estimator.reset(persona)
        distanceOffset = distance.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        distanceMeters = distanceOffset
        movingDurationMillis = duration.coerceAtLeast(0)
        maxSpeedMps = peak.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        previousTime = null
        previousDistance = 0.0
        snapshot = estimator.snapshot()
    }
    fun pause() { estimator.pause(); previousTime = null }
    fun resume() { estimator.resume(); previousTime = null }
    fun add(sample: TrackingV2Sample): TrackingV2Snapshot {
        val prior = snapshot
        snapshot = estimator.add(sample)
        if (snapshot.rejectedOutlierCount != prior.rejectedOutlierCount || snapshot.manualPauseActive) return snapshot
        val delta = snapshot.distanceMeters - previousDistance
        previousTime?.let { previous ->
            val interval = sample.elapsedRealtimeMillis - previous
            val stepBridge = snapshot.estimatedGapDistanceMeters > prior.estimatedGapDistanceMeters
            if (interval > 0 && (interval <= 15_000 || stepBridge) &&
                snapshot.movementState != TrackingV2MovementState.STATIONARY &&
                snapshot.movementState != TrackingV2MovementState.UNKNOWN &&
                (snapshot.movementState != TrackingV2MovementState.GPS_DEGRADED || delta > 0)) {
                movingDurationMillis += interval
                maxSpeedMps = maxOf(maxSpeedMps, delta.coerceAtLeast(0.0) / (interval / 1000.0),
                    snapshot.currentSpeedMetersPerSecond.toDouble())
            }
        }
        previousTime = sample.elapsedRealtimeMillis
        previousDistance = snapshot.distanceMeters
        distanceMeters = distanceOffset + snapshot.distanceMeters
        return snapshot
    }
}
