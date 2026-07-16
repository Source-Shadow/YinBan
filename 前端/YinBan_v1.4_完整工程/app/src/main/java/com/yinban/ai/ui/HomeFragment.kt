package com.yinban.ai.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.yinban.ai.databinding.FragmentHomeBinding
import com.yinban.ai.network.DeepSeekClient

class HomeFragment : Fragment() {

    interface HomeCallback {
        fun onSosTriggered()
        fun onVideoToggle(enabled: Boolean)
        fun onAudioToggle(enabled: Boolean)
        fun onSendLocation()
        fun onSendDeviceStatus()
        fun getConnectionState(): ConnectionState
    }

    data class ConnectionState(
        val connected: Boolean = false,
        val roomStatus: String = "disconnected",
        val standbyTitle: String = "AI 影伴守护中",
        val standbySubtitle: String = "已安全守护 0 分钟"
    )

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding
    private var callback: HomeCallback? = null
    private var dailyQuoteLoaded = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? HomeCallback
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        // Switch 初始状态
        setupSwitch(b.switchVideo, false) { callback?.onVideoToggle(it) }
        setupSwitch(b.switchAudio, false) { callback?.onAudioToggle(it) }

        b.btnSendLocation.setOnClickListener { callback?.onSendLocation() }
        b.btnSendDeviceStatus.setOnClickListener { callback?.onSendDeviceStatus() }
        b.btnSos.setOnClickListener { callback?.onSosTriggered() }

        callback?.getConnectionState()?.let { updateStandbyUI(it) }

        if (!dailyQuoteLoaded) loadDailyQuote()
    }

    private fun setupSwitch(sw: android.widget.CompoundButton, initial: Boolean, onChanged: (Boolean) -> Unit) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    private fun loadDailyQuote() {
        val b = binding ?: return
        b.tvDailyQuote.text = "…"
        DeepSeekClient.dailyQuote { quote ->
            if (_binding != null && isAdded) {
                b.tvDailyQuote.text = "「 $quote 」"
                dailyQuoteLoaded = true
            }
        }
    }

    fun updateStandbyUI(state: ConnectionState) {
        val b = binding ?: return
        b.tvStandbyTitle.text = state.standbyTitle
        b.tvStandbySubtitle.text = state.standbySubtitle
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}
