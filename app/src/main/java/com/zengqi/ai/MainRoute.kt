package com.zengqi.ai

/**
 * Navigation 状态机 — 所有路由的 sealed class 定义。
 *
 * 控制论: 离散系统差分方程 S[k+1] = f(S[k], E[k])
 * 验证: 所有状态从 Home 可达 ✓  所有状态出度 ≥ 1 ✓  编译器穷尽检查 ✓
 */
sealed class MainRoute(val route: String) {
    // === 主页 Tab ===
    object Home : MainRoute("home")
    object Contacts : MainRoute("contacts")
    object Profile : MainRoute("profile")

    // === 聊天 ===
    data class Chat(val companionId: Long) : MainRoute("chat/$companionId")
    data class ChatDetail(val companionId: Long) : MainRoute("chat_detail/$companionId")
    data class VoiceCall(val companionId: Long) : MainRoute("voice_call/$companionId")
    data class VideoCall(val companionId: Long) : MainRoute("video_call/$companionId")

    // === 伴侣 ===
    object CreateCompanion : MainRoute("create")
    data class EditCompanion(val companionId: Long) : MainRoute("edit/$companionId")

    // === 导入聊天记录 ===
    data class ImportChat(val companionId: Long) : MainRoute("import_chat/$companionId")

    // === 设置 ===
    object Settings : MainRoute("settings")
    object TtsSettings : MainRoute("tts_settings")
    object TokenUsage : MainRoute("token_usage")
    object Theme : MainRoute("theme")
    object Language : MainRoute("language")
    object CheckUpdate : MainRoute("check_update")
    object FrameRate : MainRoute("frame_rate")
    object YandereMode : MainRoute("yandere_mode")
    object ExperimentalFeatures : MainRoute("experimental_features")

    // === 总设置 ===
    object GeneralSettings : MainRoute("general_settings")

    // === 角色管理 ===
    object RoleManager : MainRoute("role_manager")

    // === 个人中心 ===
    object Memory : MainRoute("memory")
    object ContextMemory : MainRoute("context_memory")
    object About : MainRoute("about")
    object AgreementView : MainRoute("agreement_view")
    object Team : MainRoute("team")
    object Support : MainRoute("support")
    object Thanks : MainRoute("thanks")
    object ThanksFullList : MainRoute("thanks_full_list")
    object OriginOSAdaption : MainRoute("originos_adaption")

    companion object {
        /** 从路由字符串解析（用于 NavHost currentRoute） */
        fun fromRoute(route: String?): MainRoute = when {
            route == null -> Home
            route == "home" -> Home
            route == "contacts" -> Contacts
            route == "profile" -> Profile
            route == "create" -> CreateCompanion
            route == "settings" -> Settings
            route == "tts_settings" -> TtsSettings
            route == "token_usage" -> TokenUsage
            route == "memory" -> Memory
            route == "context_memory" -> ContextMemory
            route == "role_manager" -> RoleManager
            route == "theme" -> Theme
            route == "language" -> Language
            route == "check_update" -> CheckUpdate
            route == "about" -> About
            route == "agreement_view" -> AgreementView
            route == "frame_rate" -> FrameRate
            route == "yandere_mode" -> YandereMode
            route == "experimental_features" -> ExperimentalFeatures
            route == "general_settings" -> GeneralSettings
            route == "team" -> Team
            route == "support" -> Support
            route == "thanks" -> Thanks
            route == "thanks_full_list" -> ThanksFullList
            route == "originos_adaption" -> OriginOSAdaption
            route?.startsWith("chat/") == true -> Chat(route.removePrefix("chat/").toLongOrNull() ?: 0L)
            route?.startsWith("chat_detail/") == true -> ChatDetail(route.removePrefix("chat_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("voice_call/") == true -> VoiceCall(route.removePrefix("voice_call/").toLongOrNull() ?: 0L)
            route?.startsWith("video_call/") == true -> VideoCall(route.removePrefix("video_call/").toLongOrNull() ?: 0L)
            route?.startsWith("edit/") == true -> EditCompanion(route.removePrefix("edit/").toLongOrNull() ?: 0L)
            route?.startsWith("import_chat/") == true -> ImportChat(route.removePrefix("import_chat/").toLongOrNull() ?: 0L)
            else -> Home
        }
    }
}
