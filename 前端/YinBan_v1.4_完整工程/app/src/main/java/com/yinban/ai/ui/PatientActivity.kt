// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/PatientActivity.kt
// v1.1 — 4 Tab 底部导航 + Fragment 架构
// ============================================================

package com.yinban.ai.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityPatientBinding
import com.yinban.ai.hardware.HardwareStreamManager
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import com.yinban.ai.utils.LocationPrivacy
import java.util.Locale
import java.util.concurrent.TimeUnit

class PatientActivity : AppCompatActivity(),
    HomeFragment.HomeCallback,
    GuardianFragment.GuardianCallback,
    MeFragment.MeCallback {

    companion object { private const val TAG = "PatientActivity" }

    private lateinit var binding: ActivityPatientBinding
    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager
    private val gson = Gson()

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var hardwareStream: HardwareStreamManager = HardwareStreamManager.NO_OP
    private var isStreaming = false
    private var isPrivacyMode = false
    private var isVideoOn = false
    private var isAudioOn = false

    /** Snackbar 定位到顶部连接横幅下方，避免遮挡底部导航栏 */
    private fun snackbar(msg: String, dur: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return Snackbar.make(binding.root, msg, dur)
            .setAnchorView(R.id.fragment_container)
    }

    private fun homeSnackbar(msg: String, dur: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return snackbar(msg, dur).setAnchorView(binding.bottomNav).also { bar ->
            bar.view.background = ContextCompat.getDrawable(this, R.drawable.yb_home_bg_snackbar)
            bar.view.findViewById<TextView>(MaterialR.id.snackbar_text)
                ?.setTextColor(ContextCompat.getColor(this, R.color.yb_color_night_text_primary))
            (bar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                val margin = (20 * resources.displayMetrics.density).toInt()
                params.setMargins(margin, params.topMargin, margin, margin)
                bar.view.layoutParams = params
            }
        }
    }

    // ── 定时器 ──
    private var connectedSeconds = 0L
    private var roomStatus: String = "disconnected"
    private var latestPatientMessage: String = "暂无消息"
    private var latestLat: Double = 0.0
    private var latestLng: Double = 0.0

    private val timerHandler = Handler(Looper.getMainLooper())
    private val standbyTimer = object : Runnable {
        override fun run() {
            connectedSeconds++
            val min = TimeUnit.SECONDS.toMinutes(connectedSeconds)
            val subtitle = if (min < 60) "已安全守护 $min 分钟"
            else "已安全守护 ${min / 60}小时${min % 60}分钟"
            pushStandbyToFragment(subtitle)
            timerHandler.postDelayed(this, 60_000)
        }
    }

    private fun pushStandbyToFragment(subtitle: String) {
        homeFragment?.let { frag ->
            if (frag.isAdded && !frag.isDetached) {
                frag.updateStandbyUI(HomeFragment.ConnectionState(standbySubtitle = subtitle))
            }
        }
    }

    // ── Fragment 引用 ──
    private var homeFragment: HomeFragment? = null
    private var chatAiFragment: ChatAiFragment? = null
    private var guardianFragment: GuardianFragment? = null
    private var meFragment: MeFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.yb_color_night_background_deep)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.yb_color_night_background_deep)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        prefManager = PreferenceManager.getInstance(this)
        wsManager = WebSocketManager.getInstance()
        isPrivacyMode = prefManager.isPrivacyMode

        // 加载用户 API Key 到 DeepSeekClient
        val savedKey = prefManager.deepseekApiKey
        if (savedKey.isNotBlank()) DeepSeekClient.apiKey = savedKey

        binding.tvRoomNumber.text = "房间 ${prefManager.room.ifBlank { "----" }}"

        setupBottomNavigation()
        initTts()
        connectWebSocket()
        timerHandler.postDelayed(standbyTimer, 60_000)

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            try {
                val tag: String
                val fragment = when (item.itemId) {
                    R.id.nav_home -> {
                        tag = "home"
                        homeFragment ?: HomeFragment().also { homeFragment = it }
                    }
                    R.id.nav_chatai -> {
                        tag = "chatai"
                        chatAiFragment ?: ChatAiFragment().also { chatAiFragment = it }
                    }
                    R.id.nav_guardian -> {
                        tag = "guardian"
                        guardianFragment ?: GuardianFragment().also { guardianFragment = it }
                    }
                    R.id.nav_me -> {
                        tag = "me"
                        meFragment ?: MeFragment().also { meFragment = it }
                    }
                    else -> return@setOnItemSelectedListener false
                }

                supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                    .replace(R.id.fragment_container, fragment, tag)
                    .commitAllowingStateLoss()
            } catch (e: Exception) {
                // 如果状态丢失，静默失败；其他异常通过 Snackbar 显示
                val msg = e.message ?: "未知错误"
                try {
                    snackbar("切换失败: $msg", Snackbar.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this@PatientActivity, "切换失败: $msg", Toast.LENGTH_LONG).show()
                }
            }
            true
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { s ->
            isTtsReady = (s == TextToSpeech.SUCCESS)
            if (isTtsReady) tts?.language = Locale.CHINESE
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {}
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
        })
    }

    // ═══════════════════════════════════════════
    // HomeFragment.HomeCallback 实现
    // ═══════════════════════════════════════════

    override fun onSosTriggered() {
        // 点击 SOS 按钮 → 弹出确认对话框
        showSosConfirmDialog()
    }

    override fun onVideoToggle(enabled: Boolean) {
        isVideoOn = enabled
        if (enabled) {
            hardwareStream.startVideoStream()
            val cameraUrl = getCameraStreamUrl()
            wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("video", cameraUrl))
            homeSnackbar("摄像头已开启，监护人可查看", Snackbar.LENGTH_SHORT).show()
        } else {
            hardwareStream.stopVideoStream()
            wsManager.sendMessage(MessageType.STREAM_STOP, StreamStopData("video"))
            homeSnackbar("摄像头已关闭", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onAudioToggle(enabled: Boolean) {
        isAudioOn = enabled
        if (enabled) {
            hardwareStream.startAudioStream()
            wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("audio"))
            homeSnackbar("麦克风已开启，监护人可听到", Snackbar.LENGTH_SHORT).show()
        } else {
            hardwareStream.stopAudioStream()
            wsManager.sendMessage(MessageType.STREAM_STOP, StreamStopData("audio"))
            homeSnackbar("麦克风已关闭", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onSendLocation() {
        sendLocationUpdate()
    }

    override fun onSendDeviceStatus() {
        sendDeviceStatus()
    }

    override fun getConnectionState(): HomeFragment.ConnectionState {
        val title = when (roomStatus) {
            "paired" -> "AI 影伴守护中"
            else -> "AI 影伴待命中"
        }
        val subtitle = when {
            roomStatus != "paired" -> "等待监护人连入"
            connectedSeconds > 0 -> {
                val min = TimeUnit.SECONDS.toMinutes(connectedSeconds)
                if (min < 60) "已安全守护 $min 分钟"
                else "已安全守护 ${min / 60}小时${min % 60}分钟"
            }
            else -> "已安全守护 0 分钟"
        }
        return HomeFragment.ConnectionState(
            connected = wsManager.isConnected(),
            roomStatus = roomStatus,
            standbyTitle = title,
            standbySubtitle = subtitle
        )
    }

    // ═══════════════════════════════════════════
    // GuardianFragment.GuardianCallback 实现
    // ═══════════════════════════════════════════

    override fun onOpenChat() {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_ROLE, "patient")
            putExtra(ChatActivity.EXTRA_MODE, "manual")
            putExtra(ChatActivity.EXTRA_PEER_NAME, "监护人")
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun onOpenLocation() {
        Toast.makeText(this, "位置: ${"%.6f".format(latestLat)}, ${"%.6f".format(latestLng)}",
            Toast.LENGTH_LONG).show()
    }

    override fun onStartCall() {
        startCall("video")
    }

    override fun onViewStream() {
        // 跳转到视频通话界面查看实时画面
        startActivity(Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, "video")
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
            putExtra(VideoCallActivity.EXTRA_STREAM_URL, getCameraStreamUrl())
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun getRecentMessage(): String = latestPatientMessage

    override fun getLocationStatus(): String =
        if (latestLat != 0.0) "已共享" else "等待定位"

    // ═══════════════════════════════════════════
    // MeFragment.MeCallback 实现
    // ═══════════════════════════════════════════

    override fun onLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？退出后将断开连接。")
            .setPositiveButton("确定退出") { _, _ -> logout() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ═══════════════════════════════════════════
    // WebSocket
    // ═══════════════════════════════════════════

    private fun connectWebSocket() {
        wsManager.addMessageListener(msgListener)
        wsManager.connect(ip = prefManager.serverIp, room = prefManager.room, role = "patient")
    }

    private val msgListener = object : WebSocketManager.MessageListener {
        override fun onMessage(message: YinBanMessage) {
            when (message.type) {
                MessageType.ROOM_STATUS -> handleRoomStatus(message)
                MessageType.AI_VOICE_COMMAND -> handleAiVoice(message)
                MessageType.MANUAL_MESSAGE -> handleManualMsg(message)
                MessageType.DANGER_DETECTED -> handleDangerDetected(message)
                MessageType.CHAT_AI_RESPONSE -> handleChatAiResponse(message)
                MessageType.CALL_REQUEST -> handleCallRequest(message)
                MessageType.STREAM_STATUS -> handleStreamStatus(message)
                else -> {}
            }
        }
        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateConnectionBanner(connected)
                if (chatAiFragment?.isAdded == true && !chatAiFragment!!.isDetached) {
                    chatAiFragment?.updateAiStatus(connected)
                }
                if (!connected) timerHandler.removeCallbacks(standbyTimer)
            }
        }
        override fun onConnectionError(error: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    snackbar("连接失败: $error", Snackbar.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
    }

    // ═══════════════════════════════════════════
    // 连接状态横幅
    // ═══════════════════════════════════════════

    private fun updateConnectionBanner(connected: Boolean) {
        val dot = binding.dotStatus
        val tv = binding.tvConnectionStatus
        val banner = binding.bannerConnection
        when {
            !connected -> {
                banner.setBackgroundResource(R.drawable.yb_home_bg_compact_status)
                dot.background.setTint(getColor(R.color.yb_color_status_offline))
                tv.text = "连接已断开"
                tv.setTextColor(getColor(R.color.yb_color_night_text_primary))
            }
            roomStatus == "paired" -> {
                banner.setBackgroundResource(R.drawable.yb_home_bg_compact_status)
                dot.background.setTint(getColor(R.color.yb_color_status_connected))
                tv.text = "已配对 · AI 影伴守护中"
                tv.setTextColor(getColor(R.color.yb_color_night_text_primary))
            }
            else -> {
                banner.setBackgroundResource(R.drawable.yb_home_bg_compact_status)
                dot.background.setTint(getColor(R.color.yb_color_status_waiting))
                tv.text = "等待监护人连入"
                tv.setTextColor(getColor(R.color.yb_color_night_text_primary))
            }
        }
        binding.root.announceForAccessibility(tv.text)
    }

    // ═══════════════════════════════════════════
    // 消息处理
    // ═══════════════════════════════════════════

    private fun handleRoomStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), RoomStatusData::class.java)
        roomStatus = data.status
        runOnUiThread {
            when (data.status) {
                "paired" -> {
                    updateConnectionBanner(true)
                    connectedSeconds = 0
                    timerHandler.removeCallbacks(standbyTimer)
                    timerHandler.postDelayed(standbyTimer, 60_000)
                    // 立即更新 UI：计时未满 1 分钟时显示计时中
                    pushStandbyToFragment("已安全守护 0 分钟")
                }
                "waiting" -> {
                    updateConnectionBanner(true)
                }
                "disconnected" -> {
                    updateConnectionBanner(false)
                }
            }
        }
    }

    private fun handleAiVoice(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), AiVoiceCommandData::class.java)
        if (isTtsReady && tts != null)
            tts?.speak(data.voiceText, TextToSpeech.QUEUE_FLUSH, null, "ai_${System.currentTimeMillis()}")
        runOnUiThread { snackbar("🎙 ${data.voiceText}", Snackbar.LENGTH_SHORT).show() }
    }

    private fun handleDangerDetected(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), DangerDetectedData::class.java)
        val shouldSos = data.confidence > 0.7f
        runOnUiThread {
            snackbar("⚠️ ${data.message}", Snackbar.LENGTH_LONG).show()
            if (shouldSos) triggerSos(isAutoDetected = true)
        }
    }

    private fun handleManualMsg(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), ManualMessageData::class.java)
        latestPatientMessage = data.content
        runOnUiThread {
            if (guardianFragment?.isAdded == true && !guardianFragment!!.isDetached) {
                guardianFragment?.refreshStatus()
            }
            try {
                snackbar("💬 监护人: ${data.content}", Snackbar.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun handleChatAiResponse(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), ChatAiResponseData::class.java)
        val shouldSos = data.isDanger
        runOnUiThread {
            snackbar("🤖 小影火: ${data.reply.take(60)}...", Snackbar.LENGTH_SHORT).show()
            if (shouldSos) triggerSos(isAutoDetected = true)
        }
    }

    private fun handleCallRequest(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), CallRequestData::class.java)
        val t = if (data.callType == "video") "视频" else "语音"
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle("📞 ${t}通话请求")
                .setMessage("监护人邀请你进行${t}通话")
                .setPositiveButton("接听") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(true, "patient"))
                    startCall(data.callType)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(false, "patient"))
                }.show()
        }
    }

    private fun handleStreamStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), StreamStatusData::class.java)
        if (data.streamType == "video") {
            val active = data.status == "live"
            runOnUiThread {
                if (isVideoOn != active) {
                    isVideoOn = active
                    // 同步 HomeFragment 开关状态
                    if (homeFragment?.isAdded == true && !homeFragment!!.isDetached) {
                        homeFragment?.updateStandbyUI(getConnectionState())
                    }
                }
                val statusText = when (data.status) {
                    "live" -> "视频流已就绪"
                    "stopped" -> "视频流已停止"
                    else -> "视频流状态: ${data.status}"
                }
                snackbar(statusText, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════
    // 位置 / 设备 / 通话
    // ═══════════════════════════════════════════

    private fun sendLocationUpdate() {
        val fuzzed = LocationPrivacy.fuzzLocation(31.230416, 121.473701, isPrivacyMode, false)
        latestLat = fuzzed.lat; latestLng = fuzzed.lng
        wsManager.sendMessage(MessageType.LOCATION_UPDATE,
            LocationUpdateData(fuzzed.lat, fuzzed.lng, if (fuzzed.isFuzzed) 1100f else 5f, isPrivacyMode, false))
        homeSnackbar("位置已上报${if (fuzzed.isFuzzed) "(隐私)" else ""}", Snackbar.LENGTH_SHORT).show()
    }

    private fun sendDeviceStatus() {
        wsManager.sendMessage(MessageType.DEVICE_STATUS,
            DeviceStatusData(camera = isVideoOn, headphone = true, microphone = isAudioOn, battery = 85, networkType = "wifi"))
        homeSnackbar("设备状态已上报", Snackbar.LENGTH_SHORT).show()
    }

    private fun showSosConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🆘 确认紧急求助")
            .setMessage("即将向监护人发送紧急求助信号并共享你当前的精确位置。确定要继续吗？")
            .setPositiveButton("确定求助") { _, _ -> triggerSos() }
            .setNegativeButton("取消") { _, _ ->
                snackbar("已取消求助", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun triggerSos(isAutoDetected: Boolean = false) {
        Log.w(TAG, "🆘 SOS! auto=$isAutoDetected")
        val fuzzed = LocationPrivacy.fuzzLocation(31.230416, 121.473701, false, true)
        wsManager.sendMessage(MessageType.SOS_ALERT, SosAlertData(
            lat = fuzzed.lat, lng = fuzzed.lng,
            message = if (isAutoDetected) "AI检测异常，自动触发求助" else "患者手动触发紧急求助！",
            isAutoDetected = isAutoDetected))
        wsManager.sendMessage(MessageType.LOCATION_UPDATE,
            LocationUpdateData(fuzzed.lat, fuzzed.lng, 5f, false, true))
        hardwareStream.startVideoStream()
        val cameraUrl = getCameraStreamUrl()
        wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("video", cameraUrl))
        isStreaming = true
        binding.root.announceForAccessibility("紧急求助已发送")
        snackbar("🆘 紧急求助已发送！监护人已收到警报", Snackbar.LENGTH_LONG).show()
    }

    /** 获取摄像头推流地址（患者端可配置，默认 ESP32 热点 IP） */
    private fun getCameraStreamUrl(): String {
        return prefManager.cameraUrl.ifBlank { "http://10.240.11.161:5000/video_feed" }
    }

    private fun startCall(callType: String) {
        if (!wsManager.isConnected()) {
            snackbar("未连接", Snackbar.LENGTH_SHORT).show()
            return
        }
        wsManager.sendMessage(MessageType.CALL_REQUEST, CallRequestData(callType, "patient"))
        startActivity(Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
            putExtra(VideoCallActivity.EXTRA_STREAM_URL, getCameraStreamUrl())
        })
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun logout() {
        timerHandler.removeCallbacks(standbyTimer)
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
        super.onDestroy()
        timerHandler.removeCallbacks(standbyTimer)
        wsManager.removeMessageListener(msgListener)
        tts?.stop(); tts?.shutdown()
    }
}
