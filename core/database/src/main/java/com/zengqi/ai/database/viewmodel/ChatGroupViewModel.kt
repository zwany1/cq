package com.zengqi.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.model.ChatGroup
import com.zengqi.ai.database.repository.ChatGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ChatGroupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChatGroupRepository
    val groups: Flow<List<ChatGroup>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatGroupRepository(database.chatGroupDao())
        groups = repository.getAllGroups()
    }

    fun createGroup(name: String, companionIds: List<Long>) {
        viewModelScope.launch {
            val group = ChatGroup(
                name = name,
                companionIds = companionIds.joinToString(",")
            )
            repository.insertGroup(group)
        }
    }

    fun deleteGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.deleteGroup(group)
        }
    }

    fun updateGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.updateGroup(group)
        }
    }
}
