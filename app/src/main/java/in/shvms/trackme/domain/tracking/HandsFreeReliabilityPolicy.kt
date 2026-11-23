package `in`.shvms.trackme.domain.tracking

/**
 * TASK-371..373 (v1.9.4): Unified Hands-Free Reliability State Machine & Privacy-Safe Telemetry Contract.
 *
 * Pure policy functions shared by [in.shvms.trackme.service.TrackingService],
 * [in.shvms.trackme.ui.home.HomeScreen], and unit tests so Android and iOS uphold all six
 * cross-platform Hands-Free invariants without divergence:
 *  1. Wall-clock elapsed time classification for short indoor tests and near-empty ride discard.
 *  2. 1Hz timer-driven DwellDetector evaluation under 3-minute stationary GPS throttling.
 *  3. False GPS_LOST / location_updates_paused suppression while stationary and throttled.
 *  4. Immediate hardware un-throttling on motion sensor wake.
 *  5. Active Live Share suppressing dwell auto-end.
 *  6. Strict 2-hour standby cap enforcement.
 */
object HandsFreeReliabilityPolicy {
    const val SHORT_TEST_DISTANCE_METERS: Double = 50.0
    const val SHORT_TEST_DURATION_MILLIS: Long = 90_000L
    const val NEAR_EMPTY_DISCARD_DURATION_MILLIS: Long = 120_000L
    const val STANDBY_TIMEOUT_MINUTES: Int = 120

    /**
     * Invariant 1: Evaluates wall-clock [elapsedDurationMillis] in addition to [movingDurationMillis].
     * A stationary ride open for >= 90s wall-clock is never misclassified as a <90s short indoor test.
     */
    fun isShortIndoorTest(
        elapsedDurationMillis: Long,
        movingDurationMillis: Long,
        distanceMeters: Double,
        maxDistanceMeters: Double = SHORT_TEST_DISTANCE_METERS,
        maxDurationMillis: Long = SHORT_TEST_DURATION_MILLIS,
    ): Boolean {
        return distanceMeters < maxDistanceMeters &&
            elapsedDurationMillis < maxDurationMillis &&
            movingDurationMillis < maxDurationMillis
    }

    /**
     * Service-side safeguard for discarding a near-empty ride (allows brief time reading the dialog
     * while preventing any ride open >= 2 minutes wall-clock from being discarded as a short test).
     */
    fun canDiscardNearEmptyRide(
        finalDistanceMeters: Double,
        finalMovingDurationMillis: Long,
        finalElapsedDurationMillis: Long,
        maxDistanceMeters: Double = SHORT_TEST_DISTANCE_METERS,
        maxDurationMillis: Long = NEAR_EMPTY_DISCARD_DURATION_MILLIS,
    ): Boolean {
        return finalDistanceMeters < maxDistanceMeters &&
            finalMovingDurationMillis < maxDurationMillis &&
            finalElapsedDurationMillis < maxDurationMillis
    }

    /**
     * Invariants 2 & 5: Evaluates DwellDetector on the 1Hz tracking timer whenever Hands-Free is
     * enabled, Live Share is not active/starting, and the device is stationary, throttled, or
     * already dwelling indoors so 5/10/15/30-min Auto-End fires deterministically without needing
     * a new OS location callback.
     */
    fun shouldEvaluateDwellOnTimerTick(
        isAutoTripChainingEnabled: Boolean,
        isLiveShareBlockingDwell: Boolean,
        isAutoPaused: Boolean,
        isMotionStationary: Boolean,
        isGpsThrottled: Boolean,
        dwellState: DwellState,
    ): Boolean {
        if (!isAutoTripChainingEnabled || isLiveShareBlockingDwell) return false
        return isAutoPaused || isMotionStationary || isGpsThrottled || dwellState == DwellState.DWELLING
    }

    /**
     * Invariant 3: Suppresses false GPS_LOST transitions and location_updates_paused spam while
     * stationary GPS throttling intentionally holds back callbacks (as long as OS location services
     * remain enabled).
     */
    fun shouldSuppressGpsLost(
        isGpsThrottled: Boolean,
        isStationary: Boolean,
        isLocationServiceEnabled: Boolean = true,
    ): Boolean {
        return isLocationServiceEnabled && isGpsThrottled && isStationary
    }

    /**
     * Invariant 4: Immediately restore 1Hz high-accuracy GPS polling when the hardware motion
     * sensor transitions from stationary to moving.
     */
    fun shouldUnthrottleOnMotionWake(
        isGpsThrottled: Boolean,
        isMotionStationary: Boolean,
    ): Boolean {
        return isGpsThrottled && !isMotionStationary
    }

