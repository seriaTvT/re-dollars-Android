package mk.ry.redollars.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

data class UploadResult(val url: String? = null, val error: String? = null)

/**
 * Client for the standalone upload server ([Config.UPLOAD_BASE_URL]). Mirrors the
 * userscript's media.ts: images POST to /api/upload (multipart field `image`) with
 * the backend Bearer JWT; other files POST to /api/upload/file (field `file`), no auth.
 */
class UploadApi(base: OkHttpClient) {

    // Uploads can be slow; the shared client's 30s read timeout is too tight.
    private val client = base.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** [filePart] carries its own content type; it is streamed, never buffered whole. */
    suspend fun uploadImage(filePart: RequestBody, fileName: String, token: String?): UploadResult =
        upload(Config.UPLOAD_API_URL, "image", filePart, fileName, token)

    suspend fun uploadFile(filePart: RequestBody, fileName: String): UploadResult =
        upload(Config.FILE_UPLOAD_API_URL, "file", filePart, fileName, null)

    private suspend fun upload(
        endpoint: String,
        field: String,
        filePart: RequestBody,
        fileName: String,
        token: String?,
    ): UploadResult = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(field, fileName, filePart)
            .build()
        val req = Request.Builder()
            .url(endpoint)
            .header("User-Agent", Config.USER_AGENT)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .post(body)
            .build()
        runCatching {
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                val statusFalse = ((json as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull == "false"
                val url = json?.let { findUploadUrl(it) }
                when {
                    !res.isSuccessful -> UploadResult(error = errorMessage(json) ?: "HTTP ${res.code}")
                    statusFalse || url == null -> UploadResult(error = errorMessage(json) ?: "No URL in response")
                    else -> UploadResult(url = absolute(url))
                }
            }
        }.getOrElse { UploadResult(error = it.message ?: "Network error") }
    }

    /** The server answers in a few shapes ({url}, {data:{url}}, {data:{links:{url}}}…);
     *  mirror media.ts normalizeUploadResponse over the keys it probes. */
    private fun findUploadUrl(root: JsonElement): String? {
        val obj = root as? JsonObject ?: return null
        val nested = obj["data"] as? JsonObject
        val links = (nested?.get("links") ?: obj["links"]) as? JsonObject
        val candidates = listOfNotNull(
            obj["url"], obj["imageUrl"], obj["image_url"], obj["fileUrl"], obj["videoUrl"],
            nested?.get("url"), nested?.get("path"), nested?.get("pathname"),
            links?.get("url"), links?.get("image_url"), links?.get("imageUrl"),
            links?.get("thumbnail_url"),
        )
        for (candidate in candidates) {
            val s = (candidate as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (s.isNotEmpty()) return Regex("""https?://[^\s"'()\[\]]+""").find(s)?.value ?: s
        }
        return null
    }

    private fun errorMessage(root: JsonElement?): String? {
        val obj = root as? JsonObject ?: return null
        return ((obj["message"] ?: obj["error"]) as? JsonPrimitive)?.contentOrNull
    }

    private fun absolute(url: String) =
        if (url.startsWith("http")) url
        else Config.UPLOAD_BASE_URL + (if (url.startsWith("/")) url else "/$url")
}
