package io.github.wumisc.safewalk

/**
 * 诊断日志节流判定（**纯逻辑，不依赖 android.\***，可跑 JVM 单测）。
 *
 * ## 为什么要有它
 *
 * 4.4.0 及之前，节流状态是散落在各个业务类里的一堆 `lastXxxDiagTs`
 * 时间戳字段，每个调用点自己写 `if (now - last > N) { last = now; log(...) }`。
 * 只要新加一个日志点忘了节流，高频回调里就会瞬间刷屏。
 *
 * 真机日志 2026-11-11 19:11:38：`WalkPreviewService.hardwareStepConfirm()`
 * 在 `onSensorChanged`（SENSOR_DELAY_UI，每秒几十帧）里被每帧调用，
 * 而它的 `n == 0` 分支直接 `DiagLog.log(...)` 没有节流 —— 同一秒内刷出
 * 80+ 条一模一样的 `[gait] ⛔ 硬件计步器 10 秒 0 步`。后果不只是难读：
 * [DiagLog] 的文件超过 512KB 会整文件删、内存环形缓冲只有 400 行，
 * 洪泛会把 19:05~19:11 的真实现场全部冲掉，日志尾部断在洪泛里。
 *
 * 现在所有节流走同一套判定：按 key 隔离状态，原因变化时可以更快重发
 * （方便看状态跳变），原因不变时按稳定间隔发。业务类不再持有时间戳字段。
 *
 * ## 时钟
 *
 * 用调用方给的时间戳，不自己读系统时钟 —— 便于单测注入，也避免
 * `System.currentTimeMillis()` 被用户改时间后倒退导致「永不打印」。
 * 检测到 `now < lastTs`（时钟回拨）时重置状态并允许发出。
 */
object DiagThrottle {

    /** 某个节流 key 的状态。调用方（[DiagLog]）负责按 key 存取与更新。 */
    class State @JvmOverloads constructor(
        @JvmField var lastTs: Long = 0L,
        @JvmField var lastReason: String = ""
    )

    /**
     * 本条是否应当发出。
     *
     * @param state      该 key 的状态；发出后由调用方用 [commit] 更新
     * @param reason     本条的原因标识，变化时可更快重发
     * @param now        当前时间戳（毫秒，与 state.lastTs 同一时钟）
     * @param changedMs  原因变化时的最小间隔
     * @param stableMs   原因不变时的最小间隔
     */
    @JvmStatic
    fun shouldEmit(
        state: State,
        reason: String,
        now: Long,
        changedMs: Long,
        stableMs: Long
    ): Boolean {
        if (now < state.lastTs) return true          // 时钟回拨：重置并放行
        if (state.lastTs == 0L) return true          // 首条必发
        val interval = if (reason != state.lastReason) changedMs else stableMs
        return now - state.lastTs >= interval
    }

    /** 发出后更新状态。必须与 [shouldEmit] 成对调用。 */
    @JvmStatic
    fun commit(state: State, reason: String, now: Long) {
        state.lastTs = now
        state.lastReason = reason
    }
}
