package com.zengqi.ai.network.zengqi

import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.domain.AiServiceProvider
import com.zengqi.ai.domain.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt

/**
 * 曾栖构建引擎：风格画像 + 记忆提取（LLM）+ 本地向量检索。
 *
 * LLM 调用复用 [AiServiceProvider.callGeneration]（用户配置的 OpenAI 兼容 API）；
 * 检索向量由字符 n-gram 哈希确定性生成，无需外部 embedding 服务，离线可用。
 */
object ZengqiEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 记忆批量提取时每次送 LLM 的候选片段数（日期粒度小批，LLM 提取更聚焦） */
    internal const val MEMORY_BATCH_SIZE = 6

    /** 记忆提取候选片段采样上限：约 2400 个日期的内容，覆盖一年多的日常记录 */
    internal const val MEMORY_SAMPLE_LIMIT = 400

    /** 记忆提取并发路数 */
    internal const val MEMORY_EXTRACT_CONCURRENCY = 4

    /** 并发批次间的节流间隔（毫秒），避免触发免费端点限流 */
    internal const val MEMORY_EXTRACT_THROTTLE_MS = 1500L

    /** 风格样本送 LLM 的条数与单条截断长度（控制上下文占用） */
    internal const val STYLE_SAMPLE_COUNT = 20
    internal const val STYLE_SAMPLE_MAX_CHARS = 1500

    /** 本地向量维度 */
    const val VECTOR_DIM = 256

    @Serializable
    data class StyleProfile(
        val sentenceLength: String = "medium",
        val responseLength: String = "medium",
        val tone: List<String> = emptyList(),
        val frequentlyUsedWords: List<String> = emptyList(),
        val emojis: List<String> = emptyList(),
        val punctuationStyle: Map<String, String> = emptyMap(),
        val favoritePhrases: List<String> = emptyList(),
        val emotionStyle: String = "",
        val callingStyle: String = ""
    )

    data class ExtractedMemory(
        val type: String,
        val title: String,
        val content: String,
        val eventTimeMs: Long?,
        val importance: Float
    )

    // ── 风格分析 ──

    /**
     * 统计特征 JSON（emoji 频次、高频词滑窗），作为 LLM 分析的输入。
     */
    internal fun buildStats(samples: List<String>): String {
        val texts = samples.flatMap { it.split('\n') }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return "{}"

        val lengths = texts.map { it.length }
        val emojiRegex = Regex("[\\x{1F300}-\\x{1FAFF}☀-➿❤]")
        val emojiCounts = LinkedHashMap<String, Int>()
        for (t in texts) {
            for (m in emojiRegex.findAll(t)) {
                emojiCounts.merge(m.value, 1, Int::plus)
            }
        }
        val stopChars = "的了是不我你他她它们呢吧啊呀哦嘛么这个那个什么怎么就也都还在和跟把被让又很是的说".toSet()
        val wordFreq = HashMap<String, Int>()
        for (t in texts) {
            for (n in 2..3) {
                var i = 0
                while (i + n <= t.length) {
                    wordFreq.merge(t.substring(i, i + n), 1, Int::plus)
                    i++
                }
            }
        }
        val frequent = wordFreq.entries
            .sortedByDescending { it.value }
            .filter { it.value >= 3 && !it.key.all { c -> c in stopChars } }
            .take(30)
            .map { it.key }

        fun jsonArrayOf(items: List<String>) =
            JsonArray(items.map { JsonPrimitive(it) }).toString()

        return buildString {
            append("{")
            append("\"avg_sentence_length\":${"%.1f".format(lengths.average())},")
            append("\"max_sentence_length\":${lengths.max()},")
            append("\"emoji_top\":${jsonArrayOf(emojiCounts.entries.sortedByDescending { it.value }.take(15).map { it.key })},")
            append("\"frequent_words\":${jsonArrayOf(frequent)}")
            append("}")
        }
    }

    internal fun stylePrompt(stats: String, sampleText: String): String = """
        以下是这位人物的历史聊天发言片段和统计特征，请分析TA的说话风格，只输出 JSON（不要 markdown 代码块），字段：
        {"sentence_length": "short/medium/long", "response_length": "short/medium/long", "tone": ["..."], "frequently_used_words": ["..."], "emojis": ["..."], "punctuation_style": {"感叹号": "rare/medium/often", "问句": "rare/medium/often"}, "favorite_phrases": ["..."], "emotion_style": "...", "calling_style": "..."}

        统计特征：$stats

        发言片段：
        $sampleText
    """.trimIndent()

    /**
     * 分析风格：统计 + LLM 总结。失败抛异常，由导入流程标记 FAILED。
     */
    suspend fun analyzeStyle(samples: List<String>): StyleProfile = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) throw IllegalStateException("没有可分析的发言片段")

        val sampleText = samples.take(STYLE_SAMPLE_COUNT)
            .joinToString("\n---\n") { it.take(STYLE_SAMPLE_MAX_CHARS) }
        val raw = callLlm(stylePrompt(buildStats(samples), sampleText), maxTokens = 2000)
        ensureLlmOutput(raw)
        parseStyleProfile(raw)
    }

    /**
     * 渲染为写入人设「说话风格」字段的文本（进入聊天 System Prompt）。
     */
    fun renderStyleText(profile: StyleProfile): String = buildString {
        val tone = profile.tone.joinToString("、")
        if (tone.isNotBlank()) append("语气$tone；")
        append("句子偏${profile.sentenceLength}，回复${if (profile.responseLength == "short") "简短" else profile.responseLength}；")
        if (profile.favoritePhrases.isNotEmpty()) {
            append("口头禅：${profile.favoritePhrases.take(5).joinToString("、")}；")
        }
        if (profile.frequentlyUsedWords.isNotEmpty()) {
            append("常用词：${profile.frequentlyUsedWords.take(8).joinToString("、")}；")
        }
        if (profile.emojis.isNotEmpty()) {
            append("常用表情：${profile.emojis.take(5).joinToString(" ")}；")
        }
        if (profile.emotionStyle.isNotBlank()) append("情绪表达${profile.emotionStyle}；")
        if (profile.callingStyle.isNotBlank()) append("称呼${profile.callingStyle}")
        trimEnd('；')
    }

    private fun parseStyleProfile(raw: String): StyleProfile {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.trim('`').removePrefix("json").trim()
        }
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) {
                throw IllegalStateException("风格分析返回非 JSON: ${text.take(200)}")
            }
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull ?: ""
        fun strList(key: String): List<String> =
            (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val punctuation = (obj["punctuation_style"] as? JsonObject)?.let { o ->
            o.entries.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }.toMap()
        } ?: emptyMap()
        return StyleProfile(
            sentenceLength = str("sentence_length").ifBlank { "medium" },
            responseLength = str("response_length").ifBlank { "medium" },
            tone = strList("tone"),
            frequentlyUsedWords = strList("frequently_used_words"),
            emojis = strList("emojis"),
            punctuationStyle = punctuation,
            favoritePhrases = strList("favorite_phrases"),
            emotionStyle = str("emotion_style"),
            callingStyle = str("calling_style")
        )
    }

    // ── 记忆提取 ──

    private val memoryTypes = setOf("EVENT", "PERSON", "PLACE", "PREFERENCE", "RELATIONSHIP", "EMOTION")

    internal fun memoryExtractPrompt(chunk: String): String = """
        请从以下历史聊天片段中提取长期值得记住的信息。

        只提取有明确证据的事实，不要推测。每条记忆输出一个对象，字段：
        type（EVENT/PERSON/PLACE/PREFERENCE/RELATIONSHIP/EMOTION 之一，注意 PERSON 不是 PERSION）、
        title（简短标题）、content（一句话描述）、event_time（该事件发生的日期 YYYY-MM-DD，没有则 null）、
        importance（0~1 浮点数）。

        没有值得提取的内容时输出 []。只输出 JSON 数组，不要 markdown 代码块。

        聊天片段：
        $chunk
    """.trimIndent()

    /**
     * 从按日期分组的候选片段提取记忆。候选超上限时均匀采样封顶；
     * 批次并发执行，导入耗时为常数级。单批失败跳过该批，不阻断整体。
     */
    suspend fun extractMemories(candidates: List<String>): List<ExtractedMemory> = withContext(Dispatchers.IO) {
        val sampled = sampleCandidates(candidates)
        val batches = sampled.chunked(MEMORY_BATCH_SIZE)
        SecureLog.i("ZengqiEngine", "记忆提取: ${candidates.size}个候选, 采样后${sampled.size}, ${batches.size}批, 并发$MEMORY_EXTRACT_CONCURRENCY")

        coroutineScope {
            batches.mapIndexed { batchIdx, batch ->
                async {
                    try {
                        // 批次间节流：按批次序号错峰发请求，避免并发挤兑限流
                        if (batchIdx >= MEMORY_EXTRACT_CONCURRENCY) {
                            delay(((batchIdx / MEMORY_EXTRACT_CONCURRENCY) * MEMORY_EXTRACT_THROTTLE_MS))
                        }
                        val chunk = batch.joinToString("\n\n")
                        val raw = callLlm(memoryExtractPrompt(chunk), maxTokens = 4000)
                        val parsed = parseMemoryList(raw)
                        SecureLog.i("ZengqiEngine", "记忆提取单批完成: +${parsed.size}条")
                        parsed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SecureLog.w("ZengqiEngine", "记忆提取单批失败，跳过: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * 候选片段超上限时按步长均匀采样，保留时间跨度上的分布。
     */
    internal fun sampleCandidates(candidates: List<String>): List<String> {
        if (candidates.size <= MEMORY_SAMPLE_LIMIT) return candidates
        val step = candidates.size.toDouble() / MEMORY_SAMPLE_LIMIT
        return (0 until MEMORY_SAMPLE_LIMIT).map { i ->
            candidates[(i * step).toInt().coerceAtMost(candidates.size - 1)]
        }
    }

    internal fun parseMemoryList(raw: String): List<ExtractedMemory> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.trim('`').removePrefix("json").trim()
        }
        val arr = try {
            json.parseToJsonElement(text).jsonArray
        } catch (e: Exception) {
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) {
                throw IllegalStateException("记忆提取返回非 JSON: ${text.take(200)}")
            }
            json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
        }
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val type = ((obj["type"] as? JsonPrimitive)?.contentOrNull ?: "EVENT").uppercase()
            val importance = (obj["importance"] as? JsonPrimitive)?.content?.toFloatOrNull()
                ?.coerceIn(0f, 1f) ?: 0.5f
            val eventDay = (obj["event_time"] as? JsonPrimitive)?.contentOrNull
            val eventMs = eventDay?.takeIf { it.isNotBlank() }?.let {
                runCatching { java.time.LocalDate.parse(it) }.getOrNull()
                    ?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            }
            ExtractedMemory(
                type = if (type in memoryTypes) type else "EVENT",
                title = title,
                content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: "",
                eventTimeMs = eventMs,
                importance = importance
            )
        }
    }

    // ── 本地向量 ──

    /**
     * 文本 → 256 维哈希向量：2~3 字符 n-gram 经 FNV-1a 哈希投影到固定维度并符号累加，L2 归一化。
     * 确定性：同文本任意时刻重算结果一致，可作为持久化向量的替代或校验。
     */
    fun textVector(text: String): FloatArray {
        val vec = FloatArray(VECTOR_DIM)
        val chars = text.toCharArray()
        var count = 0
        for (n in 2..3) {
            var i = 0
            while (i + n <= chars.size) {
                var hash = 0x811C9DC5u
                for (k in 0 until n) {
                    hash = (hash xor chars[i + k].code.toUInt()) * 0x01000193u
                }
                val bucket = (hash % VECTOR_DIM.toUInt()).toInt()
                val sign = if ((hash shr 31) and 1u == 0u) 1f else -1f
                vec[bucket] += sign
                count++
                i++
            }
        }
        if (count > 0) {
            var l2 = 0f
            for (v in vec) l2 += v * v
            l2 = sqrt(l2)
            if (l2 > 1e-9f) {
                for (j in vec.indices) vec[j] /= l2
            }
        }
        return vec
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d > 1e-9f) dot / d else 0f
    }

    /** 向量 ↔ JSON 文本（Room 存储格式）。 */
    fun encodeVector(v: FloatArray): String =
        JsonArray(v.map { JsonPrimitive(it) }).toString()

    fun decodeVector(s: String): FloatArray? = runCatching {
        val arr = json.parseToJsonElement(s).jsonArray
        FloatArray(arr.size) { i -> arr[i].jsonPrimitive.content.toFloat() }
    }.getOrNull()

    // ── LLM 调用 ──

    /**
     * 校验 LLM 返回内容。reasoning 模型可能返回纯文本思考过程，
     * 只要包含可提取的 JSON 片段即可。
     */
    private fun ensureLlmOutput(raw: String) {
        if (raw.isBlank()) {
            throw IllegalStateException("AI 未返回内容，请检查 API 配置")
        }
    }

    private suspend fun callLlm(prompt: String, maxTokens: Int): String {
        val ai = ServiceRegistry.get(AiServiceProvider::class.java)
            ?: throw IllegalStateException("AI 服务未注册")
        SecureLog.i("ZengqiEngine", "callLlm开始, maxTokens=$maxTokens, prompt长度=${prompt.length}")
        val result = ai.callGeneration(prompt, maxTokens)
        SecureLog.i("ZengqiEngine", "callLlm返回, 长度=${result.length}, 前200字=${result.take(200)}")
        return result
    }
}
