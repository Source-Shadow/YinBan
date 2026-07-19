package com.yinban.ai.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.yinban.ai.R
import com.yinban.ai.databinding.FragmentChataiBinding
import com.yinban.ai.network.WebSocketManager

class ChatAiFragment : Fragment() {

    private var _binding: FragmentChataiBinding? = null
    private val binding get() = _binding
    private val wsManager = WebSocketManager.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChataiBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.btnStartChat.setOnClickListener {
            startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_ROLE, "patient")
                putExtra(ChatActivity.EXTRA_MODE, "ai")
            })
            requireActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        updateAiStatus(wsManager.isConnected())
    }

    fun updateAiStatus(connected: Boolean) {
        val b = binding ?: return
        if (connected) {
            b.tvAiStatus.text = "小影火 在线"
            val statusColor = ContextCompat.getColor(requireContext(), R.color.yb_color_status_connected)
            b.tvAiStatus.setTextColor(statusColor)
            b.viewAiStatusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        } else {
            b.tvAiStatus.text = "小影火 直连"
            val statusColor = ContextCompat.getColor(requireContext(), R.color.yb_color_status_warning)
            b.tvAiStatus.setTextColor(statusColor)
            b.viewAiStatusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = ChatAiFragment()
    }
}
