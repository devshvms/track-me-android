package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona

/** Authoritative totals. Restore opens fresh anchors and retains the committed checkpoint. */
class TrackingV2Session {
    private val estimator = TrackingV2Estimator()
    private var distanceOffset = 0.0
    private var previousTime: Long? = null
    private var pendingDurationMillis = 0L
    private var previousAutoPauseEnabled = true
    var isAutoPaused = false
        private set
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
        pendingDurationMillis = 0L
        previousAutoPauseEnabled = true
        isAutoPaused = false
        snapshot = estimator.snapshot()
    }
    fun pause() { estimator.pause(); previousTime = null; pendingDurationMillis = 0L; isAutoPaused = false }
    fun resume() { estimator.resume(); previousTime = null; pendingDurationMillis = 0L; isAutoPaused = false }
    fun add(sample: TrackingV2Sample, autoPauseEnabled: Boolean = true): TrackingV2Snapshot {
        val prior = snapshot
        snapshot = estimator.add(sample)
        if (snapshot.rejectedOutlierCount != prior.rejectedOutlierCount || snapshot.manualPauseActive) return snapshot
        isAutoPaused = autoPauseEnabled && snapshot.movementState == TrackingV2MovementState.STATIONARY
        if (autoPauseEnabled != previousAutoPauseEnabled) pendingDurationMillis = 0L
        previousTime?.let { previous ->
            val interval = sample.elapsedRealtimeMillis - previous
            val stepBridge = snapshot.estimatedGapDistanceMeters > prior.estimatedGapDistanceMeters
            if (interval > 0 && (interval <= MAX_PENDING_DURATION_MILLIS || stepBridge)) {
                when {
                    !autoPauseEnabled -> {
                        // The diagnostic override changes duration, never GPS drift admission.
                        movingDurationMillis += interval
                        pendingDurationMillis = 0L
                    }
                    snapshot.movementState == TrackingV2MovementState.MOVING || stepBridge -> {
                        movingDurationMillis += interval + pendingDurationMillis
                        pendingDurationMillis = 0L
                    }
                    snapshot.movementState == TrackingV2MovementState.POSSIBLY_MOVING -> {
                        pendingDurationMillis = (pendingDurationMillis + interval)
                            .coerceAtMost(MAX_PENDING_DURATION_MILLIS)
                    }
                    else -> pendingDurationMillis = 0L
                }
                if (snapshot.movementState == TrackingV2MovementState.MOVING) {
                    // Coordinate distance can arrive as one confirmation for several callbacks.
                    // Its delta divided by this callback interval is not instantaneous speed.
                    maxSpeedMps = maxOf(maxSpeedMps, snapshot.currentSpeedMetersPerSecond.toDouble())
                }
            } else {
                pendingDurationMillis = 0L
            }
        }
        previousTime = sample.elapsedRealtimeMillis
        previousAutoPauseEnabled = autoPauseEnabled
        distanceMeters = distanceOffset + snapshot.distanceMeters
        return snapshot
    }

    private companion object { const val MAX_PENDING_DURATION_MILLIS = 15_000L }
}
