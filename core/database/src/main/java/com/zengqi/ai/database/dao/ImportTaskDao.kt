package com.zengqi.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zengqi.ai.database.model.ImportTaskEntity

@Dao
interface ImportTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ImportTaskEntity): Long

    @Query("SELECT * FROM import_tasks WHERE characterId = :characterId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestForCharacter(characterId: Long): ImportTaskEntity?

    @Query("SELECT * FROM import_tasks WHERE characterId = :characterId ORDER BY id DESC LIMIT 1")
    fun observeLatestForCharacter(characterId: Long): kotlinx.coroutines.flow.Flow<ImportTaskEntity?>

    @Query("UPDATE import_tasks SET status = :status, updatedAt = :now WHERE characterId = :characterId")
    suspend fun updateStatus(characterId: Long, status: String, now: Long = System.currentTimeMillis())
}
