// ============================================================
// 路径: app/src/main/java/com/yinban/ai/network/MessageModels.kt
// 用途: AI 影伴系统 v1.0 — 统一消息外壳实体与 9 种业务消息定义
// 协议: 所有 JSON 使用 Gson 序列化 / 反序列化
// ============================================================

package com.yinban.ai.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.UUID

// ─────────────────────────────────────────────
// 1. 统一消息外壳 (Envelope)
// ─────────────────────────────────────────────

/**
 * 所有 WebSocket 通信的统一 JSON 外壳。
 * 发送时: msg_id 自动生成 UUID v4, timestamp 自动填充 ISO 8601。
 * 接收时: 直接反序列化。
 */
data class YinBanMessage(
    @SerializedName("msg_id")
    val msgId: String = UUID.randomUUID().toString(),

    @SerializedName("type")
    val type: String,

    @SerializedName("data")
    val data: Any? = null,

    @SerializedName("timestamp")
    val timestamp: String = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        java.util.Locale.US
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date())
) {
    companion object {
        private val gson = Gson()

        /** 从 JSON 字符串反序列化 */
        fun fromJson(json: String): YinBanMessage? {
            return try {
                gson.fromJson(json, YinBanMessage::class.java)
            } catch (e: Exception) {
                android.util.Log.e("MessageModels", "JSON 解析失败: ${e.message}", e)
                null
            }
        }

        /** 序列化为 JSON 字符串 */
        fun toJson(message: YinBanMessage): String = gson.toJson(message)
    }
}

// ─────────────────────────────────────────────
// 2. 九种业务消息的 Data 实体
// ─────────────────────────────────────────────

// --- (a) Ping / Pong 心跳 ---
data class PingData(
    @SerializedName("heartbeat") val heartbeat: String = "ping"
)

// --- (b) Error 错误 ---
data class ErrorData(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("message") val message: String = ""
)

// --- (c) Room Status 房间状态 ---
data class RoomStatusData(
    @SerializedName("room") val room: String = "",
    @SerializedName("status") val status: String = "",    // "waiting" | "paired" | "disconnected"
    @SerializedName("peer_role") val peerRole: String? = null
)

// --- (d) Stream Start 推流请求 ---
data class StreamStartData(
    @SerializedName("stream_type") val streamType: String = "video" // "video" | "audio"
)

// --- (e) Stream URL 推流地址（服务器 → 监护人） ---
data class StreamUrlData(
    @SerializedName("url") val url: String = "",
    @SerializedName("stream_type") val streamType: String = "video"
)

// --- (f) AI Voice Command AI 语音指令（服务器 → 患者） ---
data class AiVoiceCommandData(
    @SerializedName("voice_text") val voiceText: String = "",
    @SerializedName("action_code") val actionCode: String = "",
    @SerializedName("priority") val priority: Int = 0      // 0=普通, 1=警告, 2=紧急
)

// --- (g) Location Update 位置同步 ---
data class LocationUpdateData(
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lng") val lng: Double = 0.0,
    @SerializedName("accuracy") val accuracy: Float = 0f,
    @SerializedName("is_privacy_mode") val isPrivacyMode: Boolean = false,
    @SerializedName("is_sos") val isSos: Boolean = false
)

// --- (h) Device Status 设备状态 ---
data class DeviceStatusData(
    @SerializedName("camera") val camera: Boolean = false,
    @SerializedName("headphone") val headphone: Boolean = false,
    @SerializedName("microphone") val microphone: Boolean = false,
    @SerializedName("battery") val battery: Int = -1,
    @SerializedName("network_type") val networkType: String = "unknown"
)

// --- (i) Manual Message 手动文字消息 ---
data class ManualMessageData(
    @SerializedName("content") val content: String = "",
    @SerializedName("from_role") val fromRole: String = ""   // "patient" | "guardian"
)

// --- (j) Device Control Request 远程设备控制请求 ---
data class DeviceControlRequestData(
    @SerializedName("device") val device: String = "",       // "camera" | "headphone" | "microphone"
    @SerializedName("action") val action: String = "",        // "on" | "off"
    @SerializedName("from_role") val fromRole: String = "guardian"
)

// --- (k) SOS Alert 紧急警报 ---
data class SosAlertData(
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lng") val lng: Double = 0.0,
    @SerializedName("message") val message: String = "患者发出紧急求助！",
    @SerializedName("stream_url") val streamUrl: String = "",
    @SerializedName("is_auto_detected") val isAutoDetected: Boolean = false
)

// --- (l) Call Request 通话请求 ---
data class CallRequestData(
    @SerializedName("call_type") val callType: String = "video",  // "video" | "audio"
    @SerializedName("from_role") val fromRole: String = ""
)

// --- (m) Call Response 通话响应 ---
data class CallResponseData(
    @SerializedName("accepted") val accepted: Boolean = false,
    @SerializedName("from_role") val fromRole: String = ""
)

// --- (n) Danger Alert 后台AI检测到危险自动触发 ---
data class DangerDetectedData(
    @SerializedName("danger_type") val dangerType: String = "",   // "fall" | "unresponsive" | "abnormal_audio"
    @SerializedName("confidence") val confidence: Float = 0f,
    @SerializedName("message") val message: String = ""
)

// ─────────────────────────────────────────────
// 3. 消息类型常量
// ─────────────────────────────────────────────

object MessageType {
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
    const val ROOM_STATUS = "room_status"
    const val STREAM_START = "stream_start"
    const val STREAM_URL = "stream_url"
    const val AI_VOICE_COMMAND = "ai_voice_command"
    const val LOCATION_UPDATE = "location_update"
    const val DEVICE_STATUS = "device_status"
    const val MANUAL_MESSAGE = "manual_message"
    const val DEVICE_CONTROL_REQUEST = "device_control_request"
    const val SOS_ALERT = "sos_alert"
    const val DANGER_DETECTED = "danger_detected"
    const val CALL_REQUEST = "call_request"
    const val CALL_RESPONSE = "call_response"
}
