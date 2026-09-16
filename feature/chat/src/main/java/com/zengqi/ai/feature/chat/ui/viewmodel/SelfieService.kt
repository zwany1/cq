package com.zengqi.ai.feature.chat.ui.viewmodel

import android.content.Context
import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.network.imagegen.ImageGenClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 自拍/生图服务：TA 按提示词生成一张图片，保存到本地并作为 IMAGE 消息发送。
 *
 * 触发方式：AI 回复文本中包含 [自拍] 或 [图片: 描述] 标记时，由 Finalizer 调用。
 */
object SelfieService {

    private const val TAG = "SelfieService"

    /**
     * 生成自拍并发送为 IMAGE 消息。
     * @param prompt 画面描述（TA 的名字 + 场景）
     * @return 消息 id；失败返回 -1
     */
    suspend fun generateAndSend(
        context: Context,
        companionId: Long,
        companionName: String,
        prompt: String,
        sendMessage: suspend (content: String, imagePath: String) -> Long
    ): Long = withContext(Dispatchers.IO) {
        try {
            val fullPrompt = buildString {
                append("手机自拍视角，")
                append(prompt.take(120))
                append("，自然光，真实感，竖构图")
            }
            val imagePath = ImageGenClient.generate(fullPrompt, context.cacheDir)
            if (imagePath == null) {
                SecureLog.w(TAG, "generate failed for prompt: $fullPrompt")
                return@withContext -1
            }
            SecureLog.i(TAG, "selfie generated: $imagePath")
            sendMessage("[$companionName 的自拍]", imagePath)
        } catch (e: Exception) {
            SecureLog.e(TAG, "generateAndSend failed", e)
            -1
        }
    }
}
