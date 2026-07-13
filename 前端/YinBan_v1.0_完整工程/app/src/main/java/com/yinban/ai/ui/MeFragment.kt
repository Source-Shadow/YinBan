package com.yinban.ai.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yinban.ai.databinding.FragmentMeBinding
import com.yinban.ai.storage.PreferenceManager

class MeFragment : Fragment() {

    interface MeCallback {
        fun onLogout()
    }

    private val avatarList = listOf(
        "🧑", "👦", "👧", "👨", "👩", "🧒",
        "🐱", "🐶", "🐼", "🐨", "🦊", "🐰",
        "🌙", "⭐", "🌸", "🌻", "🎵", "🌈"
    )

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!
    private var callback: MeCallback? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is MeCallback) {
            callback = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pm = PreferenceManager.getInstance(requireContext())
        val prefs = pm.sharedPrefs
        val isGuardian = pm.isGuardian()

        // ── 个人信息 ──
        binding.tvProfileName.text = pm.nickname.ifEmpty { pm.account.ifEmpty { "未设置" } }
        binding.tvProfileAccount.text = "账号: " + pm.account.ifEmpty { "--" }
        binding.chipRole.text = if (pm.isPatient()) "患者" else "监护人"
        binding.tvAvatar.text = pm.avatarEmoji.ifEmpty { "🧑" }

        // ── 监护人端隐藏不需要的卡片 ──
        if (isGuardian) {
            binding.cardPromptSettings.visibility = View.GONE
            binding.cardAiSettings.visibility = View.GONE
        }

        // ── 头像切换 ──
        binding.tvAvatar.setOnClickListener {
            val cur = binding.tvAvatar.text.toString()
            val idx = avatarList.indexOf(cur)
            val next = if (idx >= 0) avatarList[(idx + 1) % avatarList.size] else avatarList[0]
            binding.tvAvatar.text = next
            pm.avatarEmoji = next
        }

        // ── 昵称编辑 ──
        binding.groupNickname.setOnClickListener {
            val input = EditText(requireContext())
            input.setText(binding.tvProfileName.text)
            input.maxLines = 1
            input.setPadding(32, 16, 32, 16)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("编辑昵称")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        pm.nickname = newName
                        binding.tvProfileName.text = newName
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── 开关 ──
        initSwitch(binding.switchVibration, prefs, "vibration_enabled", true)
        initSwitch(binding.switchVoice, prefs, "voice_enabled", true)

        // ── API Key ──
        val apiKey = pm.deepseekApiKey
        binding.tvApiKeyHint.text = if (apiKey.isNotEmpty())
            "已设置 " + apiKey.take(12) + "..." else "未设置"

        binding.groupApiKey.setOnClickListener {
            val input = EditText(requireContext())
            input.hint = "sk-..."
            input.setText(pm.deepseekApiKey)
            input.maxLines = 1
            input.setPadding(32, 16, 32, 16)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("DeepSeek API Key")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val newKey = input.text.toString().trim()
                    pm.deepseekApiKey = newKey
                    com.yinban.ai.network.DeepSeekClient.apiKey =
                        newKey.ifEmpty { com.yinban.ai.network.DeepSeekClient.DEFAULT_API_KEY }
                    binding.tvApiKeyHint.text = if (newKey.isNotEmpty())
                        "已设置 " + newKey.take(12) + "..." else "未设置"
                }
                .setNeutralButton("清除") { _, _ ->
                    pm.deepseekApiKey = ""
                    com.yinban.ai.network.DeepSeekClient.apiKey =
                        com.yinban.ai.network.DeepSeekClient.DEFAULT_API_KEY
                    binding.tvApiKeyHint.text = "未设置"
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── 退出 ──
        binding.btnLogout.setOnClickListener { callback?.onLogout() }
    }

    // ── Switch 初始化（SharedPreferences 版） ──
    private fun initSwitch(
        sw: android.widget.CompoundButton,
        prefs: SharedPreferences,
        key: String,
        default: Boolean
    ) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = prefs.getBoolean(key, default)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    // ── Switch 初始化（PreferenceManager 属性版） ──
    private fun initSwitch(
        sw: android.widget.CompoundButton,
        initial: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    fun refreshProfile() {
        val b = binding
        val pm = PreferenceManager.getInstance(requireContext())
        b.tvProfileName.text = pm.nickname.ifEmpty { pm.account.ifEmpty { "未设置" } }
        b.tvProfileAccount.text = "账号: " + pm.account.ifEmpty { "--" }
        b.chipRole.text = if (pm.isPatient()) "患者" else "监护人"
        b.tvAvatar.text = pm.avatarEmoji.ifEmpty { "🧑" }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): MeFragment = MeFragment()
    }
}
