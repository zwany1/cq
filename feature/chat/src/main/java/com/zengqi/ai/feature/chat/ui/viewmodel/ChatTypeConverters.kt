package com.zengqi.ai.feature.chat.ui.viewmodel

import com.zengqi.ai.database.model.ChatMessage
import com.zengqi.ai.database.model.CompanionEntity
import com.zengqi.ai.database.model.MessageType
import com.zengqi.ai.domain.AiChatMessage
import com.zengqi.ai.domain.AiCompanionInfo
import com.zengqi.ai.domain.AiMessageType

/**
 * Domain type conversion helpers — pure extension functions with no ViewModel dependencies.
 *
 * Extracted from ChatViewModel to reduce class size and improve testability.
 */
internal fun CompanionEntity.toAiCompanionInfo(): AiCompanionInfo = AiCompanionInfo(
    id = id, name = name, personality = personality,
    age = age, backstory = backstory, speakingStyle = speakingStyle,
    systemPrompt = systemPrompt
)

internal fun ChatMessage.toAiChatMessage(): AiChatMessage = AiChatMessage(
    isFromUser = isFromUser, content = content, timestamp = timestamp,
    type = when (type) {
        MessageType.IMAGE -> AiMessageType.IMAGE
        else -> AiMessageType.TEXT
    },
    companionId = companionId
)

internal fun List<ChatMessage>.toAiChatMessages(): List<AiChatMessage> = map { it.toAiChatMessage() }
