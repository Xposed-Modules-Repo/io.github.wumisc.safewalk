package io.github.wumisc.safewalk

/**
 * 「省电计划」：SystemUI Hook 侧定时轮询的自适应节拍（纯逻辑，便于 JVM 单测）。
 *
 * ## 背景
 *
 * Hook 运行在 SystemUI 进程，`tickRunnable` 每 2 秒醒一次，跑 [check] + updateOverlay。
 * 这是**常驻**轮询：只要总开关开着，一天 86400 秒就要醒 4.3 万次。
 * 但真正需要 2 秒级响应的只有两种情况：
 *   · 用户正在走 —— 需要及时判定步数达标、触发提醒；
 *   · 路况预览正在显示 —— 需要及时下发显隐指令。
 * 其余时间（开机解锁后放口袋里不动）没有任何事要做，2 秒轮询纯属空转耗电。
 *
 * ## 关键前提：计步是事件驱动的，不靠轮询
 *
 * 计步来自加速度计 / TYPE_STEP_DETECTOR 的 `onSensorChanged` 回调，步子一来
 * 立刻累加，与 tick 无关。所以「放慢 tick」**不会**增加走路检测的延迟 ——
 * 它只是推迟了「每 2 分钟一条日志」「静置判定」这类不急的周期性杂活。
 * 走路一旦开始，下一次 tick（最多 5 秒后）就会看到步数并切回快节拍。
 *
 * ## 三档
 *
 * | 状态 | 节拍 | 理由 |
 * | --- | --- | --- |
 * | 总开关关 | [IDLE_OFF_MS] | 只清状态，5 秒足够 |
 * | 开但静置/无预览 | [IDLE_ON_MS] | 无事可做，放慢到 5 秒 |
 * | 正在走 或 预览显示中 | [ACTIVE_MS] | 需要及时响应，维持 2 秒 |
 *
 * 这样静止场景唤醒次数直接砍半以上，而走路体验零变化。
 */
object PowerPolicy {

    const val ACTIVE_MS = 2_000L
    const val IDLE_ON_MS = 5_000L
    const val IDLE_OFF_MS = 5_000L

    /**
     * 算下一次 tick 的间隔。
     *
     * @param masterOn 总开关
     * @param walking Hook 侧最近一次 check 判定的「正在走」
     * @param previewActive 路况预览是否正在显示（含手动预览窗口）
     */
    @JvmStatic
    fun tickIntervalMs(masterOn: Boolean, walking: Boolean, previewActive: Boolean): Long {
        if (!masterOn) return IDLE_OFF_MS
        return if (walking || previewActive) ACTIVE_MS else IDLE_ON_MS
    }
}
