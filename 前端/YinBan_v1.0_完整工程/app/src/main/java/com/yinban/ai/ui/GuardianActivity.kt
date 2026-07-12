// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/GuardianActivity.kt
// 用途: AI 影伴系统 v1.0 — 监护人端专属画面（重构版）
// 核心:
//   1. 实时数据看板（位置/设备/消息）
//   2. SOS 紧急警报接收面板
//   3. 视频流容器
//   4. 远程控制请求 + 音视频通话入口
// ============================================================

package com.yinban.ai.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityGuardianBinding
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager

class GuardianActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GuardianActivity"
    }

    private lateinit var binding: ActivityGuardianBinding
    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager
    private val gson = Gson()

    private var currentStreamUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager.getInstance(this)
        wsManager = WebSocketManager.getInstance()

        initViews()
        connectWebSocket()
    }

    private fun initViews() {
        binding.tvGuardianRoom.text = prefManager.room

        // 配置 WebView（MJPEG 视频流）
        configureWebView()

        // 通话
        binding.btnGuardianVideoCall.setOnClickListener { startCall("video") }
        binding.btnGuardianAudioCall.setOnClickListener { startCall("audio") }

        // SOS 面板 — 查看画面
        binding.btnSosViewVideo.setOnClickListener {
            currentStreamUrl?.let {
                loadMJPEG(it)
                Log.i(TAG, "[WebView] 手动查看 SOS 实时画面: $it")
            } ?: Toast.makeText(this, "暂无视频流地址", Toast.LENGTH_SHORT).show()
        }
        binding.btnSosDismiss.setOnClickListener {
            binding.cardSosAlert.visibility = View.GONE
        }

        // 消息模块 → 跳转聊天页
        binding.btnGuardianGoChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_ROLE, "guardian")
                putExtra(ChatActivity.EXTRA_PEER_NAME, "患者")
            })
        }

        // 退出
        binding.btnGuardianLogout.setOnClickListener { logout() }
    }

    // ═══════════════════════════════════════════
    // WebView 初始化（MJPEG 流播放）
    // ═══════════════════════════════════════════

    private fun configureWebView() {
        binding.webVideo.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.e(TAG, "[WebView] 加载失败: code=${error?.errorCode}, desc=${error?.description}")
                    runOnUiThread { showVideoPlaceholder("视频流连接失败") }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "[WebView] MJPEG 页面加载完成")
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // WebSocket
    // ═══════════════════════════════════════════

    private fun connectWebSocket() {
        wsManager.addMessageListener(msgListener)
        wsManager.connect(ip = prefManager.serverIp, room = prefManager.room, role = "guardian")
    }

    private val msgListener = object : WebSocketManager.MessageListener {
        override fun onMessage(message: YinBanMessage) {
            when (message.type) {
                MessageType.ROOM_STATUS -> handleRoomStatus(message)
                MessageType.LOCATION_UPDATE -> handleLocation(message)
                MessageType.DEVICE_STATUS -> handleDeviceStatus(message)
                MessageType.MANUAL_MESSAGE -> handleManualMsg(message)
                MessageType.STREAM_URL -> handleStreamUrl(message)
                MessageType.SOS_ALERT -> handleSosAlert(message)
                MessageType.CALL_REQUEST -> handleCallRequest(message)
                else -> {}
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                binding.tvGuardianConnection.text = if (connected) "🟡 等待配对" else "🔴 断开"
                binding.tvGuardianConnection.setTextColor(
                    getColor(if (connected) R.color.status_waiting_text else R.color.status_disconnected))
                if (!connected) {
                    currentStreamUrl = null
                    showVideoPlaceholder("连接已断开")
                }
            }
        }

        override fun onConnectionError(error: String) {
            runOnUiThread { Toast.makeText(this@GuardianActivity, "连接错误: $error", Toast.LENGTH_LONG).show() }
        }
    }

    // ═══════════════════════════════════════════
    // 房间状态
    // ═══════════════════════════════════════════

    private fun handleRoomStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), RoomStatusData::class.java)
        runOnUiThread {
            when (data.status) {
                "paired" -> {
                    binding.tvGuardianConnection.text = "🟢 已配对"
                    binding.tvGuardianConnection.setTextColor(getColor(R.color.status_online_text))
                }
                "waiting" -> {
                    binding.tvGuardianConnection.text = "🟡 等待患者"
                    binding.tvGuardianConnection.setTextColor(getColor(R.color.status_waiting_text))
                }
                "disconnected" -> {
                    binding.tvGuardianConnection.text = "🔴 断开"
                    binding.tvGuardianConnection.setTextColor(getColor(R.color.status_disconnected))
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 位置更新
    // ═══════════════════════════════════════════

    private fun handleLocation(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), LocationUpdateData::class.java)
        runOnUiThread {
            binding.tvLocationLat.text = "纬度: ${"%.6f".format(data.lat)}"
            binding.tvLocationLng.text = "经度: ${"%.6f".format(data.lng)}"
            binding.tvLocationAccuracy.text = if (data.isPrivacyMode) "精度: ~1.1km (隐私)" else "精度: ${data.accuracy}m"
            if (data.isSos) {
                binding.tvLocationAccuracy.text = "⚠️ SOS精确位置: ${data.accuracy}m"
            }
        }
    }

    // ═══════════════════════════════════════════
    // 设备状态
    // ═══════════════════════════════════════════

    private fun handleDeviceStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), DeviceStatusData::class.java)
        runOnUiThread {
            binding.tvCameraStatus.text = if (data.camera) "摄像头: 🟢" else "摄像头: ⚪"
            binding.tvCameraStatus.setTextColor(getColor(if (data.camera) R.color.status_online else R.color.text_secondary))
            binding.tvHeadphoneStatus.text = if (data.headphone) "耳机: 🟢" else "耳机: ⚪"
            binding.tvHeadphoneStatus.setTextColor(getColor(if (data.headphone) R.color.status_online else R.color.text_secondary))
        }
    }

    // ═══════════════════════════════════════════
    // 文字消息
    // ═══════════════════════════════════════════

    private fun handleManualMsg(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), ManualMessageData::class.java)
        runOnUiThread {
            binding.tvPatientMessage.text = data.content
            Snackbar.make(binding.root, "💬 患者: ${data.content}", Snackbar.LENGTH_LONG)
                .setAction("查看") {}.show()
        }
    }

    // ═══════════════════════════════════════════
    // 视频流
    // ═══════════════════════════════════════════

    private fun handleStreamUrl(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamUrlData::class.java)
        currentStreamUrl = data.url
        Log.i(TAG, "[WebView] 收到 MJPEG 流地址: ${data.url}, stream_type=${data.streamType}")
        runOnUiThread {
            loadMJPEG(data.url)
        }
    }

    // ═══════════════════════════════════════════
    // MJPEG 流加载 & 占位恢复
    // ═══════════════════════════════════════════

    private fun loadMJPEG(url: String) {
        binding.webVideo.apply {
            visibility = View.VISIBLE
            stopLoading()
            loadUrl(url)
        }
        binding.surfaceVideo.visibility = View.GONE
        binding.tvGuardianVideoPlaceholder.visibility = View.GONE
        Log.i(TAG, "[WebView] 已载入 MJPEG 流: $url")
    }

    private fun showVideoPlaceholder(message: String = "等待患者画面") {
        binding.webVideo.visibility = View.GONE
        binding.surfaceVideo.visibility = View.GONE
        binding.tvGuardianVideoPlaceholder.apply {
            visibility = View.VISIBLE
            text = "📺\n$message"
        }
    }

    // ═══════════════════════════════════════════
    // 🚨 SOS 紧急警报接收
    // ═══════════════════════════════════════════

    private fun handleSosAlert(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), SosAlertData::class.java)
        currentStreamUrl = data.streamUrl
        Log.w(TAG, "🚨 收到 SOS 警报! ${data.message} 位置:(${data.lat},${data.lng})")

        runOnUiThread {
            // 高亮显示 SOS 面板
            binding.cardSosAlert.visibility = View.VISIBLE
            binding.tvSosMessage.text = data.message
            binding.tvSosLocation.text = "${"%.6f".format(data.lat)}, ${"%.6f".format(data.lng)}"

            // 同时更新看板
            binding.tvLocationLat.text = "纬度: ${"%.6f".format(data.lat)}"
            binding.tvLocationLng.text = "经度: ${"%.6f".format(data.lng)}"
            binding.tvLocationAccuracy.text = "⚠️ SOS紧急定位"

            // 有流地址自动显示
            if (data.streamUrl.isNotBlank()) {
                loadMJPEG(data.streamUrl)
                Log.i(TAG, "[WebView] SOS 自动载入 MJPEG 实时画面: ${data.streamUrl}")
            }

            Toast.makeText(this@GuardianActivity, "🚨 收到紧急求助！", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════
    // 通话请求
    // ═══════════════════════════════════════════

    private fun handleCallRequest(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), CallRequestData::class.java)
        val callType = data.callType
        val displayType = if (callType == "video") "视频" else "语音"
        runOnUiThread {
            MaterialAlertDialogBuilder(this@GuardianActivity)
                .setTitle("📞 ${displayType}通话请求")
                .setMessage("患者邀请你进行${displayType}通话")
                .setPositiveButton("接听") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(true, "guardian"))
                    startActivity(Intent(this@GuardianActivity, VideoCallActivity::class.java).apply {
                        putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
                        putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
                    })
                }
                .setNegativeButton("拒绝") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(false, "guardian"))
                }
                .show()
        }
    }

    // ═══════════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════════

    private fun startCall(callType: String) {
        if (!wsManager.isConnected()) { Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show(); return }
        wsManager.sendMessage(MessageType.CALL_REQUEST, CallRequestData(callType, "guardian"))
        startActivity(Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
        })
    }

    private fun logout() {
        wsManager.removeMessageListener(msgListener)
        wsManager.disconnect()
        prefManager.clearLoginInfo()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onResume() {
        super.onResume()
        binding.webVideo.onResume()
    }

    override fun onPause() {
        binding.webVideo.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webVideo.apply {
            stopLoading()
            settings.javaScriptEnabled = false
            destroy()
        }
        super.onDestroy()
        wsManager.removeMessageListener(msgListener)
    }
}
