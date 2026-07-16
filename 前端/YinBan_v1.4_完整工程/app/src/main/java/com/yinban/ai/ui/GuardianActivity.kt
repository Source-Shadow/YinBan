// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/GuardianActivity.kt
// v1.2 — 监护人端 + MJPEG ImageView + 退出确认 + Snackbar反馈
// ============================================================

package com.yinban.ai.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityGuardianBinding
import com.yinban.ai.hardware.MjpegStreamer
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager

class GuardianActivity : AppCompatActivity(), MeFragment.MeCallback {

    companion object {
        private const val TAG = "GuardianActivity"
    }

    private lateinit var binding: ActivityGuardianBinding
    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager
    private val gson = Gson()

    private var currentStreamUrl: String? = null
    private var roomStatus: String = "disconnected"
    private var meFragment: MeFragment? = null

    // ★ MJPEG 流播放器（替代 WebView）
    private val mjpegStreamer = MjpegStreamer()
    private var frameCount = 0

    private fun snackbar(msg: String, dur: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return Snackbar.make(binding.root, msg, dur)
            .setAnchorView(R.id.card_dashboard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager.getInstance(this)
        wsManager = WebSocketManager.getInstance()

        setupBottomNav()
        initViews()
        connectWebSocket()
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_guardian_home -> {
                    supportFragmentManager.findFragmentByTag("me")?.let {
                        supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
                    }
                    binding.guardianMain.visibility = View.VISIBLE
                    true
                }
                R.id.nav_me -> {
                    binding.guardianMain.visibility = View.GONE
                    val frag = meFragment ?: MeFragment.newInstance().also { meFragment = it }
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.content_area, frag, "me")
                        .commitAllowingStateLoss()
                    true
                }
                else -> false
            }
        }
    }

    // ═══════════════════════════════════════════
    // MeFragment.MeCallback
    // ═══════════════════════════════════════════

    override fun onLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？退出后将断开与患者的连接。")
            .setPositiveButton("确定退出") { _, _ -> logout() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun initViews() {
        binding.tvGuardianRoom.text = prefManager.room

        // 通话
        binding.btnGuardianAudioCall.setOnClickListener { startCall("audio") }

        // SOS 面板
        binding.btnSosViewVideo.setOnClickListener {
            currentStreamUrl?.let {
                startMjpegStream(it)
                Log.i(TAG, "[Video] 查看 SOS 实时画面: $it")
            } ?: snackbar("暂无视频流地址", Snackbar.LENGTH_SHORT).show()
        }
        binding.btnSosDismiss.setOnClickListener {
            binding.cardSosAlert.visibility = View.GONE
        }

        // 请求查看画面
        binding.btnGuardianViewVideo.setOnClickListener {
            Log.i(TAG, "[Video] 监护人请求开启摄像头")
            showVideoPlaceholder("正在请求画面...")
            wsManager.sendMessage(MessageType.DEVICE_CONTROL_REQUEST,
                DeviceControlRequestData("camera", "on", "guardian"))
            snackbar("已发送画面请求", Snackbar.LENGTH_SHORT).show()
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
                MessageType.STREAM_STATUS -> handleStreamStatus(message)
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
                    mjpegStreamer.stop()
                    showVideoPlaceholder("连接已断开")
                }
            }
        }

        override fun onConnectionError(error: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    snackbar("连接错误: $error", Snackbar.LENGTH_LONG).show()
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
            snackbar("💬 患者: ${data.content}", Snackbar.LENGTH_LONG)
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
    // ★ 视频流 — 使用 MJPEG ImageView（替代 WebView）
    // ═══════════════════════════════════════════

    private fun handleStreamUrl(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamUrlData::class.java)
        currentStreamUrl = data.url
        Log.i(TAG, "[Video] 收到推流地址: ${data.url}")
        runOnUiThread { startMjpegStream(data.url) }
    }

    private fun handleStreamStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamStatusData::class.java)
        runOnUiThread {
            when (data.status) {
                "live" -> {
                    if (data.url.isNotBlank()) {
                        currentStreamUrl = data.url
                        startMjpegStream(data.url)
                    } else if (currentStreamUrl != null) {
                        startMjpegStream(currentStreamUrl!!)
                    }
                }
                "stopped" -> {
                    mjpegStreamer.stop()
                    binding.ivVideo.visibility = View.GONE
                    showVideoPlaceholder("摄像头已关闭")
                }
                "error" -> {
                    showVideoPlaceholder("视频流错误")
                }
            }
        }
    }

    private fun startMjpegStream(url: String) {
        binding.ivVideo.visibility = View.VISIBLE
        binding.tvGuardianVideoPlaceholder.apply {
            visibility = View.VISIBLE
            text = "📺\n正在连接摄像头..."
        }
        frameCount = 0

        mjpegStreamer.stop()  // 先停掉之前的流
        mjpegStreamer.start(url, object : MjpegStreamer.Callback {
            override fun onConnected() {
                Log.i(TAG, "[Video] MJPEG 已连接")
                runOnUiThread {
                    binding.tvGuardianVideoPlaceholder.visibility = View.GONE
                }
            }

            override fun onFrame(bitmap: Bitmap) {
                frameCount++
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "[Video] 已接收 $frameCount 帧")
                }
                binding.ivVideo.setImageBitmap(bitmap)
            }

            override fun onError(message: String) {
                Log.e(TAG, "[Video] MJPEG 错误: $message")
                runOnUiThread { showVideoPlaceholder("视频流连接失败\n$message") }
            }

            override fun onDisconnected() {
                Log.i(TAG, "[Video] MJPEG 已断开")
                runOnUiThread {
                    if (currentStreamUrl != null) {
                        showVideoPlaceholder("视频流已断开")
                    }
                }
            }
        })
    }

    private fun showVideoPlaceholder(message: String = "等待患者画面") {
        mjpegStreamer.stop()
        binding.ivVideo.visibility = View.GONE
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
                startMjpegStream(data.streamUrl)
            }

            binding.root.announceForAccessibility("收到紧急求助！")
            snackbar("🚨 收到紧急求助！", Snackbar.LENGTH_LONG).show()
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
                        // ★ 传入当前推流地址
                        currentStreamUrl?.let { putExtra(VideoCallActivity.EXTRA_STREAM_URL, it) }
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
            snackbar("⚠️ 小影火检测到潜在风险！", Snackbar.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════════

    private fun startCall(callType: String) {
        if (!wsManager.isConnected()) {
            snackbar("未连接", Snackbar.LENGTH_SHORT).show()
            return
        }
        wsManager.sendMessage(MessageType.CALL_REQUEST, CallRequestData(callType, "guardian"))
        startActivity(Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
            currentStreamUrl?.let { putExtra(VideoCallActivity.EXTRA_STREAM_URL, it) }
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun logout() {
        mjpegStreamer.stop()
        wsManager.removeMessageListener(msgListener)
        wsManager.disconnect()
        prefManager.clearLoginInfo()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        mjpegStreamer.stop()
        wsManager.removeMessageListener(msgListener)
        super.onDestroy()
    }
}
