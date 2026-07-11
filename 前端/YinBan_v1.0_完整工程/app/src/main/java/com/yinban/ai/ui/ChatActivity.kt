// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/ChatActivity.kt
// 用途: AI 影伴系统 — 独立聊天界面（患者/监护人共用）
// 输入: EXTRA_ROLE ("patient"/"guardian"), EXTRA_PEER_NAME
// ============================================================

package com.yinban.ai.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.yinban.ai.databinding.ActivityChatBinding
import com.yinban.ai.network.*
import com.yinban.ai.R

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_ROLE = "role"
        const val EXTRA_PEER_NAME = "peer_name"
    }

    private lateinit var binding: ActivityChatBinding
    private val wsManager = WebSocketManager.getInstance()
    private val gson = Gson()
    private var myRole = "patient"
    private var isVoiceRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        myRole = intent.getStringExtra(EXTRA_ROLE) ?: "patient"
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME)
            ?: if (myRole == "patient") "监护人" else "患者"
        binding.tvChatPeerName.text = peerName

        binding.btnChatBack.setOnClickListener { finish() }
        binding.btnSendMessage.setOnClickListener { sendText() }
        binding.btnVoiceInput.setOnTouchListener { _, e -> handleVoice(e) }

        wsManager.addMessageListener(msgListener)
    }

    private val msgListener = object : WebSocketManager.MessageListener {
        override fun onMessage(message: YinBanMessage) {
            if (message.type == MessageType.MANUAL_MESSAGE) {
                val data = gson.fromJson(gson.toJson(message.data), ManualMessageData::class.java)
                if (data.fromRole != myRole) {
                    runOnUiThread { addBubble(data.content, false) }
                }
            }
        }
        override fun onConnectionStateChanged(connected: Boolean) {}
        override fun onConnectionError(error: String) {}
    }

    private fun sendText() {
        val text = binding.etChatInput.text.toString().trim()
        if (text.isBlank()) return
        wsManager.sendMessage(MessageType.MANUAL_MESSAGE, ManualMessageData(text, myRole))
        addBubble(text, true)
        binding.etChatInput.text.clear()
    }

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
                    val msg = "[语音消息]"
                    wsManager.sendMessage(MessageType.MANUAL_MESSAGE, ManualMessageData(msg, myRole))
                    addBubble(msg, true)
                }
            }
        }
        return true
    }

    private fun addBubble(content: String, isSelf: Boolean) {
        val tv = TextView(this).apply {
            text = content; textSize = 14f
            setPadding(28, 14, 28, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isSelf) Gravity.END else Gravity.START
                setMargins(40, 4, 40, 4)
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
            setTextColor(if (isSelf) ContextCompat.getColor(context, R.color.text_on_dark)
            else ContextCompat.getColor(context, R.color.text_primary))
            background = ContextCompat.getDrawable(context,
                if (isSelf) R.drawable.bg_chat_bubble_self else R.drawable.bg_chat_bubble_peer)
        }
        binding.containerMessages.addView(tv)
        binding.scrollChat.post { binding.scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager.removeMessageListener(msgListener)
    }
}
