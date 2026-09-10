package com.zengqi.ai.network

/**
 * 向后兼容别名。
 * AiResponse 已迁移至 core:domain，此 typealias 保证现有 import 路径仍可编译。
 * 新代码请直接使用 [com.zengqi.ai.domain.AiResponse]。
 */
typealias AiResponse = com.zengqi.ai.domain.AiResponse
