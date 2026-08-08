package `in`.shvms.trackme.domain.group

/**
 * Parses an invite that arrived from outside the app — SCOPE_1.7.0 §2.4, §6.1 **B5**.
 *
 * B5 records that this app has *no deep links of any kind*: one `MAIN`/`LAUNCHER` filter and
 * nothing else. §15.1 then deferred join-by-link to 1.7.1 precisely because Digital Asset Links
 * verification is an external dependency that can slip a release.
 *
 * That deferral is why **two** shapes are accepted, not one:
 *
 * - `trackme://group?...` — a custom scheme. Needs no verification, no hosted file, and no
 *   Play-signing fingerprint. It works the moment the app is installed, including sideloaded
 *   builds, which is what makes the landing page's button work *today*.
 * - `https://trackme.shvms.in/g#<token>` — a real App Link. Nicer (no scheme in the URL, opens
 *   straight from any browser) but only once `assetlinks.json` is served AND the fingerprint in it
 *   matches the installed build. Until then Android just opens the browser, which is a soft
 *   failure rather than a broken button.
 *
 * **The token stays out of the path and query.** A6 fixed that: a token in a URL that a server
 * might see ends up in an access log, permanently, and §10 forbids it. Over the https path it
 * rides in the fragment, which browsers never transmit; over the custom scheme it comes as an
 * intent extra, which never becomes a URL at all.
 *
 * Pure so it is testable — and it needs to be, because every input here is attacker-supplied. Any
 * app or web page can fire an intent at us with whatever it likes.
 */
object GroupInviteLink {

    const val SCHEME = "trackme"
    const val HOST = "group"

    /** Intent extra used by the custom-scheme path, so the token is never part of a URI. */
    const val EXTRA_TOKEN = "token"
    const val EXTRA_CODE = "code"

    /** Query parameter for the join code. The code is short-lived and manual; a query is fine. */
    const val QUERY_CODE = "c"

    /**
     * What an invite carried. At least one of the two is non-null, or [parse] returns null.
     *
     * A token is strictly better than a code — no 30-minute expiry, no rate limit, and it is the
     * actual key material — so a link carrying both is treated as a token link.
     */
    data class Invite(val token: String?, val code: String?) {
        val hasToken: Boolean get() = token != null
    }

    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{22}$")
    private val CODE_PATTERN = Regex("^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{6}$")

    /**
     * @param uriString the intent's data, or null
     * @param extraToken an intent extra, for the custom-scheme path
     * @param extraCode an intent extra
     * @param fragment the URI fragment, supplied separately because callers read it from `Uri`
     * @param queryCode the `c` query parameter, likewise
     *
     * Deliberately takes already-extracted pieces rather than an `android.net.Uri`: that keeps this
     * a plain JVM function, and `Uri` parsing is the Android part the caller already has to do.
     */
    fun parse(
        uriString: String?,
        fragment: String? = null,
        queryCode: String? = null,
        extraToken: String? = null,
        extraCode: String? = null,
    ): Invite? {
        if (uriString.isNullOrBlank()) return null

        val token = listOf(extraToken, fragment)
            .firstOrNull { it != null && TOKEN_PATTERN.matches(it.trim()) }
            ?.trim()

        // Normalised the same way the join screen normalises typed input, or a lower-case code in
        // a link would derive a different key from the same six characters (crypto contract §1b).
        val code = listOf(extraCode, queryCode)
            .asSequence()
            .filterNotNull()
            .map { normaliseCode(it) }
            .firstOrNull { it != null }

        return if (token == null && code == null) null else Invite(token, code)
    }

    /**
     * Upper-cases, strips spaces and dashes, and maps the confusable letters Crockford omits —
     * the same canonicalisation the manual entry field applies, because a code from a link and a
     * code typed by hand must produce the same key.
     */
    private fun normaliseCode(raw: String): String? {
        val cleaned = raw.trim()
            .uppercase()
            .replace(Regex("[\\s-]"), "")
            .replace('I', '1')
            .replace('L', '1')
            .replace('O', '0')
        return if (CODE_PATTERN.matches(cleaned)) cleaned else null
    }

    /** The custom-scheme URL the landing page's button targets. */
    fun deepLinkPrefix(): String = "$SCHEME://$HOST"
}
