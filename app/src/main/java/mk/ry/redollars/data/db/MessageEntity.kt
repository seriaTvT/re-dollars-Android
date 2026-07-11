package mk.ry.redollars.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.builtins.ListSerializer
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.ReactionDto
import mk.ry.redollars.net.ReplyDetails

private val reactionsSerializer = ListSerializer(ReactionDto.serializer())

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: Long,
    val timestamp: Long,
    val uid: Long,
    val nickname: String,
    val avatar: String,
    val message: String,
    val color: String?,
    val type: String,
    val isDeleted: Boolean,
    /** Local db id of the message this one replies to, if any. */
    val replyToId: Long?,
    /** ReplyDetails serialized to JSON, or null. Kept as a blob to avoid a join. */
    val replyJson: String?,
    /** List<ReactionDto> serialized to JSON, or null when empty. */
    val reactionsJson: String?,
)

fun MessageDto.toEntity() = MessageEntity(
    id = id,
    timestamp = timestamp,
    uid = uid,
    nickname = nickname,
    avatar = avatar,
    message = message,
    color = color,
    type = type,
    isDeleted = isDeleted,
    replyToId = replyToId,
    replyJson = replyDetails?.let { AppJson.encodeToString(ReplyDetails.serializer(), it) },
    reactionsJson = reactions.takeIf { it.isNotEmpty() }
        ?.let { AppJson.encodeToString(reactionsSerializer, it) },
)

fun MessageEntity.toDto() = MessageDto(
    id = id,
    timestamp = timestamp,
    uid = uid,
    nickname = nickname,
    avatar = avatar,
    message = message,
    color = color,
    type = type,
    isDeleted = isDeleted,
    replyToId = replyToId,
    replyDetails = replyJson?.let {
        runCatching { AppJson.decodeFromString(ReplyDetails.serializer(), it) }.getOrNull()
    },
    reactions = reactionsJson?.let {
        runCatching { AppJson.decodeFromString(reactionsSerializer, it) }.getOrNull()
    } ?: emptyList(),
)
