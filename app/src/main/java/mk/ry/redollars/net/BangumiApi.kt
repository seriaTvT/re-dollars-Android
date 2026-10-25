package mk.ry.redollars.net

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mk.ry.redollars.SessionInfo
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Website credentials are separate from the RD Bearer token and only sent to BGM_HOST. */
@Singleton
class BangumiApi @Inject constructor(
    http: OkHttpClient,
    private val prefs: SharedPreferences,
) {
    // Never replay an ambiguous POST or follow a redirect with website credentials.
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    @Synchronized
    fun captureCookies(header: String, userAgent: String) {
        prefs.edit().putString("bangumi_cookie", header).putString("bangumi_user_agent", userAgent).apply()
    }

    suspend fun saveSession(info: SessionInfo) = withContext(Dispatchers.IO) {
        prefs.edit().putLong("bangumi_uid", info.uid).putString("bangumi_name", info.name).apply()
    }

    suspend fun restoreSession(): SessionInfo? = withContext(Dispatchers.IO) {
        val uid = prefs.getLong("bangumi_uid", 0)
        if (uid <= 0 || prefs.getString("bangumi_cookie", null).isNullOrBlank()) null
        else SessionInfo(uid, prefs.getString("bangumi_name", null) ?: uid.toString(), "")
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove("bangumi_cookie").remove("bangumi_uid").remove("bangumi_name").remove("bangumi_user_agent").apply()
    }

    suspend fun post(message: String): PostResult = withContext(Dispatchers.IO) {
        val cookie = prefs.getString("bangumi_cookie", null)
        if (cookie.isNullOrBlank()) return@withContext PostResult.LoginRequired
        val req = Request.Builder().url("${Config.DOLLARS_URL}?ajax=1")
            .header("Cookie", cookie)
            .header("User-Agent", prefs.getString("bangumi_user_agent", null) ?: Config.USER_AGENT)
            .header("Origin", Config.BGM_HOST)
            .header("Referer", Config.DOLLARS_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().add("message", message).build()).build()
        try {
            client.newCall(req).execute().use { res ->
                // Retain session rotation/deletion, accepting only cookies for this exact request URL.
                val updates = Cookie.parseAll(req.url, res.headers)
                if (updates.isNotEmpty()) {
                    val jar = cookie.split(';').mapNotNull { part ->
                        val pair = part.trim().split('=', limit = 2)
                        if (pair.size == 2) pair[0] to pair[1] else null
                    }.toMap().toMutableMap()
                    for (updated in updates) {
                        if (updated.matches(req.url) && updated.expiresAt > System.currentTimeMillis()) {
                            jar[updated.name] = updated.value
                        } else if (updated.expiresAt <= System.currentTimeMillis()) jar.remove(updated.name)
                    }
                    // A concurrent logout must not resurrect credentials.
                    synchronized(this@BangumiApi) {
                        if (prefs.getString("bangumi_cookie", null) == cookie) {
                            prefs.edit().putString("bangumi_cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" }).apply()
                        }
                    }
                }
                val location = res.header("Location").orEmpty()
                val body = res.body.string()
                if (res.code == 401 || location.contains("/login", true)) {
                    return@withContext PostResult.LoginRequired
                }
                // A 403 may also be an anti-bot challenge: preserve credentials and draft.
                if (!res.isSuccessful) return@withContext PostResult.Failed(
                    "发送失败（HTTP ${res.code}），请稍后重试",
                    requiresLogin = body.trimStart().startsWith("<"),
                )
                val status = runCatching {
                    AppJson.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content
                }.getOrNull()
                if (status != "ok") return@withContext PostResult.Failed(
                    "发送未成功确认，请检查登录状态后重试",
                    requiresLogin = body.trimStart().startsWith("<"),
                )
                PostResult.Sent
            }
        } catch (_: IOException) {
            PostResult.Failed("网络异常，发送结果未知，请检查消息列表后再重试")
        }
    }
}

sealed interface PostResult {
    data object Sent : PostResult
    data object LoginRequired : PostResult
    data class Failed(val message: String, val requiresLogin: Boolean = false) : PostResult
}
