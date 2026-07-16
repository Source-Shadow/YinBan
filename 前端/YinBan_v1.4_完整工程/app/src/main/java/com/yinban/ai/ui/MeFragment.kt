package com.yinban.ai.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yinban.ai.databinding.FragmentMeBinding
import com.yinban.ai.storage.PreferenceManager
import java.io.File

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

    // ── 选图 → 自动圆形裁剪 → 保存 → 显示 ──
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) cropAndSave(uri, 256)
    }

    private fun cropAndSave(uri: Uri, size: Int) {
        try {
            val ctx = requireContext().applicationContext
            val pm = PreferenceManager.getInstance(ctx)
            // 读入 bitmap（子采样防 OOM）
            val opts = BitmapFactory.Options().apply { inSampleSize = calcSample(uri, size * 2) }
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return
            // 取中心正方形并缩放
            val edge = minOf(src.width, src.height)
            val x = (src.width - edge) / 2
            val y = (src.height - edge) / 2
            val square = Bitmap.createBitmap(src, x, y, edge, edge)
            if (square != src) src.recycle()
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            square.recycle()
            // 圆形裁剪
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val clipPath = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(clipPath)
            canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.restore(); scaled.recycle()
            // 保存
            val dir = File(ctx.filesDir, "avatars").also { it.mkdirs() }
            val file = File(dir, "avatar_${pm.role}.png")
            file.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 90, it) }
            result.recycle()
            pm.avatarPath = file.absolutePath
            // 显示
            showCircularAvatar(BitmapFactory.decodeFile(file.absolutePath) ?: result)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "设置头像失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calcSample(uri: Uri, target: Int): Int {
        var s = 1
        val ctx = requireContext().applicationContext
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val max = maxOf(opts.outWidth, opts.outHeight)
        while (max / s > target) s *= 2
        return s
    }

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

        // 加载头像（优先显示自定义照片，否则显示 emoji）
        loadAvatar(pm)

        // ── 监护人端隐藏不需要的卡片 ──
        if (isGuardian) {
            binding.cardPromptSettings.visibility = View.GONE
            binding.cardAiSettings.visibility = View.GONE
        }

        // ── 头像点击：弹出选择方式 ──
        binding.avatarContainer.setOnClickListener {
            showAvatarPickerDialog(pm)
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

    // ═══════════════════════════════════════════
    // 头像
    // ═══════════════════════════════════════════

    private fun showAvatarPickerDialog(pm: PreferenceManager) {
        val items = arrayOf("选择 emoji 头像", "从相册选择图片")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("更换头像")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEmojiPicker(pm)
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .setNeutralButton("移除头像") { _, _ ->
                pm.avatarPath = ""
                binding.ivAvatarPhoto.setImageBitmap(null)
                binding.ivAvatarPhoto.visibility = View.GONE
                binding.tvAvatar.visibility = View.VISIBLE
                binding.tvAvatar.text = pm.avatarEmoji.ifEmpty { "🧑" }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEmojiPicker(pm: PreferenceManager) {
        val cur = binding.tvAvatar.text.toString()
        val idx = avatarList.indexOf(cur)
        val next = if (idx >= 0) avatarList[(idx + 1) % avatarList.size] else avatarList[0]
        binding.tvAvatar.text = next
        pm.avatarEmoji = next
        // 清除自定义照片
        pm.avatarPath = ""
        binding.ivAvatarPhoto.setImageBitmap(null)
        binding.ivAvatarPhoto.visibility = View.GONE
        binding.tvAvatar.visibility = View.VISIBLE
    }

    // ── 显示圆形头像 ──
    private fun showCircularAvatar(bitmap: Bitmap) {
        val size = minOf(bitmap.width, bitmap.height)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val clipPath = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        val srcX = (bitmap.width - size) / 2f
        val srcY = (bitmap.height - size) / 2f
        canvas.drawBitmap(bitmap, -srcX, -srcY, Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        binding.ivAvatarPhoto.setImageBitmap(result)
        binding.ivAvatarPhoto.visibility = View.VISIBLE
        binding.tvAvatar.visibility = View.GONE
    }

    private fun loadAvatar(pm: PreferenceManager) {
        val path = pm.avatarPath
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        showCircularAvatar(bitmap)
                        binding.tvAvatar.text = pm.avatarEmoji.ifEmpty { "🧑" }
                        bitmap.recycle()
                        return
                    }
                } catch (_: Exception) {}
            }
            pm.avatarPath = ""
        }
        // 默认 emoji
        binding.tvAvatar.text = pm.avatarEmoji.ifEmpty { "🧑" }
        binding.ivAvatarPhoto.setImageBitmap(null)
        binding.ivAvatarPhoto.visibility = View.GONE
        binding.tvAvatar.visibility = View.VISIBLE
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
        loadAvatar(pm)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): MeFragment = MeFragment()
    }
}
