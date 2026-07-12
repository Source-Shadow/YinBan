// ============================================================
// 路径: app/src/main/java/com/yinban/ai/ui/home/ProfileFragment.kt
// 用途: 我的 Tab — 个人中心
// 功能: 头像昵称 / 音量振动隐私设置 / 设备电量 / 关于与反馈
// ============================================================

package com.yinban.ai.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yinban.ai.R
import com.yinban.ai.databinding.FragmentProfileBinding
import com.yinban.ai.network.WebSocketManager
import com.yinban.ai.storage.PreferenceManager
import com.yinban.ai.ui.LoginActivity

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefManager: PreferenceManager
    private lateinit var wsManager: WebSocketManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefManager = PreferenceManager.getInstance(requireContext())
        wsManager = WebSocketManager.getInstance()

        initViews()
        loadProfile()
    }

    private fun initViews() {
        // 角色显示
        val roleName = if (prefManager.isPatient()) "患者端" else "监护人端"
        val roleEmoji = if (prefManager.isPatient()) "🏥" else "👨‍👩‍👧"
        binding.tvProfileName.text = "${prefManager.account}.${roleName}"
        binding.tvProfileRole.text = roleEmoji
        binding.tvProfileAvatar.text = if (prefManager.isPatient()) "🏥" else "👨‍👩‍👧"

        // 振动开关
        binding.switchProfileVibrate.setOnCheckedChangeListener { _, checked ->
            Toast.makeText(requireContext(), if (checked) "📳 振动提醒已开启" else "🔇 振动提醒已关闭", Toast.LENGTH_SHORT).show()
        }

        // 隐私模式
        binding.switchProfilePrivacy.isChecked = prefManager.isPrivacyMode
        binding.switchProfilePrivacy.setOnCheckedChangeListener { _, checked ->
            prefManager.isPrivacyMode = checked
            Toast.makeText(requireContext(), if (checked) "🔒 隐私模式已开启" else "📍 精确位置已开启", Toast.LENGTH_SHORT).show()
        }

        // 退出登录
        binding.btnProfileLogout.setOnClickListener { logout() }
    }

    private fun loadProfile() {
        binding.tvBattery.text = "85%"
        binding.tvNetwork.text = "WiFi"
    }

    private fun logout() {
        wsManager.disconnect()
        prefManager.clearLoginInfo()

        Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
        startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        activity?.finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
