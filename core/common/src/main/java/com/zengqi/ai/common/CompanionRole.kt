package com.zengqi.ai.common

/**
 * 人物与用户的关系类型。
 *
 * 曾栖重建的是任意一段真实关系——恋人、好友、家人、同学、同事，
 * 或用户自定义的关系。用于：
 * - 创建/编辑页的关系选择
 * - 系统提示词中的身份与语气差异
 * - 默认人设预设的切换
 */
enum class CompanionRole {
    GIRLFRIEND,
    BOYFRIEND,
    FRIEND,
    FAMILY,
    CLASSMATE,
    COLLEAGUE,
    CUSTOM;

    companion object {
        fun fromName(name: String?): CompanionRole = when (name?.uppercase()) {
            "BOYFRIEND" -> BOYFRIEND
            "FRIEND" -> FRIEND
            "FAMILY" -> FAMILY
            "CLASSMATE" -> CLASSMATE
            "COLLEAGUE" -> COLLEAGUE
            "CUSTOM" -> CUSTOM
            else -> GIRLFRIEND
        }
    }
}
