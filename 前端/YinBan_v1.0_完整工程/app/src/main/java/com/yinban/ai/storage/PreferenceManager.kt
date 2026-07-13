// ============================================================
// 路径: app/src/main/java/com/yinban/ai/storage/PreferenceManager.kt
// 用途: AI 影伴系统 v1.0 — SharedPreferences 持久化管理器
// 功能:
//   - 登录状态 & 凭据持久化（免登录复访）
//   - 双端角色 & 房间号存储
//   - 隐私模式开关存储
//   - 服务器 IP 存储
// ============================================================

package com.yinban.ai.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferenceManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "yinban_prefs"

        // ── Key 常量 ──
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PASSWORD = "password"            // 注意: 生产环境应使用加密存储
        private const val KEY_ROOM = "room"
        private const val KEY_ROLE = "role"                    // "patient" | "guardian"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_PRIVACY_MODE = "privacy_mode"    // 患者端隐私模式
        private const val KEY_NICKNAME = "nickname"           // 用户自定义昵称
        private const val KEY_AVATAR_EMOJI = "avatar_emoji"  // 头像 emoji
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"  // 用户自己的 DeepSeek API Key

            private const val KEY_CHAT_HISTORY_PREFIX = "chat_history_"

        @Volatile
        private var INSTANCE: PreferenceManager? = null

        fun getInstance(context: Context): PreferenceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferenceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 暴露 SharedPreferences 供 Fragment 直接读取开关状态 */
    val sharedPrefs: SharedPreferences get() = prefs

    // ═══════════════════════════════════════════
    // 登录状态
    // ═══════════════════════════════════════════

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_LOGGED_IN, value) }

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ACCOUNT, value) }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PASSWORD, value) }

    var room: String
        get() = prefs.getString(KEY_ROOM, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ROOM, value) }

    var role: String
        get() = prefs.getString(KEY_ROLE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ROLE, value) }

    var serverIp: String
        get() = prefs.getString(KEY_SERVER_IP, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SERVER_IP, value) }

    // ═══════════════════════════════════════════
    // 患者端隐私模式
    // ═══════════════════════════════════════════

    /** 隐私模式：开启后位置数据自动模糊化（除非 SOS 触发） */
    var isPrivacyMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_PRIVACY_MODE, value) }

    // ═══════════════════════════════════════════
    // 个人资料（昵称 + 头像 emoji）
    // ═══════════════════════════════════════════

    /** 用户自定义昵称（按角色独立） */
    var nickname: String
        get() = prefs.getString(roleKey(KEY_NICKNAME), "") ?: ""
        set(value) = prefs.edit { putString(roleKey(KEY_NICKNAME), value) }

    /** 头像 emoji（按角色独立），默认 🧑 */
    var avatarEmoji: String
        get() = prefs.getString(roleKey(KEY_AVATAR_EMOJI), "") ?: ""
        set(value) = prefs.edit { putString(roleKey(KEY_AVATAR_EMOJI), value) }

    /** 自定义头像图片路径（按角色独立），空表示未设置 */
    var avatarPath: String
        get() = prefs.getString(roleKey("avatar_path"), "") ?: ""
        set(value) = prefs.edit { putString(roleKey("avatar_path"), value) }

    /** 根据当前角色生成独立的 key，保证患者/监护人数据隔离 */
    private fun roleKey(base: String): String {
        val r = role
        return if (r.isNotBlank()) "${base}_$r" else base
    }

    /** DeepSeek API Key（用户自己的），空则使用内置 key */
    var deepseekApiKey: String
        get() = prefs.getString(KEY_DEEPSEEK_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_DEEPSEEK_API_KEY, value) }

    // ═══════════════════════════════════════════
    // 便捷方法
    // ═══════════════════════════════════════════

    /** 保存完整登录信息 */
    fun saveLoginInfo(account: String, password: String, room: String, role: String, serverIp: String) {
        prefs.edit {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_ACCOUNT, account)
            putString(KEY_PASSWORD, password)
            putString(KEY_ROOM, room)
            putString(KEY_ROLE, role)
            putString(KEY_SERVER_IP, serverIp)
        }
    }

    /** 清除登录状态（退出登录） */
    fun clearLoginInfo() {
        prefs.edit {
            putBoolean(KEY_IS_LOGGED_IN, false)
            remove(KEY_ACCOUNT)
            remove(KEY_PASSWORD)
            remove(KEY_ROOM)
            remove(KEY_ROLE)
            remove(KEY_SERVER_IP)
            // 注意: 不清除隐私模式设置
        }
    }

    /** 判断是否为患者端 */
    fun isPatient(): Boolean = role == "patient"

    /** 判断是否为监护人端 */
    fun isGuardian(): Boolean = role == "guardian"

    /** 是否有有效的登录信息可用于自动连接 */
    fun hasValidAutoLogin(): Boolean {
        return isLoggedIn &&
                account.isNotBlank() &&
                room.isNotBlank() &&
                role.isNotBlank() &&
                serverIp.isNotBlank()
    }

    // ═══════════════════════════════════════════
    // 聊天历史持久化
    // ═══════════════════════════════════════════

    fun saveChatHistory(room: String, role: String, messages: List<ChatHistoryItem>, maxMessages: Int = 200) {
        val key = "$KEY_CHAT_HISTORY_PREFIX${room}_$role"
        val gson = com.google.gson.Gson()
        val toSave = if (messages.size > maxMessages) messages.takeLast(maxMessages) else messages
        prefs.edit { putString(key, gson.toJson(toSave)) }
    }

    fun loadChatHistory(room: String, role: String): List<ChatHistoryItem> {
        val key = "$KEY_CHAT_HISTORY_PREFIX${room}_$role"
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<ChatHistoryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearChatHistory(room: String, role: String) {
        prefs.edit { remove("$KEY_CHAT_HISTORY_PREFIX${room}_$role") }
    }
}

data class ChatHistoryItem(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
