package mk.ry.redollars.spike.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.SerialName

/**
 * The backend serializes BIGINT columns (timestamp, bangumi_id, latest_id...) as
 * JSON *strings* in some places and *numbers* in others. This serializer accepts
 * either and yields a Long. See ANDROID_APP_SPEC.md §4.
 */
object FlexLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return jsonDecoder.decodeJsonElement().jsonPrimitive.content.trim().toLong()
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * A single chat message (subset of the backend EnrichedMessage relevant to the spike).
 * Unknown fields (image_meta, link_previews, reply_details, reactions...) are ignored.
 */
@Serializable
data class MessageDto(
    val id: Long,
    @Serializable(with = FlexLongSerializer::class) val timestamp: Long,
    val uid: Long,
    val nickname: String = "",
    val avatar: String = "",
    val message: String = "",
    val color: String? = null,
    val type: String = "text",
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("reply_details") val replyDetails: ReplyDetails? = null,
)

/**
 * A reply's quoted source. On dollars, a reply body is `[quote=<id>][/quote]<text>`
 * (empty inline quote); the actual quoted author/content arrives here instead.
 */
@Serializable
data class ReplyDetails(
    val uid: Long = 0,
    val nickname: String = "",
    val avatar: String = "",
    val content: String = "",
    val firstImage: String? = null,
)

@Serializable
data class MessageStatus(
    val status: Boolean = false,
    @SerialName("latest_id") val latestId: Long = 0,
    @SerialName("new_count") val newCount: Long = 0,
    @SerialName("server_time") val serverTime: Long = 0,
)

@Serializable
data class ConfirmResponse(
    val status: Boolean = false,
    val found: Boolean = false,
    val message: MessageDto? = null,
)
