package com.yinban.ai.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.yinban.ai.databinding.FragmentGuardianBinding

class GuardianFragment : Fragment() {

    interface GuardianCallback {
        fun onOpenChat()
        fun onOpenLocation()
        fun onStartCall()
        fun onViewStream()
        fun getRecentMessage(): String
        fun getLocationStatus(): String
    }

    private var _binding: FragmentGuardianBinding? = null
    private val binding get() = _binding
    private var callback: GuardianCallback? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? GuardianCallback
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _binding = FragmentGuardianBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.cardShortcutMessage.setOnClickListener { callback?.onOpenChat() }
        b.cardShortcutLocation.setOnClickListener { callback?.onOpenLocation() }
        b.cardShortcutCall.setOnClickListener { callback?.onStartCall() }
        b.cardShortcutVideo.setOnClickListener { callback?.onViewStream() }

        refreshStatus()
    }

    fun refreshStatus() {
        val b = binding ?: return
        b.tvRecentMessage.text = callback?.getRecentMessage() ?: "暂无消息"
        b.tvLocationStatus.text = callback?.getLocationStatus() ?: "等待定位"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = GuardianFragment()
    }
}
