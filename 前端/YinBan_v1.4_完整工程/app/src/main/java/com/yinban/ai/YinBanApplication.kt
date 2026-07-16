// ============================================================
// 路径: app/src/main/java/com/yinban/ai/YinBanApplication.kt
// 用途: AI 影伴系统 v1.0 — Application 入口
// ============================================================

package com.yinban.ai

import android.app.Application
import android.util.Log
import com.yinban.ai.hardware.HardwareStreamManager
import com.yinban.ai.network.WebSocketManager
import com.yinban.ai.storage.PreferenceManager

class YinBanApplication : Application() {

    companion object {
        private const val TAG = "YinBanApp"

        lateinit var instance: YinBanApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "🚀 AI 影伴系统 v1.0 启动")

        // 预初始化核心单例
        PreferenceManager.getInstance(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        // 应用退出时断开 WebSocket
        WebSocketManager.getInstance().disconnect()
        Log.i(TAG, "AI 影伴系统已关闭")
    }
}
