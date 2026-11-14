package io.github.wumisc.safewalk

import kotlin.math.max

/**
 * 「走太久了」策略：累计行走时长达到阈值就提醒一次。
 *
 * 纯逻辑、无 `android.*` 依赖，便于 JVM 单测。同一个实现服务两条提醒，
 * 靠参数区分，不复制第二份判定逻辑：
 *
 * | 提醒 | walkMs | pauseGraceMs | cooldownMs |
 * |---|---|---|---|
 * | 久走提醒（白天/任意时段） | 45 分钟 | 3 分钟 | 90 分钟 |
 * | 夜间独行提醒 | 10 分钟 | 2 分钟 | 30 分钟 |
 *
 * ## 为什么是「累计」而不是「连续」
 *
 * 严格的连续判定在真实路况下几乎不可能达标：等一个 30 秒红灯，步态判停就会把
 * 连续计时清零，走一小时也攒不到 45 分钟。所以这里用「累计 + 暂停宽限」：
 * 停下不超过 [pauseGraceMs] 视为途中停顿（红灯、系鞋带、看路牌），时长保留；
 * 超过宽限才认为这一趟结束了，归零重算。
 *
 * ## 为什么 tick 间隔要钳制
 *
 * 调用方是 1 秒一次的巡检。设备休眠、进程被冻结、主线程卡顿都会让两次调用
 * 相隔几分钟甚至几小时。若直接按 `now - lastTickTs` 累加，一次唤醒就能凭空
 * 攒出几十分钟，刚起步就弹「你走了很久」。所以单次结算上限 [MAX_TICK_MS]。
 *
 * ## 冷却期为什么不走表
 *
 * 冷却期内如果继续累加，冷却一结束就会拿「冷却期间攒的时长」立刻再弹一次，
 * 表现为「刚提醒完 30 分钟又提醒」。这里在冷却期直接把累计清零，
 * 让下一次提醒必须重新走满阈值。
 */
object LongWalkPolicy {

    /** 单次结算的时长上限：tick 是 1s，给 5s 余量，再多就是休眠/卡顿，不算真实行走 */
    const val MAX_TICK_MS = 5_000L

    /**
     * 「尚无基线」哨兵值。
     *
     * 不能用 0：调用方第一帧的 now 完全可能就是 0（测试里就是），
     * 那样 `lastTickTs == 0L` 会一直命中，前两帧的时长被白白丢掉，
     * 表现为「累计慢了一秒」这种很难查的偏移。用 Long.MIN_VALUE 与任何真实时间戳都不冲突。
     */
    private const val NO_BASELINE = Long.MIN_VALUE

    /** 久走提醒默认阈值：连续出行 45 分钟 */
    const val DEFAULT_WALK_MS = 45 * 60 * 1000L

    /** 久走提醒默认暂停宽限：停下 3 分钟内仍算同一趟 */
    const val DEFAULT_PAUSE_GRACE_MS = 3 * 60 * 1000L

    /** 久走提醒默认冷却：同一趟出行最多打扰一次半 */
    const val DEFAULT_COOLDOWN_MS = 90 * 60 * 1000L

    /** 夜间独行提醒默认阈值：夜间步行 10 分钟 */
    const val NIGHT_WALK_MS = 10 * 60 * 1000L

    /** 夜间独行默认暂停宽限：夜间停顿更短，2 分钟 */
    const val NIGHT_PAUSE_GRACE_MS = 2 * 60 * 1000L

    /** 夜间独行默认冷却：30 分钟 */
    const val NIGHT_COOLDOWN_MS = 30 * 60 * 1000L

    class State @JvmOverloads constructor(
        /** 上次 tick 的时间戳；[NO_BASELINE] 表示尚未建立基线 */
        @JvmField var lastTickTs: Long = NO_BASELINE,
        /** 本趟已累计的行走时长（毫秒） */
        @JvmField var accumMs: Long = 0L,
        /** 本次「开始停下」的时间戳；0 表示当前正在走 */
        @JvmField var pauseSince: Long = 0L,
        /** 上次真正提醒的时间戳 */
        @JvmField var lastFireTs: Long = 0L
    ) {
        fun reset() {
            lastTickTs = NO_BASELINE
            accumMs = 0L
            pauseSince = 0L
        }
    }

    /**
     * @param walking 调用方已过全部防误触闸门的「确认真在走」状态
     * @return true 表示这一帧应触发提醒（调用方负责发通知）
     */
    @JvmStatic
    @JvmOverloads
    fun evaluate(
        enabled: Boolean,
        walking: Boolean,
        now: Long,
        state: State,
        walkMs: Long = DEFAULT_WALK_MS,
        pauseGraceMs: Long = DEFAULT_PAUSE_GRACE_MS,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean {
        // 时钟回拨：整段基线作废，避免用未来的时间戳算出恒真 / 恒负
        if (now < state.lastTickTs || now < state.lastFireTs) {
            state.lastTickTs = now
            state.accumMs = 0L
            state.pauseSince = 0L
            return false
        }

        val raw = if (state.lastTickTs == NO_BASELINE) 0L else now - state.lastTickTs
        state.lastTickTs = now
        // 一帧最多结算 MAX_TICK_MS：休眠唤醒 / 主线程卡顿不应算作真实行走
        val dt = raw.coerceIn(0L, MAX_TICK_MS)

        if (!enabled) {
            state.accumMs = 0L
            state.pauseSince = 0L
            return false
        }

        if (state.lastFireTs != 0L && now - state.lastFireTs < max(0L, cooldownMs)) {
            // 冷却期不走表，理由见类注释
            state.accumMs = 0L
            state.pauseSince = 0L
            return false
        }

        if (walking) {
            state.accumMs += dt
            state.pauseSince = 0L
        } else {
            if (state.pauseSince == 0L) state.pauseSince = now
            if (now - state.pauseSince > max(0L, pauseGraceMs)) {
                // 这一趟结束：清零并保持「已停」基线，重新走时从头累计
                state.accumMs = 0L
            }
        }

        if (state.accumMs >= max(0L, walkMs)) {
            state.accumMs = 0L
            state.pauseSince = 0L
            state.lastFireTs = now
            return true
        }
        return false
    }
}
