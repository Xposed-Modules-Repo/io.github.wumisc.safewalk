package io.github.wumisc.safewalk

import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 一帧加速度样本。在 CrashDetector 既有的 onSensorChanged 回调内构造，
 * 不新增传感器监听器、不新增 wake lock（省电铁律）。
 */
data class CrashSample(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val timestampMs: Long
)

/**
 * 撞击事件：跨进程只传标量（无卡内容、无隐私）。
 *
 * [shouldAlert] 是融合层的最终结论，CrashDetector 只有判定为真才发广播。
 */
data class CrashEvent(
    val timestampMs: Long = 0L,
    val magnitude: Float = 0f,
    val deltaV: Float = 0f,
    val poseAngle: Float = 90f,
    val screenOff: Boolean = false,
    val fallExcluded: Boolean = false,
    val confidence: Float = 0f,
    val sensitivity: Int = -1,
    val eventId: String = ""
) {
    /** 置信度阈值：低于它一律当误报，交给 60s 内的下一次采样 */
    fun shouldAlert(): Boolean = !fallExcluded && confidence >= ALERT_CONFIDENCE

    companion object {
        const val ALERT_CONFIDENCE = 0.42f
    }
}

/**
 * 碰撞融合二级判定（纯函数，可 JVM 单测）。
 *
 * v1.0 的 `ALPHA=0.8 低通 + 2 连续采样 + 60s 冷却 + 25/20/15 m/s²` 是**第一级门控**，
 * 本类是叠加在其上的第二级：速度突变（同一路加速度短窗积分）+ 姿态 + 屏幕 + 跌倒排除。
 *
 * 全部计算 O(n)，n≤10 帧，在既有传感器回调里完成，不引入额外线程或轮询。
 */
object CrashFusionPolicy {
    /** 融合窗口：只取最近 200ms，避免长窗把撞击峰值稀释 */
    const val FUSION_WINDOW_MS = 200L
    /** 重力加速度 */
    const val GRAVITY = 9.8f
    /** 速度突变达到该值视为充分证据（m/s） */
    const val DELTA_V_FULL = 3.0f
    /** 跌倒排除的速度上限：低于它且姿态接近平躺，更可能是"手机摔了"不是"人撞了" */
    const val FALL_DELTA_V_MAX = 1.2f
    /** 姿态夹角小于该值视为接近平躺（0°=屏幕朝天，90°=竖直握持） */
    const val FALL_POSE_MAX_DEG = 40f
    /** 峰值极高时直接认为不是跌倒（真实撞击） */
    const val HARD_HIT_PEAK = 25f

    /**
     * 由同一路加速度计积分出的粗速度估计：Δv = Σ|a_dynamic|·Δt。
     *
     * 只用于短窗（≤200ms），不做位置积分、不引入陀螺仪；动态量取
     * `| |a| - g |`，静止握持时为 0，撞击时为真实突变量。
     */
    @JvmStatic
    fun deltaV(samples: List<CrashSample>): Float {
        if (samples.size < 2) return 0f
        var sum = 0.0
        for (i in 1 until samples.size) {
            var dt = samples[i].timestampMs - samples[i - 1].timestampMs
            if (dt <= 0L) continue
            if (dt > FUSION_WINDOW_MS) dt = FUSION_WINDOW_MS
            sum += dynamicMagnitude(samples[i]) * dt / 1000.0
        }
        return sum.toFloat()
    }

    /** 窗口内动态加速度峰值（m/s²，"去重力后"的突变量） */
    @JvmStatic
    fun peak(samples: List<CrashSample>): Float {
        var max = 0f
        for (s in samples) {
            val d = dynamicMagnitude(s)
            if (d > max) max = d
        }
        return max
    }

    /**
     * 重力向量与屏幕法线（z 轴）的夹角：0°=平放，90°=竖直握持。
     * 用窗口内首帧样本估计"撞击前姿态"，避免撞击瞬间姿态被甩飞干扰。
     */
    @JvmStatic
    fun poseAngleDeg(samples: List<CrashSample>): Float {
        if (samples.isEmpty()) return 90f
        val s = samples.first()
        val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        if (mag < 1f) return 90f
        val ratio = (s.az / mag).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(ratio).toDouble()).toFloat()
    }

    /**
     * 跌倒排除：低速度突变 + 接近平躺 + 峰值不够高 ⇒ 更可能是"手机掉地上"。
     *
     * 强撞击（峰值≥25 或 Δv≥3.0）一律不排除，避免把真实事故漏掉。
     */
    @JvmStatic
    fun excludeFall(samples: List<CrashSample>, poseAngleDeg: Float, deltaV: Float): Boolean {
        if (samples.size < 2) return true
        if (peak(samples) >= HARD_HIT_PEAK || deltaV >= DELTA_V_FULL) return false
        return deltaV < FALL_DELTA_V_MAX && poseAngleDeg < FALL_POSE_MAX_DEG
    }

    /**
     * 融合判定入口。
     *
     * @param threshold 第一级门控当前档位阈值（25/20/15），由 CrashDetector 传入，
     *                  保持两个门控使用同一档位，避免配置漂移。
     */
    @JvmStatic
    fun evaluate(
        samples: List<CrashSample>,
        poseAngleDeg: Float,
        screenOff: Boolean,
        threshold: Float
    ): CrashEvent {
        val dv = deltaV(samples)
        val pk = peak(samples)
        val fall = excludeFall(samples, poseAngleDeg, dv)

        // 静息/跌倒：置信度直接归零，不会进入广播链路
        val safeThreshold = if (threshold <= 0f) GRAVITY else threshold
        val confidence = if (fall) {
            0f
        } else {
            val magScore = ((pk - safeThreshold) / safeThreshold).coerceIn(0f, 1f) * 0.40f
            val dvScore = (dv / DELTA_V_FULL).coerceIn(0f, 1f) * 0.35f
            // 撞后姿态偏平躺/倾斜（夹角小）更符合"人倒地"，加权更高
            val poseScore = if (poseAngleDeg < 60f) 0.15f else 0.05f
            // 撞击瞬间屏幕熄灭是辅助证据（车祸时常被甩灭），只占小权重
            val screenScore = if (screenOff) 0.10f else 0f
            (magScore + dvScore + poseScore + screenScore).coerceIn(0f, 1f)
        }
        return CrashEvent(
            timestampMs = System.currentTimeMillis(),
            magnitude = pk,
            deltaV = dv,
            poseAngle = poseAngleDeg,
            screenOff = screenOff,
            fallExcluded = fall,
            confidence = confidence
        )
    }

    private fun dynamicMagnitude(s: CrashSample): Float {
        val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        return abs(mag - GRAVITY)
    }
}
