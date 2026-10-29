package io.github.wumisc.safewalk

import kotlin.math.sqrt

/**
 * 步态检测参数。
 */
object MotionConfig {
    const val ENTER_DEBOUNCE_MS = 2500L
    const val EXIT_DEBOUNCE_MS = 4500L
    const val MIN_STEP_FREQ = 1.4
    const val MAX_STEP_FREQ = 2.2
    const val MIN_WALK_SPEED = 0.3f
    const val MAX_WALK_SPEED = 2.5f
    const val LOCATION_QUEUE_SIZE = 3

    // 自适应阈值
    const val ADAPT_WINDOW_SIZE = 60
    const val THRESHOLD_SCALE = 1.8f
    const val MIN_PEAK_THRESHOLD = 0.6f
    const val MAX_PEAK_THRESHOLD = 2.8f

    // 姿态判定
    const val LAY_FLAT_Z_THRESHOLD = 8.5f
    const val LAY_FLAT_WEIGHT = 1.45f
}

enum class PhoneAttitude {
    UNKNOWN, LAY_FLAT, UPRIGHT, TILTED
}

/**
 * 步态检测器：IIR滤波分离重力，自适应峰值阈值，步频校验。
 * feedAcc 返回 true 表示检测到一步；hasStableGait 要求连续3步间隔稳定才算真走路。
 */
class GaitDetector {
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.2f

    private var lastPeakTime = 0L
    private val peakIntervals = mutableListOf<Long>()

    private val magWindow = mutableListOf<Float>()
    private var currentAdaptiveThreshold = MotionConfig.MIN_PEAK_THRESHOLD
    var currentAttitude: PhoneAttitude = PhoneAttitude.UNKNOWN
        private set

    fun getAttitude(): PhoneAttitude = currentAttitude

    private fun updateAttitude(gx: Float, gy: Float, gz: Float) {
        val gMag = sqrt(gx * gx + gy * gy + gz * gz)
        if (gMag < 8f) { currentAttitude = PhoneAttitude.UNKNOWN; return }
        currentAttitude = when {
            kotlin.math.abs(gz) > MotionConfig.LAY_FLAT_Z_THRESHOLD -> PhoneAttitude.LAY_FLAT
            kotlin.math.abs(gx) > 7f || kotlin.math.abs(gy) > 7f -> PhoneAttitude.UPRIGHT
            else -> PhoneAttitude.TILTED
        }
    }

    fun feedAcc(ax: Float, ay: Float, az: Float): Boolean {
        gravityX = alpha * gravityX + (1 - alpha) * ax
        gravityY = alpha * gravityY + (1 - alpha) * ay
        gravityZ = alpha * gravityZ + (1 - alpha) * az

        val linX = ax - gravityX
        val linY = ay - gravityY
        val linZ = az - gravityZ
        val mag = sqrt(linX * linX + linY * linY + linZ * linZ)

        updateAttitude(gravityX, gravityY, gravityZ)

        magWindow.add(mag)
        while (magWindow.size > MotionConfig.ADAPT_WINDOW_SIZE) magWindow.removeFirst()
        updateAdaptiveThreshold()

        // 平躺时阈值放大1.45倍，更难触发
        var effectiveThreshold = currentAdaptiveThreshold
        if (currentAttitude == PhoneAttitude.LAY_FLAT) {
            effectiveThreshold *= MotionConfig.LAY_FLAT_WEIGHT
        }

        if (mag > effectiveThreshold) {
            val now = System.currentTimeMillis()
            if (lastPeakTime != 0L) {
                val interval = now - lastPeakTime
                val freq = 1000.0 / interval
                if (freq in MotionConfig.MIN_STEP_FREQ..MotionConfig.MAX_STEP_FREQ) {
                    peakIntervals.add(interval)
                    if (peakIntervals.size > 3) peakIntervals.removeFirst()
                    lastPeakTime = now
                    return true
                }
            }
            lastPeakTime = now
        }
        return false
    }

    private fun updateAdaptiveThreshold() {
        if (magWindow.size < 10) return
        val mean = magWindow.average().toFloat()
        var sumSqDiff = 0f
        for (m in magWindow) {
            val diff = m - mean
            sumSqDiff += diff * diff
        }
        val std = sqrt(sumSqDiff / magWindow.size)
        var thresh = mean + std * MotionConfig.THRESHOLD_SCALE
        thresh = thresh.coerceIn(MotionConfig.MIN_PEAK_THRESHOLD, MotionConfig.MAX_PEAK_THRESHOLD)
        currentAdaptiveThreshold = thresh
    }

    fun hasStableGait(): Boolean {
        if (peakIntervals.size < 3) return false
        val avg = peakIntervals.average()
        return peakIntervals.all { kotlin.math.abs(it - avg) < 180 }
    }

    fun reset() {
        peakIntervals.clear()
        magWindow.clear()
        lastPeakTime = 0L
        currentAdaptiveThreshold = MotionConfig.MIN_PEAK_THRESHOLD
        currentAttitude = PhoneAttitude.UNKNOWN
        gravityX = 0f; gravityY = 0f; gravityZ = 0f
    }

    fun getCurrentThreshold(): Float = currentAdaptiveThreshold
}
