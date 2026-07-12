// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/home/HomeFragment.kt
// 用途: 主页 Tab — 设备状态总览 + 快捷SOS + 连接状态
// 说明: 不需连接监护人即可使用的基本功能
// ============================================================

package com.yinban.ai.ui.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.FragmentHomeBinding
import com.yinban.ai.hardware.HardwareStreamManager
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import com.yinban.ai.utils.LocationPrivacy
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "HomeFragment"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager
    private val gson = Gson()
    private var hardwareStream: HardwareStreamManager = HardwareStreamManager.NO_OP

    private var isVideoOn = false
    private var isAudioOn = false
    private var isPrivacyMode = false
    private var connectedSeconds = 0L

    private val timerHandler = Handler(Looper.getMainLooper())
    private val standbyTimer = object : Runnable {
        override fun run() {
            connectedSeconds++
            val min = TimeUnit.SECONDS.toMinutes(connectedSeconds)
            binding.tvHomeStandby.apply {
                visibility = View.VISIBLE
                text = if (min < 60) "已安全守护 $min 分钟"
                else "已安全守护 ${min / 60}小时${min % 60}分钟"
            }
            timerHandler.postDelayed(this, 60_000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefManager = PreferenceManager.getInstance(requireContext())
        wsManager = WebSocketManager.getInstance()
        isPrivacyMode = prefManager.isPrivacyMode

        initViews()
        connectIfLoggedIn()
    }

    private fun initViews() {
        binding.tvHomeRoom.text = "房间: ${prefManager.room}"
        binding.switchHomePrivacy.isChecked = isPrivacyMode

        // 连接按钮
        binding.btnHomeConnect.setOnClickListener {
            if (wsManager.isConnected()) {
                wsManager.disconnect()
                Toast.makeText(requireContext(), "已断开连接", Toast.LENGTH_SHORT).show()
            } else {
                connectWebSocket()
            }
        }

        // SOS
        binding.btnHomeSos.setOnClickListener { triggerSos() }

        // 视频开关
        binding.switchHomeVideo.setOnCheckedChangeListener { _, on ->
            isVideoOn = on
            if (on) {
                hardwareStream.startVideoStream()
                wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("video"))
                Toast.makeText(requireContext(), "🎥 视频已开启", Toast.LENGTH_SHORT).show()
            } else {
                hardwareStream.stopVideoStream()
                Toast.makeText(requireContext(), "🎥 视频已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // 音频开关
        binding.switchHomeAudio.setOnCheckedChangeListener { _, on ->
            isAudioOn = on
            if (on) {
                hardwareStream.startAudioStream()
                wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("audio"))
                Toast.makeText(requireContext(), "🎤 音频已开启", Toast.LENGTH_SHORT).show()
            } else {
                hardwareStream.stopAudioStream()
                Toast.makeText(requireContext(), "🎤 音频已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // 隐私模式
        binding.switchHomePrivacy.setOnCheckedChangeListener { _, on ->
            isPrivacyMode = on
            prefManager.isPrivacyMode = on
            Toast.makeText(requireContext(), if (on) "🔒 隐私模式开" else "📍 精确定位", Toast.LENGTH_SHORT).show()
        }

        // 快捷操作
        binding.btnHomeLocation.setOnClickListener { sendLocationUpdate() }
        binding.btnHomeDevice.setOnClickListener { sendDeviceStatus() }
    }

    private fun connectIfLoggedIn() {
        if (prefManager.hasValidAutoLogin() && !wsManager.isConnected()) {
            connectWebSocket()
        }
    }

    private fun connectWebSocket() {
        wsManager.addMessageListener(msgListener)
        wsManager.connect(
            ip = prefManager.serverIp,
            room = prefManager.room,
            role = prefManager.role
        )
        binding.btnHomeConnect.text = "连接中…"
    }

    private val msgListener = object : WebSocketManager.MessageListener {
        override fun onMessage(message: YinBanMessage) {
            when (message.type) {
                MessageType.ROOM_STATUS -> handleRoomStatus(message)
                MessageType.AI_VOICE_COMMAND -> handleAiVoice(message)
                MessageType.DANGER_DETECTED -> handleDangerDetected(message)
                else -> {}
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            activity?.runOnUiThread {
                if (connected) {
                    binding.tvHomeStatus.text = "🟡 等待配对"
                    binding.tvHomeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_waiting_text))
                    binding.btnHomeConnect.text = "断开连接"
                } else {
                    binding.tvHomeStatus.text = "🔴 断开"
                    binding.tvHomeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected))
                    binding.btnHomeConnect.text = "连接服务器"
                    binding.tvHomeStandby.visibility = View.GONE
                    timerHandler.removeCallbacks(standbyTimer)
                }
            }
        }

        override fun onConnectionError(error: String) {
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "连接失败: $error", Toast.LENGTH_LONG).show()
                binding.btnHomeConnect.text = "连接服务器"
            }
        }
    }

    private fun handleRoomStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), RoomStatusData::class.java)
        activity?.runOnUiThread {
            when (data.status) {
                "paired" -> {
                    binding.tvHomeStatus.text = "🟢 已配对"
                    binding.tvHomeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online_text))
                    binding.tvHomeSubtitle.text = "AI 影伴守护中"
                    connectedSeconds = 0
                    timerHandler.removeCallbacks(standbyTimer)
                    timerHandler.postDelayed(standbyTimer, 60_000)
                }
                "waiting" -> {
                    binding.tvHomeStatus.text = "🟡 等待配对"
                    binding.tvHomeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_waiting_text))
                }
                "disconnected" -> {
                    binding.tvHomeStatus.text = "🔴 已断开"
                    binding.tvHomeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected))
                    binding.tvHomeStandby.visibility = View.GONE
                }
            }
        }
    }

    private fun handleAiVoice(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), AiVoiceCommandData::class.java)
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "🎙 ${data.voiceText}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDangerDetected(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), DangerDetectedData::class.java)
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "⚠️ ${data.message}", Toast.LENGTH_LONG).show()
        }
        if (data.confidence > 0.7f) triggerSos(isAutoDetected = true)
    }

    private fun sendLocationUpdate() {
        val fuzzed = LocationPrivacy.fuzzLocation(31.230416, 121.473701, isPrivacyMode, false)
        wsManager.sendMessage(MessageType.LOCATION_UPDATE, LocationUpdateData(
            fuzzed.lat, fuzzed.lng, if (fuzzed.isFuzzed) 1100f else 5f, isPrivacyMode, false))
        Toast.makeText(requireContext(), "📍 位置已上报${if (fuzzed.isFuzzed) "(隐私)" else ""}", Toast.LENGTH_SHORT).show()
    }

    private fun sendDeviceStatus() {
        wsManager.sendMessage(MessageType.DEVICE_STATUS, DeviceStatusData(
            camera = isVideoOn, headphone = isAudioOn, microphone = isAudioOn, battery = 85, networkType = "wifi"))
        Toast.makeText(requireContext(), "📱 设备状态已上报", Toast.LENGTH_SHORT).show()
    }

    private fun triggerSos(isAutoDetected: Boolean = false) {
        val fuzzed = LocationPrivacy.fuzzLocation(31.230416, 121.473701, false, true)
        wsManager.sendMessage(MessageType.SOS_ALERT, SosAlertData(
            lat = fuzzed.lat, lng = fuzzed.lng,
            message = if (isAutoDetected) "AI检测异常，自动触发求助" else "患者手动触发紧急求助！",
            isAutoDetected = isAutoDetected))
        wsManager.sendMessage(MessageType.LOCATION_UPDATE, LocationUpdateData(
            fuzzed.lat, fuzzed.lng, 5f, false, true))
        hardwareStream.startVideoStream()
        wsManager.sendMessage(MessageType.STREAM_START, StreamStartData("video"))
        Toast.makeText(requireContext(), "🆘 紧急求助已发送！监护人已收到警报", Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerHandler.removeCallbacks(standbyTimer)
        wsManager.removeMessageListener(msgListener)
        _binding = null
    }
}
