package `in`.shvms.trackme.domain.export

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideEntity

/** The public route printed into every shareable ride artifact. */
fun artifactDeepLink(ride: RideEntity): String {
    val publicId = ride.firestoreId
        ?.takeLast(12)
        ?.takeIf(String::isNotBlank)
        ?: ride.id.toString()
    return AppConfig.REPLAY_DEEP_LINK_BASE_URL + publicId
}

/** Prevents an arbitrary caller-supplied URL from being burned into a TrackMe-branded export. */
fun isTrackMeArtifactDeepLink(value: String?): Boolean {
    if (value == null || !value.startsWith(AppConfig.REPLAY_DEEP_LINK_BASE_URL)) return false
    val publicId = value.removePrefix(AppConfig.REPLAY_DEEP_LINK_BASE_URL)
    return publicId.isNotBlank() && publicId.none(Char::isWhitespace)
}
