package com.zengqi.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天记录导入任务状态，与 BuildStatus 一一对应（小写下划线存储）。
 */
@Entity(
    tableName = "import_tasks",
    indices = [
        Index(value = ["characterId"], name = "index_import_tasks_character_id")
    ]
)
data class ImportTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val status: String,
    val totalMessages: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
