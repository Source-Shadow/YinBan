// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/LoginActivity.kt
// 用途: AI 影伴系统 v1.0 — 三步登录注册流程
//
//  步骤1: 选择身份角色（患者端 / 监护人端）— 首页直接展示
//  步骤2: 账号 + 密码（+ 服务器IP仅debug）
//  步骤3: 输入配对码 → 持久化并跳转
//
//  BuildConfig:
//   - debug:   显示 IP 输入框，手填
//   - release: 隐藏 IP 输入框，使用内置 wss 域名
// ============================================================

package com.yinban.ai.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.yinban.ai.BuildConfig
import com.yinban.ai.R
import com.yinban.ai.storage.PreferenceManager
import com.yinban.ai.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefManager: PreferenceManager

    private var selectedRole: String = "patient"
    private var currentStep: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager.getInstance(this)

        initBuildConfig()
        initListeners()
        restoreSavedFields()
        showStep(1)
    }

    // ═══════════════════════════════════════════
    // BuildConfig
    // ═══════════════════════════════════════════

    private fun initBuildConfig() {
        if (BuildConfig.SHOW_SERVER_CONFIG) {
            binding.tilServerIp.visibility = View.VISIBLE
            binding.etServerIp.setText(BuildConfig.WS_BASE_URL.replace("ws://", ""))
        } else {
            binding.tilServerIp.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════════════
    // 监听器
    // ═══════════════════════════════════════════

    private fun initListeners() {
        // ── 步骤1: 点击选择患者端 ──
        binding.cardRolePatient.setOnClickListener { selectRole("patient") }

        // ── 步骤1: 点击选择监护人端 ──
        binding.cardRoleGuardian.setOnClickListener { selectRole("guardian") }

        // ── 步骤1: 确认身份 → 进入步骤2 ──
        binding.btnStep1Confirm.setOnClickListener { showStep(2) }

        // ── 步骤2: 下一步 → 进入步骤3 ──
        binding.btnStep2Next.setOnClickListener {
            if (validateStep2()) {
                updatePairingHint()
                showStep(3)
            }
        }

        // ── 步骤2: 返回步骤1 ──
        binding.btnStep2Back.setOnClickListener { showStep(1) }

        // ── 步骤3: 配对并进入 ──
        binding.btnPairEnter.setOnClickListener {
            if (validateStep3()) {
                performLogin()
            }
        }

        // ── 步骤3: 返回步骤2 ──
        binding.btnStep3Back.setOnClickListener { showStep(2) }

        // 默认选中患者端
        selectRole("patient")
    }

    private fun restoreSavedFields() {
        binding.etAccount.setText(prefManager.account)
        binding.etRoom.setText(prefManager.room)
        if (prefManager.isGuardian()) {
            selectRole("guardian")
        }
    }

    // ═══════════════════════════════════════════
    // 步骤切换
    // ═══════════════════════════════════════════

    private fun selectRole(role: String) {
        selectedRole = role
        val isPatient = role == "patient"

        // 患者端卡片高亮
        binding.cardRolePatient.apply {
            strokeColor = ContextCompat.getColor(this@LoginActivity, if (isPatient) R.color.primary else R.color.text_secondary)
            strokeWidth = if (isPatient) 4 else 1
        }
        binding.tvCheckPatient.visibility = if (isPatient) View.VISIBLE else View.INVISIBLE

        // 监护人端卡片高亮
        binding.cardRoleGuardian.apply {
            strokeColor = ContextCompat.getColor(this@LoginActivity, if (!isPatient) R.color.primary else R.color.text_secondary)
            strokeWidth = if (!isPatient) 4 else 1
        }
        binding.tvCheckGuardian.visibility = if (!isPatient) View.VISIBLE else View.INVISIBLE
    }

    private fun showStep(step: Int) {
        currentStep = step

        // 隐藏所有内容卡片和按钮
        binding.cardStep1.visibility = View.GONE
        binding.cardStep2.visibility = View.GONE
        binding.cardStep3.visibility = View.GONE
        binding.btnStep1Confirm.visibility = View.GONE
        binding.btnStep2Next.visibility = View.GONE
        binding.btnStep2Back.visibility = View.GONE
        binding.groupStep3Buttons.visibility = View.GONE

        val activeBg = R.drawable.bg_step_active
        val inactiveBg = R.drawable.bg_step_inactive

        when (step) {
            1 -> {
                binding.cardStep1.visibility = View.VISIBLE
                binding.btnStep1Confirm.visibility = View.VISIBLE
                binding.tvLoginTitle.text = "AI 影伴系统"
                binding.tvLoginSubtitle.text = getString(R.string.login_subtitle)

                setDot(binding.tvStep1Dot, "1", R.color.text_on_dark, activeBg)
                setDot(binding.tvStep2Dot, "2", R.color.text_secondary, inactiveBg)
                setDot(binding.tvStep3Dot, "3", R.color.text_secondary, inactiveBg)
            }
            2 -> {
                binding.cardStep2.visibility = View.VISIBLE
                binding.btnStep2Next.visibility = View.VISIBLE
                binding.btnStep2Back.visibility = View.VISIBLE
                binding.tvLoginTitle.text = "账号登录"
                binding.tvLoginSubtitle.text = "登录您的 AI 影伴账号"

                setDot(binding.tvStep1Dot, "✓", R.color.text_on_dark, activeBg)
                setDot(binding.tvStep2Dot, "2", R.color.text_on_dark, activeBg)
                setDot(binding.tvStep3Dot, "3", R.color.text_secondary, inactiveBg)
            }
            3 -> {
                binding.cardStep3.visibility = View.VISIBLE
                binding.groupStep3Buttons.visibility = View.VISIBLE
                binding.tvLoginTitle.text = "设备配对"
                binding.tvLoginSubtitle.text = "输入相同的配对码以建立连接"

                setDot(binding.tvStep1Dot, "✓", R.color.text_on_dark, activeBg)
                setDot(binding.tvStep2Dot, "✓", R.color.text_on_dark, activeBg)
                setDot(binding.tvStep3Dot, "3", R.color.text_on_dark, activeBg)
            }
        }
    }

    private fun setDot(view: android.widget.TextView, text: String, colorRes: Int, bgRes: Int) {
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, colorRes))
        view.setBackgroundResource(bgRes)
    }

    private fun updatePairingHint() {
        val peerRole = if (selectedRole == "patient") "监护人端" else "患者端"
        binding.tvPairingHint.text = "请输入和${peerRole}一样的配对码来建立连接"
    }

    // ═══════════════════════════════════════════
    // 校验
    // ═══════════════════════════════════════════

    private fun validateStep2(): Boolean {
        val account = binding.etAccount.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (account.isBlank()) {
            binding.tilAccount.error = "请输入账号"
            return false
        }
        binding.tilAccount.error = null

        if (password.isBlank()) {
            binding.tilPassword.error = "请输入密码"
            return false
        }
        binding.tilPassword.error = null

        if (BuildConfig.SHOW_SERVER_CONFIG) {
            val ip = getServerIp()
            if (ip.isBlank()) {
                binding.tilServerIp.error = "请输入服务器 IP"
                return false
            }
            if (!isValidIp(ip)) {
                binding.tilServerIp.error = "IP 格式不正确"
                return false
            }
            binding.tilServerIp.error = null
        }
        return true
    }

    private fun validateStep3(): Boolean {
        val room = binding.etRoom.text.toString().trim()
        if (room.isBlank()) {
            binding.tilRoom.error = "请输入配对码"
            return false
        }
        if (room.length < 3) {
            binding.tilRoom.error = "至少 3 位"
            return false
        }
        binding.tilRoom.error = null
        return true
    }

    // ═══════════════════════════════════════════
    // 登录持久化
    // ═══════════════════════════════════════════

    private fun performLogin() {
        val account = binding.etAccount.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val room = binding.etRoom.text.toString().trim()
        val serverIp = getServerIp()

        prefManager.saveLoginInfo(account, password, room, selectedRole, serverIp)

        Toast.makeText(
            this,
            "配对成功，正在进入 ${if (selectedRole == "patient") "患者端" else "监护人端"}…",
            Toast.LENGTH_SHORT
        ).show()

        navigateToRoleScreen()
    }

    private fun getServerIp(): String {
        return if (BuildConfig.SHOW_SERVER_CONFIG) {
            binding.etServerIp.text.toString().trim()
        } else {
            BuildConfig.WS_BASE_URL.replace("wss://", "").replace("ws://", "")
        }
    }

    private fun navigateToRoleScreen() {
        val target = when (selectedRole) {
            "patient" -> PatientActivity::class.java
            "guardian" -> GuardianActivity::class.java
            else -> PatientActivity::class.java
        }
        startActivity(Intent(this, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun isValidIp(ip: String): Boolean {
        val p = ip.split(".")
        return p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } ?: false }
    }
}
