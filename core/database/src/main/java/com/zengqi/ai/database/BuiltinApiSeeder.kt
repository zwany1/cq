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
 * 内置 TokenRouter 模型种子：首启无任何 API 配置时写入并激活，
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
            repository.saveConfig(
                ApiConfig(
                    provider = ApiProvider.TOKENROUTER,
                    name = "内置模型",
                    apiKey = BUILTIN_KEY,
                    baseUrl = ApiProvider.TOKENROUTER.defaultBaseUrl,
                    model = ApiProvider.TOKENROUTER.defaultModel
                )
            )
            SecureLog.i(TAG, "内置 TokenRouter 配置已写入")
        } catch (e: SQLiteException) {
            SecureLog.e(TAG, "内置配置写入失败", e)
        }
    }
}

private const val BUILTIN_KEY = "sk-3hnMD1NyxBZlMdTYB5YlcgbAmXAPHo6O5O5saQWkb071NHiu"
