// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/ChatActivity.kt
// v1.1 — RecyclerView + 离线状态 + 小影火 / 人工双模式
// ============================================================

package com.yinban.ai.ui

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityChatBinding
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_ROLE = "role"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_MODE = "mode"          // "ai" | "manual"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private val wsManager = WebSocketManager.getInstance()
    private val gson = Gson()
    private var myRole = "patient"
    private var chatMode = "manual"
    private var isVoiceRecording = false
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        myRole = intent.getStringExtra(EXTRA_ROLE) ?: "patient"
        chatMode = intent.getStringExtra(EXTRA_MODE) ?: "manual"

        // 从本地存储读取用户 API Key（优先用户自己的，否则用内置）
        val prefs = PreferenceManager.getInstance(this)
        val savedKey = prefs.deepseekApiKey
        if (savedKey.isNotBlank()) {
            DeepSeekClient.apiKey = savedKey
        }

        // RecyclerView setup
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = ChatMessageAdapter(this)
        binding.rvChatMessages.apply {
            layoutManager = this@ChatActivity.layoutManager
            adapter = this@ChatActivity.adapter
        }

        when (chatMode) {
            "ai" -> setupAiMode()
            else -> setupManualMode()
        }

        binding.btnChatBack.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        binding.btnSendMessage.setOnClickListener { sendText() }
        binding.btnVoiceInput.setOnTouchListener { _, e -> handleVoice(e) }

        // 初始离线状态
        updateOfflineState(wsManager.isConnected())
        wsManager.addMessageListener(msgListener)
    }

    // ═══════════════════════════════════════════
    // 模式设置
    // ═══════════════════════════════════════════

    private fun setupManualMode() {
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME)
            ?: if (myRole == "patient") "监护人" else "患者"
        binding.tvChatPeerName.text = peerName
    }

    private fun setupAiMode() {
        binding.tvChatPeerName.text = "🤖 在线"
        binding.tvChatPeerName.setTextColor(ContextCompat.getColor(this, R.color.status_online_text))
        adapter.addMessage(ChatMessage.AiMessage("嗨嗨～我是小影火 🔥 你的温暖小伴。想说什么都可以跟我讲哦，我一直在这儿呢～"))
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
            if (isTtsReady) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(0.9f)
            }
        }
    }

    // ═══════════════════════════════════════════
    // 离线状态
    // ═══════════════════════════════════════════

    private fun updateOfflineState(connected: Boolean) {
        if (chatMode == "ai") {
            // AI 模式：断网也能用（直连 DeepSeek HTTP）
            binding.etChatInput.isEnabled = true
            binding.btnSendMessage.isEnabled = true
            binding.btnVoiceInput.isEnabled = true
            binding.tvOfflineBanner.visibility = View.GONE
            return
        }
        // 人工模式：依赖 WebSocket
        binding.etChatInput.isEnabled = connected
        binding.btnSendMessage.isEnabled = connected
        binding.btnVoiceInput.isEnabled = connected
        binding.tvOfflineBanner.visibility = if (connected) View.GONE else View.VISIBLE
        binding.etChatInput.hint = if (connected) "输入消息..." else "离线模式，暂不可用"
        if (!connected) {
            binding.root.announceForAccessibility("连接已断开，对话功能暂不可用")
        }
    }

    // ═══════════════════════════════════════════
    // WebSocket 消息处理
    // ═══════════════════════════════════════════

    private val msgListener = object : WebSocketManager.MessageListener {
        override fun onMessage(message: YinBanMessage) {
            when (message.type) {
                MessageType.MANUAL_MESSAGE -> handleManualMessage(message)
                MessageType.CHAT_AI_RESPONSE -> handleAiResponse(message)
                MessageType.AI_VOICE_COMMAND -> handleAiVoiceCommand(message)
                MessageType.DANGER_DETECTED -> handleDangerDetected(message)
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            runOnUiThread {
                binding.tvChatPeerName.text = if (chatMode == "ai") {
                    if (connected) "🤖 在线 · 小影火" else "🤖 小影火 · 直连"
                } else {
                    if (connected) peerNameBiz else "🔴 离线"
                }
                if (chatMode == "ai") {
                    binding.tvChatPeerName.setTextColor(
                        getColor(if (connected) R.color.status_online_text else R.color.primary))
                }
                updateOfflineState(connected)
            }
        }

        override fun onConnectionError(error: String) {
            runOnUiThread {
                Toast.makeText(this@ChatActivity, "连接错误: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val peerNameBiz: String
        get() = intent.getStringExtra(EXTRA_PEER_NAME) ?: "对方"

    // ═══════════════════════════════════════════
    // 消息处理
    // ═══════════════════════════════════════════

    private fun handleManualMessage(message: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(message.data), ManualMessageData::class.java)
        if (data.fromRole != myRole) {
            runOnUiThread {
                adapter.addMessage(
                    if (chatMode == "ai") ChatMessage.AiMessage(data.content)
                    else ChatMessage.PeerMessage(data.content))
                scrollToBottom()
            }
        }
    }

    private fun handleAiResponse(message: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(message.data), ChatAiResponseData::class.java)
        runOnUiThread {
            adapter.removeThinkingIndicator()
            adapter.addMessage(ChatMessage.AiMessage(data.reply))
            scrollToBottom()
            if (isTtsReady && tts != null) {
                tts?.speak(data.reply, TextToSpeech.QUEUE_FLUSH, null, "ai_reply_${System.currentTimeMillis()}")
            }
            if (data.isDanger) {
                Toast.makeText(this, "⚠️ 小影火检测到潜在风险", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleAiVoiceCommand(message: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(message.data), AiVoiceCommandData::class.java)
        runOnUiThread {
            adapter.addMessage(ChatMessage.AiMessage("🔔 ${data.voiceText}"))
            scrollToBottom()
            if (isTtsReady && tts != null) {
                tts?.speak(data.voiceText, TextToSpeech.QUEUE_FLUSH, null, "ai_voice_${System.currentTimeMillis()}")
            }
            if (data.priority >= 1) {
                Toast.makeText(this, "⚠️ ${data.voiceText}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleDangerDetected(message: YinBanMessage) {
        val data = gson.fromJson(gson.toJson(message.data), DangerDetectedData::class.java)
        runOnUiThread {
            adapter.addMessage(ChatMessage.AiMessage("⚠️ ${data.message}"))
            scrollToBottom()
            if (isTtsReady && tts != null) {
                tts?.speak(data.message, TextToSpeech.QUEUE_ADD, null, "danger_${System.currentTimeMillis()}")
            }
            Toast.makeText(this, "🚨 小影火检测到异常！", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════
    // 发送
    // ═══════════════════════════════════════════

    private fun sendText() {
        val text = binding.etChatInput.text.toString().trim()
        if (text.isBlank()) return

        when (chatMode) {
            "ai" -> sendAiMessage(text)
            else -> sendManualMessage(text)
        }
    }

    private fun sendAiMessage(text: String) {
        adapter.addMessage(ChatMessage.SelfMessage(text))
        scrollToBottom()
        adapter.addMessage(ChatMessage.ThinkingIndicator())
        scrollToBottom()
        binding.etChatInput.text.clear()

        val context = mapOf(
            "time" to java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(java.util.Date()))

        if (wsManager.isConnected()) {
            // 正常路径：通过 WebSocket 后端调用 DeepSeek
            wsManager.sendMessage(MessageType.CHAT_AI_REQUEST,
                ChatAiRequestData(text = text, context = context))
        } else {
            // ★ 兜底路径：直连 DeepSeek API（无需启动后端也能用AI）
            DeepSeekClient.chat(text, context) { result ->
                if (isFinishing || isDestroyed) return@chat
                adapter.removeThinkingIndicator()
                adapter.addMessage(ChatMessage.AiMessage(result.reply))
                scrollToBottom()
                if (isTtsReady && tts != null) {
                    tts?.speak(result.reply, TextToSpeech.QUEUE_FLUSH, null, "ai_fb_${System.currentTimeMillis()}")
                }
                if (result.isDanger) {
                    Toast.makeText(this@ChatActivity, "⚠️ 小影火检测到潜在风险", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendManualMessage(text: String) {
        wsManager.sendMessage(MessageType.MANUAL_MESSAGE, ManualMessageData(text, myRole))
        adapter.addMessage(ChatMessage.SelfMessage(text))
        scrollToBottom()
        binding.etChatInput.text.clear()
    }

    // ═══════════════════════════════════════════
    // 语音输入（占位 → 后续接入 SpeechRecognizer）
    // ═══════════════════════════════════════════

    private fun handleVoice(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isVoiceRecording = true
                binding.btnVoiceInput.text = "🔴"
                Toast.makeText(this, "🎤 录音中...", Toast.LENGTH_SHORT).show()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isVoiceRecording) {
                    isVoiceRecording = false
                    binding.btnVoiceInput.text = "🎤"
                    if (chatMode == "ai") {
                        binding.etChatInput.setText("你好")
                        sendText()
                    } else {
                        val msg = "[语音消息]"
                        wsManager.sendMessage(MessageType.MANUAL_MESSAGE, ManualMessageData(msg, myRole))
                        adapter.addMessage(ChatMessage.SelfMessage(msg))
                        scrollToBottom()
                    }
                }
            }
        }
        return true
    }

    private fun scrollToBottom() {
        binding.rvChatMessages.post {
            binding.rvChatMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    // ═══════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager.removeMessageListener(msgListener)
        tts?.stop()
        tts?.shutdown()
    }
}
