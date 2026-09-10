package com.zengqi.ai.domain.model

/**
 * 曾栖人物领域模型。
 *
 * 由历史聊天记录构建的人物：relationship/description 来自用户输入，
 * styleProfileSummary 由服务端 Style 分析回填的摘要文本。
 */
data class Character(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
    val relationship: String?,
    val description: String?,
    val styleProfileSummary: String?,
    val createdAt: Long
)

/**
 * 一段历史对话消息（导入的原始聊天记录）。
 */
data class ImportedMessage(
    val id: Long,
    val characterId: Long,
    val isFromCharacter: Boolean,
    val content: String,
    val sentAt: Long
)

enum class MemoryType {
    EVENT,
    PERSON,
    PLACE,
    PREFERENCE,
    RELATIONSHIP,
    EMOTION
}

/**
 * 长期记忆领域模型。sourceMessageIds 指向产生该记忆的原始消息，
 * 支持从记忆回溯到当时的对话。
 */
data class Memory(
    val id: Long,
    val characterId: Long,
    val type: MemoryType,
    val title: String,
    val content: String,
    val eventTime: Long?,
    val importance: Float,
    val sourceMessageIds: List<Long>
)

/**
 * 人物构建状态机。IMPORTING → PARSING → ANALYZING_STYLE →
 * EXTRACTING_MEMORY → EMBEDDING → READY / FAILED。
 */
enum class BuildStatus {
    IMPORTING,
    PARSING,
    ANALYZING_STYLE,
    EXTRACTING_MEMORY,
    EMBEDDING,
    READY,
    FAILED
}
