// ============================================================
// 路径: app/src/main/java/com/yinban/ai/hardware/HardwareStreamManager.kt
// 用途: AI 影伴系统 v1.0 — 硬件音视频流预留接口
// 说明:
//   - 抽象接口供硬件/模组同学实现具体的摄像头与音频板卡数据注入
//   - 患者端通过此接口触发视频推流与音频推流
//   - 当前提供默认空实现 (NoOp)，方便在硬件就绪前进行 UI 联调
// ============================================================

package com.yinban.ai.hardware

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 硬件音视频流管理器接口。
 *
 * 硬件同学需实现此接口，将摄像头/麦克风/音频板卡的原始数据
 * 注入到 App 的推流管线中。
 */
interface HardwareStreamManager {

    companion object {
        private const val TAG = "HardwareStream"

        /** 默认空实现，硬件就绪前使用 */
        val NO_OP: HardwareStreamManager = object : HardwareStreamManager {
            override fun injectHardwareVideoFrame(nv21Data: ByteArray, w: Int, h: Int) {
                Log.d(TAG, "[NoOp] 收到视频帧: ${w}x${h}, ${nv21Data.size} bytes (未处理)")
            }

            override fun injectHardwareAudioFrame(pcmData: ByteArray, sampleRate: Int) {
                Log.d(TAG, "[NoOp] 收到音频帧: sampleRate=$sampleRate, ${pcmData.size} bytes (未处理)")
            }

            override fun startVideoStream() {
                Log.d(TAG, "[NoOp] startVideoStream() 被调用 — 无硬件实现")
            }

            override fun stopVideoStream() {
                Log.d(TAG, "[NoOp] stopVideoStream() 被调用 — 无硬件实现")
            }

            override fun startAudioStream() {
                Log.d(TAG, "[NoOp] startAudioStream() 被调用 — 无硬件实现")
            }

            override fun stopAudioStream() {
                Log.d(TAG, "[NoOp] stopAudioStream() 被调用 — 无硬件实现")
            }

            override fun isStreaming(): Boolean = false
        }
    }

    // ═══════════════════════════════════════════
    // 数据注入函数（硬件同学实现）
    // ═══════════════════════════════════════════

    /**
     * 注入原始视频帧。
     *
     * 摄像头 / 硬件模组采集到 NV21 格式的原始帧后调用此函数。
     * App 内部会将帧数据编码后通过 WebSocket 或推流协议发送。
     *
     * @param nv21Data NV21 格式的原始 YUV 数据
     * @param w        帧宽度（像素）
     * @param h        帧高度（像素）
     */
    fun injectHardwareVideoFrame(nv21Data: ByteArray, w: Int, h: Int)

    /**
     * 注入原始音频帧。
     *
     * 麦克风 / 音频板卡采集到 PCM 数据后调用此函数。
     *
     * @param pcmData    PCM 16bit 原始音频数据
     * @param sampleRate 采样率（Hz），如 16000、44100
     */
    fun injectHardwareAudioFrame(pcmData: ByteArray, sampleRate: Int)

    // ═══════════════════════════════════════════
    // 流控制（患者端主动控制）
    // ═══════════════════════════════════════════

    /** 开始视频推流 */
    fun startVideoStream()

    /** 停止视频推流 */
    fun stopVideoStream()

    /** 开始音频推流 */
    fun startAudioStream()

    /** 停止音频推流 */
    fun stopAudioStream()

    /** 当前是否正在推流 */
    fun isStreaming(): Boolean
}

/**
 * 硬件流回调 — 用于将编码后的数据传递到网络层。
 */
interface HardwareStreamCallback {
    /** 编码后的视频数据就绪 */
    fun onEncodedVideoData(data: ByteArray, isKeyFrame: Boolean)

    /** 编码后的音频数据就绪 */
    fun onEncodedAudioData(data: ByteArray)

    /** 推流错误 */
    fun onStreamError(error: Exception)
}
