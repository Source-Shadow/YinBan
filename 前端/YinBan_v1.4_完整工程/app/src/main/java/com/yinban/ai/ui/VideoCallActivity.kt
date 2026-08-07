// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/VideoCallActivity.kt
// 用途: AI 影伴系统 v1.0 — 独立音视频通话界面
// 特性:
//   - ImageView MJPEG 视频流（替代 WebView）
//   - 通话时长计时
//   - 静音/挂断/切换摄像头
//   - ★ v2: 双摄像头来源自动兜底（IP Webcam → ESP32 鱼眼）
// ============================================================

package com.yinban.ai.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yinban.ai.config.AppConfig
import com.yinban.ai.config.CameraSource
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityVideoCallBinding
import com.yinban.ai.hardware.MjpegStreamer
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoCall"
        const val EXTRA_CALL_TYPE = "call_type"        // "video" | "audio"
        const val EXTRA_ROOM = "room"
        const val EXTRA_STREAM_URL = "stream_url"       // 摄像头 MJPEG 地址（旧兼容）
        const val EXTRA_PRIMARY_SOURCE = "primary_source" // ★ 首选摄像头来源 key
    }

    private lateinit var binding: ActivityVideoCallBinding
    private var callType: String = "video"
    private var room: String = ""
    private var streamUrl: String = ""

    // ★ 双摄像头来源
    private var currentSource: CameraSource = CameraSource.DEFAULT
    private var fallbackSource: CameraSource = CameraSource.ESP32_FISHEYE
    private var primarySource: CameraSource = CameraSource.DEFAULT
    private var isUsingFallback: Boolean = false

    private var secondsElapsed = 0
    private var isMuted = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            secondsElapsed++
            binding.tvCallDuration.text = formatDuration(secondsElapsed)
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ★ MJPEG 流播放器（替代 WebView）
    private val mjpegStreamer = MjpegStreamer()

    private var frameCount = 0

    // ★ 连通性探测专用短超时 client
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "video"
        room = intent.getStringExtra(EXTRA_ROOM) ?: ""
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""

        val prefManager = PreferenceManager.getInstance(this)
        val serverIp = prefManager.serverIp.ifBlank { AppConfig.defaultServerIp() }

        // ★ 解析首选摄像头来源
        primarySource = intent.getStringExtra(EXTRA_PRIMARY_SOURCE)
            ?.let { CameraSource.fromKey(it) }
            ?: CameraSource.fromKey(prefManager.primaryCamera)
        fallbackSource = CameraSource.fallbackOf(primarySource)

        // 如果没传 URL，根据摄像头来源构建
        if (streamUrl.isBlank()) {
            streamUrl = prefManager.cameraUrl.ifBlank {
                primarySource.buildUrl(serverIp)
            }
        }

        initViews()
        startCallTimer()
        Log.i(TAG, "[Call] 通话开始: type=$callType, room=$room, " +
                "primary=$primarySource, url=$streamUrl")
    }

    private fun initViews() {
        // 语音通话模式隐藏视频相关
        if (callType == "audio") {
            binding.ivVideoStream.visibility = View.GONE
            binding.surfaceLocal.visibility = View.GONE
            binding.tvCallPlaceholder.text = "🎙️ 语音通话中..."
        }

        updateSourceIndicator()

        // ★ 视频模式：启动 MJPEG 流（带兜底）
        if (callType == "video" && streamUrl.isNotBlank()) {
            startMjpegWithFallback()
        }

        // 挂断
        binding.fabHangup.setOnClickListener {
            Log.i(TAG, "[Call] 挂断通话")
            mjpegStreamer.stop()
            Toast.makeText(this, "通话已结束", Toast.LENGTH_SHORT).show()
            finish()
        }

        // 静音
        binding.fabMute.setOnClickListener {
            isMuted = !isMuted
            binding.fabMute.apply {
                backgroundTintList =
                    if (isMuted) android.content.res.ColorStateList.valueOf(getColor(R.color.error))
                    else android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary))
            }
            Toast.makeText(this, if (isMuted) "已静音" else "已取消静音", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "[Call] 静音: $isMuted")
        }

        // ★ 切换摄像头：在两路来源之间手动切换
        binding.fabSwitchCamera.setOnClickListener {
            switchCameraSource()
        }
    }

    // ═══════════════════════════════════════════
    // ★ 双源兜底逻辑
    // ═══════════════════════════════════════════

    /**
     * 启动 MJPEG 流，优先尝试主摄像头，失败则自动切换到兜底摄像头。
     */
    private fun startMjpegWithFallback() {
        val prefManager = PreferenceManager.getInstance(this)
        val ip = prefManager.serverIp.ifBlank { AppConfig.defaultServerIp() }

        val primaryUrl = if (prefManager.cameraUrl.isNotBlank()) {
            // 用户自定义了摄像头地址，直接使用
            prefManager.cameraUrl
        } else {
            primarySource.buildUrl(ip)
        }

        val fallbackUrl = fallbackSource.buildUrl(ip)

        binding.tvCallPlaceholder.visibility = View.VISIBLE
        binding.tvCallPlaceholder.text = "📹 正在连接 ${primarySource.displayName}..."

        // 先探测主路
        if (probeUrl(primaryUrl)) {
            currentSource = primarySource
            isUsingFallback = false
            startMjpegStream(primaryUrl)
            Log.i(TAG, "[Fallback] 主路可用 → ${primarySource.displayName}: $primaryUrl")
        } else {
            Log.w(TAG, "[Fallback] 主路不可达: $primaryUrl，尝试兜底...")
            binding.tvCallPlaceholder.text = "📹 ${primarySource.displayName} 不可达，尝试 ${fallbackSource.displayName}..."

            // 尝试兜底路
            if (probeUrl(fallbackUrl)) {
                currentSource = fallbackSource
                isUsingFallback = true
                startMjpegStream(fallbackUrl)
                Toast.makeText(this,
                    "已自动切换到 ${fallbackSource.displayName}", Toast.LENGTH_LONG).show()
                Log.i(TAG, "[Fallback] 兜底可用 → ${fallbackSource.displayName}: $fallbackUrl")
            } else {
                // 两路都不可达，仍然尝试连接主路（让用户看到具体错误）
                Log.e(TAG, "[Fallback] 两路均不可达，回退到主路直连")
                currentSource = primarySource
                startMjpegStream(primaryUrl)
            }
        }
    }

    /**
     * 手动切换摄像头来源。
     * 断开当前流，切换到另一路重新连接。
     */
    private fun switchCameraSource() {
        val prefManager = PreferenceManager.getInstance(this)
        val ip = prefManager.serverIp.ifBlank { AppConfig.defaultServerIp() }

        // 切换到另一个来源
        val newSource = CameraSource.fallbackOf(currentSource)
        val newUrl = newSource.buildUrl(ip)

        Log.i(TAG, "[Switch] 手动切换: ${currentSource.displayName} → ${newSource.displayName}")

        mjpegStreamer.stop()
        currentSource = newSource
        isUsingFallback = !isUsingFallback
        frameCount = 0

        binding.tvCallPlaceholder.visibility = View.VISIBLE
        binding.tvCallPlaceholder.text = "📹 正在连接 ${newSource.displayName}..."

        startMjpegStream(newUrl)
        updateSourceIndicator()
        Toast.makeText(this, "已切换到 ${newSource.displayName}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新摄像头来源指示标签。
     */
    private fun updateSourceIndicator() {
        val label = if (isUsingFallback) {
            "📷 ${currentSource.displayName}（兜底）"
        } else {
            "📷 ${currentSource.displayName}"
        }
        binding.tvCallStatus.text = if (callType == "video") "📹 视频通话中" else "🎙️ 语音通话中"
        binding.tvCameraSource.text = label
    }

    // ═══════════════════════════════════════════
    // ★ 连通性探测
    // ═══════════════════════════════════════════

    /**
     * 用短超时 HTTP HEAD 请求探测 MJPEG URL 是否可达。
     * @param url 待探测的推流地址
     * @param timeoutMs 超时毫秒
     * @return true 表示目标可达并返回了成功状态码
     */
    private fun probeUrl(url: String, timeoutMs: Long = 3000): Boolean {
        val client = if (timeoutMs == 3000L) probeClient else {
            OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.d(TAG, "[Probe] $url 不可达: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════
    // MJPEG 流播放
    // ═══════════════════════════════════════════

    private fun startMjpegStream(url: String) {
        binding.ivVideoStream.visibility = View.VISIBLE
        binding.tvCallPlaceholder.visibility = View.VISIBLE

        mjpegStreamer.start(url, object : MjpegStreamer.Callback {
            override fun onConnected() {
                Log.i(TAG, "[MJPEG] 已连接 → $currentSource ($url)")
                runOnUiThread {
                    binding.tvCallPlaceholder.visibility = View.GONE
                    updateSourceIndicator()
                }
            }

            override fun onFrame(bitmap: Bitmap) {
                frameCount++
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "[MJPEG] 已接收 $frameCount 帧 (${currentSource.displayName})")
                }
                binding.ivVideoStream.setImageBitmap(bitmap)
            }

            override fun onError(message: String) {
                Log.e(TAG, "[MJPEG] 错误 ($currentSource): $message")
                runOnUiThread {
                    // ★ 如果当前是主路出错且还没尝试过兜底，自动切换
                    if (!isUsingFallback && currentSource == primarySource) {
                        Log.i(TAG, "[Fallback] 主路出错，自动尝试兜底...")
                        switchCameraSource()
                        return@runOnUiThread
                    }

                    binding.tvCallPlaceholder.text = "📺\n视频流连接失败\n$message"
                    binding.tvCallPlaceholder.visibility = View.VISIBLE
                    binding.tvCallStatus.text = "视频流连接失败"
                }
            }

            override fun onDisconnected() {
                Log.i(TAG, "[MJPEG] 已断开 ($currentSource)")
                runOnUiThread {
                    binding.tvCallPlaceholder.text = "📺\n视频流已断开"
                    binding.tvCallPlaceholder.visibility = View.VISIBLE
                }
            }
        })
    }

    // ═══════════════════════════════════════════
    // 通话计时
    // ═══════════════════════════════════════════

    private fun startCallTimer() {
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun formatDuration(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        mjpegStreamer.stop()
        Log.i(TAG, "[Call] 通话结束 — 时长: ${formatDuration(secondsElapsed)}, " +
                "帧数: $frameCount, 来源: ${currentSource.displayName}")
    }
}
