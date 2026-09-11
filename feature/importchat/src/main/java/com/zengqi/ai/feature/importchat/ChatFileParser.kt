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
 * 支持的格式：
 * 1. 微信导出（留痕/WeChat-Dump）：
 *    "[2025-07-14 10:31:21] 昵称(wxid_xxx) [avatar=...]: 消息内容"
 *    "[2025-07-14 10:31:21] 昵称(wxid_xxx) [avatar=...]: [表情]"
 * 2. 传统 QQ/微信手动导出：
 *    "2023-06-18 21:04:32 小雨：消息内容"
 * 3. 括号时间戳自定义导出：
 *    "[2023-06-18 21:04] 小雨: 消息内容"
 * 4. 名字在前、时间在后：
 *    "小雨 2023-06-18 21:04:32"
 *
 * 解析在本地完成；风格分析与记忆提取由服务端执行。
 */
object ChatFileParser {

    // ── 时间戳正则 ──

    // "[2025-07-14 10:31:21]" 或 "[2023-06-18 21:04:32]" — 方括号包裹的完整时间戳
    private val tsBracketFull = Regex(
        """^\[(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\]\s*"""
    )

    // "2023-06-18 21:04:32" 或 "2023/06/18 21:04" — 无括号的完整时间戳
    private val tsColon = Regex(
        """^[\[（(]?\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*[\]）)]?\s*"""
    )

    // "小雨 2023-06-18 21:04:32" —— 名字在前、时间在后的导出格式
    private val nameThenTs = Regex(
        """^([^\d\s\[][^:：\[]{1,20}?)\s+[\[（(]?\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*[\]）)]?\s+"""
    )

    private val timeOnly = Regex("""^\d{1,2}:\d{2}(:\d{2})?\s+""")

    // ── 发送方匹配 ──

    /**
     * 匹配微信导出格式的发送方部分：
     * "昵称(wxid_xxx) [avatar=...]: " 或 "昵称 [avatar=...]: "
     *
     * 捕获组：
     * 1. 昵称（不含 wxid 和 avatar）
     * 2. 消息正文（冒号之后的部分）
     */
    private val weChatSender = Regex(
        """^(.+?)\s*(?:\([^)]*\))?\s*(?:\[avatar=[^\]]*\])?\s*[:：]\s*(.*)$"""
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

            // 尝试方括号完整时间戳 "[2025-07-14 10:31:21]"
            val bracketMatch = tsBracketFull.find(line)
            if (bracketMatch != null) {
                val rest = line.substring(bracketMatch.value.length)
                val parsed = parseSenderAndContent(rest)
                if (parsed != null) {
                    val (sender, body) = parsed
                    val g = bracketMatch.groupValues
                    val ts = toMillis(g[1], g[2], g[3], g[4], g[5], g.getOrNull(6) ?: "0")
                    messages += ParsedChatMessage(sender, body, ts)
                    speakers += sender
                    continue
                }
            }

            // 尝试无括号完整时间戳 "2023-06-18 21:04:32"
            val tsMatch = tsColon.find(line)
            if (tsMatch != null) {
                val rest = line.substring(tsMatch.value.length)
                val parsed = parseSenderAndContent(rest)
                if (parsed != null) {
                    val (sender, body) = parsed
                    val g = tsMatch.groupValues
                    val ts = toMillis(g[1], g[2], g[3], g[4], g[5], g.getOrNull(6) ?: "0")
                    messages += ParsedChatMessage(sender, body, ts)
                    speakers += sender
                    continue
                }
            }

            // 名字在前、时间在后
            val nMatch = nameThenTs.find(line)
            if (nMatch != null) {
                val sender = nMatch.groupValues[1].trim().trim('：', ':')
                val body = line.substring(nMatch.range.last + 1).trim()
                val g = nMatch.groupValues
                val ts = toMillis(g[2], g[3], g[4], g[5], g[6], g.getOrNull(7) ?: "0")
                if (sender.isNotEmpty() && body.isNotEmpty()) {
                    messages += ParsedChatMessage(sender, body, ts)
                    speakers += sender
                    continue
                }
            }

            // 纯时间行（如 "21:04 小雨：内容"）沿用上一条的日期
            val tMatch = timeOnly.find(line)
            if (tMatch != null && messages.isNotEmpty()) {
                val rest = line.substring(tMatch.value.length)
                val parsed = parseSenderAndContent(rest)
                if (parsed != null) {
                    val (sender, body) = parsed
                    val last = messages.last()
                    val hh = tMatch.value.substringBefore(':').toInt()
                    val mm = tMatch.value.substringAfter(':').take(2).toInt()
                    val ts = last.timestamp - last.timestamp % 86400000L + hh * 3600000L + mm * 60000L
                    messages += ParsedChatMessage(sender, body, ts)
                    speakers += sender
                    continue
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

    /**
     * 从时间戳之后的剩余文本中提取发送方和消息正文。
     *
     * 支持以下格式：
     * - "昵称(wxid_xxx) [avatar=...]: 消息正文"
     * - "昵称: 消息正文"
     * - "昵称：消息正文"
     *
     * 返回 (发送方昵称, 消息正文)，无法解析时返回 null。
     */
    private fun parseSenderAndContent(rest: String): Pair<String, String>? {
        if (rest.isBlank()) return null

        val match = weChatSender.find(rest) ?: return null
        val sender = match.groupValues[1].trim().trim('：', ':')
        val body = match.groupValues[2].trim()

        if (sender.isEmpty() || body.isEmpty()) return null
        // 排除把元数据行（如 "会话: xxx"）误认为消息的情况
        if (sender.startsWith("会话") || sender.startsWith("账号") || sender.startsWith("导出时间")) return null

        return sender to body
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
