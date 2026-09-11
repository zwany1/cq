package com.zengqi.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zengqi.ai.database.model.ImportedMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ImportedMessageEntity>): List<Long>

    @Query("DELETE FROM imported_messages WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: Long)

    @Query("SELECT * FROM imported_messages WHERE characterId = :characterId ORDER BY sentAt ASC")
    fun getMessagesForCharacter(characterId: Long): Flow<List<ImportedMessageEntity>>

    @Query(
        "SELECT * FROM imported_messages WHERE characterId = :characterId AND isFromCharacter = 1 " +
            "ORDER BY sentAt DESC LIMIT :limit"
    )
    suspend fun getRecentFromCharacter(characterId: Long, limit: Int): List<ImportedMessageEntity>

    @Query(
        "SELECT * FROM imported_messages WHERE characterId = :characterId " +
            "AND sentAt >= :startOfDay AND sentAt < :endOfDay ORDER BY sentAt ASC"
    )
    suspend fun getMessagesBetween(characterId: Long, startOfDay: Long, endOfDay: Long): List<ImportedMessageEntity>

    @Query("SELECT COUNT(*) FROM imported_messages WHERE characterId = :characterId")
    suspend fun countForCharacter(characterId: Long): Int

    @Query("SELECT * FROM imported_messages WHERE id IN (:ids) ORDER BY sentAt ASC")
    suspend fun getByIds(ids: List<Long>): List<ImportedMessageEntity>

    @Query("SELECT * FROM imported_messages WHERE id = :id")
    suspend fun getById(id: Long): ImportedMessageEntity?

    @Query("SELECT DISTINCT characterId FROM imported_messages")
    suspend fun getDistinctSpeakerIds(): List<Long>
}
