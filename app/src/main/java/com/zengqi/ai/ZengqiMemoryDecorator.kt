package com.zengqi.ai

import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.model.CharacterMemoryEntity
import com.zengqi.ai.domain.MemoryProvider
import com.zengqi.ai.network.zengqi.ZengqiEngine

/**
 * 曾栖记忆装饰器：在原生跨会话记忆之上，合并由历史聊天记录提取的人物记忆。
 *
 * 检索用本地 n-gram 向量余弦（[ZengqiEngine.textVector]），无向量数据时回退关键词匹配；
 * 只在角色有导入历史时生效，普通伴侣行为不变。
 */
class ZengqiMemoryDecorator(
    private val delegate: MemoryProvider,
    private val characterMemoryDao: com.zengqi.ai.database.dao.CharacterMemoryDao,
    private val importedMessageDao: com.zengqi.ai.database.dao.ImportedMessageDao
) : MemoryProvider {

    override fun initialize() = delegate.initialize()

    override suspend fun getMemoryContext(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int
    ): String {
        val base = delegate.getMemoryContext(companionId, groupId, query, limit)
        if (companionId == null || query.isBlank()) return base

        val importedCount = importedMessageDao.countForCharacter(companionId)
        if (importedCount == 0) return base

        val zengqiSections = mutableListOf<String>()

        val all = characterMemoryDao.getMemoriesForCharacterSync(companionId)
        if (all.isNotEmpty()) {
            val picked = searchMemories(all, query, topK = 3)
            if (picked.isNotEmpty()) {
                zengqiSections += buildString {
                    append("=== 与TA有关的记忆 ===\n")
                    picked.forEach { m ->
                        val day = m.eventTime?.let {
                            java.time.Instant.ofEpochMilli(it)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate().toString()
                        } ?: "时间未知"
                        append("【${m.type}】${m.title}（$day）：${m.content}\n")
                    }
                }
            }
        }

        // TA 的真实发言片段：风格模仿的 few-shot 素材
        val samples = sampleVoice(companionId)
        if (samples.isNotEmpty()) {
            zengqiSections += buildString {
                append("=== TA的真实发言示例（模仿这个说话方式，不要照抄内容） ===\n")
                samples.forEach { append("TA：$it\n") }
            }
        }

        if (zengqiSections.isEmpty()) return base
        val zengqi = "\n" + zengqiSections.joinToString("\n")
        return base + zengqi
    }

    /**
     * 取 TA 的代表性发言：优先短句（口语风格最浓），最长 8 条。
     */
    private suspend fun sampleVoice(companionId: Long): List<String> {
        val recent = importedMessageDao.getRecentFromCharacter(companionId, 40)
        return recent
            .map { it.content.trim() }
            .filter { it.length in 2..60 && !it.startsWith("[") }
            .sortedBy { it.length }
            .take(8)
            .reversed()
    }

    /**
     * 语义检索：向量余弦优先，向量缺失的记录用关键词兜底。
     */
    private fun searchMemories(
        memories: List<CharacterMemoryEntity>,
        query: String,
        topK: Int
    ): List<CharacterMemoryEntity> {
        val queryVec = ZengqiEngine.textVector(query)
        val scored = memories.mapNotNull { m ->
            val vec = m.embedding?.let { ZengqiEngine.decodeVector(it) }
            val score = if (vec != null) {
                ZengqiEngine.cosine(queryVec, vec)
            } else {
                keywordScore(query, m)
            }
            if (score > 0.05f) m to score else null
        }
        return scored.sortedByDescending { it.second }.take(topK).map { it.first }
    }

    private fun keywordScore(query: String, m: CharacterMemoryEntity): Float {
        val text = m.title + m.content
        if (text.isBlank()) return 0f
        var hits = 0
        for (n in 2..3) {
            var i = 0
            while (i + n <= query.length) {
                if (text.contains(query.substring(i, i + n))) hits++
                i++
            }
        }
        return if (hits == 0) 0f else (hits.toFloat() / (query.length * 2))
    }

    override suspend fun extractAndSaveFromConversation(
        userInput: String,
        aiResponse: String,
        companionId: Long,
        groupId: Long?
    ) {
        // 记忆提取委托原生 MemoryManager（提取的是“与用户的对话”记忆，
        // 人物历史记忆只由导入流水线产生，不在此追加）
        delegate.extractAndSaveFromConversation(userInput, aiResponse, companionId, groupId)
    }
}
