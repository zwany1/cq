package com.zengqi.ai.feature.importchat

import com.zengqi.ai.common.SecureLog

/**
 * 解析后的单条消息。
 */
data class ParsedChatMessage(
    val sender: String,
    val content: String,
    val timestamp: Long
)

/**
 * 解析结果：消息序列 + 识别出的参与双方名称。
 */
data class ParseResult(
    val messages: List<ParsedChatMessage>,
    val speakers: List<String>
)

/**
 * 纯文本聊天记录解析器。
 *
 * 支持两类常见格式：
 * 1. 微信/QQ 导出："2023-06-18 21:04:32 小雨：消息内容"
 * 2. 自定义导出："[2023-06-18 21:04] 小雨: 消息内容"
 *
 * 解析在本地完成；风格分析与记忆提取由服务端执行。
 */
object ChatFileParser {

    // "2023-06-18 21:04:32" 或 "2023/06/18 21:04" 或 "2023.6.18 21:04"
    private val tsColon = Regex(
        """^[\[（(]?\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*[\]）)]?\s*"""
    )
    private val tsBracket = Regex(
        """^[\[（(]\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*[\]）)]"""
    )

    private val timeOnly = Regex("""^\d{1,2}:\d{2}(\:\d{2})?\s+""")

    // "小雨 2023-06-18 21:04:32" —— 名字在前、时间在后的导出格式
    private val nameThenTs = Regex(
        """^([^\d\s\[][^:：\[]{1,20}?)\s+[\[（(]?\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*[\]）)]?\s+"""
    )

    /**
     * 解析文件内容。无法解析时间戳的行作为上一条消息的续行。
     */
    fun parse(content: String): ParseResult {
        val messages = mutableListOf<ParsedChatMessage>()
        val speakers = linkedSetOf<String>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue

            val tsMatch = tsColon.find(line) ?: tsBracket.find(line)
            if (tsMatch != null) {
                val rest = line.substring(tsMatch.value.length)
                val sepIdx = indexOfSenderSeparator(rest)
                if (sepIdx > 0) {
                    val sender = rest.substring(0, sepIdx).trim().trim('：', ':')
                    val body = rest.substring(sepIdx + 1).trim()
                    if (sender.isNotEmpty() && body.isNotEmpty()) {
                        val ts = tsMatch.groupValues.let { g ->
                            toMillis(g[1], g[2], g[3], g[4], g[5], g.getOrNull(6) ?: "0")
                        }
                        messages += ParsedChatMessage(sender, body, ts)
                        speakers += sender
                        continue
                    }
                }
            }

            // 名字在前、时间在后：正文为时间戳之后的剩余部分
            val nMatch = nameThenTs.find(line)
            if (nMatch != null) {
                val (sender, body, ts) = nMatch.let { m ->
                    val g = m.groupValues
                    Triple(
                        m.groupValues[1].trim().trim('：', ':'),
                        line.substring(m.range.last + 1).trim(),
                        toMillis(g[2], g[3], g[4], g[5], g[6], g.getOrNull(7) ?: "0")
                    )
                }
                if (sender.isNotEmpty() && body.isNotEmpty()) {
                    messages += ParsedChatMessage(sender, body, ts)
                    speakers += sender
                    continue
                }
            }

            // 纯时间行（如 "21:04 小雨：内容"）沿用上一条的日期
            val tMatch = timeOnly.find(line)
            if (tMatch != null) {
                val rest = line.substring(tMatch.value.length)
                val sepIdx = indexOfSenderSeparator(rest)
                if (sepIdx > 0 && messages.isNotEmpty()) {
                    val last = messages.last()
                    val sender = rest.substring(0, sepIdx).trim().trim('：', ':')
                    val body = rest.substring(sepIdx + 1).trim()
                    if (sender.isNotEmpty() && body.isNotEmpty()) {
                        val hh = tMatch.value.substringBefore(':').toInt()
                        val mm = tMatch.value.substringAfter(':').take(2).toInt()
                        messages += ParsedChatMessage(sender, body, last.timestamp - last.timestamp % 86400000L + hh * 3600000L + mm * 60000L)
                        speakers += sender
                        continue
                    }
                }
            }

            // 续行：合并进上一条
            if (messages.isNotEmpty()) {
                val last = messages.last()
                messages[messages.size - 1] = last.copy(content = last.content + "\n" + line.trim())
            }
        }

        SecureLog.d("ChatFileParser", "Parsed ${messages.size} messages, ${speakers.size} speakers")
        return ParseResult(messages.toList(), speakers.toList())
    }

    private fun indexOfSenderSeparator(rest: String): Int {
        val cjk = rest.indexOf('：')
        val ascii = rest.indexOf(':')
        return when {
            cjk >= 0 && ascii >= 0 -> minOf(cjk, ascii)
            cjk >= 0 -> cjk
            ascii >= 0 -> ascii
            else -> -1
        }
    }

    /** 同一发送者的连续消息在该时长内合并为一条。 */
    private val mergeWindowMs = 3600_000L

    /**
     * 「她」的连续发言拼成片段（对方插话即断开），供风格分析。
     */
    fun buildStyleSamples(messages: List<ParsedChatMessage>, characterSpeaker: String): List<String> {
        val samples = mutableListOf<String>()
        val buf = mutableListOf<String>()
        for (m in messages) {
            if (m.sender == characterSpeaker) {
                buf += m.content
            } else if (buf.isNotEmpty()) {
                samples += buf.joinToString("\n")
                buf.clear()
            }
        }
        if (buf.isNotEmpty()) samples += buf.joinToString("\n")
        return samples
    }

    /**
     * 按日期分组的对话片段，标注 你/她 身份，供记忆提取。
     */
    fun buildMemoryCandidates(messages: List<ParsedChatMessage>, characterSpeaker: String): List<String> {
        val byDay = linkedMapOf<String, MutableList<ParsedChatMessage>>()
        for (m in messages) {
            val day = java.time.Instant.ofEpochMilli(m.timestamp)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            byDay.getOrPut(day) { mutableListOf() } += m
        }
        return byDay.map { (day, msgs) ->
            buildString {
                appendLine(day)
                for (m in msgs) {
                    appendLine("${if (m.sender == characterSpeaker) "她" else "你"}：${m.content}")
                }
            }
        }
    }

    private fun toMillis(y: String, mo: String, d: String, h: String, mi: String, s: String): Long =
        runCatching {
            java.time.LocalDateTime.of(
                y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()
            ).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
}
