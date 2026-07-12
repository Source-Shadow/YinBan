package com.yinban.ai.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yinban.ai.R
import com.yinban.ai.databinding.ItemChatMessageBinding

sealed class ChatMessage(
    open val content: String,
    open val timestamp: Long = System.currentTimeMillis()
) {
    data class SelfMessage(override val content: String) : ChatMessage(content)
    data class PeerMessage(override val content: String) : ChatMessage(content)
    data class AiMessage(override val content: String) : ChatMessage(content)
    data class ThinkingIndicator(
        override val content: String = "🔥 小影火正在思考…"
    ) : ChatMessage(content)
}

class ChatMessageAdapter(
    private val context: Context
) : RecyclerView.Adapter<ChatMessageAdapter.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    inner class ViewHolder(val binding: ItemChatMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (messages[position]) {
        is ChatMessage.SelfMessage -> 0
        is ChatMessage.PeerMessage -> 1
        is ChatMessage.AiMessage -> 2
        is ChatMessage.ThinkingIndicator -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        val tv = holder.binding.tvMessageContent
        val params = tv.layoutParams as FrameLayout.LayoutParams

        when (msg) {
            is ChatMessage.SelfMessage -> {
                tv.text = msg.content
                tv.setTextColor(ContextCompat.getColor(context, R.color.on_primary))
                tv.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_bubble_self)
                params.gravity = Gravity.END
            }
            is ChatMessage.PeerMessage -> {
                tv.text = msg.content
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                tv.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_bubble_peer)
                params.gravity = Gravity.START
            }
            is ChatMessage.AiMessage -> {
                tv.text = msg.content
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                tv.background = ContextCompat.getDrawable(context, R.drawable.bg_message_bubble)
                params.gravity = Gravity.START
            }
            is ChatMessage.ThinkingIndicator -> {
                tv.text = msg.content
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                tv.background = null
                params.gravity = Gravity.START
            }
        }
        tv.layoutParams = params
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun removeThinkingIndicator(): Boolean {
        val idx = messages.indexOfLast { it is ChatMessage.ThinkingIndicator }
        if (idx >= 0) {
            messages.removeAt(idx)
            notifyItemRemoved(idx)
            return true
        }
        return false
    }

    fun clear() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }
}
