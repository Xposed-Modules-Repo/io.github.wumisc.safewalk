package io.github.wumisc.safewalk

/**
 * 硬件计步通道的「健康度」判定（**纯逻辑，不依赖 android.\***，可跑 JVM 单测）。
 *
 * ## 修的是什么
 *
 * 5.1.0 及以前，[WalkPreviewService.hardwareStepConfirm] 把硬件计步器当成
 * **二元状态**：注册成功 → 用它当闸门；注册失败 → 退回纯加速度。
 * 但真机日志（2026-11-14 14:17~14:35，Redmi / HyperOS 4）证明还有第三、
 * 第四种状态，而它们都被错误地当成「计步器正常工作、只是你没走」：
 *
 * ```
 * 18 分钟内 [ACTIVE] ⏸ 达标但被拦截：硬件计步确认=拦  出现 40+ 次
 * [gait] ⛔ 硬件计步器 10 秒 0 步（真走路不可能不出数）  出现 27 次
 * [ACTIVE] ✅ 激活行走模式                              0 次
 * ```
 *
 * 也就是说：**自动跟随全程一次都没弹出过**。用户走了十几分钟，界面毫无反应。
 *
 * ## 根因：`TYPE_STEP_COUNTER` 不是「每走一步回调一次」
 *
 * `TYPE_STEP_COUNTER` 是**累计型**传感器，`onSensorChanged` 的触发时机由 HAL 决定。
 * 在小米/三星这类 ROM 上，它的实际上报间隔是
 * **10~60 秒一次**（省电策略会把高频回调合并），而代码用「最近 10 秒内步数」
 * 判定有没有在走路 —— 只要上报间隔超过 10 秒，这个窗口就**恒为 0**。
 *
 * 于是形成死锁：
 *   1. 传感器确实出过数（`everSteppedTotal > 0`），所以不会走「从未出数」的兜底分支；
 *   2. 但 10 秒窗口永远空，命中 `n == 0` 分支，直接 `return false`；
 *   3. `return false` 之后**再也走不到** `accelOnlyConfirm()` —— 纯加速度兜底形同虚设。
 *
 * 这不是「判得太严」，是**判据本身不成立**：拿一个 30 秒粒度的信号做 10 秒窗口判定。
 *
 * ## 四档健康度
 *
 * | 档位 | 判定条件 | 硬件计步能否当闸门 |
 * |---|---|---|
 * | [Channel.NONE] | 本机没有计步传感器 | 不能，走纯加速度 |
 * | [Channel.DENIED] | 有传感器但注册失败 / 从未回调 | 不能，走纯加速度 |
 * | [Channel.STALE] | 回调过，但最近一次已超过 [STALE_AFTER_MS] | 不能，走纯加速度 |
 * | [Channel.LIVE] | 最近 [STALE_AFTER_MS] 内有回调 | 能 |
 *
 * 关键在 [Channel.STALE]：**「刚才还活着、现在没动静」不等于「你没走路」**，
 * 只等于「这个信号现在不可用」。这时必须降级，而不是把功能判死。
 *
 * ## 为什么阈值是 45 秒
 *
 * 三星 One UI 与小米 HyperOS 的后台传感器限流档位实测在 20~30 秒之间；
 * 45 秒给了 1.5 倍余量，既不会把「真停下来的手机」误判成 LIVE
 * （那时 `everSteppedTotal` 也不再增长，另有 [isStalled] 兜底），
 * 也不会因为单次限流抖动就反复降级。
 */
object StepHealthPolicy {

    /** 计步通道健康度 */
    enum class Channel {
        /** 本机没有计步传感器（`getDefaultSensor` 返回 null） */
        NONE,
        /** 有传感器但注册被拒，或注册后从未收到任何回调 */
        DENIED,
        /** 收到过回调，但最近一次距今已超过 [STALE_AFTER_MS]（被系统限流/休眠） */
        STALE,
        /** 最近 [STALE_AFTER_MS] 内有回调，可以当闸门用 */
        LIVE
    }

    /** 超过这个时长没有回调，就认为通道暂时不可用（不是「没走路」） */
    const val STALE_AFTER_MS = 45_000L

    /**
     * 自适应窗口的上下限。
     *
     * 硬件计步上报本身就是 10~60 秒粒度，用一个固定 10 秒窗口去数步数没有意义。
     * 这里按「实际观测到的回调间隔」放大窗口：至少 [MIN_WINDOW_MS]，
     * 至多 [MAX_WINDOW_MS]（再大就会把「刚停下」也容纳进来，失去判停能力）。
     */
    const val MIN_WINDOW_MS = 10_000L
    const val MAX_WINDOW_MS = 60_000L

    /**
     * 判定通道健康度。
     *
     * @param hasSensor         `getDefaultSensor(TYPE_STEP_*)` 是否非空
     * @param registered        `registerListener` 是否返回 true
     * @param everStepped       本次注册以来是否累计到过 ≥1 步
     * @param lastCallbackAgeMs 距最近一次回调的毫秒数；从未回调传 [Long.MAX_VALUE]
     */
    @JvmStatic
    fun channelOf(
        hasSensor: Boolean,
        registered: Boolean,
        everStepped: Boolean,
        lastCallbackAgeMs: Long
    ): Channel {
        if (!hasSensor) return Channel.NONE
        if (!registered) return Channel.DENIED
        if (!everStepped || lastCallbackAgeMs == Long.MAX_VALUE) return Channel.DENIED
        // 时钟回拨 / 负数一律按「刚刚回调过」处理：宁可多拦一次，也不因异常值放行
        if (lastCallbackAgeMs < 0L) return Channel.LIVE
        return if (lastCallbackAgeMs > STALE_AFTER_MS) Channel.STALE else Channel.LIVE
    }

    /**
     * 按观测到的回调间隔算一个合适的统计窗口。
     *
     * @param avgCallbackIntervalMs 最近若干次回调的平均间隔；未知传 0
     */
    @JvmStatic
    fun windowMs(avgCallbackIntervalMs: Long): Long {
        if (avgCallbackIntervalMs <= 0L) return MIN_WINDOW_MS
        // 两倍间隔：保证至少能容纳两次回调，不会因为刚好错过一次就数出 0
        val want = avgCallbackIntervalMs * 2L
        return want.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }

    /**
     * 硬件计步能否用来「否决」这一次激活。
     *
     * **只有 [Channel.LIVE] 才有否决权**。其余三档一律返回 false，
     * 让调用方落到纯加速度判定（[WalkConfirmPolicy]），而不是直接判死。
     *
     * @param channel        [channelOf] 的结论
     * @param stepsInWindow  统计窗口内的硬件步数
     * @param minSteps       放行所需的最少步数
     */
    @JvmStatic
    fun canVeto(channel: Channel, stepsInWindow: Int, minSteps: Int): Boolean {
        return channel == Channel.LIVE && stepsInWindow < minSteps
    }

    /** 给日志用的一句话说明，避免用户看到「0 步」就以为是自己没走 */
    @JvmStatic
    fun describe(channel: Channel): String = when (channel) {
        Channel.NONE -> "本机无计步传感器 → 纯加速度判定"
        Channel.DENIED -> "计步器未注册/从未回调（多半缺「身体活动」权限）→ 纯加速度判定"
        Channel.STALE -> "计步器回调已停 > ${STALE_AFTER_MS / 1000}s（被系统限流）→ 暂按纯加速度判定"
        Channel.LIVE -> "计步器在线，按硬件步数判定"
    }
}
