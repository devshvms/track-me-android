package `in`.shvms.trackme.data.local.entity

import androidx.room.TypeConverter

/**
 * Authoritative origin for a persisted paused point written by v1.8.6 or later.
 *
 * A null origin is deliberately retained for legacy points. It is not inferred from speed,
 * duration, coordinates, or sample count because none of those signals can reliably distinguish a
 * manual boundary from an automatic pause.
 */
enum class PauseOrigin {
    AUTO,
    MANUAL;

    companion object {
        fun fromStoredValue(value: String?): PauseOrigin? =
            entries.firstOrNull { it.name == value }
    }
}

internal class PauseOriginConverters {
    @TypeConverter
    fun fromStoredValue(value: String?): PauseOrigin? = PauseOrigin.fromStoredValue(value)

    @TypeConverter
    fun toStoredValue(value: PauseOrigin?): String? = value?.name
}

/**
 * Legacy paused samples retain the pre-1.8.6 auto-pause presentation. The fallback is explicit and
 * stable; it never guesses an origin from mutable GPS characteristics.
 */
val GPSPointEntity.isExplicitAutoPause: Boolean
    get() = isPaused && pauseOrigin != PauseOrigin.MANUAL

val GPSPointEntity.isManualPauseBoundary: Boolean
    get() = pauseOrigin == PauseOrigin.MANUAL
