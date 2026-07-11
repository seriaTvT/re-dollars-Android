package mk.ry.redollars.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Read-only backend REST calls (no auth needed). */
class RestApi(private val client: OkHttpClient) {

    private val jsonMedia = "application/json".toMediaType()

    /** GET /api/v1/messages?limit=  -> ascending list of recent messages. */
    suspend fun fetchRecent(limit: Int = 40): List<MessageDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages?limit=$limit")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext emptyList()
            runCatching { AppJson.decodeFromString<List<MessageDto>>(body) }.getOrDefault(emptyList())
        }
    }

    /** GET /api/v1/messages?before_id=&limit= — messages older than a db id (history paging). */
    suspend fun fetchHistory(beforeId: Long, limit: Int = 50): List<MessageDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages?before_id=$beforeId&limit=$limit")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext emptyList()
            runCatching { AppJson.decodeFromString<List<MessageDto>>(body) }.getOrDefault(emptyList())
        }
    }

    /**
     * GET /api/v1/messages?since_db_id=&limit= — messages newer than a db id, in
     * ascending id order (gap recovery). The server clamps limit to 100, so a deep
     * backlog needs repeated calls advancing since_db_id.
     */
    suspend fun fetchNewer(sinceDbId: Long, limit: Int = 100): List<MessageDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages?since_db_id=$sinceDbId&limit=$limit")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext emptyList()
            runCatching { AppJson.decodeFromString<List<MessageDto>>(body) }.getOrDefault(emptyList())
        }
    }

    /** GET /api/v1/auth/me — validate a backend Bearer token, returning its user. */
    suspend fun authMe(token: String): AuthUserDto? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/auth/me")
            .header("User-Agent", Config.USER_AGENT)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext null
            runCatching { AppJson.decodeFromString<AuthMeResponse>(body) }.getOrNull()
                ?.takeIf { it.status }?.user
        }
    }

    /** PUT /api/v1/messages/:id {content} — edit own message (requires Bearer token). */
    suspend fun editMessage(id: Long, content: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val payload = AppJson.encodeToString(EditRequest.serializer(), EditRequest(content))
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages/$id")
            .header("User-Agent", Config.USER_AGENT)
            .header("Authorization", "Bearer $token")
            .put(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    /** DELETE /api/v1/messages/:id — delete own message (requires Bearer token). */
    suspend fun deleteMessage(id: Long, token: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages/$id")
            .header("User-Agent", Config.USER_AGENT)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    /** GET /api/v1/notifications?uid= — unread mention/reply notifications, newest first. */
    suspend fun fetchNotifications(uid: Long): List<NotificationItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/notifications?uid=$uid")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext emptyList()
            runCatching { AppJson.decodeFromString<NotificationsResponse>(body) }.getOrNull()
                ?.takeIf { it.status }?.notifications?.map { it.toItem() } ?: emptyList()
        }
    }

    /** POST /api/v1/notifications/:id/read {uid} */
    suspend fun markNotificationRead(id: Long, uid: Long): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/notifications/$id/read")
            .header("User-Agent", Config.USER_AGENT)
            .post("""{"uid":$uid}""".toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    /** POST /api/v1/notifications/read-all {uid} */
    suspend fun markAllNotificationsRead(uid: Long): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/notifications/read-all")
            .header("User-Agent", Config.USER_AGENT)
            .post("""{"uid":$uid}""".toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    /** GET /api/v1/users/search?q=&exact=true&limit= — mention autocomplete (matches
     *  nickname or username substring; same params as the userscript's completer). */
    suspend fun searchUsers(query: String, limit: Int = 8): List<UserSearchDto> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/users/search?q=$q&exact=true&limit=$limit")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext emptyList()
            runCatching { AppJson.decodeFromString<UserSearchResponse>(body) }.getOrNull()
                ?.takeIf { it.status }?.data ?: emptyList()
        }
    }

    /** GET /api/v1/users/:id — resolve the true display nickname + avatar for a uid. */
    suspend fun getUser(uid: Long): UserProfileDto? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/users/$uid")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext null
            runCatching { AppJson.decodeFromString<UserLookupResponse>(body) }.getOrNull()
                ?.takeIf { it.status }?.data
        }
    }

    /** GET /api/v1/messages/status?since_db_id= */
    suspend fun status(sinceDbId: Long = 0): MessageStatus? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages/status?since_db_id=$sinceDbId")
            .header("User-Agent", Config.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext null
            runCatching { AppJson.decodeFromString<MessageStatus>(body) }.getOrNull()
        }
    }

    /**
     * POST /api/v1/messages/:id/reactions — toggle our reaction. The endpoint takes
     * user identity from the body (no auth token; mirrors the userscript).
     */
    suspend fun toggleReaction(
        messageId: Long,
        uid: Long,
        nickname: String,
        emoji: String,
    ): ReactionToggleResponse? = withContext(Dispatchers.IO) {
        val payload = AppJson.encodeToString(
            ReactionToggleRequest.serializer(),
            ReactionToggleRequest(emoji = emoji, userId = uid, nickname = nickname),
        )
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages/$messageId/reactions")
            .header("User-Agent", Config.USER_AGENT)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext null
            runCatching { AppJson.decodeFromString<ReactionToggleResponse>(body) }.getOrNull()
        }
    }

    /** POST /api/v1/messages/confirm {uid, message} — fallback confirm of a sent message. */
    suspend fun confirm(uid: Long, message: String): ConfirmResponse? = withContext(Dispatchers.IO) {
        val payload = AppJson.encodeToString(
            ConfirmRequest.serializer(),
            ConfirmRequest(uid = uid, message = message),
        )
        val req = Request.Builder()
            .url("${Config.BACKEND_API_URL}/messages/confirm")
            .header("User-Agent", Config.USER_AGENT)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful || body.isBlank()) return@withContext null
            runCatching { AppJson.decodeFromString<ConfirmResponse>(body) }.getOrNull()
        }
    }
}

@kotlinx.serialization.Serializable
private data class ConfirmRequest(val uid: Long, val message: String)

@kotlinx.serialization.Serializable
private data class EditRequest(val content: String)

@kotlinx.serialization.Serializable
private data class ReactionToggleRequest(
    val emoji: String,
    @kotlinx.serialization.SerialName("user_id") val userId: Long,
    val nickname: String,
)
