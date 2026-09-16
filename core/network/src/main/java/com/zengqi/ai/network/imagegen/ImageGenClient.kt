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
 * 免费生图客户端（Pollinations，无需 API key）。
 *
 * 生成结果保存到 cache/image_gen/，返回本地文件路径供消息气泡展示。
 */
object ImageGenClient {

    private const val TAG = "ImageGenClient"
    private const val ENDPOINT = "https://image.pollinations.ai/prompt/%s?width=768&height=768&nologo=true&seed=%d"

    /**
     * 按提示词生成一张图，返回本地文件路径；失败返回 null。
     */
    suspend fun generate(prompt: String, cacheDir: File): String? = withContext(Dispatchers.IO) {
        try {
            val seed = System.currentTimeMillis() % 100000
            val encoded = java.net.URLEncoder.encode(prompt.take(300), "UTF-8")
            val url = URL(ENDPOINT.format(encoded, seed))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                SecureLog.w(TAG, "image gen HTTP ${conn.responseCode}")
                return@withContext null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext null

            val dir = File(cacheDir, "image_gen")
            dir.mkdirs()
            val file = File(dir, "gen_${System.currentTimeMillis()}.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bitmap.recycle()
            SecureLog.i(TAG, "image generated: ${file.absolutePath} (${file.length()} bytes)")
            file.absolutePath
        } catch (e: Exception) {
            SecureLog.e(TAG, "image generation failed", e)
            null
        }
    }

}
