package `in`.shvms.trackme.config

import android.graphics.Color

object AppConfig {
    // Map Rendering Constants
    const val MAP_LINE_COLOR = "0x0000ff"
    const val MAP_LINE_WEIGHT = 4
    
    // Static Maps API Base
    const val STATIC_MAP_BASE_URL = "https://maps.googleapis.com/maps/api/staticmap"

    // High Quality Image Export Constants
    const val HQ_IMAGE_WIDTH = 1080
    const val HQ_IMAGE_RATIO_1_1 = 1080 // 1080x1080
    const val HQ_IMAGE_RATIO_16_9 = 607 // 1080x607
    const val HQ_IMAGE_SCALE = 2 // Retina scale for Maps API
    
    // Social Template Rendering Constants
    const val OVERLAY_BANNER_HEIGHT_RATIO = 0.2f // Banner takes bottom 20% of image
    const val OVERLAY_BANNER_COLOR = Color.BLACK
    const val OVERLAY_BANNER_ALPHA = 180 // 0-255 transparency
    const val OVERLAY_TEXT_COLOR = Color.WHITE
    
    // File Paths
    const val EXPORT_DIR_NAME = "trackme_exports"
    const val GPX_FILE_PREFIX = "TrackMe_Ride_"
    const val IMAGE_FILE_PREFIX = "TrackMe_Share_"

    // --- Emergency Configuration ---
    // SOS countdown duration in seconds before broadcasting
    const val SOS_COUNTDOWN_SECONDS = 5

    // Maximum number of broadcast ticks to vibrate for
    const val MAX_HAPTIC_MESSAGES = 5

    // Vibration duration in milliseconds for each broadcast tick
    const val HAPTIC_VIBRATION_DURATION_MS = 1000L

    // --- Post Processing Configuration ---
    const val MAX_ACCELERATION_G = 2.0f
    
    // Speed boundary between Walk/Run and Bike (km/h)
    const val WALKING_MAX_SPEED_KMH = 15.0f
}
