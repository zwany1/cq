package com.zengqi.ai.feature.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zengqi.ai.common.DeviceIdProvider
import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.model.CompanionEntity
import com.zengqi.ai.database.model.MemoryCategory
import com.zengqi.ai.database.model.MemoryEntry
import com.zengqi.ai.database.model.TempMemory
import com.zengqi.ai.database.repository.CompanionRepository
import com.zengqi.ai.database.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryRepository: MemoryRepository
    private val companionRepository: CompanionRepository

    val companions: Flow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        memoryRepository = MemoryRepository(database.memoryDao(), DeviceIdProvider.getDeviceId(application))
        companionRepository = CompanionRepository(database.companionDao())
        companions = companionRepository.getAllCompanions()
    }

    fun getMemoriesForCompanion(companionId: Long): Flow<List<MemoryEntry>> {
        return memoryRepository.getMemoriesForCompanion(companionId)
    }

    fun getTempMemoriesForCompanion(companionId: Long): Flow<List<TempMemory>> {
        return memoryRepository.getRecentTempMemories(companionId)
    }

    fun deleteMemory(memory: MemoryEntry) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory)
        }
    }

    fun updateMemory(memory: MemoryEntry) {
        viewModelScope.launch {
            memoryRepository.updateMemory(memory)
        }
    }

    fun addManualMemory(
        companionId: Long,
        content: String,
        category: MemoryCategory = MemoryCategory.FACT,
        importance: Float = 0.7f,
        context: String = ""
    ) {
        viewModelScope.launch {
            memoryRepository.addMemory(companionId, content, category, importance, context)
        }
    }

    fun deleteMemoriesForCompanion(companionId: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemoriesForCompanion(companionId)
        }
    }
}
