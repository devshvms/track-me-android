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
    const val HQ_IMAGE_RATIO_9_16 = 1920 // 1080x1920 (social-story default)
    const val HQ_IMAGE_SCALE = 2 // Retina scale for Maps API

    // Presentation-only privacy transform for shared images. Source ride/GPS data is unchanged.
    const val PRIVACY_TRIM_METERS = 200.0

    // TrackMe lockup sizing for exported images.
    const val LOCKUP_MARGIN_RATIO = 0.04f
    const val LOCKUP_ICON_RATIO = 0.08f
    
    // Social Template Rendering Constants
    const val OVERLAY_BANNER_HEIGHT_RATIO = 0.2f // Banner takes bottom 20% of image
    const val OVERLAY_BANNER_COLOR = Color.BLACK
    const val OVERLAY_BANNER_ALPHA = 180 // 0-255 transparency
    const val OVERLAY_TEXT_COLOR = Color.WHITE
    
    // File Paths
    const val EXPORT_DIR_NAME = "trackme_exports"
    const val GPX_FILE_PREFIX = "TrackMe_Ride_"
    const val IMAGE_FILE_PREFIX = "TrackMe_Share_"

    // Phase 1 persona replay export contract.
    const val REPLAY_DEEP_LINK_BASE_URL = "https://trackme.shvms.in/r/"

    // --- Post Processing Configuration ---
    const val MAX_ACCELERATION_G = 2.0f
    
    // Speed boundary between Walk/Run and Bike (km/h)
    const val WALKING_MAX_SPEED_KMH = 15.0f

    // --- Live Share Configuration ---
    const val LIVE_SHARE_BASE_URL = "https://trackme.shvms.in"
    const val LIVE_SHARE_API_PATH = "/api/track"
    const val LIVE_SHARE_START_ENDPOINT = "$LIVE_SHARE_API_PATH/start"
    const val LIVE_SHARE_LOCATION_ENDPOINT_TEMPLATE = "$LIVE_SHARE_API_PATH/%s/location"
    const val LIVE_SHARE_STOP_ENDPOINT_TEMPLATE = "$LIVE_SHARE_API_PATH/%s/stop"

    // --- Group Ride relay (1.7.x) ---
    // Overridable at build time (see app/build.gradle.kts, §6.2 H7) so group development can
    // point at a staging or preview relay; defaults to the same production host as live share.
    val GROUP_BASE_URL: String = `in`.shvms.trackme.BuildConfig.GROUP_RELAY_BASE_URL
    const val GROUP_API_PATH = "/api/group"
    const val GROUP_CREATE_ENDPOINT = "$GROUP_API_PATH/create"
    const val GROUP_RESOLVE_ENDPOINT = "$GROUP_API_PATH/resolve"
    const val GROUP_JOIN_ENDPOINT = "$GROUP_API_PATH/join"
    const val GROUP_SYNC_ENDPOINT = "$GROUP_API_PATH/sync"
    const val GROUP_STATE_ENDPOINT = "$GROUP_API_PATH/state"
    const val GROUP_LEAVE_ENDPOINT = "$GROUP_API_PATH/leave"
    const val GROUP_REMOVE_ENDPOINT = "$GROUP_API_PATH/remove"
    const val GROUP_META_ENDPOINT = "$GROUP_API_PATH/meta"

    /**
     * The share link the create sheet hands to the OS share sheet. Token in the FRAGMENT (A6).
     *
     * No trailing slash: `/g/` and `/g` are different paths to a rewrite, and the slash form
     * needed its own rule. The slash-free form is canonical; the server accepts both.
     */
    const val GROUP_INVITE_LINK_PREFIX = "/g#"

    // --- Transactional Email (D3) ---
    // Same backend host; the endpoint owns the templates and derives the
    // recipient from the verified Firebase token (client passes only a type).
    const val NOTIFY_SEND_ENDPOINT = "/api/notify/send"
}
