package com.zengqi.ai.feature.importchat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.model.CharacterMemoryEntity
import com.zengqi.ai.database.model.ImportTaskEntity
import com.zengqi.ai.database.model.ImportedMessageEntity
import com.zengqi.ai.domain.model.BuildStatus
import com.zengqi.ai.network.zengqi.ZengqiEngine
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
                // 1. 原始消息入库（重导入时清空旧数据）
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
                importTaskDao.upsert(
                    ImportTaskEntity(
                        characterId = characterId,
                        status = BuildStatus.PARSING.name.lowercase(),
                        totalMessages = entities.size
                    )
                )

                // 2. 风格分析
                _uiState.value = _uiState.value.copy(buildStatus = BuildStatus.ANALYZING_STYLE)
                val styleSamples = ChatFileParser.buildStyleSamples(result.messages, speaker)
                val profile = ZengqiEngine.analyzeStyle(styleSamples)
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
                val memories = ZengqiEngine.extractMemories(candidates)

                _uiState.value = _uiState.value.copy(buildStatus = BuildStatus.EMBEDDING)
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
                    ).let { it.copy(sourceMessageIds = encodeSourceIds(sourceIdsFor(memories, insertedIds, idx))) }
                }
                characterMemoryDao.insertAll(memoryEntities)

                importTaskDao.updateStatus(characterId, BuildStatus.READY.name.lowercase())
                _uiState.value = _uiState.value.copy(phase = ImportPhase.DONE, buildStatus = BuildStatus.READY)
            } catch (e: Exception) {
                SecureLog.e("ImportChat", "Build failed", e)
                importTaskDao.updateStatus(characterId, BuildStatus.FAILED.name.lowercase())
                _uiState.value = _uiState.value.copy(
                    phase = ImportPhase.FAILED,
                    buildStatus = BuildStatus.FAILED,
                    error = e.message ?: "构建失败"
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
