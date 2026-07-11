package mk.ry.redollars.spike.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import mk.ry.redollars.spike.net.AppJson
import mk.ry.redollars.spike.net.MessageDto
import mk.ry.redollars.spike.net.ReplyDetails

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
    /** ReplyDetails serialized to JSON, or null. Kept as a blob to avoid a join. */
    val replyJson: String?,
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
    replyJson = replyDetails?.let { AppJson.encodeToString(ReplyDetails.serializer(), it) },
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
    replyDetails = replyJson?.let {
        runCatching { AppJson.decodeFromString(ReplyDetails.serializer(), it) }.getOrNull()
    },
)
