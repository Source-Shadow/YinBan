// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/home/AIChatFragment.kt
// 用途: 小影火 Tab — AI 聊天伴侣
// 能力: DeepSeek V4 API 接入 / 状态分析 / 社交辅助 / 日常聊天
// 说明: 半 Agent 对话，结合上下文主动推送提醒和安抚
// ============================================================

package com.yinban.ai.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.yinban.ai.R
import com.yinban.ai.databinding.FragmentAiChatBinding
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType

class AIChatFragment : Fragment() {

    companion object {
        private const val TAG = "AIChatFragment"
        // DeepSeek API 配置（需替换为实际 API Key）
        private const val DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val DEEPSEEK_API_KEY = "" // TODO: 配置 API Key

        // 小影火系统提示词（.trimIndent() 是函数调用，不能用 const）
        private val SYSTEM_PROMPT = """
你叫"小影火"，是 AI 影伴系统的 AI 影子老师。你的职责是陪伴和辅助轻中度成年自闭症患者。

核心能力：
1. 分析患者当前状态（情绪、压力、环境）
2. 辅助社交互动（提供社交话术建议、情境判断）
3. 日常聊天陪伴（温暖、耐心、鼓励性语言）
4. 主动推送提醒和安抚

回复原则：
- 语气温暖、简洁、有耐心，像朋友一样
- 每次回复控制在 2-4 句话
- 如果察觉患者情绪低落或焦虑，主动安抚
- 适当使用表情符号增加亲和力
- 不要过度说教，保持轻松自然
        """.trimIndent()
    }

    private var _binding: FragmentAiChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val chatHistory = mutableListOf<Pair<String, String>>() // (role, content)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefManager = PreferenceManager.getInstance(requireContext())
        wsManager = WebSocketManager.getInstance()

        initViews()
    }

    private fun initViews() {
        // 发送按钮
        binding.btnAiSend.setOnClickListener { sendMessage() }

        // 快捷Chip: 分析状态
        binding.chipAiStatus.setOnClickListener {
            sendPresetMessage("请帮我分析一下我现在的状态，我感觉有点紧张。")
        }

        // 快捷Chip: 社交练习
        binding.chipAiSocial.setOnClickListener {
            sendPresetMessage("我要去商店买东西，能给我一些社交对话的建议吗？")
        }

        // 快捷Chip: 情绪安抚
        binding.chipAiCalm.setOnClickListener {
            sendPresetMessage("我现在感觉不太好，能陪我聊聊吗？")
        }
    }

    private fun sendPresetMessage(text: String) {
        binding.etAiInput.setText(text)
        sendMessage()
    }

    private fun sendMessage() {
        val text = binding.etAiInput.text.toString().trim()
        if (text.isBlank()) return

        addMessageBubble(text, true)
        binding.etAiInput.text?.clear()

        chatHistory.add("user" to text)

        if (DEEPSEEK_API_KEY.isBlank()) {
            // 无 API Key 时使用本地模拟回复
            addMessageBubble(generateLocalResponse(text), false)
            return
        }

        // 调用 DeepSeek API
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { callDeepSeekAPI(text) }
                addMessageBubble(response, false)
                chatHistory.add("assistant" to response)
            } catch (e: Exception) {
                addMessageBubble("抱歉，我暂时无法回复。请检查网络连接后重试。", false)
            }
        }
    }

    private fun addMessageBubble(content: String, isUser: Boolean) {
        val cardBg = if (isUser) R.color.primary_light else android.R.color.white
        val textColor = if (isUser) R.color.text_on_dark else R.color.text_primary
        val align = if (isUser) "→ " else "🔥 "

        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(if (isUser) 48 else 8, 4, if (isUser) 8 else 48, 4)
            }
            radius = 24f
            cardElevation = 1f
            setCardBackgroundColor(ContextCompat.getColor(requireContext(), cardBg))
            setContentPadding(20, 12, 20, 12)
        }

        val tv = TextView(requireContext()).apply {
            text = "$align$content"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), textColor))
            setLineSpacing(4f, 1f)
        }

        card.addView(tv)
        binding.containerAiMessages.addView(card)
        binding.scrollAiChat.post {
            binding.scrollAiChat.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * 本地模拟回复（无 API Key 时的后备方案）。
     * 实际接入 DeepSeek API 后替换为 callDeepSeekAPI()。
     */
    private fun generateLocalResponse(input: String): String {
        return when {
            input.contains("紧张") || input.contains("焦虑") || input.contains("不好") ->
                "我理解你的感受 🤗 深呼吸一下，慢慢来。你现在的环境安全吗？如果需要的话，可以找个安静的地方坐一会儿。我一直在你身边。"

            input.contains("社交") || input.contains("商店") || input.contains("买东西") ->
                "去商店是个好主意！结账时可以试试这样说：\n1. 先微笑说\"你好\"\n2. 把商品放在柜台上\n3. 如果紧张，可以低头看手机假装发消息\n记住，慢慢来不着急，你是很棒的 💪"

            input.contains("状态") || input.contains("分析") ->
                "让我看看你的状态：\n📍 当前位置安全\n📱 设备连接正常\n🎧 耳机已就绪\n整体看起来不错！有什么让你烦恼的吗？"

            input.contains("谢谢") || input.contains("感谢") ->
                "不客气！能帮到你我很开心 🥰 需要的时候随时找我。"

            else ->
                "我听到了 😊 能再具体说说吗？无论是想聊天、需要建议、还是只是想有人说说话，我都在这里陪着你。"
        }
    }

    /**
     * 调用 DeepSeek V4 API。
     * 将聊天历史发送到 API，获取 AI 回复。
     */
    private fun callDeepSeekAPI(userMessage: String): String {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // 构建 messages 数组
        val messagesList = mutableListOf<Map<String, String>>()
        messagesList.add(mapOf("role" to "system", "content" to SYSTEM_PROMPT))

        // 只保留最近10轮对话（避免上下文过长）
        val recent = chatHistory.takeLast(10)
        for ((role, content) in recent) {
            val apiRole = if (role == "user") "user" else "assistant"
            messagesList.add(mapOf("role" to apiRole, "content" to content))
        }

        val requestBody = mapOf(
            "model" to "deepseek-chat",
            "messages" to messagesList,
            "temperature" to 0.7,
            "max_tokens" to 500
        )

        val jsonBody = gson.toJson(requestBody)
        val request = okhttp3.Request.Builder()
            .url(DEEPSEEK_API_URL)
            .addHeader("Authorization", "Bearer $DEEPSEEK_API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), jsonBody))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) throw Exception("API error: ${response.code}")

        // 解析响应
        val json = gson.fromJson(responseBody, Map::class.java)
        val choices = json["choices"] as? List<*> ?: throw Exception("No choices")
        val choice = choices.firstOrNull() as? Map<*, *> ?: throw Exception("Empty choice")
        val message = choice["message"] as? Map<*, *> ?: throw Exception("No message")
        return message["content"] as? String ?: "（小影火正在思考...）"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
