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
}
