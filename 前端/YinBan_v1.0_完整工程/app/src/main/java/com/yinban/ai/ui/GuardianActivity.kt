// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/GuardianActivity.kt
// v1.1 — 监护人端 + 退出确认 + Snackbar反馈 + 淡入淡出
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
    private var roomStatus: String = "disconnected"

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

        configureWebView()

        // 通话
        binding.btnGuardianVideoCall.setOnClickListener { startCall("video") }
        binding.btnGuardianAudioCall.setOnClickListener { startCall("audio") }

        // SOS 面板
        binding.btnSosViewVideo.setOnClickListener {
            currentStreamUrl?.let {
                loadMJPEG(it)
                Log.i(TAG, "[WebView] 查看 SOS 实时画面: $it")
            } ?: Snackbar.make(binding.root, "暂无视频流地址", Snackbar.LENGTH_SHORT).show()
        }
        binding.btnSosDismiss.setOnClickListener {
            binding.cardSosAlert.visibility = View.GONE
        }

        // 消息模块
        binding.btnGuardianGoChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_ROLE, "guardian")
                putExtra(ChatActivity.EXTRA_MODE, "manual")
                putExtra(ChatActivity.EXTRA_PEER_NAME, "患者")
            })
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // 退出 → 二次确认
        binding.btnGuardianLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("退出登录")
                .setMessage("确定要退出登录吗？退出后将断开与患者的连接。")
                .setPositiveButton("确定退出") { _, _ -> logout() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ═══════════════════════════════════════════
    // WebView
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
                    Log.e(TAG, "[WebView] 加载失败: code=${error?.errorCode}")
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
                MessageType.DANGER_DETECTED -> handleDangerDetected(message)
                else -> {}
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                updateConnectionBanner(connected)
                if (!connected) {
                    currentStreamUrl = null
                    showVideoPlaceholder("连接已断开")
                }
            }
        }

        override fun onConnectionError(error: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    Snackbar.make(binding.root, "连接错误: $error", Snackbar.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
    }

    private fun updateConnectionBanner(connected: Boolean) {
        val dot = binding.dotStatus
        val tv = binding.tvGuardianConnection
        when {
            !connected -> {
                dot.background.setTint(getColor(R.color.status_disconnected))
                tv.text = "连接已断开"
                tv.setTextColor(getColor(R.color.text_secondary))
            }
            roomStatus == "paired" -> {
                dot.background.setTint(getColor(R.color.status_online))
                tv.text = "已配对 · 守护中"
                tv.setTextColor(getColor(R.color.status_online_text))
            }
            else -> {
                dot.background.setTint(getColor(R.color.status_waiting))
                tv.text = "等待患者连入"
                tv.setTextColor(getColor(R.color.status_waiting_text))
            }
        }
    }

    // ═══════════════════════════════════════════
    // 房间状态
    // ═══════════════════════════════════════════

    private fun handleRoomStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), RoomStatusData::class.java)
        roomStatus = data.status
        runOnUiThread { updateConnectionBanner(true) }
    }

    // ═══════════════════════════════════════════
    // 位置更新
    // ═══════════════════════════════════════════

    private fun handleLocation(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), LocationUpdateData::class.java)
        runOnUiThread {
            binding.tvLocationLat.text = "纬度: ${"%.6f".format(data.lat)}"
            binding.tvLocationLng.text = "经度: ${"%.6f".format(data.lng)}"
            binding.tvLocationAccuracy.text = if (data.isPrivacyMode)
                "精度: ~1.1km (隐私)" else "精度: ${data.accuracy}m"
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
            binding.tvCameraStatus.setTextColor(
                getColor(if (data.camera) R.color.status_online_text else R.color.text_secondary))
            binding.tvHeadphoneStatus.text = if (data.headphone) "耳机: 🟢" else "耳机: ⚪"
            binding.tvHeadphoneStatus.setTextColor(
                getColor(if (data.headphone) R.color.status_online_text else R.color.text_secondary))
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
                .setAction("查看") {
                    startActivity(Intent(this@GuardianActivity, ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_ROLE, "guardian")
                        putExtra(ChatActivity.EXTRA_MODE, "manual")
                        putExtra(ChatActivity.EXTRA_PEER_NAME, "患者")
                    })
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                }.show()
        }
    }

    // ═══════════════════════════════════════════
    // 视频流
    // ═══════════════════════════════════════════

    private fun handleStreamUrl(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamUrlData::class.java)
        currentStreamUrl = data.url
        Log.i(TAG, "[WebView] 收到 MJPEG: ${data.url}")
        runOnUiThread { loadMJPEG(data.url) }
    }

    private fun loadMJPEG(url: String) {
        binding.webVideo.apply {
            visibility = View.VISIBLE
            stopLoading()
            loadUrl(url)
        }
        binding.surfaceVideo.visibility = View.GONE
        binding.tvGuardianVideoPlaceholder.visibility = View.GONE
        Log.i(TAG, "[WebView] MJPEG 载入: $url")
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
    // SOS 警报
    // ═══════════════════════════════════════════

    private fun handleSosAlert(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), SosAlertData::class.java)
        currentStreamUrl = data.streamUrl
        Log.w(TAG, "🚨 SOS! ${data.message}")

        runOnUiThread {
            binding.cardSosAlert.visibility = View.VISIBLE
            binding.tvSosMessage.text = data.message
            binding.tvSosLocation.text = "${"%.6f".format(data.lat)}, ${"%.6f".format(data.lng)}"
            binding.tvLocationLat.text = "纬度: ${"%.6f".format(data.lat)}"
            binding.tvLocationLng.text = "经度: ${"%.6f".format(data.lng)}"
            binding.tvLocationAccuracy.text = "⚠️ SOS紧急定位"

            if (data.streamUrl.isNotBlank()) {
                loadMJPEG(data.streamUrl)
            }

            binding.root.announceForAccessibility("收到紧急求助！")
            Snackbar.make(binding.root, "🚨 收到紧急求助！", Snackbar.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════
    // 通话请求
    // ═══════════════════════════════════════════

    private fun handleCallRequest(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), CallRequestData::class.java)
        val displayType = if (data.callType == "video") "视频" else "语音"
        runOnUiThread {
            MaterialAlertDialogBuilder(this@GuardianActivity)
                .setTitle("📞 ${displayType}通话请求")
                .setMessage("患者邀请你进行${displayType}通话")
                .setPositiveButton("接听") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(true, "guardian"))
                    startActivity(Intent(this@GuardianActivity, VideoCallActivity::class.java).apply {
                        putExtra(VideoCallActivity.EXTRA_CALL_TYPE, data.callType)
                        putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
                    })
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(false, "guardian"))
                }
                .show()
        }
    }

    // ═══════════════════════════════════════════
    // 危险检测
    // ═══════════════════════════════════════════

    private fun handleDangerDetected(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), DangerDetectedData::class.java)
        Log.w(TAG, "⚠️ 小影火检测到风险: ${data.message}")
        runOnUiThread {
            binding.cardSosAlert.visibility = View.VISIBLE
            binding.tvSosMessage.text = "🔥 小影火: ${data.message}"
            binding.tvSosLocation.text = "AI 自动检测"
            Snackbar.make(binding.root, "⚠️ 小影火检测到潜在风险！", Snackbar.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════════

    private fun startCall(callType: String) {
        if (!wsManager.isConnected()) {
            Snackbar.make(binding.root, "未连接", Snackbar.LENGTH_SHORT).show()
            return
        }
        wsManager.sendMessage(MessageType.CALL_REQUEST, CallRequestData(callType, "guardian"))
        startActivity(Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun logout() {
        wsManager.removeMessageListener(msgListener)
        wsManager.disconnect()
        prefManager.clearLoginInfo()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
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
