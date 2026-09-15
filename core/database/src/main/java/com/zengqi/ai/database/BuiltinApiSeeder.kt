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
 * 内置 SenseAudio 模型种子：首启无任何 API 配置时写入并激活，
 * 让导入构建与聊天开箱可用。用户自行添加配置后照常覆盖。
 */
object BuiltinApiSeeder {

    private const val TAG = "BuiltinApiSeeder"

    suspend fun seedIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        try {
            val repository = ApiConfigRepository(
                AppDatabase.getDatabase(context).apiConfigDao(),
                S0
            )
            if (repository.getAllConfiguredConfigs().first().isNotEmpty()) return@withContext
            val configId = repository.saveConfig(
                ApiConfig(
                    provider = ApiProvider.SENSEAUDIO,
                    name = "内置模型",
                    apiKey = BUILTIN_KEY,
                    baseUrl = ApiProvider.SENSEAUDIO.defaultBaseUrl,
                    model = ApiProvider.SENSEAUDIO.defaultModel
                )
            )
            repository.disableOtherConfigs(configId)
            repository.enableConfig(configId)
            SecureLog.i(TAG, "内置 SenseAudio 配置已写入并激活, id=$configId")
        } catch (e: SQLiteException) {
            SecureLog.e(TAG, "内置配置写入失败", e)
        }
    }
}

private const val BUILTIN_KEY = "sk-7tUHUF12DyTxolpwjKFDYiT5BB0pBAUO27B4Ff11B02246Db98A18a4bF67a9b2a"
