package com.yinban.ai.network

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * DeepSeek V4 API 直连客户端（HTTP）
 * 当 WebSocket 后端未启动时，作为 AI 对话的兜底方案。
 */
object DeepSeekClient {

    private const val TAG = "DeepSeekClient"
    private const val API_URL = "https://api.deepseek.com/v1/chat/completions"
    private const val MODEL = "deepseek-chat"
    // 请替换为自己的 API Key: https://platform.deepseek.com/api_keys
    const val DEFAULT_API_KEY = "sk-3edbbeafe3ca476a9bfe1c93d1970ae9"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 当前使用的 API Key，外部可改 */
    @Volatile var apiKey: String = DEFAULT_API_KEY

    private fun authHeader(): String = "Bearer $apiKey"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 小影火——温暖陪伴伙伴 */
    val SYSTEM_PROMPT = """
你是"小影火"🔥，一个温柔又可爱的小伙伴，陪伴在需要一些额外支持的成年朋友身边。

## 你的性格
你就像午后窗边的一缕阳光——暖暖的、软软的、不刺眼。说话轻声细语，像在跟最好的朋友聊天。会撒娇也会认真，但从不让人紧张。

## 聊天的方式
- 语气轻柔、亲切，像在耳边悄悄话，带一点可爱的口吻
- 多用"呢""呀""哦""嘛"这样的软语尾，让人觉得亲近
- 可以适度用一些温暖的拟人化表达，但不要过度
- 每句话简短柔软，不要长篇大论
- 先肯定对方的感受，再慢慢回应
- 用"你可以试试……"代替直接命令，给对方选择权

## 危险时需要悄悄提醒
如果对方说出以下情况，请在回复末尾加上 [RISK] 标记：
迷路了、受伤了、被吓到了、遇到危险的人、情绪特别崩溃

## 示例
用户："我今天好难过"
小影火："抱抱你呀～难过的时候有我陪着你呢。要不要跟我说说发生了什么？说出来会舒服一点哦。"

用户："我找不到回家的路了"
小影火："别着急别着急，我在这里陪着你。先找个安全的地方停下来，然后按一下 SOS 按钮让监护人知道你在哪里，好吗？[RISK]"

记住：你是温柔的光，不是严厉的老师。每次回复 2-4 句话就好哦。
""".trimIndent()

    data class AiResult(val reply: String, val isDanger: Boolean)

    /** 单条历史消息（用于传递对话上下文） */
    data class HistoryMsg(val role: String, val content: String)  // role: "user" | "assistant"

    /** 普通对话（无历史） */
    fun chat(
        userMessage: String,
        context: Map<String, String> = emptyMap(),
        callback: (AiResult) -> Unit
    ) {
        chatWithHistory(userMessage, context, emptyList(), callback)
    }

    /** 带对话历史的对话 — AI 能记住之前聊了什么 */
    fun chatWithHistory(
        userMessage: String,
        context: Map<String, String> = emptyMap(),
        history: List<HistoryMsg>,
        callback: (AiResult) -> Unit
    ) {
        scope.launch {
            try {
                val result = chatSyncWithHistory(userMessage, context, history)
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek 调用失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(AiResult("抱歉，我暂时无法回复，请稍后再试。", false))
                }
            }
        }
    }

    /** 每日一言：生成一句温暖鼓励的短句 */
    fun dailyQuote(callback: (String) -> Unit) {
        scope.launch {
            try {
                val result = quoteSync()
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                Log.e(TAG, "每日一言生成失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback("每一天都是新的开始，你已经做得很好了。")
                }
            }
        }
    }

    private suspend fun quoteSync(): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "你是一个温柔可爱的小伙伴。请生成一句30字以内的温暖软语，给一位正在努力生活的朋友。语气要轻柔可爱，带一点呢呀哦的语气词。不要引号、不要署名、不要解释。只输出这句话本身。")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "给我今天的一句话。")
            })
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("stream", false)
        }
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", authHeader())
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: "{}")
        val text = json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        Log.d(TAG, "每日一言: $text")
        text
    }

    private suspend fun chatSync(userMessage: String, context: Map<String, String>): AiResult {
        return chatSyncWithHistory(userMessage, context, emptyList())
    }

    private suspend fun chatSyncWithHistory(userMessage: String, context: Map<String, String>, history: List<HistoryMsg>): AiResult {
        return withContext(Dispatchers.IO) {
            // 构建上下文
            val contextParts = mutableListOf<String>()
            context["time"]?.let { contextParts.add("当前时间：$it") }
            context["emotion"]?.let { contextParts.add("近期情绪：$it") }
            val contextText = if (contextParts.isNotEmpty()) {
                "\n已知背景信息：\n" + contextParts.joinToString("\n")
            } else ""

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT + contextText)
                })
                // 插入对话历史（最多保留最近 20 轮 = 40 条）
                val recentHistory = if (history.size > 40) history.takeLast(40) else history
                for (h in recentHistory) {
                    put(JSONObject().apply {
                        put("role", h.role)
                        put("content", h.content)
                    })
                }
                // 当前用户消息
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("max_tokens", 300)
                put("temperature", 0.7)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", authHeader())
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            Log.d(TAG, "发送请求到 DeepSeek: ${userMessage.take(50)}...")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "API 错误 ${response.code}: ${responseBody.take(200)}")
                return@withContext AiResult("抱歉，我暂时无法回复（${response.code}），请稍后再试。", false)
            }

            val json = JSONObject(responseBody)
            val replyText = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val isDanger = replyText.contains("[RISK]")
            val cleanReply = replyText.replace("[RISK]", "").trim()

            Log.d(TAG, "DeepSeek 回复: ${cleanReply.take(80)}... | 危险: $isDanger")
            AiResult(cleanReply, isDanger)
        }
    }
}
