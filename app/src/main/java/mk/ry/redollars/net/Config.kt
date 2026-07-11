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

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) ReDollarsAndroid/0.2"

    const val HEARTBEAT_INTERVAL_MS = 25_000L
    const val RECONNECT_DELAY_MS = 2_000L

    fun avatarUrl(raw: String): String =
        if (raw.isBlank() || raw.startsWith("http")) raw else AVATAR_BASE + raw
}
