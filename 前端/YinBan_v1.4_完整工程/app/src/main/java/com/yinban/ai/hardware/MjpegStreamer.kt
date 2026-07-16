// ============================================================
// 路径: app/src/main/java/com/yinban/ai/hardware/MjpegStreamer.kt
// 用途: 基于 ImageView 的 MJPEG 视频流播放器（替代 WebView）
// 特性:
//   - OkHttp 拉取 multipart/x-mixed-replace 流
//   - 逐帧解析 JPEG → ImageView 显示
//   - 主线程回调：onFrame、onError、onConnected
// ============================================================

package com.yinban.ai.hardware

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MjpegStreamer {

    companion object {
        private const val TAG = "MjpegStreamer"
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val READ_TIMEOUT_SEC = 10L
    }

    interface Callback {
        fun onConnected()
        fun onFrame(bitmap: Bitmap)
        fun onError(message: String)
        fun onDisconnected()
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var callback: Callback? = null
    private var url: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    fun start(streamUrl: String, cb: Callback) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "已在运行中，先停止再重启")
            stop()
            running.set(true)
        }
        url = streamUrl
        callback = cb
        thread = Thread(::streamLoop, "MjpegStreamer").apply { start() }
        Log.i(TAG, "启动 MJPEG 流: $url")
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        callback = null
        Log.i(TAG, "已停止 MJPEG 流")
    }

    private fun streamLoop() {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                postError("HTTP ${response.code}: ${response.message}")
                return
            }

            val body = response.body ?: run {
                postError("响应体为空")
                return
            }

            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("multipart/x-mixed-replace")) {
                // 不是 MJPEG 流，尝试当作单张 JPEG 加载
                val bytes = body.bytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    postConnected()
                    postFrame(bitmap)
                    Log.w(TAG, "非 MJPEG 流，已加载为静态图片")
                } else {
                    postError("非 MJPEG 流且无法解码图片: $contentType")
                }
                return
            }

            // 提取 boundary
            val boundary = extractBoundary(contentType)
            if (boundary.isNullOrBlank()) {
                postError("无法解析 MJPEG boundary: $contentType")
                return
            }

            postConnected()
            Log.i(TAG, "MJPEG 连接成功, boundary=$boundary")

            // 逐帧读取
            val input = BufferedInputStream(body.byteStream())
            val boundaryBytes = "--$boundary".toByteArray()
            val frameBuffer = java.io.ByteArrayOutputStream()

            var state = 0  // 0=寻找boundary, 1=读取header, 2=读取body
            var contentLength = -1
            val headerBuffer = StringBuilder()

            var b: Int = -1
            while (running.get() && input.read().also { b = it } != -1) {
                when (state) {
                    0 -> {
                        // 寻找 boundary 起始
                        frameBuffer.write(b)
                        val buf = frameBuffer.toByteArray()

                        // 扫描 buffer 中是否包含 boundary 标记
                        var found = false
                        val scanEnd = buf.size - boundaryBytes.size
                        if (scanEnd >= 0) {
                            for (i in 0..scanEnd) {
                                if (buf.sliceArray(i until i + boundaryBytes.size)
                                        .contentEquals(boundaryBytes)
                                ) {
                                    // 有效 boundary：位置 0（首个 boundary 无 \r\n 前缀）
                                    // 或前面有 \r\n（后续 boundary）
                                    if (i == 0 || (i >= 2 &&
                                                buf[i - 2] == '\r'.code.toByte() &&
                                                buf[i - 1] == '\n'.code.toByte())
                                    ) {
                                        found = true
                                        break
                                    }
                                }
                            }
                        }

                        if (found) {
                            state = 1
                            frameBuffer.reset()
                            headerBuffer.clear()
                        }
                        // 防止缓冲区无限增长
                        if (frameBuffer.size() > 4096) {
                            frameBuffer.reset()
                        }
                    }
                    1 -> {
                        // 读取 header 行
                        headerBuffer.append(b.toChar())
                        if (headerBuffer.endsWith("\r\n\r\n")) {
                            // header 结束，解析 Content-Length
                            contentLength = parseContentLength(headerBuffer.toString())
                            // Content-Length 已知或未知都进入状态 2
                            // contentLength <= 0 表示未知，在状态 2 靠 boundary 分隔
                            state = 2
                            frameBuffer.reset()
                            headerBuffer.clear()
                        }
                    }
                    2 -> {
                        // 读取 JPEG body
                        frameBuffer.write(b)

                        if (contentLength > 0) {
                            // 有 Content-Length：读取精确字节数
                            if (frameBuffer.size() >= contentLength) {
                                val jpegData = frameBuffer.toByteArray()
                                decodeAndPostFrame(jpegData)
                                frameBuffer.reset()
                                state = 0
                            }
                        } else {
                            // ★ 无 Content-Length（Flask 等）：扫描 boundary 作为帧结束标记
                            val buf = frameBuffer.toByteArray()
                            if (buf.size >= boundaryBytes.size + 2 &&
                                buf[buf.size - boundaryBytes.size - 2] == '\r'.code.toByte() &&
                                buf[buf.size - boundaryBytes.size - 1] == '\n'.code.toByte() &&
                                buf.sliceArray(buf.size - boundaryBytes.size until buf.size)
                                    .contentEquals(boundaryBytes)
                            ) {
                                // 提取 boundary 之前的 JPEG 数据（去掉末尾 \r\n--boundary）
                                val jpegLen = buf.size - boundaryBytes.size - 2
                                if (jpegLen > 0) {
                                    val jpegData = buf.sliceArray(0 until jpegLen)
                                    decodeAndPostFrame(jpegData)
                                }
                                frameBuffer.reset()
                                // 已读到 boundary，直接进入 header 读取
                                state = 1
                                headerBuffer.clear()
                            }
                        }
                    }
                }
            }

        } catch (e: java.net.SocketException) {
            if (running.get()) postError("连接中断: ${e.message}")
        } catch (e: java.io.IOException) {
            if (running.get()) postError("IO 错误: ${e.message}")
        } catch (e: Exception) {
            if (running.get()) postError("异常: ${e.message}")
        }

        if (running.get()) {
            postDisconnected()
        }
    }

    private fun decodeAndPostFrame(jpegData: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (bitmap != null) {
                postFrame(bitmap)
            }
        } catch (e: Exception) {
            Log.w(TAG, "帧解码失败: ${e.message}")
        }
    }

    private fun extractBoundary(contentType: String): String? {
        val regex = Regex("boundary=([^;]+)")
        val match = regex.find(contentType) ?: return null
        return match.groupValues[1].trim().removePrefix("\"").removeSuffix("\"")
    }

    private fun parseContentLength(header: String): Int {
        val regex = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val match = regex.find(header) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }

    private fun postConnected() {
        mainHandler.post { callback?.onConnected() }
    }

    private fun postFrame(bitmap: Bitmap) {
        mainHandler.post { callback?.onFrame(bitmap) }
    }

    private fun postError(message: String) {
        Log.e(TAG, "错误: $message")
        mainHandler.post { callback?.onError(message) }
    }

    private fun postDisconnected() {
        mainHandler.post { callback?.onDisconnected() }
    }
}