    /**
     * Anti-duplication rule for the single top banner slot on Home:
     * never show the Hands-Free discovery tip when standby is active, Hands-Free is already
     * enabled, the user dismissed the tip, or an operator broadcast banner is visible.
     */
    fun shouldShowHomeHandsFreeTip(
        isStandbyActive: Boolean,
        autoTripChainingEnabled: Boolean,
        tipDismissed: Boolean,
        hasUnreadBroadcast: Boolean = false,
    ): Boolean {
        return !isStandbyActive && !autoTripChainingEnabled && !tipDismissed && !hasUnreadBroadcast
    }
}

data class HandsFreeTelemetryEvent(
    val name: String,
    val properties: Map<String, Any>,
)

/**
 * Privacy-safe PostHog contract for TASK-371..373 (§3.2).
 * Strictly contains zero coordinates, zero user identifiers, and zero ride titles.
 */
object HandsFreeTelemetryContract {
    val OFFER_SURFACES = setOf("discard_dialog", "home_top_slot", "post_ride_reveal")
    val ENABLE_SURFACES = setOf("discard_dialog", "home_top_slot", "post_ride_reveal", "settings")
    val DISABLE_SURFACES = setOf("standby_banner", "settings")
    val STANDBY_TRIGGERS = setOf("indoor_test_discard", "auto_end_dwell", "home_opt_in")

    fun offerShown(surface: String): HandsFreeTelemetryEvent {
        require(surface in OFFER_SURFACES) { "Unsupported hands_free_offer_shown surface: $surface" }
        return HandsFreeTelemetryEvent(
            name = "hands_free_offer_shown",
            properties = mapOf("surface" to surface),
        )
    }

    fun enabled(surface: String, dwellMinutes: Int): HandsFreeTelemetryEvent {
        require(surface in ENABLE_SURFACES) { "Unsupported hands_free_enabled surface: $surface" }
        return HandsFreeTelemetryEvent(
            name = "hands_free_enabled",
            properties = mapOf(
                "surface" to surface,
                "dwell_minutes" to dwellMinutes.coerceIn(1, 60),
            ),
        )
    }

    fun disabled(surface: String): HandsFreeTelemetryEvent {
        require(surface in DISABLE_SURFACES) { "Unsupported hands_free_disabled surface: $surface" }
        return HandsFreeTelemetryEvent(
            name = "hands_free_disabled",
            properties = mapOf("surface" to surface),
        )
    }

    fun standbyArmed(
        trigger: String,
        timeoutMinutes: Int = HandsFreeReliabilityPolicy.STANDBY_TIMEOUT_MINUTES,
    ): HandsFreeTelemetryEvent {
        require(trigger in STANDBY_TRIGGERS) { "Unsupported hands_free_standby_armed trigger: $trigger" }
        return HandsFreeTelemetryEvent(
            name = "hands_free_standby_armed",
            properties = mapOf(
                "trigger" to trigger,
                "timeout_minutes" to timeoutMinutes.coerceAtLeast(1),
            ),
        )
    }

    fun rideAutoStarted(standbyWaitSeconds: Int, activityType: String): HandsFreeTelemetryEvent {
        val sanitizedType = activityType.trim().ifEmpty { "AUTO" }
        return HandsFreeTelemetryEvent(
            name = "hands_free_ride_auto_started",
            properties = mapOf(
                "standby_wait_seconds" to standbyWaitSeconds.coerceAtLeast(0),
                "activity_type" to sanitizedType,
            ),
        )
    }

    fun rideAutoEnded(
        dwellMinutes: Int,
        croppedPoints: Int,
        wasGpsThrottled: Boolean,
    ): HandsFreeTelemetryEvent {
        return HandsFreeTelemetryEvent(
            name = "hands_free_ride_auto_ended",
            properties = mapOf(
                "dwell_minutes" to dwellMinutes.coerceIn(1, 60),
                "cropped_points" to croppedPoints.coerceAtLeast(0),
                "was_gps_throttled" to wasGpsThrottled,
            ),
        )
    }

    fun falseStartAborted(elapsedSeconds: Int, distanceMeters: Int): HandsFreeTelemetryEvent {
        return HandsFreeTelemetryEvent(
            name = "hands_free_false_start_aborted",
            properties = mapOf(
                "elapsed_seconds" to elapsedSeconds.coerceAtLeast(0),
                "distance_meters" to distanceMeters.coerceAtLeast(0),
            ),
        )
    }

    fun privacySamples(): List<HandsFreeTelemetryEvent> = listOf(
        offerShown("discard_dialog"),
        enabled("home_top_slot", 10),
        disabled("standby_banner"),
        standbyArmed("indoor_test_discard", 120),
        rideAutoStarted(standbyWaitSeconds = 1800, activityType = "AUTO"),
        rideAutoEnded(dwellMinutes = 10, croppedPoints = 24, wasGpsThrottled = true),
        falseStartAborted(elapsedSeconds = 25, distanceMeters = 12),
    )
}
