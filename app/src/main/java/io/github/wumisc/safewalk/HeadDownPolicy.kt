package io.github.wumisc.safewalk

import kotlin.math.max

/**
 * 低头提醒策略（参考 Pixel 数字健康的「Heads Up」：走路时一直盯着手机就提醒抬头）。
 *
 * 纯逻辑、无 `android.*` 依赖，便于 JVM 单测。
 *
 * ## 为什么这样判
 *
 * 只靠「手机倾斜」会误报：放在桌上、躺床上刷手机都可能倾斜。所以必须同时满足：
 * 1. **已确认在走路**（调用方传 gaitWalking，即已过全部防误触闸门）；
 * 2. **屏幕亮着**（人在看手机，而不是揣兜里 / 放包里）；
 * 3. **屏幕朝上偏水平**（tilt 小于阈值），持续达到 holdMs；
 * 4. 距离上次提醒超过 cooldownMs。
 *
 * 任一条件不成立就清零计时，绝不「攒着」——避免断续低头被累加成一次误报。
 */
object HeadDownPolicy {

    /** 屏幕法线与水平面夹角上限：超过它视为手机接近竖直（正常握持），不算低头 */
    const val DEFAULT_TILT_MAX_DEG = 45f

    /** 持续低头多久才提醒（太短会频繁打扰） */
    const val DEFAULT_HOLD_MS = 8_000L

    /** 两次提醒的最小间隔 */
    const val DEFAULT_COOLDOWN_MS = 180_000L

    class State @JvmOverloads constructor(
        /** 本轮「开始低头」的时间戳；0 表示当前没在低头 */
        @JvmField var downSince: Long = 0L,
        /** 上次真正提醒的时间戳 */
        @JvmField var lastFireTs: Long = 0L
    )

    /**
     * @return true 表示这一帧应触发提醒（调用方负责发通知）
     */
    @JvmStatic
    @JvmOverloads
    fun evaluate(
        enabled: Boolean,
        walking: Boolean,
        screenOn: Boolean,
        tiltDeg: Float,
        now: Long,
        state: State,
        tiltMaxDeg: Float = DEFAULT_TILT_MAX_DEG,
        holdMs: Long = DEFAULT_HOLD_MS,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean {
        // 时钟回拨：清掉基线，避免用未来的时间戳算出恒真 / 恒假
        if (now < state.downSince || now < state.lastFireTs) {
            state.downSince = 0L
            state.lastFireTs = 0L
            return false
        }

        val down = enabled && walking && screenOn && tiltDeg >= 0f && tiltDeg <= tiltMaxDeg
        if (!down) {
            state.downSince = 0L
            return false
        }

        if (state.downSince == 0L) {
            state.downSince = now
            return false
        }
        if (now - state.downSince < max(0L, holdMs)) return false
        if (state.lastFireTs != 0L && now - state.lastFireTs < max(0L, cooldownMs)) return false

        // 触发后重新计时：持续低头也不会每个 tick 都越过门槛
        state.downSince = 0L
        state.lastFireTs = now
        return true
    }

    /** 离开行走 / 关闭开关时显式复位 */
    @JvmStatic
    fun reset(state: State) {
        state.downSince = 0L
    }
}
