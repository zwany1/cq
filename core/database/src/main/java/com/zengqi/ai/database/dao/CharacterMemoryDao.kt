package com.zengqi.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zengqi.ai.database.model.CharacterMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<CharacterMemoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: CharacterMemoryEntity): Long

    @Query("DELETE FROM character_memories WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: Long)

    @Query("SELECT * FROM character_memories WHERE characterId = :characterId ORDER BY eventTime DESC")
    fun getMemoriesForCharacter(characterId: Long): Flow<List<CharacterMemoryEntity>>

    @Query("SELECT * FROM character_memories WHERE characterId = :characterId ORDER BY eventTime DESC")
    suspend fun getMemoriesForCharacterSync(characterId: Long): List<CharacterMemoryEntity>

    @Query(
        "SELECT * FROM character_memories WHERE characterId = :characterId " +
            "AND eventTime >= :startOfDay AND eventTime < :endOfDay ORDER BY eventTime DESC"
    )
    suspend fun getMemoriesBetween(characterId: Long, startOfDay: Long, endOfDay: Long): List<CharacterMemoryEntity>

    @Query(
        "SELECT * FROM character_memories WHERE characterId = :characterId " +
            "ORDER BY importance DESC LIMIT 1 OFFSET :offset"
    )
    suspend fun getHighImportance(characterId: Long, offset: Int): CharacterMemoryEntity?

    @Query(
        "SELECT * FROM character_memories WHERE characterId = :characterId " +
            "AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY importance DESC LIMIT :limit"
    )
    suspend fun searchByKeyword(characterId: Long, query: String, limit: Int): List<CharacterMemoryEntity>

    @Query("SELECT COUNT(*) FROM character_memories WHERE characterId = :characterId")
    suspend fun countForCharacter(characterId: Long): Int

    @Query("SELECT COUNT(*) FROM character_memories WHERE characterId = :characterId")
    fun countForCharacterFlow(characterId: Long): Flow<Int>
}
