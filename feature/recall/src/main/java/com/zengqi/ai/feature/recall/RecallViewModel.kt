package com.zengqi.ai.feature.recall

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.model.ImportedMessageEntity
import com.zengqi.ai.domain.model.ImportedMessage
import com.zengqi.ai.domain.model.Memory
import com.zengqi.ai.domain.model.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * “那天 / 回忆”召回 UI 状态。
 */
sealed interface RecallUiState {
    data object Idle : RecallUiState
    data object Loading : RecallUiState
    data class ThatDay(
        val date: LocalDate,
        val summary: String?,
        val messages: List<ImportedMessage>,
        val memories: List<Memory>
    ) : RecallUiState

    data class RandomMemory(
        val title: String,
        val date: LocalDate?,
        val content: String,
        val importance: Float
    ) : RecallUiState

    data class Error(val message: String) : RecallUiState
}

class RecallViewModel(
    application: Application,
    private val characterId: Long
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<RecallUiState>(RecallUiState.Idle)
    val uiState: StateFlow<RecallUiState> = _uiState.asStateFlow()

    private val database = AppDatabase.getDatabase(application)
    private val importedMessageDao = database.importedMessageDao()
    private val characterMemoryDao = database.characterMemoryDao()

    /**
     * “那天”：本地按日期过滤原始消息与相关记忆。
     */
    fun loadThatDay(date: LocalDate) {
        _uiState.value = RecallUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val zone = ZoneId.systemDefault()
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

                val messages: List<ImportedMessageEntity> =
                    importedMessageDao.getMessagesBetween(characterId, start, end)

                if (messages.isEmpty()) {
                    _uiState.value = RecallUiState.Error("那一天没有留下对话")
                    return@launch
                }

                val memories = characterMemoryDao.getMemoriesBetween(characterId, start, end)
                    .map { m ->
                        Memory(
                            id = m.id,
                            characterId = m.characterId,
                            type = runCatching { MemoryType.valueOf(m.type.uppercase()) }
                                .getOrDefault(MemoryType.EVENT),
                            title = m.title,
                            content = m.content,
                            eventTime = m.eventTime,
                            importance = m.importance,
                            sourceMessageIds = decodeSourceIds(m.sourceMessageIds)
                        )
                    }
                _uiState.value = RecallUiState.ThatDay(
                    date = date,
                    summary = memories.firstOrNull()?.title,
                    messages = messages.map { m ->
                        ImportedMessage(
                            id = m.id,
                            characterId = m.characterId,
                            isFromCharacter = m.isFromCharacter,
                            content = m.content,
                            sentAt = m.sentAt
                        )
                    },
                    memories = memories
                )
            } catch (e: Exception) {
                SecureLog.e("Recall", "loadThatDay failed", e)
                _uiState.value = RecallUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    /**
     * “回忆”：按重要性加权随机召回一条记忆。
     */
    fun loadRandomMemory() {
        _uiState.value = RecallUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = characterMemoryDao.countForCharacter(characterId)
                if (count == 0) {
                    _uiState.value = RecallUiState.Error("还没有整理出回忆")
                    return@launch
                }
                val entity = characterMemoryDao.getHighImportance(characterId, (0 until count).random())
                    ?: characterMemoryDao.getHighImportance(characterId, 0)
                if (entity == null) {
                    _uiState.value = RecallUiState.Error("还没有整理出回忆")
                    return@launch
                }
                _uiState.value = RecallUiState.RandomMemory(
                    title = entity.title,
                    date = entity.eventTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
                    content = entity.content,
                    importance = entity.importance
                )
            } catch (e: Exception) {
                SecureLog.e("Recall", "loadRandomMemory failed", e)
                _uiState.value = RecallUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun reset() {
        _uiState.value = RecallUiState.Idle
    }

    private fun decodeSourceIds(json: String): List<Long> = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { el -> (el as? kotlinx.serialization.json.JsonArray) ?: return emptyList() }
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
    }.getOrDefault(emptyList())
}
