// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/PatientActivity.kt
// 用途: AI 影伴系统 v1.0 — 患者端专属画面
// 比例: 安心待机 3 : 消息模块 3 : SOS 1
// 消息: 文字 + 语音双输入
// ============================================================

package com.yinban.ai.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityPatientBinding
import com.yinban.ai.hardware.HardwareStreamManager
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import com.yinban.ai.utils.LocationPrivacy
import java.util.Locale
import java.util.concurrent.TimeUnit

class PatientActivity : AppCompatActivity() {

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

    private var connectedSeconds = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val standbyTimer = object : Runnable {
        override fun run() {
            connectedSeconds++
            val min = TimeUnit.SECONDS.toMinutes(connectedSeconds)
            binding.tvStandbySubtitle.text = if (min < 60) "已安全守护 $min 分钟"
            else "已安全守护 ${min / 60}小时${min % 60}分钟"
            timerHandler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager.getInstance(this)
        wsManager = WebSocketManager.getInstance()
        isPrivacyMode = prefManager.isPrivacyMode

        initViews()
        initTts()
        connectWebSocket()
        timerHandler.postDelayed(standbyTimer, 60_000)
    }

    private fun initViews() {
        binding.tvRoomNumber.text = prefManager.room
        binding.switchPrivacyMode.isChecked = isPrivacyMode
        binding.switchPrivacyMode.setOnCheckedChangeListener { _, c ->
            isPrivacyMode = c; prefManager.isPrivacyMode = c
            Toast.makeText(this, if (c) "🔒 隐私模式开" else "📍 精确定位", Toast.LENGTH_SHORT).show()
        }

        binding.btnSendLocation.setOnClickListener { sendLocationUpdate() }
        binding.btnSendDeviceStatus.setOnClickListener { sendDeviceStatus() }
        binding.btnVideoCall.setOnClickListener { startCall("video") }
        binding.btnAudioCall.setOnClickListener { startCall("audio") }

        // ── 视频/音频开关 ──
        binding.switchVideo.setOnCheckedChangeListener { _, on ->
            isVideoOn = on
            if (on) {
                hardwareStream.startVideoStream()
                wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("video"))
                Toast.makeText(this, "🎥 视频已开启，监护人可查看", Toast.LENGTH_SHORT).show()
            } else {
                hardwareStream.stopVideoStream()
                Toast.makeText(this, "🎥 视频已关闭", Toast.LENGTH_SHORT).show()
            }
        }
        binding.switchAudio.setOnCheckedChangeListener { _, on ->
            isAudioOn = on
            if (on) {
                hardwareStream.startAudioStream()
                wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("audio"))
                Toast.makeText(this, "🎤 音频已开启，监护人可听到", Toast.LENGTH_SHORT).show()
            } else {
                hardwareStream.stopAudioStream()
                Toast.makeText(this, "🎤 音频已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSos.setOnClickListener { triggerSos() }
        binding.btnLogout.setOnClickListener { logout() }

        // ── 消息模块 → 跳转聊天页 ──
        binding.btnGoChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_ROLE, "patient")
                putExtra(ChatActivity.EXTRA_PEER_NAME, "监护人")
            })
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { s -> isTtsReady = (s == TextToSpeech.SUCCESS); if (isTtsReady) tts?.language = Locale.CHINESE }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {}
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
        })
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
                MessageType.CALL_REQUEST -> handleCallRequest(message)
                else -> {}
            }
        }
        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                binding.tvConnectionStatus.text = if (connected) "🟡 等待配对" else "🔴 断开"
                binding.tvConnectionStatus.setTextColor(getColor(if (connected) R.color.status_waiting_text else R.color.status_disconnected))
                if (!connected) timerHandler.removeCallbacks(standbyTimer)
            }
        }
        override fun onConnectionError(error: String) {
            runOnUiThread { Toast.makeText(this@PatientActivity, "连接失败: $error", Toast.LENGTH_LONG).show() }
        }
    }

    // ═══════════════════════════════════════════
    // 消息处理
    // ═══════════════════════════════════════════

    private fun handleRoomStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), RoomStatusData::class.java)
        runOnUiThread {
            when (data.status) {
                "paired" -> {
                    binding.tvConnectionStatus.text = "🟢 已配对"
                    binding.tvConnectionStatus.setTextColor(getColor(R.color.status_online_text))
                    binding.tvStandbyTitle.text = "AI 影伴守护中"
                    connectedSeconds = 0
                    timerHandler.removeCallbacks(standbyTimer)
                    timerHandler.postDelayed(standbyTimer, 60_000)
                }
                "waiting" -> {
                    binding.tvConnectionStatus.text = "🟡 等待监护人连入"
                    binding.tvConnectionStatus.setTextColor(getColor(R.color.status_waiting_text))
                }
                "disconnected" -> {
                    binding.tvConnectionStatus.text = "🔴 已断开"
                    binding.tvConnectionStatus.setTextColor(getColor(R.color.status_disconnected))
                }
            }
        }
    }

    private fun handleAiVoice(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), AiVoiceCommandData::class.java)
        if (isTtsReady && tts != null) tts?.speak(data.voiceText, TextToSpeech.QUEUE_FLUSH, null, "ai_${System.currentTimeMillis()}")
        runOnUiThread { Toast.makeText(this, "🎙 ${data.voiceText}", Toast.LENGTH_SHORT).show() }
    }

    private fun handleDangerDetected(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), DangerDetectedData::class.java)
        runOnUiThread { Toast.makeText(this, "⚠️ ${data.message}", Toast.LENGTH_LONG).show() }
        if (data.confidence > 0.7f) triggerSos(isAutoDetected = true)
    }

    private fun handleManualMsg(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), ManualMessageData::class.java)
        runOnUiThread {
            Toast.makeText(this, "💬 监护人: ${data.content}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCallRequest(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), CallRequestData::class.java)
        val t = if (data.callType == "video") "视频" else "语音"
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

    // ═══════════════════════════════════════════
    // 位置 / 设备 / 通话
    // ═══════════════════════════════════════════

    private fun sendLocationUpdate() {
        val fuzzed = LocationPrivacy.fuzzLocation(31.230416, 121.473701, isPrivacyMode, false)
        wsManager.sendMessage(MessageType.LOCATION_UPDATE,
            LocationUpdateData(fuzzed.lat, fuzzed.lng, if (fuzzed.isFuzzed) 1100f else 5f, isPrivacyMode, false))
        Toast.makeText(this, "📍 位置已上报${if (fuzzed.isFuzzed) "(隐私)" else ""}", Toast.LENGTH_SHORT).show()
    }

    private fun sendDeviceStatus() {
        wsManager.sendMessage(MessageType.DEVICE_STATUS,
            DeviceStatusData(camera = true, headphone = true, microphone = false, battery = 85, networkType = "wifi"))
        Toast.makeText(this, "📱 设备状态已上报", Toast.LENGTH_SHORT).show()
    }

    private fun triggerSos(isAutoDetected: Boolean = false) {
        Log.w(TAG, "🆘 SOS! auto=$isAutoDetected")
        val fuzzed = LocationPrivacy.fuzzLocation(31.230416, 121.473701, false, true)
        wsManager.sendMessage(MessageType.SOS_ALERT, SosAlertData(
            lat = fuzzed.lat, lng = fuzzed.lng,
            message = if (isAutoDetected) "AI检测异常，自动触发求助" else "患者手动触发紧急求助！",
            isAutoDetected = isAutoDetected))
        wsManager.sendMessage(MessageType.LOCATION_UPDATE, LocationUpdateData(fuzzed.lat, fuzzed.lng, 5f, false, true))
        hardwareStream.startVideoStream()
        wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("video"))
        isStreaming = true
        binding.tvStandbyTitle.text = "🆘 求助已发出"
        binding.tvStandbySubtitle.text = "请保持冷静，监护人已收到警报"
        binding.tvStandbyTitle.setTextColor(getColor(R.color.error))
        Toast.makeText(this, "🆘 紧急求助已发送！", Toast.LENGTH_LONG).show()
    }

    private fun startCall(callType: String) {
        if (!wsManager.isConnected()) { Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show(); return }
        wsManager.sendMessage(MessageType.CALL_REQUEST, CallRequestData(callType, "patient"))
        startActivity(Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
        })
    }

    private fun logout() {
        timerHandler.removeCallbacks(standbyTimer)
        wsManager.removeMessageListener(msgListener); wsManager.disconnect()
        prefManager.clearLoginInfo()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(standbyTimer)
        wsManager.removeMessageListener(msgListener)
        tts?.stop(); tts?.shutdown()
    }
}
