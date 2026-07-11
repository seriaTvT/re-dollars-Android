package mk.ry.redollars.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** The most recent [limit] messages, returned in ascending id order for display. */
    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT :limit) ORDER BY id ASC")
    fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    @Upsert
    suspend fun upsertAll(items: List<MessageEntity>)

    @Query("SELECT MAX(id) FROM messages")
    suspend fun maxId(): Long?

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: Long)
}
