package io.github.wumisc.safewalk

import kotlin.math.abs

/**
 * 姿态判定的**滞回 + 驻留**滤波（纯逻辑，可跑 JVM 单测）。
 *
 * ## 修的是什么
 *
 * 5.1.0 的 [GaitDetector.updateAttitude] 是**单帧直判**：
 *
 * ```kotlin
 * currentAttitude = when {
 *     abs(gz) > 8.5f -> LAY_FLAT      // 倾斜 30° 以内就算平放
 *     abs(gx) > 7f || abs(gy) > 7f -> UPRIGHT
 *     else -> TILTED
 * }
 * ```
 *
 * 真机日志（2026-11-14 14:17~14:35）里姿态在 3 秒内翻了两次：
 *
 * ```
 * 14:29:14 姿态=TILTED
 * 14:29:17 姿态=LAY_FLAT     ← 手持看屏幕，被当成平放
 * 14:29:22 姿态=TILTED
 * 14:29:24 [walk] 静置超时，判定停下 → 收起预览
 * ```
 *
 * 全程统计：`LAY_FLAT` 58 次、`TILTED` 71 次、`UPRIGHT` 9 次。
 * 用户握着手机走路，姿态却在三个值之间乱跳。
 *
 * 后果不是「日志难看」：`LAY_FLAT` 是 [WalkConfirmPolicy] 的**第一道硬闸**，
 * 一旦命中就 `return Verdict(false, "姿态平放")`，步流再好也放行不了。
 * 每 3 秒被误判一次平放，等于把激活概率砍掉一大半。
 *
 * ## 两个根因
 *
 * **① 阈值换算成倾角太宽松。** `gz > 8.5` 对应屏幕法线与水平面夹角
 * `acos(8.5/9.8) ≈ 30°`。手持低头看屏幕的常见姿态是 30~50° 仰角，
 * 恰好在阈值边界上，微小的握持变化就会跨过。
 *
 * **② 没有滞回。** 单一阈值意味着「在阈值附近抖动 → 状态高频翻转」。
 * 正确做法是两个阈值：进入用严的、退出用松的，中间留一段「保持原状」的死区。
 *
 * **③ 没有驻留时间。** 即使有滞回，一帧异常（磕到桌角、手臂撞到门框）
 * 仍会瞬间改变状态。姿态是**慢变量**，物理上不可能 100ms 内从竖持变平放，
 * 所以必须要求候选状态持续 [DWELL_MS] 才承认。
 *
 * ## 阈值为什么是 8.8 / 8.0
 *
 * - 进入 LAY_FLAT：`gz > 8.8` → 倾角 < 26°，只有真正平放/平躺才够。
 * - 退出 LAY_FLAT：`gz < 8.0` → 倾角 > 35°，留 9° 死区，
 *   覆盖手持微抖与加速度计零偏（消费级 MEMS 零偏可达 ±0.2 m/s²）。
 *
 * 三星 S24U 与小米旗舰的加速度计零偏都在这个量级内，同一套阈值通用。
 */
class AttitudeStabilizer(
    /** 进入 LAY_FLAT 的重力 z 分量阈值（越大越难判平放） */
    private val enterFlatZ: Float = ENTER_FLAT_Z,
    /** 退出 LAY_FLAT 的阈值（必须小于 [enterFlatZ] 才构成滞回） */
    private val exitFlatZ: Float = EXIT_FLAT_Z,
    /** 判定竖直握持的 x/y 分量阈值 */
    private val uprightXy: Float = UPRIGHT_XY,
    /** 候选姿态需要持续的时长 */
    private val dwellMs: Long = DWELL_MS
) {

    companion object {
        /** 进入 LAY_FLAT：gz > 8.8（倾角 < 26°） */
        const val ENTER_FLAT_Z = 8.8f
        /** 退出 LAY_FLAT：gz < 8.0（倾角 > 35°） */
        const val EXIT_FLAT_Z = 8.0f
        /** 竖直握持：x 或 y 分量超过 7.2（≈ 倾角 > 47°） */
        const val UPRIGHT_XY = 7.2f
        /** 候选姿态驻留时长：真人握持姿态的变化周期远大于此 */
        const val DWELL_MS = 700L
        /** 重力模长下限：低于它说明还在收敛，返回 UNKNOWN 不参与判定 */
        const val MIN_GRAVITY = 8.0f
    }

    /** 当前已生效（稳定）的姿态 */
    var attitude: PhoneAttitude = PhoneAttitude.UNKNOWN
        private set

    /** 正在候选、尚未驻留满的姿态 */
    private var candidate: PhoneAttitude = PhoneAttitude.UNKNOWN
    /** 候选姿态的起始时刻；0 = 无候选 */
    private var candidateSince: Long = 0L

    /**
     * 喂一帧重力向量（**已低通滤波**，不是原始加速度）。
     *
     * @return 滤波后的稳定姿态；重力未收敛时返回 [PhoneAttitude.UNKNOWN]
     */
    fun feed(gx: Float, gy: Float, gz: Float, now: Long): PhoneAttitude {
        val mag = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        if (mag < MIN_GRAVITY) {
            // 重力还在收敛（启动瞬间 / 自由落体），不参与判定，也不清空已有状态：
            // 清空会让「拿起来晃一下」把稳定姿态打回 UNKNOWN，反而更容易误判
            return attitude
        }

        val raw = rawOf(gx, gy, gz)

        // 首帧：直接采纳，不然要等 700ms 才有第一个结论
        if (attitude == PhoneAttitude.UNKNOWN) {
            attitude = raw
            candidate = PhoneAttitude.UNKNOWN
            candidateSince = 0L
            return attitude
        }

        if (raw == attitude) {
            // 与当前一致：清掉候选，重置计时
            candidate = PhoneAttitude.UNKNOWN
            candidateSince = 0L
            return attitude
        }

        // 出现不同读数：开始/继续计时，驻留够久才真正切换
        if (candidate != raw) {
            candidate = raw
            candidateSince = now
        }
        if (candidateSince == 0L) candidateSince = now
        if (now - candidateSince >= dwellMs) {
            attitude = raw
            candidate = PhoneAttitude.UNKNOWN
            candidateSince = 0L
        }
        return attitude
    }

    /** 连续多帧都没变化时也可以直接问，不用重复 feed */
    fun current(): PhoneAttitude = attitude

    /** 复位（停止监听 / 重新注册传感器时调用） */
    fun reset() {
        attitude = PhoneAttitude.UNKNOWN
        candidate = PhoneAttitude.UNKNOWN
        candidateSince = 0L
    }

    /**
     * 单帧原始判定，带滞回。
     *
     * 注意 [PhoneAttitude.LAY_FLAT] 的两个分支：如果当前已经是平放，
     * 用更松的 [exitFlatZ] 才允许退出 —— 这就是滞回的核心。
     */
    private fun rawOf(gx: Float, gy: Float, gz: Float): PhoneAttitude {
        val az = abs(gz)
        if (attitude == PhoneAttitude.LAY_FLAT) {
            // 已经在平放：只有明显翘起来才退出
            if (az >= exitFlatZ) return PhoneAttitude.LAY_FLAT
        } else {
            // 不在平放：必须足够平才进入
            if (az > enterFlatZ) return PhoneAttitude.LAY_FLAT
        }
        if (abs(gx) > uprightXy || abs(gy) > uprightXy) return PhoneAttitude.UPRIGHT
        return PhoneAttitude.TILTED
    }
}
