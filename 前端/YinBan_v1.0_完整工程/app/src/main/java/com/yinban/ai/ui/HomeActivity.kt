// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/HomeActivity.kt
// 用途: AI 影伴系统 v1.0 — 主页容器（底部四栏导航）
// 架构: BottomNavigationView + Fragment 切换
//   Tab 1: 主页 — 设备状态 / 快捷SOS / 连接状态
//   Tab 2: 小影火 — AI 聊天伴侣（DeepSeek API）
//   Tab 3: 监护栏 — 监护人交互核心
//   Tab 4: 我的 — 个人中心与设置
// ============================================================

package com.yinban.ai.ui

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.yinban.ai.R
import com.yinban.ai.databinding.ActivityHomeBinding
import com.yinban.ai.ui.home.AIChatFragment
import com.yinban.ai.ui.home.GuardianFragment
import com.yinban.ai.ui.home.HomeFragment
import com.yinban.ai.ui.home.ProfileFragment
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var binding: ActivityHomeBinding

    // TTS 用于全局 AI 语音播报
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // 当前 Fragment
    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initBottomNav()
        initGlobalTts()

        // 默认显示主页
        if (savedInstanceState == null) {
            switchFragment(HomeFragment(), "home")
        }
    }

    // ═══════════════════════════════════════════
    // 底部导航
    // ═══════════════════════════════════════════

    private fun initBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchFragment(HomeFragment(), "home")
                R.id.nav_ai_chat -> switchFragment(AIChatFragment(), "ai_chat")
                R.id.nav_guardian -> switchFragment(GuardianFragment(), "guardian")
                R.id.nav_profile -> switchFragment(ProfileFragment(), "profile")
            }
            true
        }

        binding.bottomNav.setOnItemReselectedListener { /* no-op: already on this tab */ }
    }

    private fun switchFragment(fragment: Fragment, tag: String) {
        val ft = supportFragmentManager.beginTransaction()

        // 隐藏当前
        currentFragment?.let { ft.hide(it) }

        // 查找或添加目标
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            ft.show(existing)
            currentFragment = existing
        } else {
            ft.add(R.id.fragment_container, fragment, tag)
            currentFragment = fragment
        }

        ft.commit()
    }

    // ═══════════════════════════════════════════
    // 全局 TTS（AI 语音播报）
    // ═══════════════════════════════════════════

    private fun initGlobalTts() {
        tts = TextToSpeech(this) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
            if (isTtsReady) {
                tts?.language = Locale.CHINESE
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {}
        })
    }

    fun speakText(text: String) {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "global_${System.currentTimeMillis()}")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
