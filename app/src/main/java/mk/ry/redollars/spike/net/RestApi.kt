package mk.ry.redollars.spike.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Read-only backend REST calls used by the spike (no auth needed). */
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

    /** GET /api/v1/messages?since_db_id=&limit= — messages newer than a db id (gap recovery). */
    suspend fun fetchNewer(sinceDbId: Long, limit: Int = 200): List<MessageDto> = withContext(Dispatchers.IO) {
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
