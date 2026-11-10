package io.github.wumisc.safewalk

import kotlin.math.max
import kotlin.math.min

/**
 * 纯加速度「确实在走路」判定器（纯逻辑，不碰传感器与窗口，便于单测）。
 *
 * 【为什么需要它】
 * 上一轮为治「沙发上自动弹小窗」，把无硬件计步器机型的兜底判定改成
 * 「累计 ≥ max(确认步数×2, 15) 步 + 节律稳定 + 连续步流 15 秒」。
 * 但 [GaitDetector.confirmedStepCount] 是**滑动窗口内连续合格步数**，
 * 队列上限写死 10（`peakIntervals.size > 10` 就 `removeAt(0)`），
 * 于是 `confirmedStepCount()` 永远 ≤ 10，而门槛要 15 —— **条件恒不成立**。
 * 真机日志（2026-11-10 13:48~14:02，Redmi / HyperOS 4，无 ACTIVITY_RECOGNITION）
 * 里 `累计=7/15`、`累计=10/7` 反复出现 968 次，自动跟随全程一次都没激活。
 *
 * 所以判据不能建立在「累计多少步」上，只能建立在**步流的时间形态**上：
 * 连续 8~10 个合格步、步间隔既不过长也不断裂、平均步频落在人类步行区间。
 * 沙发/床上抖动是间歇的（抖几下停几秒）且多半平放（LAY_FLAT 直接拦），
 * 时间形态上过不了这三关。
 *
 * 【三道闸】
 * 1. 姿态：LAY_FLAT（平放/平躺）直接拦。
 * 2. 步数：连续合格步 ≥ [requiredSteps]（上限受采集层窗口 10 步约束）。
 * 3. 形态：相邻步最大间隔 ≤ [maxGapMs]、平均步间隔落在
 *    [minAvgIntervalMs, maxAvgIntervalMs]、整段跨度 ≥ [minSpanMs]。
 */
class WalkConfirmPolicy @JvmOverloads constructor(
    /** 需要的连续合格步数（由用户「连续走 N 步」换算，封顶 [WINDOW_CAP]） */
    private val requiredSteps: Int,
    private val maxGapMs: Long = 1600L,
    private val minAvgIntervalMs: Long = 350L,
    private val maxAvgIntervalMs: Long = 1400L,
    private val minSpanMs: Long = 4000L
) {

    companion object {
        /**
         * 采集层 `peakIntervals` 队列上限。
         *
         * 这个数写在 [GaitDetector.feedAcc] 里（`if (peakIntervals.size > 10) removeAt(0)`），
         * 任何「需要累计超过 10 步」的判据都是死条件，本类不得越过它。
         */
        const val WINDOW_CAP = 10

        /** 纯加速度最低门槛：低于 8 步，两三下抖动就能凑满，没有区分度 */
        const val MIN_REQUIRED_STEPS = 8

        /**
         * 把用户设置换算成纯加速度门槛。
         *
         * 用户设置的是「连续走 N 步」，硬件计步确认按 2 步放行；纯加速度抗噪差，
         * 取 2N 并夹在 [MIN_REQUIRED_STEPS, WINDOW_CAP] 之间。
         * 默认 N=5 → 10 步（窗口满格），恰好是「连续走满一整个窗口」。
         */
        @JvmStatic
        fun requiredStepsFor(prefSteps: Int): Int =
            min(WINDOW_CAP, max(MIN_REQUIRED_STEPS, prefSteps * 2))
    }

    /** 判定结果（含失败原因与量化指标，供日志与单测断言） */
    class Verdict(
        @JvmField val pass: Boolean,
        @JvmField val reason: String,
        @JvmField val steps: Int,
        @JvmField val spanMs: Long,
        @JvmField val maxGapMs: Long,
        @JvmField val avgIntervalMs: Long
    )

    /**
     * @param layFlat  当前姿态是否平放（LAY_FLAT）
     * @param steps    采集层给出的连续合格步数
     * @param stepTs   最近若干个合格步的时间戳（升序，毫秒）
     * @param now      当前时间戳（毫秒）
     */
    @JvmOverloads
    fun evaluate(layFlat: Boolean, steps: Int, stepTs: List<Long>, now: Long = 0L): Verdict {
        if (layFlat) {
            return Verdict(false, "姿态平放", steps, 0L, 0L, 0L)
        }
        if (steps < requiredSteps) {
            return Verdict(false, "连续合格步不足", steps, 0L, 0L, 0L)
        }
        // 只取最近 requiredSteps 个步：更早的步属于上一段步流，掺进来会拉长跨度
        val tail = if (stepTs.size > requiredSteps) stepTs.subList(stepTs.size - requiredSteps, stepTs.size) else stepTs
        if (tail.size < requiredSteps) {
            return Verdict(false, "步流样本不足", steps, 0L, 0L, 0L)
        }
        if (now > 0L && now - tail[tail.size - 1] > maxGapMs) {
            return Verdict(false, "最近一步已过去 " + ((now - tail[tail.size - 1]) / 1000) + "s", steps, 0L, 0L, 0L)
        }

        var maxGap = 0L
        for (i in 1 until tail.size) {
            val gap = tail[i] - tail[i - 1]
            if (gap > maxGap) maxGap = gap
        }
        val span = tail[tail.size - 1] - tail[0]
        val avg = if (tail.size > 1) span / (tail.size - 1) else 0L

        if (maxGap > maxGapMs) {
            return Verdict(false, "步流断裂 " + (maxGap / 1000.0).let { String.format("%.1f", it) } + "s", steps, span, maxGap, avg)
        }
        if (avg < minAvgIntervalMs) {
            return Verdict(false, "步频过快 " + String.format("%.2f", 1000.0 / avg) + "Hz", steps, span, maxGap, avg)
        }
        if (avg > maxAvgIntervalMs) {
            return Verdict(false, "步频过慢 " + String.format("%.2f", 1000.0 / avg) + "Hz", steps, span, maxGap, avg)
        }
        if (span < minSpanMs) {
            return Verdict(false, "连续时长不足 " + (span / 1000) + "s", steps, span, maxGap, avg)
        }
        // 相邻间隔的离散度：用中位数做基准，允许 ±60%，挡掉「一快一慢」交替的抖动
        val sorted = tail.zipWithNext { a, b -> b - a }.sorted()
        val median = sorted[sorted.size / 2]
        val loose = sorted.count { it < median * 0.4 || it > median * 1.6 }
        if (loose * 5 > sorted.size) {   // 超过 20% 的间隔偏离中位数太远
            return Verdict(false, "节律散乱(中位 " + median + "ms)", steps, span, maxGap, avg)
        }
        return Verdict(true, "连续步流可信", steps, span, maxGap, avg)
    }

    /** 供日志展示的门槛 */
    fun requiredSteps(): Int = requiredSteps
}
