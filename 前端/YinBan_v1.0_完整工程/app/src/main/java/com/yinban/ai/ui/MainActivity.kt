// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/MainActivity.kt
// 用途: AI 影伴系统 v1.0 — 应用入口 + 启动分流（Splash 路由）
// 策略:
//   - 若已登录 & 凭据有效 → 自动跳转对应角色专属画面（跳过登录页）
//   - 若未登录 → 进入登录页
//   - "只激活并显示当前角色对应的专属操作画面"
// ============================================================

package com.yinban.ai.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.yinban.ai.storage.PreferenceManager

class MainActivity : AppCompatActivity() {

    companion object {
        /** Splash 最短展示时间（毫秒），避免闪屏体验差 */
        private const val SPLASH_MIN_DURATION_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Splash 阶段不做布局渲染，直接路由决策
        val prefManager = PreferenceManager.getInstance(this)

        // 延迟以保证 Splash 过渡自然
        Handler(Looper.getMainLooper()).postDelayed({
            if (prefManager.hasValidAutoLogin()) {
                navigateToRoleScreen(prefManager.role)
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            finish()
        }, SPLASH_MIN_DURATION_MS)
    }

    /**
     * 根据角色跳转到 HomeActivity（底部四栏导航）。
     * 患者端和监护人端共用同一套导航架构，
     * 但在"我的"Tab 中显示当前角色信息。
     */
    private fun navigateToRoleScreen(role: String) {
        val target = if (role == "patient") PatientActivity::class.java else GuardianActivity::class.java
        val intent = Intent(this, target)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
