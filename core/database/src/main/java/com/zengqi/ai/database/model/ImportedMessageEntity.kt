package com.zengqi.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导入的原始历史消息。isFromCharacter 标记该条是否为“她”所说。
 */
@Entity(
    tableName = "imported_messages",
    indices = [
        Index(value = ["characterId"], name = "index_imported_messages_character_id"),
        Index(value = ["sentAt"], name = "index_imported_messages_sent_at")
    ]
)
data class ImportedMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val isFromCharacter: Boolean,
    val content: String,
    val sentAt: Long
)
