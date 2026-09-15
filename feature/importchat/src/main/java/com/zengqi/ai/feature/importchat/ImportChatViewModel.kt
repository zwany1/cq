package com.zengqi.ai.feature.importchat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.model.CharacterMemoryEntity
import com.zengqi.ai.database.model.ImportTaskEntity
import com.zengqi.ai.database.model.ImportedMessageEntity
import com.zengqi.ai.database.model.MemoryEntry
import com.zengqi.ai.domain.model.BuildStatus
import com.zengqi.ai.network.zengqi.ZengqiEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 导入页 UI 状态机：选文件 → 预览（识别双方）→ 选她 → 构建 → 完成/失败。
 */
data class ImportChatUiState(
    val phase: ImportPhase = ImportPhase.PICK_FILE,
    val fileName: String? = null,
    val totalMessages: Int = 0,
    val speakers: List<String> = emptyList(),
    val selectedSpeaker: String? = null,
    val buildStatus: BuildStatus? = null,
    val error: String? = null
)

enum class ImportPhase {
    PICK_FILE,
    PREVIEW,
    SELECT_SPEAKER,
    BUILDING,
    DONE,
    FAILED
}

class ImportChatViewModel(
    application: Application,
    private val characterId: Long
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImportChatUiState())
    val uiState: StateFlow<ImportChatUiState> = _uiState.asStateFlow()

    private val database = AppDatabase.getDatabase(application)
    private val deviceId = com.zengqi.ai.common.DeviceIdProvider.getDeviceId(application)
    private val importTaskDao = database.importTaskDao()
    private val importedMessageDao = database.importedMessageDao()
    private val characterMemoryDao = database.characterMemoryDao()
    private val companionDao = database.companionDao()

    private var parsed: ParseResult? = null

    fun loadFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = readText(uri)
                val result = ChatFileParser.parse(content)
                parsed = result
                if (result.messages.isEmpty()) {
                    _uiState.value = ImportChatUiState(
                        phase = ImportPhase.FAILED,
                        error = "未能从文件中解析出聊天记录，请确认导出格式"
                    )
                    return@launch
                }
                _uiState.value = ImportChatUiState(
                    phase = if (result.speakers.size >= 2) ImportPhase.SELECT_SPEAKER else ImportPhase.PREVIEW,
                    fileName = queryDisplayName(uri),
                    totalMessages = result.messages.size,
                    speakers = result.speakers
                )
            } catch (e: Exception) {
                SecureLog.e("ImportChat", "Failed to read file", e)
                _uiState.value = ImportChatUiState(
                    phase = ImportPhase.FAILED,
                    error = "读取文件失败：${e.message}"
                )
            }
        }
    }

    fun selectSpeaker(name: String) {
        _uiState.value = _uiState.value.copy(selectedSpeaker = name)
    }

    /**
     * 确认"她"的身份后，执行本地构建流水线：
     * 写入 imported_messages → 风格分析（写入 speakingStyle）→ 记忆提取（带向量入库）。
     */
    fun startBuild() {
        val result = parsed ?: return
        val speaker = _uiState.value.selectedSpeaker ?: return
        _uiState.value = _uiState.value.copy(phase = ImportPhase.BUILDING, buildStatus = BuildStatus.PARSING)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                SecureLog.i("ImportChat", "=== 构建开始 === characterId=$characterId, speaker=$speaker, messages=${result.messages.size}")

                // 1. 原始消息入库（事务包裹，避免 Room Flow 递归更新）
                database.withTransaction {
                    importedMessageDao.deleteForCharacter(characterId)
                    val entities = result.messages.map { m ->
                        ImportedMessageEntity(
                            characterId = characterId,
                            isFromCharacter = m.sender == speaker,
                            content = m.content,
                            sentAt = m.timestamp
                        )
                    }
                    val insertedIds = importedMessageDao.insertAll(entities)
                    SecureLog.i("ImportChat", "步骤1完成: 写入${entities.size}条消息, ids=${insertedIds.size}")
                }

                importTaskDao.upsert(
                    ImportTaskEntity(
                        characterId = characterId,
                        status = BuildStatus.PARSING.name.lowercase(),
                        totalMessages = result.messages.size
                    )
                )

                // 2. 风格分析（失败回退本地统计渲染，不阻断构建）
                _uiState.value = _uiState.value.copy(buildStatus = BuildStatus.ANALYZING_STYLE)
                val styleSamples = ChatFileParser.buildStyleSamples(result.messages, speaker)
                SecureLog.i("ImportChat", "步骤2开始: 风格分析, samples=${styleSamples.size}")
                val profile = try {
                    ZengqiEngine.analyzeStyle(styleSamples)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.w("ImportChat", "风格分析失败，使用默认风格: ${e.message}")
                    ZengqiEngine.StyleProfile()
                }
                SecureLog.i("ImportChat", "步骤2完成: 风格分析, sentenceLength=${profile.sentenceLength}")
                val companion = companionDao.getCompanionById(characterId)
                    ?: throw IllegalStateException("人物不存在：$characterId")
                companionDao.updateCompanion(
                    companion.copy(
                        speakingStyle = ZengqiEngine.renderStyleText(profile),
                        updatedAt = System.currentTimeMillis()
                    )
                )

                // 3. 记忆提取与入库
                _uiState.value = _uiState.value.copy(buildStatus = BuildStatus.EXTRACTING_MEMORY)
                characterMemoryDao.deleteForCharacter(characterId)
                val candidates = ChatFileParser.buildMemoryCandidates(result.messages, speaker)
                SecureLog.i("ImportChat", "步骤3开始: 记忆提取, candidates=${candidates.size}")
                val extracted = ZengqiEngine.extractMemories(candidates)
                SecureLog.i("ImportChat", "步骤3完成: 提取${extracted.size}条记忆")

                // LLM 提取为空时用本地规则生成保底记忆（高频话题、称呼、活跃日期）
                val memories = if (extracted.isNotEmpty()) extracted else {
                    SecureLog.w("ImportChat", "LLM 提取为空，使用本地保底记忆")
                    buildFallbackMemories(result.messages, speaker)
                }

                // 4. 向量入库（事务包裹）+ 同步进核心记忆页
                _uiState.value = _uiState.value.copy(buildStatus = BuildStatus.EMBEDDING)
                database.withTransaction {
                    val memoryEntities = memories.mapIndexed { idx, m ->
                        CharacterMemoryEntity(
                            characterId = characterId,
                            type = m.type,
                            title = m.title,
                            content = m.content,
                            eventTime = m.eventTimeMs,
                            importance = m.importance,
                            sourceMessageIds = "[]",
                            embedding = ZengqiEngine.encodeVector(ZengqiEngine.textVector("${m.title}。${m.content}"))
                        ).let { it.copy(sourceMessageIds = encodeSourceIds(sourceIdsFor(memories, listOf(), idx))) }
                    }
                    characterMemoryDao.insertAll(memoryEntities)

                    // 同步写一份到 memory_entries，核心记忆页可见
                    val memoryDao = database.memoryDao()
                    memoryDao.deleteMemoriesForCompanion(characterId, deviceId)
                    memories.forEach { m ->
                        memoryDao.insertMemory(
                            MemoryEntry(
                                companionId = characterId,
                                content = "${m.title}：${m.content}",
                                category = mapMemoryCategory(m.type),
                                importance = m.importance,
                                deviceId = deviceId
                            )
                        )
                    }
                    SecureLog.i("ImportChat", "步骤4完成: 写入${memoryEntities.size}条记忆向量, ${memories.size}条进核心记忆")
                }

                importTaskDao.updateStatus(characterId, BuildStatus.READY.name.lowercase())
                _uiState.value = _uiState.value.copy(phase = ImportPhase.DONE, buildStatus = BuildStatus.READY)
                SecureLog.i("ImportChat", "=== 构建成功 ===")
            } catch (e: Exception) {
                SecureLog.e("ImportChat", "=== 构建失败 ===", e)
                importTaskDao.updateStatus(characterId, BuildStatus.FAILED.name.lowercase())
                _uiState.value = _uiState.value.copy(
                    phase = ImportPhase.FAILED,
                    buildStatus = BuildStatus.FAILED,
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    /**
     * 记忆与原始消息的关联：以消息序号区间近似映射，保证每条记忆有可回溯落点，
     * sourceMessageIds 指向 imported_messages.id。
     */
    private fun sourceIdsFor(
        memories: List<ZengqiEngine.ExtractedMemory>,
        insertedIds: List<Long>,
        idx: Int
    ): List<Long> {
        if (insertedIds.isEmpty()) return emptyList()
        val perMemory = insertedIds.size.toDouble() / memories.size
        val start = (idx * perMemory).toInt().coerceIn(0, insertedIds.size - 1)
        val end = ((idx + 1) * perMemory).toInt().coerceIn(start + 1, insertedIds.size)
        return insertedIds.subList(start, end).take(20)
    }

    /**
     * 曾栖记忆类型 → 核心记忆页分类映射。
     */
    private fun mapMemoryCategory(type: String): com.zengqi.ai.database.model.MemoryCategory =
        when (type) {
            "EVENT" -> com.zengqi.ai.database.model.MemoryCategory.EVENT
            "EMOTION" -> com.zengqi.ai.database.model.MemoryCategory.EMOTION
            "PREFERENCE" -> com.zengqi.ai.database.model.MemoryCategory.PREFERENCE
            "RELATIONSHIP" -> com.zengqi.ai.database.model.MemoryCategory.RELATIONSHIP
            "PERSON" -> com.zengqi.ai.database.model.MemoryCategory.RELATIONSHIP
            "PLACE" -> com.zengqi.ai.database.model.MemoryCategory.EVENT
            else -> com.zengqi.ai.database.model.MemoryCategory.FACT
        }

    /**
     * 本地保底记忆：LLM 提取为空时从原始消息生成，保证回忆与核心记忆不为空。
     * 规则：TA 的称呼/口头禅（高频短语）、对话最活跃的日期（EVENT）。
     */
    private fun buildFallbackMemories(
        messages: List<ParsedChatMessage>,
        speaker: String
    ): List<ZengqiEngine.ExtractedMemory> {
        val result = mutableListOf<ZengqiEngine.ExtractedMemory>()

        // 1. 对话最活跃的 3 个日期 → EVENT 记忆
        val byDay = messages.groupBy {
            java.time.Instant.ofEpochMilli(it.timestamp)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
        byDay.entries.sortedByDescending { it.value.size }.take(3).forEach { (day, msgs) ->
            val firstOfSpeaker = msgs.firstOrNull { it.sender == speaker }
            result += ZengqiEngine.ExtractedMemory(
                type = "EVENT",
                title = "聊了很多的一天",
                content = "$day 这天你们聊了 ${msgs.size} 条消息${firstOfSpeaker?.content?.take(20)?.let { "，TA说：$it" } ?: ""}",
                eventTimeMs = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                importance = 0.7f
            )
        }

        // 2. TA 的标志性发言（非表情、非纯数字、长度适中的前 5 条）→ 其他记忆
        val voice = messages.filter { it.sender == speaker }
            .map { it.content.trim() }
            .filter { it.length in 3..30 && !it.startsWith("[") && !it.matches(Regex("""[\d\W]+""")) }
            .distinct()
            .take(5)
        voice.forEach { line ->
            result += ZengqiEngine.ExtractedMemory(
                type = "EMOTION",
                title = "TA说过的话",
                content = "「$line」",
                eventTimeMs = null,
                importance = 0.5f
            )
        }
        return result
    }

    fun retry() {
        _uiState.value = ImportChatUiState(phase = ImportPhase.PICK_FILE)
    }

    private fun readText(uri: Uri): String =
        getApplication<Application>().contentResolver.openInputStream(uri)!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
    }.getOrNull()

    companion object {
        /**
         * sourceMessageIds JSON 编解码工具，供记忆模块回溯原始消息。
         */
        fun encodeSourceIds(ids: List<Long>): String = buildJsonArray {
            ids.forEach { add(JsonPrimitive(it)) }
        }.toString()

        fun decodeSourceIds(json: String): List<Long> = runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
                (element as? JsonPrimitive)?.content?.toLongOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
