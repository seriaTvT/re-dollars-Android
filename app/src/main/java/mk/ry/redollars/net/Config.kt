package mk.ry.redollars.net

/** Ported from re-dollars-preact/src/utils/constants.ts */
object Config {
    const val BACKEND_URL = "https://rd.ry.mk"
    const val BACKEND_API_URL = "$BACKEND_URL/api/v1"
    const val WEBSOCKET_URL = "wss://rd.ry.mk/ws"

    /** Bangumi mirror used for login + posting. Cookies are per-domain. */
    const val BGM_HOST = "https://chii.in"
    const val DOLLARS_URL = "$BGM_HOST/dollars"
    const val DOLLARS_POST_URL = "$BGM_HOST/dollars?ajax=1"

    /** Bangumi avatar CDN prefix; `l` = large. Avatars come back as relative paths. */
    const val AVATAR_BASE = "https://lain.bgm.tv/pic/user/l/"

    /** OAuth app id (userscript BGM_APP_ID); the callback URL must match the backend's
     *  registered redirect URI. The callback response sets a dollars_auth cookie on the
     *  backend domain, which the login WebView harvests. */
    const val BGM_APP_ID = "bgm460268b348b05f082"
    const val OAUTH_CALLBACK_URL = "$BACKEND_API_URL/auth/callback"

    fun oauthAuthorizeUrl(): String {
        val redirect = java.net.URLEncoder.encode(OAUTH_CALLBACK_URL, "UTF-8")
        return "$BGM_HOST/oauth/authorize?client_id=$BGM_APP_ID&response_type=code&redirect_uri=$redirect"
    }

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) ReDollarsAndroid/0.2"

    const val HEARTBEAT_INTERVAL_MS = 25_000L
    const val RECONNECT_DELAY_MS = 2_000L

    fun avatarUrl(raw: String): String =
        if (raw.isBlank() || raw.startsWith("http")) raw else AVATAR_BASE + raw
}
