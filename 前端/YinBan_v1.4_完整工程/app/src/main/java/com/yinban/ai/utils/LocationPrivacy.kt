// ============================================================
// 路径: app/src/main/java/com/yinban/ai/utils/LocationPrivacy.kt
// 用途: AI 影伴系统 v1.0 — 位置隐私模糊化工具
// 策略:
//   - 正常隐私模式: 经纬度精确到小数点后 2 位 (~1.1km 街区级)
//   - 非隐私模式 / SOS 紧急: 保持原始精度
//   - 使用确定性舍入（非随机），防止多次查询反推真实位置
// ============================================================

package com.yinban.ai.utils

import kotlin.math.roundToInt

object LocationPrivacy {

    /**
     * 隐私模式下的精度因子。
     * 2 位小数 ≈ 1.1km 精度（街区级别，隐藏具体门牌号）
     */
    private const val PRIVACY_DECIMAL_PLACES = 2

    /**
     * SOS 模式下的精度因子。
     * 6 位小数 ≈ 0.1m（与原始 GPS 精度一致）
     */
    private const val SOS_DECIMAL_PLACES = 6

    /**
     * 对经纬度进行隐私模糊化。
     *
     * @param lat           原始纬度
     * @param lng           原始经度
     * @param isPrivacyMode 是否开启隐私模式
     * @param isSos         是否为 SOS 紧急状态 (SOS 下忽略隐私模式)
     * @return FuzzedLocation 模糊化后的经纬度
     */
    fun fuzzLocation(
        lat: Double,
        lng: Double,
        isPrivacyMode: Boolean,
        isSos: Boolean = false
    ): FuzzedLocation {
        val decimalPlaces = when {
            isSos -> SOS_DECIMAL_PLACES          // SOS: 全精度
            isPrivacyMode -> PRIVACY_DECIMAL_PLACES // 隐私: 街区级
            else -> SOS_DECIMAL_PLACES            // 正常: 全精度
        }

        return FuzzedLocation(
            lat = roundToDecimals(lat, decimalPlaces),
            lng = roundToDecimals(lng, decimalPlaces),
            originalLat = lat,
            originalLng = lng,
            isFuzzed = decimalPlaces == PRIVACY_DECIMAL_PLACES
        )
    }

    /**
     * 将 double 舍入到指定小数位数。
     * 使用确定性舍入 (round half up)，非随机。
     */
    private fun roundToDecimals(value: Double, places: Int): Double {
        val factor = Math.pow(10.0, places.toDouble())
        return (value * factor).roundToInt() / factor
    }
}

/**
 * 模糊化后的位置数据。
 */
data class FuzzedLocation(
    val lat: Double,
    val lng: Double,
    val originalLat: Double,
    val originalLng: Double,
    val isFuzzed: Boolean
) {
    /** 模糊化半径的文本描述 */
    val precisionDescription: String
        get() = if (isFuzzed) "≈ 1.1km (街区级，隐私保护中)" else "≈ 0.1m (精确)"
}
