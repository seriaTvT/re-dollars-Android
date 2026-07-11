package mk.ry.redollars.net

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
 * A single chat message (subset of the backend EnrichedMessage currently used by the app).
 * Unknown fields (image_meta, link_previews...) are ignored.
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
    val reactions: List<ReactionDto> = emptyList(),
)

/** One user's emoji reaction. `emoji` is unicode or a smiley code like `(bgm67)`. */
@Serializable
data class ReactionDto(
    val emoji: String = "",
    @SerialName("user_id") val userId: Long = 0,
    val nickname: String = "",
    val avatar: String? = null,
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

/** POST /messages/:id/reactions result. action: added | removed | replaced. */
@Serializable
data class ReactionToggleResponse(
    val status: Boolean = false,
    val action: String = "",
    val data: ReactionDto? = null,
)

/** GET /users/:id — backend profile cache. nickname is the display name (username is the slug). */
@Serializable
data class UserLookupResponse(
    val status: Boolean = false,
    val data: UserProfileDto? = null,
)

@Serializable
data class UserProfileDto(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: UserAvatarDto? = null,
)

@Serializable
data class UserAvatarDto(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)
