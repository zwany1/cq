package com.zengqi.ai.network.imagegen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zengqi.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 免费生图客户端（Pollinations，无需 API key），多模型自动路由：
 * 主模型失败自动冷却并切换候选，与聊天模型路由同一套机制。
 *
 * 生成结果保存到 cache/image_gen/，返回本地文件路径供消息气泡展示。
 */
object ImageGenClient {

    private const val TAG = "ImageGenClient"
    private const val ENDPOINT = "https://image.pollinations.ai/prompt/%s?width=768&height=768&nologo=true&seed=%d&model=%s"

    /** 生图模型候选（按顺序尝试，前一个不可用自动切换后一个） */
    val IMAGE_MODEL_CANDIDATES = listOf("flux", "gptimage", "turbo")

    // 模型失败冷却：失败后冷却 10 分钟，期间跳过该模型
    private val modelFailureUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MODEL_FAILURE_COOLDOWN_MS = 10 * 60_000L

    /** 解析当前可用生图模型：冷却期内的模型跳过。 */
    @Synchronized
    private fun resolveImageModel(): String {
        val now = System.currentTimeMillis()
        for (candidate in IMAGE_MODEL_CANDIDATES) {
            if ((modelFailureUntil[candidate] ?: 0L) <= now) return candidate
        }
        // 全部冷却中 → 回退主模型
        return IMAGE_MODEL_CANDIDATES.first()
    }

    private fun markModelFailed(model: String) {
        modelFailureUntil[model] = System.currentTimeMillis() + MODEL_FAILURE_COOLDOWN_MS
        SecureLog.w(TAG, "生图模型失败冷却10分钟: $model")
    }

    /**
     * 按提示词生成一张图，返回本地文件路径；全部候选失败返回 null。
     */
    suspend fun generate(prompt: String, cacheDir: File): String? = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(prompt.take(300), "UTF-8")
        val seed = System.currentTimeMillis() % 100000

        for (candidate in IMAGE_MODEL_CANDIDATES) {
            val model = resolveImageModel()
            try {
                val url = URL(ENDPOINT.format(encoded, seed, model))
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 90000
                conn.requestMethod = "GET"
                if (conn.responseCode != 200) {
                    SecureLog.w(TAG, "image gen [$model] HTTP ${conn.responseCode}")
                    markModelFailed(model)
                    continue
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    SecureLog.w(TAG, "image gen [$model] returned invalid image")
                    markModelFailed(model)
                    continue
                }

                val dir = File(cacheDir, "image_gen")
                dir.mkdirs()
                val file = File(dir, "gen_${System.currentTimeMillis()}.png")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                bitmap.recycle()
                SecureLog.i(TAG, "image generated [$model]: ${file.absolutePath} (${file.length()} bytes)")
                return@withContext file.absolutePath
            } catch (e: Exception) {
                SecureLog.w(TAG, "image gen [$model] failed: ${e.message}")
                markModelFailed(model)
            }
        }
        SecureLog.e(TAG, "all image models failed")
        null
    }
}
