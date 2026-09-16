package com.zengqi.ai.database

import android.content.Context
import android.database.sqlite.SQLiteException
import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.database.model.ApiConfig
import com.zengqi.ai.database.model.ApiProvider
import com.zengqi.ai.database.repository.ApiConfigRepository
import com.zengqi.ai.database.repository.S0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 内置免费模型种子：写入多路免费模型配置（SenseAudio + 备用中继），
 * 由 resolveConfig 按"可用优先"自动切换。旧内置配置自动升级。
 * 用户自行添加的其他配置不受影响。
 */
object BuiltinApiSeeder {

    private const val TAG = "BuiltinApiSeeder"
    private const val BUILTIN_NAME = "内置模型"

    suspend fun seedIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        try {
            val repository = ApiConfigRepository(
                AppDatabase.getDatabase(context).apiConfigDao(),
                S0
            )
            val existing = repository.getAllConfiguredConfigs().first()

            // 旧内置 TokenRouter 配置 → 升级为 SenseAudio
            val staleBuiltin = existing.filter {
                it.provider == ApiProvider.TOKENROUTER && it.name == BUILTIN_NAME
            }
            if (staleBuiltin.isNotEmpty()) {
                staleBuiltin.forEach { repository.deleteConfigById(it.id) }
                val configId = repository.saveConfig(builtinConfig())
                repository.disableOtherConfigs(configId)
                repository.enableConfig(configId)
                SecureLog.i(TAG, "内置模型已从 TokenRouter 升级为 SenseAudio, id=$configId")
                return@withContext
            }

            // 已有内置 SenseAudio 配置则不再写入
            if (existing.any { it.provider == ApiProvider.SENSEAUDIO && it.name == BUILTIN_NAME }) {
                return@withContext
            }

            // 无任何配置 → 首次写入
            if (existing.isEmpty()) {
                val configId = repository.saveConfig(builtinConfig())
                repository.disableOtherConfigs(configId)
                repository.enableConfig(configId)
                SecureLog.i(TAG, "内置 SenseAudio 配置已写入并激活, id=$configId")
            }
        } catch (e: SQLiteException) {
            SecureLog.e(TAG, "内置配置写入失败", e)
        }
    }

    private fun builtinConfig() = ApiConfig(
        provider = ApiProvider.SENSEAUDIO,
        name = BUILTIN_NAME,
        apiKey = BUILTIN_KEY,
        baseUrl = ApiProvider.SENSEAUDIO.defaultBaseUrl,
        model = ApiProvider.SENSEAUDIO.defaultModel
    )
}

private const val BUILTIN_KEY = "sk-7tUHUF12DyTxolpwjKFDYiT5BB0pBAUO27B4Ff11B02246Db98A18a4bF67a9b2a"

/** 内置免费模型候选（按顺序尝试，前一个不可用自动切换后一个） */
val BUILTIN_MODEL_CANDIDATES = listOf("glm-5.3-flash", "qwen3.6-35b-a3b", "qwen3.8-27b", "deepseek-v4-flash-0731")
