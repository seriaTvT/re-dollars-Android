package mk.ry.redollars.net

/** Ported from re-dollars-preact/src/utils/constants.ts */
object Config {
    const val BACKEND_URL = "https://rd.ry.mk"
    const val BACKEND_API_URL = "$BACKEND_URL/api/v1"
    const val WEBSOCKET_URL = "wss://rd.ry.mk/ws"

    /** Bangumi domain used for login + posting. bangumi.tv connects directly to the
     *  origin (no Cloudflare), unlike chii.in. Cookies are per-domain, so switching
     *  hosts requires a fresh login. */
    const val BGM_HOST = "https://bangumi.tv"
    const val DOLLARS_URL = "$BGM_HOST/dollars"
    const val DOLLARS_POST_URL = "$BGM_HOST/dollars?ajax=1"

    /** Bangumi avatar CDN prefix; `l` = large. Avatars come back as relative paths. */
    const val AVATAR_BASE = "https://lain.bgm.tv/pic/user/l/"

    /** rymk-auth unified login (auth.ry.mk) — the same flow the web client uses
     *  (performLogin in auth.ts). The login WebView opens the popup-mode /start
     *  endpoint, the user completes Bangumi OAuth, and rymk-auth hands back a signed
     *  JWT via the postMessage its callback page makes to `window.opener` (which
     *  BangumiWebView shims). That JWT is accepted directly as the backend Bearer
     *  token AND by the up.ry.mk upload server, unlike the legacy opaque dollars_auth
     *  token that uploads reject. */
    const val AUTH_BASE_URL = "https://auth.ry.mk"
    const val AUTH_CLIENT = "re-dollars"

    /** Builds the popup /start URL. [state] is a per-request nonce echoed back in the
     *  postMessage so we can reject stale/foreign messages. `origin` is validated
     *  server-side against the client's allowlist (custom app schemes are rejected);
     *  the Bangumi host is an allowed https origin, and since we capture the token from
     *  the opener shim rather than a real cross-window post, that check is all it needs
     *  to satisfy. */
    fun rymkAuthStartUrl(state: String): String {
        fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        return "$AUTH_BASE_URL/api/auth/bangumi/start" +
            "?mode=popup" +
            "&client=${enc(AUTH_CLIENT)}" +
            "&origin=${enc(BGM_HOST)}" +
            "&state=${enc(state)}"
    }

    /** Standalone upload server (media.ts): images to /api/upload need the backend
     *  Bearer JWT; other files to /api/upload/file need no auth. Files are served
     *  from the lsky instance behind it. */
    const val UPLOAD_BASE_URL = "https://up.ry.mk"
    const val UPLOAD_API_URL = "$UPLOAD_BASE_URL/api/upload"
    const val FILE_UPLOAD_API_URL = "$UPLOAD_API_URL/file"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) ReDollarsAndroid/0.2"

    /** BMO assets live on the Bangumi site itself; bgm.tv sits behind Cloudflare,
     *  which rejects non-browser user agents — fetch them with a desktop browser UA
     *  (bgm.tv passed the UA-only check in testing). */
    const val BROWSER_UA =
        "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val BMO_BASE_URL = "https://bgm.tv"
    const val BMO_MANIFEST_URL = "$BMO_BASE_URL/js/lib/bmo/assets/manifest.local.json"

    const val HEARTBEAT_INTERVAL_MS = 25_000L
    const val RECONNECT_DELAY_MS = 2_000L

    fun avatarUrl(raw: String): String =
        if (raw.isBlank() || raw.startsWith("http")) raw else AVATAR_BASE + raw
}
