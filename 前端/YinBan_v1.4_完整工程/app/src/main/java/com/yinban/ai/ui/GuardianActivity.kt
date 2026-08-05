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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.config.CameraSource
import com.yinban.ai.databinding.ActivityGuardianBinding
import com.yinban.ai.hardware.MjpegStreamer
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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

    // ★ 摄像头来源追踪
    private var currentCameraSource: CameraSource = CameraSource.DEFAULT
    private var usingFallback: Boolean = false

    // ★ MJPEG 流播放器（替代 WebView）
    private val mjpegStreamer = MjpegStreamer()
    private var frameCount = 0

    // ★ 连通性探测专用短超时 client
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun snackbar(msg: String, dur: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return Snackbar.make(binding.root, msg, dur)
            .setAnchorView(R.id.card_dashboard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyNightSystemBars()

        prefManager = PreferenceManager.getInstance(this)
        wsManager = WebSocketManager.getInstance()

        setupBottomNav()
        initViews()
        connectWebSocket()
    }

    private fun applyNightSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.yb_color_night_background_deep)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.yb_color_night_background_deep)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？退出后将断开与患者的连接。")
            .setPositiveButton("确定退出") { _, _ -> logout() }
            .setNegativeButton("取消", null)
            .show()
        applyNightLogoutDialogStyle(dialog)
    }

    private fun applyNightLogoutDialogStyle(dialog: AlertDialog) {
        val primaryText = ContextCompat.getColor(this, R.color.yb_color_night_text_primary)
        val secondaryText = ContextCompat.getColor(this, R.color.yb_color_night_text_secondary)
        val cancelAction = ContextCompat.getColor(this, R.color.yb_color_primary)
        val dangerAction = ContextCompat.getColor(this, R.color.yb_color_status_danger)

        dialog.window?.setBackgroundDrawableResource(R.drawable.yb_me_bg_dialog)
        dialog.findViewById<TextView>(MaterialR.id.alertTitle)?.setTextColor(primaryText)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(secondaryText)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cancelAction)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(dangerAction)
    }

    private fun initViews() {
        binding.tvGuardianRoom.text = prefManager.room

        // 通话
        binding.btnGuardianAudioCall.setOnClickListener { startCall("audio") }

        // SOS 面板
        binding.btnSosViewVideo.setOnClickListener {
            currentStreamUrl?.let {
                startMjpegWithFallback(it)
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

        // 生成报告
        binding.btnGenerateReport.setOnClickListener {
            if (!wsManager.isConnected()) {
                snackbar("未连接服务器", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            wsManager.sendMessage(MessageType.GENERATE_REPORT_REQUEST,
                mapOf("room" to prefManager.room))
            snackbar("正在生成报告...", Snackbar.LENGTH_SHORT).show()
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
                MessageType.GENERATE_REPORT_RESPONSE -> handleReportResponse(message)
                MessageType.AUDIO_TRANSCRIPT -> handleAudioTranscript(message)
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
                tv.setTextColor(getColor(R.color.yb_color_night_text_secondary))
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
            binding.tvMicrophoneStatus.text = if (data.microphone) "麦克风: 🟢" else "麦克风: ⚪"
            binding.tvMicrophoneStatus.setTextColor(
                getColor(if (data.microphone) R.color.status_online_text else R.color.text_secondary))
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
    // ★ 视频流 — MJPEG ImageView + 双源兜底
    // ═══════════════════════════════════════════

    private fun handleStreamUrl(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamUrlData::class.java)
        currentStreamUrl = data.url
        Log.i(TAG, "[Video] 收到推流地址: ${data.url}")
        runOnUiThread { startMjpegWithFallback(data.url) }
    }

    private fun handleStreamStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamStatusData::class.java)
        runOnUiThread {
            // 音频流状态 → Snackbar 反馈
            if (data.streamType == "audio") {
                if (data.status == "live") snackbar("🎤 患者麦克风已开启，语音转录中", Snackbar.LENGTH_SHORT).show()
                else snackbar("🔇 患者麦克风已关闭", Snackbar.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            // 视频流状态
            when (data.status) {
                "live" -> {
                    if (data.url.isNotBlank()) {
                        currentStreamUrl = data.url
                        startMjpegWithFallback(data.url)
                    } else if (currentStreamUrl != null) {
                        startMjpegWithFallback(currentStreamUrl!!)
                    }
                }
                "stopped" -> {
                    mjpegStreamer.stop()
                    binding.ivVideo.visibility = View.GONE
                    showVideoPlaceholder("摄像头已关闭")
                }
                "error" -> showVideoPlaceholder("视频流错误")
            }
        }
    }

    /**
     * 启动 MJPEG 预览流，优先尝试主摄像头，失败则自动切换到兜底摄像头。
     * 如果传入的 URL 是服务器下发的（非本地构建），直接使用 URL 但跟踪来源。
     */
    private fun startMjpegWithFallback(url: String) {
        val primarySource = CameraSource.fromKey(prefManager.primaryCamera)
        val fallbackSource = CameraSource.fallbackOf(primarySource)
        val ip = extractIpFromUrl(url) ?: prefManager.serverIp

        // 先用服务器给的 URL
        currentStreamUrl = url
        currentCameraSource = detectSourceFromUrl(url)
        usingFallback = false

        binding.ivVideo.visibility = View.VISIBLE
        binding.tvGuardianVideoPlaceholder.apply {
            visibility = View.VISIBLE
            text = "正在连接 ${currentCameraSource.displayName}..."
        }
        frameCount = 0

        if (probeUrl(url)) {
            startMjpegStream(url)
            Log.i(TAG, "[Video] 主路可用 → ${currentCameraSource.displayName}: $url")
        } else {
            // 尝试构建兜底 URL
            val fallbackUrl = fallbackSource.buildUrl(ip)
            Log.w(TAG, "[Video] 主路不可达: $url，尝试兜底: $fallbackUrl")

            binding.tvGuardianVideoPlaceholder.text = "尝试连接 ${fallbackSource.displayName}..."

            if (probeUrl(fallbackUrl)) {
                currentStreamUrl = fallbackUrl
                currentCameraSource = fallbackSource
                usingFallback = true
                startMjpegStream(fallbackUrl)
                Toast.makeText(this, "已自动切换到 ${fallbackSource.displayName}", Toast.LENGTH_LONG).show()
                Log.i(TAG, "[Video] 兜底可用 → ${fallbackSource.displayName}: $fallbackUrl")
            } else {
                // 两路都不通，用原始 URL 尝试连接（让用户看到具体错误）
                Log.w(TAG, "[Video] 两路均不可达，回退到原始 URL")
                startMjpegStream(url)
            }
        }
    }

    private fun startMjpegStream(url: String) {
        binding.ivVideo.visibility = View.VISIBLE
        binding.tvGuardianVideoPlaceholder.apply {
            visibility = View.VISIBLE
        }

        mjpegStreamer.stop()  // 先停掉之前的流
        mjpegStreamer.start(url, object : MjpegStreamer.Callback {
            override fun onConnected() {
                Log.i(TAG, "[Video] MJPEG 已连接 (${currentCameraSource.displayName})")
                runOnUiThread {
                    binding.tvGuardianVideoPlaceholder.visibility = View.GONE
                }
            }

            override fun onFrame(bitmap: Bitmap) {
                frameCount++
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "[Video] 已接收 $frameCount 帧 (${currentCameraSource.displayName})")
                }
                binding.ivVideo.setImageBitmap(bitmap)
            }

            override fun onError(message: String) {
                Log.e(TAG, "[Video] MJPEG 错误 (${currentCameraSource}): $message")
                runOnUiThread {
                    // ★ 如果主路出错还没试过兜底，自动切换
                    if (!usingFallback) {
                        val fallbackSource = CameraSource.fallbackOf(currentCameraSource)
                        val ip = prefManager.serverIp
                        val fallbackUrl = fallbackSource.buildUrl(ip)
                        Log.i(TAG, "[Video] 主路出错，自动尝试兜底: $fallbackUrl")
                        currentCameraSource = fallbackSource
                        currentStreamUrl = fallbackUrl
                        usingFallback = true
                        binding.tvGuardianVideoPlaceholder.text = "正在连接 ${fallbackSource.displayName}..."
                        startMjpegStream(fallbackUrl)
                        return@runOnUiThread
                    }
                    showVideoPlaceholder("视频流连接失败\n$message")
                }
            }

            override fun onDisconnected() {
                Log.i(TAG, "[Video] MJPEG 已断开 (${currentCameraSource})")
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
            text = message
        }
    }

    // ═══════════════════════════════════════════
    // 音频转录
    // ═══════════════════════════════════════════

    private val transcriptLog = mutableListOf<String>()

    private fun handleAudioTranscript(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), AudioTranscriptData::class.java)
        val text = data.text
        runOnUiThread {
            // 保留最近 20 条转录
            transcriptLog.add(text)
            if (transcriptLog.size > 20) transcriptLog.removeAt(0)

            // 更新"最近消息"栏
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            binding.tvPatientMessage.text = "🎤 [$now] $text"
            binding.tvPatientMessage.setTextColor(getColor(R.color.status_online_text))

            // Snackbar 底部弹出
            snackbar("💬 患者语音: $text", Snackbar.LENGTH_LONG).show()
            // Toast 顶部弹出，双保险确保看见
            Toast.makeText(this@GuardianActivity, "🎤 患者: $text", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════
    // ★ 连通性探测 & URL 工具
    // ═══════════════════════════════════════════

    /** 用短超时 HTTP HEAD 请求探测 URL 是否可达 */
    private fun probeUrl(url: String, timeoutMs: Long = 3000): Boolean {
        val client = if (timeoutMs == 3000L) probeClient else {
            OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.d(TAG, "[Probe] $url 不可达: ${e.message}")
            false
        }
    }

    /** 从 URL 中提取 IP 地址 */
    private fun extractIpFromUrl(url: String): String? {
        return try {
            val host = java.net.URL(url).host
            if (host.isNotBlank()) host else null
        } catch (_: Exception) { null }
    }

    /** 根据 URL 特征推测摄像头来源 */
    private fun detectSourceFromUrl(url: String): CameraSource {
        return when {
            url.contains(":81") || url.contains("/stream") -> CameraSource.ESP32_FISHEYE
            else -> CameraSource.IP_WEBCAM
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
                startMjpegWithFallback(data.streamUrl)
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
                        putExtra(VideoCallActivity.EXTRA_PRIMARY_SOURCE, prefManager.primaryCamera)
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
        val levelLabel = when (data.dangerLevel) {
            "danger" -> "🔴 危险"
            "warning" -> "🟡 注意"
            else -> "⚠️ 提醒"
        }
        val tagsInfo = if (data.visualTags.isNotEmpty()) " | 环境: ${data.visualTags.joinToString(", ")}" else ""
        Log.w(TAG, "$levelLabel 小影火检测到风险: ${data.message}$tagsInfo")
        runOnUiThread {
            binding.cardSosAlert.visibility = View.VISIBLE
            binding.tvSosMessage.text = "🔥 小影火 $levelLabel: ${data.message}$tagsInfo"
            binding.tvSosLocation.text = "AI 自动检测 | 置信度: ${"%.0f".format(data.confidence * 100)}%"
            snackbar("$levelLabel 小影火检测到潜在风险！", Snackbar.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════
    // 报告生成
    // ═══════════════════════════════════════════

    private fun handleReportResponse(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), GenerateReportResponseData::class.java)
        runOnUiThread {
            if (data.logCount == 0) {
                snackbar(data.report, Snackbar.LENGTH_LONG).show()
                return@runOnUiThread
            }
            MaterialAlertDialogBuilder(this@GuardianActivity)
                .setTitle("📋 患者行为分析报告")
                .setMessage("${data.report}\n\n— 基于最近 ${data.logCount} 条事件记录 | ${data.generatedAt}")
                .setPositiveButton("我知道了") { _, _ -> }
                .show()
            snackbar("报告已生成 (${data.logCount} 条记录)", Snackbar.LENGTH_SHORT).show()
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
            putExtra(VideoCallActivity.EXTRA_PRIMARY_SOURCE, prefManager.primaryCamera)
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
