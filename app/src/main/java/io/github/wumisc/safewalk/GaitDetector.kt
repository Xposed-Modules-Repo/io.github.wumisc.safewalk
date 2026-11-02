package io.github.wumisc.safewalk

import io.github.wumisc.safewalk.torch.GaitStateCallback
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
    /** 上一帧是否已经在阈值之上：用来只认「上穿」的那一刻，避免一次波峰被算成好几步 */
    private var aboveThreshold = false
    private val peakIntervals = mutableListOf<Long>()

    private val magWindow = mutableListOf<Float>()
    private var currentAdaptiveThreshold = MotionConfig.MIN_PEAK_THRESHOLD
    var currentAttitude: PhoneAttitude = PhoneAttitude.UNKNOWN
        private set

    fun getAttitude(): PhoneAttitude = currentAttitude

    // ========== 静置状态回调（手电筒静置提醒用） ==========
    private var gaitStateCallback: GaitStateCallback? = null
    private var staticDetectTimerStart: Long? = null
    private val SENSOR_STATIC_THRESHOLD = 3000L // 传感器3秒无运动判定为静置

    fun setGaitStateCallback(callback: GaitStateCallback?) {
        gaitStateCallback = callback
    }

    private fun updateAttitude(gx: Float, gy: Float, gz: Float) {
        val gMag = sqrt(gx * gx + gy * gy + gz * gz)
        if (gMag < 8f) { currentAttitude = PhoneAttitude.UNKNOWN; return }
        currentAttitude = when {
            kotlin.math.abs(gz) > MotionConfig.LAY_FLAT_Z_THRESHOLD -> PhoneAttitude.LAY_FLAT
            kotlin.math.abs(gx) > 7f || kotlin.math.abs(gy) > 7f -> PhoneAttitude.UPRIGHT
            else -> PhoneAttitude.TILTED
        }
    }

    fun feedAcc(ax: Float, ay: Float, az: Float): Boolean =
        feedAcc(ax, ay, az, System.currentTimeMillis())

    /**
     * 带时间戳的重载：让单元测试可以喂「虚拟时间」的合成波形，
     * 不用真的 sleep 三秒去验证步态判定。
     */
    fun feedAcc(ax: Float, ay: Float, az: Float, now: Long): Boolean {
        gravityX = alpha * gravityX + (1 - alpha) * ax
        gravityY = alpha * gravityY + (1 - alpha) * ay
        gravityZ = alpha * gravityZ + (1 - alpha) * az

        val linX = ax - gravityX
        val linY = ay - gravityY
        val linZ = az - gravityZ
        val mag = sqrt(linX * linX + linY * linY + linZ * linZ)

        updateAttitude(gravityX, gravityY, gravityZ)

        magWindow.add(mag)
        while (magWindow.size > MotionConfig.ADAPT_WINDOW_SIZE) magWindow.removeAt(0)
        updateAdaptiveThreshold()

        // 平躺时阈值放大1.45倍，更难触发
        var effectiveThreshold = currentAdaptiveThreshold
        if (currentAttitude == PhoneAttitude.LAY_FLAT) {
            effectiveThreshold *= MotionConfig.LAY_FLAT_WEIGHT
        }

        var hasWalkingMotion = false
        if (mag > effectiveThreshold) {
            // 关键：只在「从阈值下穿到阈值上」的第一帧记峰。
            //
            // 加速度计以 SENSOR_DELAY_GAME（约 50Hz）回调，一个波峰通常连续 4~6 帧
            // 都高于阈值。原实现每帧都执行 `lastPeakTime = now`，于是下一帧算出的
            // interval 是 20ms、freq 是 50Hz —— 永远落不进 1.4~2.2Hz 的步行窗口，
            // peakIntervals 一个都加不进去，hasStableGait() 恒为 false。
            // 结果就是：步数够、状态机也进了 VERIFYING，但永远判不出「在走路」，
            // 证件 / 社交 / 夜间手电三条提醒一条都不会弹。
            if (!aboveThreshold) {
                aboveThreshold = true
                if (lastPeakTime != 0L) {
                    val interval = now - lastPeakTime
                    val freq = 1000.0 / interval
                    if (freq in MotionConfig.MIN_STEP_FREQ..MotionConfig.MAX_STEP_FREQ) {
                        peakIntervals.add(interval)
                        if (peakIntervals.size > 3) peakIntervals.removeAt(0)
                        lastPeakTime = now
                        hasWalkingMotion = true
                        // 有运动，重置静置计时器
                        staticDetectTimerStart = null
                        gaitStateCallback?.onLongStaticDetected(false)
                        return true
                    }
                }
                lastPeakTime = now
            }
        } else {
            aboveThreshold = false
        }

        // 静置判定：无持续运动，累积静置时间
        if (!hasWalkingMotion) {
            if (staticDetectTimerStart == null) {
                staticDetectTimerStart = now
            }
            val staticDuration = now - staticDetectTimerStart!!
            val isStatic = staticDuration >= SENSOR_STATIC_THRESHOLD
            gaitStateCallback?.onLongStaticDetected(isStatic)
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
        // 至少2步间隔稳定就算真走路（3秒采样窗口内2步足够判定）
        if (peakIntervals.size < 2) return false
        val avg = peakIntervals.average()
        return peakIntervals.all { kotlin.math.abs(it - avg) < 200 }
    }

    fun reset() {
        peakIntervals.clear()
        magWindow.clear()
        lastPeakTime = 0L
        aboveThreshold = false
        // 初始阈值设为1.0，比MIN_PEAK_THRESHOLD(0.6)更保守，
        // 避免前10个样本期间阈值太低导致误触发
        currentAdaptiveThreshold = 1.0f
        currentAttitude = PhoneAttitude.UNKNOWN
        gravityX = 0f; gravityY = 0f; gravityZ = 0f
        // 清空静置状态
        staticDetectTimerStart = null
        gaitStateCallback?.onLongStaticDetected(false)
    }

    fun getCurrentThreshold(): Float = currentAdaptiveThreshold
}
