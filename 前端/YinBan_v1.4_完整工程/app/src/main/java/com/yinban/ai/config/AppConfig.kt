// ============================================================
// 路径: app/src/main/java/com/yinban/ai/config/AppConfig.kt
// 用途: 网络 + 服务配置集中管理，消除分散硬编码
// v2: 新增 CameraSource 枚举，支持多摄像头来源（IP Webcam / ESP32 鱼眼）
// ============================================================

package com.yinban.ai.config

import com.yinban.ai.BuildConfig

/**
 * 应用中所有网络相关常量和 URL 构建逻辑的唯一入口。
 * 修改服务器 IP / 端口时只需改这一个文件（以及 BuildConfig 默认值）。
 */
object AppConfig {

    // ═══════════════════════════════════════════
    // 端口常量
    // ═══════════════════════════════════════════

    /** WebSocket 服务器端口 */
    const val WS_PORT = 8000

    /** MJPEG 视频流默认端口（硬件 Flask 服务 / IP Webcam） */
    const val MJPEG_PORT = 5000

    /** MJPEG 推流路径（IP Webcam / Flask） */
    const val MJPEG_PATH = "/video_feed"

    /** ESP32 鱼眼摄像头默认端口 */
    const val ESP32_PORT = 81

    /** ESP32 鱼眼摄像头推流路径 */
    const val ESP32_PATH = "/stream"

    // ═══════════════════════════════════════════
    // 默认值（来自 BuildConfig）
    // ═══════════════════════════════════════════

    /**
     * 获取默认服务器 IP（不含 ws:// 前缀）。
     * 优先使用 BuildConfig 中编译时设定的值。
     */
    fun defaultServerIp(): String {
        return BuildConfig.WS_BASE_URL.replace("ws://", "").trimEnd('/')
    }

    // ═══════════════════════════════════════════
    // URL 构建
    // ═══════════════════════════════════════════

    /**
     * 构建完整的 WebSocket 连接 URL。
     * @param ip 服务器 IP（不含协议前缀），如 "192.168.1.8"
     * @param room 房间号
     * @param role 角色 ("patient" | "guardian")
     */
    fun buildWsUrl(ip: String, room: String, role: String): String {
        return "ws://$ip:$WS_PORT/ws?room=$room&role=$role"
    }

    /**
     * 构建 MJPEG 推流 HTTP URL（IP Webcam / Flask 服务）。
     * @param ip 硬件/服务器 IP（不含协议前缀）
     */
    fun buildMjpegUrl(ip: String): String {
        return "http://$ip:$MJPEG_PORT$MJPEG_PATH"
    }

    /**
     * 根据 CameraSource 构建对应的推流 URL。
     * @param ip 摄像头所在设备的 IP（不含协议前缀）
     * @param source 摄像头来源类型
     */
    fun buildCameraUrl(ip: String, source: CameraSource): String {
        return source.buildUrl(ip)
    }
}

// ═══════════════════════════════════════════
// 摄像头来源枚举
// ═══════════════════════════════════════════

/**
 * 摄像头来源类型，集中管理不同摄像头的 URL 模式。
 *
 * 每种来源定义了各自的默认端口和推流路径，
 * 通过 [buildUrl] 直接生成完整 MJPEG URL。
 */
enum class CameraSource(
    val key: String,
    val displayName: String,
    val defaultPort: Int,
    val defaultPath: String
) {
    /** 手机 IP Webcam 软件推流（主路） */
    IP_WEBCAM("ip_webcam", "IP摄像头", 5000, "/video_feed"),

    /** M5Stack ESP32 鱼眼摄像头推流（兜底） */
    ESP32_FISHEYE("esp32_fisheye", "ESP32鱼眼", 81, "/stream");

    /** 根据 IP 构建完整推流 URL */
    fun buildUrl(ip: String): String = "http://$ip:$defaultPort$defaultPath"

    companion object {
        /** 默认主摄像头 */
        val DEFAULT = IP_WEBCAM

        /** 根据 key 反查，找不到则返回默认值 */
        fun fromKey(key: String): CameraSource =
            entries.find { it.key == key } ?: DEFAULT

        /**
         * 获取某个来源的兜底来源。
         * IP_WEBCAM → ESP32_FISHEYE，反之亦然。
         */
        fun fallbackOf(source: CameraSource): CameraSource = when (source) {
            IP_WEBCAM -> ESP32_FISHEYE
            ESP32_FISHEYE -> IP_WEBCAM
        }
    }
}
