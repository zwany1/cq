package com.zengqi.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人物长期记忆。sourceMessageIds 存 JSON 数组字符串，
 * 指向 imported_messages 的消息 ID，支持记忆回溯原始对话。
 * embedding 存 n-gram 向量的 JSON 数组字符串，供本地余弦检索。
 */
@Entity(
    tableName = "character_memories",
    indices = [
        Index(value = ["characterId"], name = "index_character_memories_character_id"),
        Index(value = ["eventTime"], name = "index_character_memories_event_time")
    ]
)
data class CharacterMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val type: String,
    val title: String,
    val content: String,
    val eventTime: Long?,
    val importance: Float,
    val sourceMessageIds: String,
    val embedding: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
