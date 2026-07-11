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
import kotlinx.serialization.json.JsonPrimitive
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

/** Nullable variant of [FlexLongSerializer] for optional BIGINT columns (reply_to_id). */
object FlexLongOrNullSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexLongOrNull", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content.trim().toLongOrNull()
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value != null) encoder.encodeLong(value)
    }
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
    @SerialName("reply_to_id")
    @Serializable(with = FlexLongOrNullSerializer::class)
    val replyToId: Long? = null,
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

/** A mention/reply notification, unified from the two wire shapes (REST nested, WS flat). */
data class NotificationItem(
    val id: Long,
    val type: String, // mention | reply
    val timestamp: Long,
    val messageId: Long,
    val uid: Long,
    val nickname: String,
    val avatar: String,
    val content: String,
)

/** GET /notifications?uid= response: unread only, newest first, max 50. */
@Serializable
data class NotificationsResponse(
    val status: Boolean = false,
    val notifications: List<NotificationRestDto> = emptyList(),
)

@Serializable
data class NotificationRestDto(
    val id: Long = 0,
    val type: String = "mention",
    @Serializable(with = FlexLongSerializer::class) val timestamp: Long = 0,
    @SerialName("message_id") @Serializable(with = FlexLongSerializer::class) val messageId: Long = 0,
    val message: NotificationMessageDto? = null,
) {
    fun toItem() = NotificationItem(
        id = id,
        type = type,
        timestamp = timestamp,
        messageId = messageId,
        uid = message?.uid?.toLongOrNull() ?: 0,
        nickname = message?.nickname ?: "",
        avatar = message?.avatar ?: "",
        content = message?.content ?: "",
    )
}

@Serializable
data class NotificationMessageDto(
    val id: String = "",
    val uid: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val content: String = "",
)

/** WS `notification` frame payload (flat). */
@Serializable
data class NotificationWsDto(
    val id: Long = 0,
    @SerialName("message_id") @Serializable(with = FlexLongSerializer::class) val messageId: Long = 0,
    @Serializable(with = FlexLongSerializer::class) val uid: Long = 0,
    val nickname: String = "",
    val avatar: String = "",
    val content: String = "",
    @Serializable(with = FlexLongSerializer::class) val timestamp: Long = 0,
    val type: String = "mention",
) {
    fun toItem() = NotificationItem(
        id = id,
        type = type,
        timestamp = timestamp,
        messageId = messageId,
        uid = uid,
        nickname = nickname,
        avatar = avatar,
        content = content,
    )
}

/** GET /auth/me with a Bearer token. */
@Serializable
data class AuthMeResponse(
    val status: Boolean = false,
    val user: AuthUserDto? = null,
)

@Serializable
data class AuthUserDto(
    val id: Long = 0,
    val nickname: String = "",
    val avatar: String = "",
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

/** GET /favorites?uid= — saved sticker image URLs (favorites.ts). */
@Serializable
data class FavoritesResponse(
    val status: Boolean = false,
    val data: List<String> = emptyList(),
)

/** GET /users/search — mention autocomplete. username is the login slug (often the
 *  numeric uid for users who never set one); nickname is the display name. */
@Serializable
data class UserSearchResponse(
    val status: Boolean = false,
    val data: List<UserSearchDto> = emptyList(),
)

@Serializable
data class UserSearchDto(
    @Serializable(with = FlexLongSerializer::class) val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
