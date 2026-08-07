package com.yinban.ai.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.yinban.ai.R
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
    private var idleAnimatorSet: AnimatorSet? = null
    private var feedbackAnimatorSet: AnimatorSet? = null
    private var isXiaoyinghuoFeedbackPlaying = false
    private val xiaoyinghuoInterpolator = AccelerateDecelerateInterpolator()

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
        b.ivXiaoyinghuo.setOnClickListener { playXiaoyinghuoSuccessFeedback() }

        callback?.getConnectionState()?.let { updateStandbyUI(it) }

        if (!dailyQuoteLoaded) loadDailyQuote()
    }

    override fun onStart() {
        super.onStart()
        startXiaoyinghuoIdleMotion()
    }

    override fun onStop() {
        stopXiaoyinghuoAnimations()
        super.onStop()
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

    /** 服务器状态回执时同步开关 UI，避免状态不一致 */
    fun syncSwitchState(type: String, active: Boolean) {
        val b = binding ?: return
        when (type) {
            "video" -> {
                b.switchVideo.setOnCheckedChangeListener(null)
                b.switchVideo.isChecked = active
                b.switchVideo.setOnCheckedChangeListener { _, checked ->
                    callback?.onVideoToggle(checked)
                }
            }
            "audio" -> {
                b.switchAudio.setOnCheckedChangeListener(null)
                b.switchAudio.isChecked = active
                b.switchAudio.setOnCheckedChangeListener { _, checked ->
                    callback?.onAudioToggle(checked)
                }
            }
        }
    }

    private fun startXiaoyinghuoIdleMotion() {
        val logo = binding?.ivXiaoyinghuo ?: return
        if (isXiaoyinghuoFeedbackPlaying || idleAnimatorSet?.isStarted == true) return

        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetXiaoyinghuoView(logo)
            return
        }

        val floatDistance = -4f * resources.displayMetrics.density
        val floatAnimator = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 0f, floatDistance, 0f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = xiaoyinghuoInterpolator
        }
        val scaleXAnimator = ObjectAnimator.ofFloat(logo, View.SCALE_X, 1f, 1.018f, 1f).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = xiaoyinghuoInterpolator
        }
        val scaleYAnimator = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1f, 1.018f, 1f).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = xiaoyinghuoInterpolator
        }

        idleAnimatorSet = AnimatorSet().apply {
            playTogether(floatAnimator, scaleXAnimator, scaleYAnimator)
            start()
        }
    }

    private fun playXiaoyinghuoSuccessFeedback() {
        val logo = binding?.ivXiaoyinghuo ?: return
        if (isXiaoyinghuoFeedbackPlaying) return

        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetXiaoyinghuoView(logo)
            return
        }

        isXiaoyinghuoFeedbackPlaying = true
        logo.isEnabled = false
        stopXiaoyinghuoIdleMotion(resetView = true)

        val fadeOutToSuccess = ObjectAnimator.ofFloat(logo, View.ALPHA, 1f, 0f).apply {
            duration = 140L
            interpolator = xiaoyinghuoInterpolator
        }
        val fadeInSuccess = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).apply {
            duration = 180L
            interpolator = xiaoyinghuoInterpolator
        }
        val holdSuccess = ValueAnimator.ofFloat(1f, 1f).apply {
            duration = 840L
        }
        val fadeOutToIdle = ObjectAnimator.ofFloat(logo, View.ALPHA, 1f, 0f).apply {
            duration = 140L
            interpolator = xiaoyinghuoInterpolator
        }
        val fadeInIdle = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).apply {
            duration = 180L
            interpolator = xiaoyinghuoInterpolator
        }

        fadeOutToSuccess.setImageWhenFinished(logo, R.drawable.yb_xiaoyinghuo_success)
        fadeOutToIdle.setImageWhenFinished(logo, R.drawable.yb_xiaoyinghuo_idle)

        feedbackAnimatorSet = AnimatorSet().apply {
            playSequentially(fadeOutToSuccess, fadeInSuccess, holdSuccess, fadeOutToIdle, fadeInIdle)
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    feedbackAnimatorSet = null
                    isXiaoyinghuoFeedbackPlaying = false
                    logo.isEnabled = true
                    resetXiaoyinghuoView(logo)
                    if (!canceled) startXiaoyinghuoIdleMotion()
                }
            })
            start()
        }
    }

    private fun ObjectAnimator.setImageWhenFinished(logo: ImageView, drawableRes: Int) {
        addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!canceled) logo.setImageResource(drawableRes)
            }
        })
    }

    private fun stopXiaoyinghuoAnimations() {
        feedbackAnimatorSet?.cancel()
        feedbackAnimatorSet = null
        isXiaoyinghuoFeedbackPlaying = false
        stopXiaoyinghuoIdleMotion(resetView = true)
        binding?.ivXiaoyinghuo?.isEnabled = true
    }

    private fun stopXiaoyinghuoIdleMotion(resetView: Boolean) {
        idleAnimatorSet?.cancel()
        idleAnimatorSet = null
        if (resetView) binding?.ivXiaoyinghuo?.let(::resetXiaoyinghuoView)
    }

    private fun resetXiaoyinghuoView(logo: ImageView) {
        logo.translationY = 0f
        logo.scaleX = 1f
        logo.scaleY = 1f
        logo.alpha = 1f
        logo.setImageResource(R.drawable.yb_xiaoyinghuo_idle)
    }

    override fun onDestroyView() {
        stopXiaoyinghuoAnimations()
        binding?.ivXiaoyinghuo?.setOnClickListener(null)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}
