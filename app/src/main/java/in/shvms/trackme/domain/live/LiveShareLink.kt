package `in`.shvms.trackme.domain.live

import `in`.shvms.trackme.data.remote.TrustedWatchersRepository
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * TASK-366: cross-platform `/live` deep link parser for App Links (`https://trackme.shvms.in/live/...`),
 * custom-scheme fallbacks (`trackme://live/...` or `trackme://live?sessionId=...`),
 * and `live_share_started` FCM notification payloads.
 */
sealed class LiveShareDeepLink {
    data class Session(val sessionId: String) : LiveShareDeepLink()
    data class WatcherInvite(val inviterUid: String, val inviterName: String?) : LiveShareDeepLink()
}

object LiveShareLink {
    private val allowedHttpsHosts = setOf("trackme.shvms.in", "www.trackme.shvms.in")
    private val validSessionIdRegex = Regex("^[A-Za-z0-9_-]{4,128}$")

    fun parse(
        uriString: String?,
        extraSessionId: String? = null,
        extraType: String? = null,
        extraShareUrl: String? = null,
    ): LiveShareDeepLink? {
        if (!uriString.isNullOrBlank()) {
            TrustedWatchersRepository.parseWatcherInviteUri(uriString)?.let { invite ->
                return LiveShareDeepLink.WatcherInvite(
                    inviterUid = invite.inviterUid,
                    inviterName = invite.inviterName,
                )
            }
            parseSessionUri(uriString)?.let { sessionId ->
                return LiveShareDeepLink.Session(sessionId)
            }
        }

        if (extraType == "live_share_started") {
            val fromExtra = extraSessionId?.trim()?.takeIf { validSessionIdRegex.matches(it) }
            if (fromExtra != null) {
                return LiveShareDeepLink.Session(fromExtra)
            }
            if (!extraShareUrl.isNullOrBlank()) {
                parseSessionUri(extraShareUrl)?.let { sessionId ->
                    return LiveShareDeepLink.Session(sessionId)
                }
            }
        }

        return null
    }

    fun parseSessionUri(uriString: String): String? = runCatching {
        val uri = URI(uriString.trim())
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path.orEmpty().trim('/')

        when {
            (scheme == "https" || scheme == "http") && host in allowedHttpsHosts -> {
                if (!path.startsWith("live/")) return@runCatching null
                val candidate = path.removePrefix("live/").substringBefore('/')
                candidate.takeIf { it != "watcher" && validSessionIdRegex.matches(it) }
            }
            scheme == "trackme" && host == "live" -> {
                val querySession = parseQueryParam(uri.rawQuery, "sessionId")
                if (!querySession.isNullOrBlank()) {
                    return@runCatching querySession.takeIf { validSessionIdRegex.matches(it) }
                }
                val pathSegment = path.substringBefore('/')
                pathSegment.takeIf { it.isNotEmpty() && it != "watcher" && validSessionIdRegex.matches(it) }
            }
            else -> null
        }
    }.getOrNull()

    private fun parseQueryParam(rawQuery: String?, key: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split("&").firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else {
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                if (k == key) {
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()).trim()
                } else {
                    null
                }
            }
        }
    }
}
