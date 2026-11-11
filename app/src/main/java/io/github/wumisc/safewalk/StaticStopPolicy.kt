package io.github.wumisc.safewalk

/**
 * 「静置超时 → 收起预览」的会话基线判定（**纯逻辑，不依赖 android.\***，可跑 JVM 单测）。
 *
 * ## 修的是什么
 *
 * 4.4.0 的 `gaitTick` 只看 `lastStepTs != 0 && now - lastStepTs > stopMs` 就收起预览。
 * 但服务被保活机制重新拉起（或 START_STICKY 重建）时，[startGait] 因为
 * `gaitListener != null` 直接 return，**不会重置 lastStepTs** —— 于是沿用上一
 * 会话残留的时间戳，服务刚复活 8 秒就被判「静置超时」把预览收掉。
 *
 * 真机日志 2026-11-11：
 *   19:06:49 [FGS] 前台服务已提起｜type=camera      ← 保活复活成功
 *   19:06:57 [walk] 静置超时，判定停下 → 收起预览    ← 8 秒后又被收掉
 * 用户看到的正是上一轮反馈的「通知消失，就证明行走模式停止运行了」。
 *
 * ## 判定
 *
 * 引入会话基线 `sessionStartTs`（服务每次被显式启动/复活时刷新）：
 *   · 旧会话残留的 `lastStepTs < sessionStartTs` 一律作废，不再当判停依据；
 *   · 本会话还没有合格步时，给 [graceMs] 宽限，宽限过后才允许收起一次空预览；
 *   · 本会话已走出合格步后，回到正常的 `stopMs` 静置判停；
 *   · 任何时钟回拨都不判停（宁可多挂一会儿，也不要错收）。
 *
 * 防误触判定本身（硬件计步确认 / 姿态节律闸门）**不受影响**：这里只决定
 * 「已经显示着的预览什么时候收起」，不决定「要不要显示」。
 */
object StaticStopPolicy {

    /** 会话启动宽限：复活后至少给这么久，避免刚拉起就收 */
    const val SESSION_GRACE_MS = 30_000L

    @JvmStatic
    @JvmOverloads
    fun shouldStop(
        lastStepTs: Long,
        sessionStartTs: Long,
        now: Long,
        stopMs: Long,
        graceMs: Long = SESSION_GRACE_MS
    ): Boolean {
        // 从未有过合格步：不判停（与旧行为一致，避免每秒刷一条收起日志）
        if (lastStepTs == 0L) return false
        // 时钟回拨：一律不判停
        if (now < lastStepTs || (sessionStartTs > 0L && now < sessionStartTs)) return false

        if (sessionStartTs > 0L && lastStepTs < sessionStartTs) {
            // 旧会话残留时间戳：作废。本会话还没走起来，给宽限；
            // 宽限过后允许收起一次空预览，避免复活后永远挂着一个没有画面的窗。
            return now - sessionStartTs > graceMs
        }
        // 刚进入本会话不久：宽限期内不收
        if (sessionStartTs > 0L && now - sessionStartTs < graceMs) return false
        // 正常静置判停
        return now - lastStepTs > stopMs
    }
}
