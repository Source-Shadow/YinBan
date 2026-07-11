// ============================================================
// 路径: app/src/main/java/com/yinban/ai/network/WebSocketManager.kt
// 用途: AI 影伴系统 v1.0 — 工业级 WebSocket 协议管理器（单例）
// 特性:
//   - 基于 OkHttpClient WebSocket
//   - 动态 URL 构建: ws://<IP>:8000/ws?room=<room>&role=<role>
//   - 15~30s 心跳保活 (ping/pong)
//   - 统一消息外壳自动封装 (UUID + ISO 8601)
//   - 错误日志自动 Log.e
//   - 线程安全的消息分发器 (Observer 模式)
// ============================================================

package com.yinban.ai.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class WebSocketManager private constructor() {

    companion object {
        private const val TAG = "WebSocketManager"

        /** 心跳间隔 (毫秒)，范围 [15000, 30000] */
        private const val PING_INTERVAL_MS = 20_000L

        /** WebSocket 默认端口 */
        private const val WS_PORT = 8000

        @Volatile
        private var INSTANCE: WebSocketManager? = null

        /** 获取单例 */
        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager().also { INSTANCE = it }
            }
        }
    }

    // ── OkHttp 客户端 ──
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)     // 长连接不超时
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)         // OkHttp 层 WebSocket Ping
            .retryOnConnectionFailure(true)
            .build()
    }

    // ── 连接状态 ──
    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected: Boolean = false
    @Volatile
    private var isConnecting: Boolean = false

    // ── 连接参数 ──
    private var serverIp: String = ""
    private var room: String = ""
    private var role: String = ""                       // "patient" | "guardian"

    // ── 心跳定时器 ──
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable { sendPing() }

    // ── 消息监听器列表 (线程安全) ──
    private val messageListeners = CopyOnWriteArrayList<MessageListener>()

    private val gson = Gson()

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    /**
     * 连接到 WebSocket 服务器。
     * @param ip   服务器 IP 地址
     * @param room 房间号 / 统一匹配码
     * @param role 角色: "patient" 或 "guardian"
     */
    fun connect(ip: String, room: String, role: String) {
        if (isConnected || isConnecting) {
            Log.w(TAG, "WebSocket 已连接或正在连接中，忽略重复 connect()")
            return
        }

        // 参数校验
        require(role == "patient" || role == "guardian") {
            "role 只能是 'patient' 或 'guardian'，当前值: $role"
        }

        this.serverIp = ip
        this.room = room
        this.role = role

        isConnecting = true

        val url = buildWebSocketUrl(ip, room, role)
        Log.i(TAG, "正在连接 WebSocket: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "YinBan-Android/1.0")
            .build()

        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    /**
     * 断开 WebSocket 连接并清理资源。
     */
    fun disconnect() {
        Log.i(TAG, "断开 WebSocket 连接")
        stopHeartbeat()
        isConnected = false
        isConnecting = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        notifyConnectionState(false)
    }

    /**
     * 发送业务消息。
     * 自动包裹 msgId + timestamp 到统一外壳中。
     */
    fun sendMessage(type: String, data: Any) {
        if (!isConnected) {
            Log.w(TAG, "WebSocket 未连接，无法发送消息 type=$type")
            return
        }

        val message = YinBanMessage(type = type, data = data)
        val json = YinBanMessage.toJson(message)

        Log.d(TAG, "发送消息: type=$type, msgId=${message.msgId}")
        webSocket?.send(json)
    }

    /**
     * 注册消息监听器。
     * 所有收到的服务器消息都会回调给所有注册的监听器（主线程）。
     */
    fun addMessageListener(listener: MessageListener) {
        messageListeners.add(listener)
    }

    /**
     * 移除消息监听器。
     */
    fun removeMessageListener(listener: MessageListener) {
        messageListeners.remove(listener)
    }

    /** 当前连接状态 */
    fun isConnected(): Boolean = isConnected

    /** 获取当前房间号 */
    fun getRoom(): String = room

    /** 获取当前角色 */
    fun getRole(): String = role

    /** 获取当前服务器 IP */
    fun getServerIp(): String = serverIp

    // ═══════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════

    /** 构建 WebSocket URL: ws://<IP>:8000/ws?room=<room>&role=<role> */
    private fun buildWebSocketUrl(ip: String, room: String, role: String): String {
        return "ws://$ip:$WS_PORT/ws?room=$room&role=$role"
    }

    /** 创建 WebSocket 监听器 */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "✅ WebSocket 连接成功 — room=$room, role=$role")
                isConnected = true
                isConnecting = false
                startHeartbeat()
                notifyConnectionState(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到消息: ${text.take(200)}")
                dispatchMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
                isConnected = false
                isConnecting = false
                stopHeartbeat()
                notifyConnectionState(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket 连接失败: ${t.message}", t)
                isConnected = false
                isConnecting = false
                stopHeartbeat()
                notifyConnectionState(false)

                // 通知所有监听器错误
                mainHandler.post {
                    messageListeners.forEach {
                        it.onConnectionError(t.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    /** 分发收到的消息到所有监听器（主线程） */
    private fun dispatchMessage(rawJson: String) {
        try {
            val jsonElement = JsonParser.parseString(rawJson)
            if (!jsonElement.isJsonObject) return

            val jsonObj = jsonElement.asJsonObject
            val type = jsonObj.get("type")?.asString ?: return

            // 特殊处理: pong 心跳回复（仅做健康标记，不分发给业务层）
            if (type == MessageType.PONG) {
                Log.d(TAG, "💓 收到 pong — 连接健康")
                return
            }

            // 特殊处理: error 消息 → Log.e 自动打印
            if (type == MessageType.ERROR) {
                val dataObj = jsonObj.getAsJsonObject("data")
                val errorMsg = dataObj?.get("message")?.asString ?: "Unknown error"
                Log.e(TAG, "❌ 服务器错误: $errorMsg")
            }

            // 反序列化为统一消息外壳
            val message = YinBanMessage.fromJson(rawJson) ?: return

            // 切换到主线程通知所有监听器
            mainHandler.post {
                messageListeners.forEach { listener ->
                    try {
                        listener.onMessage(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "消息监听器异常: ${e.message}", e)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "消息分发异常: ${e.message}", e)
        }
    }

    // ── 心跳机制 ──

    private fun startHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, PING_INTERVAL_MS)
        Log.d(TAG, "心跳定时器已启动，间隔 ${PING_INTERVAL_MS}ms")
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        Log.d(TAG, "心跳定时器已停止")
    }

    private fun sendPing() {
        if (!isConnected) {
            stopHeartbeat()
            return
        }

        try {
            val pingMessage = YinBanMessage(type = MessageType.PING, data = PingData())
            val json = YinBanMessage.toJson(pingMessage)
            webSocket?.send(json)
            Log.d(TAG, "💓 发送 ping")

            // 安排下一次心跳
            mainHandler.postDelayed(heartbeatRunnable, PING_INTERVAL_MS)
        } catch (e: Exception) {
            Log.e(TAG, "发送 ping 失败: ${e.message}", e)
        }
    }

    private fun notifyConnectionState(connected: Boolean) {
        mainHandler.post {
            messageListeners.forEach { it.onConnectionStateChanged(connected) }
        }
    }

    // ═══════════════════════════════════════════
    // 消息监听器接口
    // ═══════════════════════════════════════════

    interface MessageListener {
        /** 收到服务器消息（主线程回调） */
        fun onMessage(message: YinBanMessage)

        /** 连接状态变化 */
        fun onConnectionStateChanged(connected: Boolean)

        /** 连接错误 */
        fun onConnectionError(error: String)
    }
}
