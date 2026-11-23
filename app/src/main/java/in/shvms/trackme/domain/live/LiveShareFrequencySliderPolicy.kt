package `in`.shvms.trackme.domain.live

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TASK-377 (v1.9.4): Pure cross-platform contract for the 6-step Live Share update frequency
 * battery-aware slider (parity with iOS `LiveShareFrequencySliderPolicy.swift`).
 *
 * Positions are evenly spaced from less frequent (index 0 = 5 minutes, less battery use)
 * to more frequent (index 5 = 5 seconds, more frequent updates).
 */
object LiveShareFrequencySliderPolicy {
    const val PREF_KEY: String = "live_share_frequency_sec"
    const val DEFAULT_SECONDS: Int = 15

    /** Ordered from less frequent (left) to more frequent (right). */
    val STEPS_SECONDS: List<Int> = listOf(300, 60, 30, 15, 10, 5)

    val STEP_COUNT: Int = STEPS_SECONDS.size
    val MAX_INDEX: Int = STEPS_SECONDS.lastIndex

    /** Material 3 Slider `steps` count = interior tick marks strictly between start and end. */
    val SLIDER_INTERNAL_STEPS: Int = (STEP_COUNT - 2).coerceAtLeast(0)

    val DEFAULT_INDEX: Int = STEPS_SECONDS.indexOf(DEFAULT_SECONDS)

    /**
     * Maps a persisted interval in seconds to its stepped slider index `[0..5]`.
     * Non-positive values fall back to [DEFAULT_INDEX] (15 seconds); non-standard positive values
     * snap to the nearest supported step so legacy/manual prefs stay valid.
     */
    fun indexForSeconds(seconds: Int): Int {
        if (seconds <= 0) return DEFAULT_INDEX
        val exact = STEPS_SECONDS.indexOf(seconds)
        if (exact >= 0) return exact
        return STEPS_SECONDS.indices.minByOrNull { abs(STEPS_SECONDS[it] - seconds) } ?: DEFAULT_INDEX
    }

    /** Rounds and clamps a raw slider value onto one of the 6 supported intervals in seconds. */
    fun secondsForSliderValue(value: Float): Int {
        if (!value.isFinite()) return DEFAULT_SECONDS
        val index = value.roundToInt().coerceIn(0, MAX_INDEX)
        return STEPS_SECONDS[index]
    }

    /** Clamps an integer step index onto one of the 6 supported intervals in seconds. */
    fun secondsForIndex(index: Int): Int = STEPS_SECONDS[index.coerceIn(0, MAX_INDEX)]
}
