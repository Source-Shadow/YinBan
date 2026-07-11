// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/VideoCallActivity.kt
// 用途: AI 影伴系统 v1.0 — 独立音视频通话界面
// 特性:
//   - SurfaceView 双画面 (远端+本地小窗)
//   - 通话时长计时
//   - 静音/挂断/切换摄像头
//   - WebRTC / 推流硬件预留接口
// ============================================================

package com.yinban.ai.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityVideoCallBinding
import com.yinban.ai.network.*

class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoCall"
        const val EXTRA_CALL_TYPE = "call_type"   // "video" | "audio"
        const val EXTRA_ROOM = "room"
    }

    private lateinit var binding: ActivityVideoCallBinding
    private var callType: String = "video"
    private var room: String = ""

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "video"
        room = intent.getStringExtra(EXTRA_ROOM) ?: ""

        initViews()
        startCallTimer()
        Log.i(TAG, "[Call] 通话开始: type=$callType, room=$room")
    }

    private fun initViews() {
        // 语音通话模式隐藏视频相关
        if (callType == "audio") {
            binding.surfaceRemote.visibility = View.GONE
            binding.surfaceLocal.visibility = View.GONE
            binding.tvCallPlaceholder.text = "🎙️ 语音通话中..."
        }

        binding.tvCallStatus.text = if (callType == "video") "📹 视频通话中" else "🎙️ 语音通话中"

        // 挂断
        binding.fabHangup.setOnClickListener {
            Log.i(TAG, "[Call] 挂断通话")
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
        Log.i(TAG, "[Call] 通话结束 — 时长: ${formatDuration(secondsElapsed)}")
    }
}
