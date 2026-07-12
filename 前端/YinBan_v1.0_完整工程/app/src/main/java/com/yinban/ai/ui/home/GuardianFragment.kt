// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/home/GuardianFragment.kt
// 用途: 监护栏 Tab — 与监护人交互核心入口
// 功能: 收发消息 / 摄像头耳机权限管理 / 位置共享 / 通话请求
// ============================================================

package com.yinban.ai.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.FragmentGuardianBinding
import com.yinban.ai.hardware.HardwareStreamManager
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import com.yinban.ai.ui.ChatActivity
import com.yinban.ai.ui.VideoCallActivity
import com.yinban.ai.utils.LocationPrivacy

class GuardianFragment : Fragment() {

    companion object {
        private const val TAG = "GuardianFragment"
    }

    private var _binding: FragmentGuardianBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager
    private val gson = Gson()
    private var hardwareStream: HardwareStreamManager = HardwareStreamManager.NO_OP

    private var isVideoOn = false
    private var isAudioOn = false
    private var isPaired = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGuardianBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefManager = PreferenceManager.getInstance(requireContext())
        wsManager = WebSocketManager.getInstance()

        initViews()
        wsManager.addMessageListener(msgListener)
        updateRoomInfo()
    }

    private fun initViews() {
        binding.tvGuardianRoomInfo.text = "房间: ${prefManager.room}"

        // 位置共享
        binding.btnShareLocation.setOnClickListener { sendLocation() }

        // 视频开关
        binding.switchGuardianVideo.setOnCheckedChangeListener { _, on ->
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
        binding.switchGuardianAudio.setOnCheckedChangeListener { _, on ->
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

        // 消息
        binding.btnGuardianChat.setOnClickListener {
            startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_ROLE, prefManager.role)
                putExtra(ChatActivity.EXTRA_PEER_NAME, if (prefManager.isPatient()) "监护人" else "患者")
            })
        }

        // 通话
        binding.btnGuardianVideoCall.setOnClickListener { startCall("video") }
        binding.btnGuardianAudioCall.setOnClickListener { startCall("audio") }
    }

    private fun updateRoomInfo() {
        binding.tvGuardianRoomInfo.text = "房间: ${prefManager.room}"
        binding.tvGuardianStatus.text = if (wsManager.isConnected()) "🟡 等待配对" else "🔴 未连接"
    }

    private val msgListener = object : WebSocketManager.MessageListener {
        override fun onMessage(message: YinBanMessage) {
            when (message.type) {
                MessageType.ROOM_STATUS -> handleRoomStatus(message)
                MessageType.LOCATION_UPDATE -> handleLocation(message)
                MessageType.MANUAL_MESSAGE -> handleManualMsg(message)
                MessageType.CALL_REQUEST -> handleCallRequest(message)
                else -> {}
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            activity?.runOnUiThread {
                if (connected) {
                    binding.tvGuardianStatus.text = if (isPaired) "🟢 已配对" else "🟡 等待配对"
                } else {
                    binding.tvGuardianStatus.text = "🔴 未连接"
                    isPaired = false
                }
            }
        }

        override fun onConnectionError(error: String) {
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "连接错误: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleRoomStatus(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), RoomStatusData::class.java)
        activity?.runOnUiThread {
            when (data.status) {
                "paired" -> {
                    isPaired = true
                    binding.tvGuardianStatus.text = "🟢 已配对"
                    binding.tvGuardianStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online_text))
                }
                "waiting" -> {
                    binding.tvGuardianStatus.text = "🟡 等待配对"
                }
                "disconnected" -> {
                    isPaired = false
                    binding.tvGuardianStatus.text = "🔴 已断开"
                    binding.tvGuardianStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected))
                }
            }
        }
    }

    private fun handleLocation(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), LocationUpdateData::class.java)
        activity?.runOnUiThread {
            val mode = if (data.isPrivacyMode) "隐私" else "精确"
            val sos = if (data.isSos) " ⚠️SOS" else ""
            binding.tvLocationInfo.text = "${String.format("%.4f", data.lat)}, ${String.format("%.4f", data.lng)} ($mode$sos)"
        }
    }

    private fun handleManualMsg(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), ManualMessageData::class.java)
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "💬 ${data.fromRole}: ${data.content}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCallRequest(msg: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(msg.data), CallRequestData::class.java)
        val t = if (data.callType == "video") "视频" else "语音"
        activity?.runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("📞 ${t}通话请求")
                .setMessage("${data.fromRole}邀请你进行${t}通话")
                .setPositiveButton("接听") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(true, prefManager.role))
                    startCall(data.callType)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    wsManager.sendMessage(MessageType.CALL_RESPONSE, CallResponseData(false, prefManager.role))
                }.show()
        }
    }

    private fun sendLocation() {
        val fuzzed = LocationPrivacy.fuzzLocation(
            31.230416, 121.473701,
            prefManager.isPrivacyMode, false
        )
        wsManager.sendMessage(MessageType.LOCATION_UPDATE, LocationUpdateData(
            fuzzed.lat, fuzzed.lng,
            if (fuzzed.isFuzzed) 1100f else 5f,
            prefManager.isPrivacyMode, false
        ))
        Toast.makeText(requireContext(), "📍 位置已共享", Toast.LENGTH_SHORT).show()
    }

    private fun startCall(callType: String) {
        if (!wsManager.isConnected()) {
            Toast.makeText(requireContext(), "未连接服务器", Toast.LENGTH_SHORT).show()
            return
        }
        wsManager.sendMessage(MessageType.CALL_REQUEST, CallRequestData(callType, prefManager.role))
        startActivity(Intent(requireContext(), VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(VideoCallActivity.EXTRA_ROOM, prefManager.room)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        wsManager.removeMessageListener(msgListener)
        _binding = null
    }
}
