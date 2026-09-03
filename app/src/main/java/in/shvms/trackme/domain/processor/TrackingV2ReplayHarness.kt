package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona

/**
 * One deterministic replay event shared by synthetic fixtures on Android and iOS.
 *
 * Fixtures use local metre coordinates and are converted to [TrackingV2Sample] before they reach
 * this harness. That keeps precise routes and platform clocks out of source control while the
 * estimator still receives the same raw-evidence shape as a live ride.
 */
sealed interface TrackingV2ReplayEvent {
    data class Sample(val value: TrackingV2Sample) : TrackingV2ReplayEvent
    data object Discontinuity : TrackingV2ReplayEvent
}

data class TrackingV2ReplayScenario(
    val id: String,
    val persona: RidePersona,
    val events: List<TrackingV2ReplayEvent>,
)

/** Pure adapter: no Android framework, storage, network, or production-authority dependency. */
object TrackingV2ReplayHarness {
    fun run(
        scenario: TrackingV2ReplayScenario,
        estimator: TrackingV2Estimator = TrackingV2Estimator(),
    ): TrackingV2Snapshot {
        estimator.reset(scenario.persona)
        scenario.events.forEach { event ->
            when (event) {
                is TrackingV2ReplayEvent.Sample -> estimator.add(event.value)
                TrackingV2ReplayEvent.Discontinuity -> estimator.markDiscontinuity()
            }
        }
        return estimator.finish()
    }
}
