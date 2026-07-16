// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/VideoCallActivity.kt
// 用途: AI 影伴系统 v1.0 — 独立音视频通话界面
// 特性:
//   - ImageView MJPEG 视频流（替代 WebView）
//   - 通话时长计时
//   - 静音/挂断/切换摄像头
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
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityVideoCallBinding
import com.yinban.ai.hardware.MjpegStreamer
import com.yinban.ai.network.*
import com.yinban.ai.storage.PreferenceManager

class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoCall"
        const val EXTRA_CALL_TYPE = "call_type"   // "video" | "audio"
        const val EXTRA_ROOM = "room"
        const val EXTRA_STREAM_URL = "stream_url" // ★ 摄像头 MJPEG 地址
    }

    private lateinit var binding: ActivityVideoCallBinding
    private var callType: String = "video"
    private var room: String = ""
    private var streamUrl: String = ""

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "video"
        room = intent.getStringExtra(EXTRA_ROOM) ?: ""
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""

        // 如果没传 URL，尝试从 WebSocket 服务器 IP 猜测（兼容旧版）
        if (streamUrl.isBlank()) {
            val prefManager = PreferenceManager.getInstance(this)
            val serverIp = prefManager.serverIp.ifBlank { "127.0.0.1" }
            streamUrl = "http://$serverIp:5000/video_feed"
        }

        initViews()
        startCallTimer()
        Log.i(TAG, "[Call] 通话开始: type=$callType, room=$room, url=$streamUrl")
    }

    private fun initViews() {
        // 语音通话模式隐藏视频相关
        if (callType == "audio") {
            binding.ivVideoStream.visibility = View.GONE
            binding.surfaceLocal.visibility = View.GONE
            binding.tvCallPlaceholder.text = "🎙️ 语音通话中..."
        }

        binding.tvCallStatus.text = if (callType == "video") "📹 视频通话中" else "🎙️ 语音通话中"

        // ★ 视频模式：启动 MJPEG 流到 ImageView
        if (callType == "video" && streamUrl.isNotBlank()) {
            startMjpegStream()
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

        // 切换摄像头
        binding.fabSwitchCamera.setOnClickListener {
            Log.i(TAG, "[Call] 切换摄像头")
            Toast.makeText(this, "切换摄像头", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMjpegStream() {
        binding.ivVideoStream.visibility = View.VISIBLE
        binding.tvCallPlaceholder.visibility = View.VISIBLE
        binding.tvCallPlaceholder.text = "📹 正在连接摄像头..."

        mjpegStreamer.start(streamUrl, object : MjpegStreamer.Callback {
            override fun onConnected() {
                Log.i(TAG, "[MJPEG] 已连接")
                runOnUiThread {
                    binding.tvCallPlaceholder.visibility = View.GONE
                    binding.tvCallStatus.text = "📹 视频通话中"
                }
            }

            override fun onFrame(bitmap: Bitmap) {
                frameCount++
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "[MJPEG] 已接收 $frameCount 帧")
                }
                binding.ivVideoStream.setImageBitmap(bitmap)
            }

            override fun onError(message: String) {
                Log.e(TAG, "[MJPEG] 错误: $message")
                runOnUiThread {
                    binding.tvCallPlaceholder.text = "📺\n视频流连接失败\n$message"
                    binding.tvCallPlaceholder.visibility = View.VISIBLE
                    binding.tvCallStatus.text = "视频流连接失败"
                }
            }

            override fun onDisconnected() {
                Log.i(TAG, "[MJPEG] 已断开")
                runOnUiThread {
                    binding.tvCallPlaceholder.text = "📺\n视频流已断开"
                    binding.tvCallPlaceholder.visibility = View.VISIBLE
                }
            }
        })
    }

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
        Log.i(TAG, "[Call] 通话结束 — 时长: ${formatDuration(secondsElapsed)}, 帧数: $frameCount")
    }
}
