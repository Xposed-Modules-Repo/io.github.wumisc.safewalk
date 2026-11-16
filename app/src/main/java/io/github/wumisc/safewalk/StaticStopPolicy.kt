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
 *
 * ## 5.3.8 判停时钟解耦（合格步门槛 ≠ 步活动）
 *
 * `shouldStop` 的第一个入参 `lastStepTs` 是「凑够 `gaitConfirmSteps` 个合格步」时
 * 才刷新的时间戳，会被防误触门槛卡住：真机把确认步数设成 8，而 2.5s 峰值窗口
 * 物理上最多约 5 个峰，永远攒不满 8 步 → `qualified` 恒 false → `lastStepTs`
 * 冻结在激活时刻。走路 12s 后 `now - lastStepTs > stopMs` 成立，预览被误判停下收起。
 *
 * 真机日志 2026-11-15（走路中被收起）：
 *   16:28:25 [STEP] 连续合格步 5/8 最近10s出步=0 ... walking=true
 *   16:28:35 [STEP] 连续合格步 2/8 最近10s出步=3 ... walking=true
 *   16:28:45 [STEP] 连续合格步 3/8 最近10s出步=0 ... walking=true
 *   16:28:46 [walk] 静置超时，判定停下 → 收起预览（静置门槛 12s）
 *
 * 因此新增入参 `observedStepTs`：最近一次「观测到步活动」的时间戳（调用方在
 * `recordAccelStep` 里刷新，已过 `GaitDetector.feedAcc` 的 LAY_FLAT 门控，不引入
 * 新算法）。判停依据取 `effectiveTs = maxOf(lastStepTs, observedStepTs)` —— 二者较新者。
 * 合格步门槛照旧只管激活，不再兼职「最近有没有在走」的时钟；walking 闸门、
 * 显示驻留 `showStartedTs`、会话宽限 `graceMs` 的语义一个字不改。
 */
object StaticStopPolicy {

    /** 会话启动宽限：复活后至少给这么久，避免刚拉起就收 */
    const val SESSION_GRACE_MS = 30_000L

    /**
     * 【5.1.0】显示驻留：小窗显示后至少这么久不判停。
     *
     * 真机日志（13:57~16:25）里小窗反复「弹出 → 十几秒后收起 → 再弹出」，
     * 用户感受是「在手里经常跳」。误判已经由 [WalkConfirmPolicy] 收紧解决，
     * 这一层是兜底：万一还是判错了，让他看到的是一个稳定存在 12 秒的画面，
     * 而不是闪一下就没、以为软件坏了。
     */
    const val DEFAULT_MIN_SHOW_MS = 12_000L

    @JvmStatic
    @JvmOverloads
    fun shouldStop(
        lastStepTs: Long,
        sessionStartTs: Long,
        now: Long,
        stopMs: Long,
        graceMs: Long = SESSION_GRACE_MS,
        /** 本次预览开始显示的时刻；0 = 不是本会话自动显示的（常显/手动） */
        showStartedTs: Long = 0L,
        minShowMs: Long = DEFAULT_MIN_SHOW_MS,
        /**
         * 【5.2.0】当前是否已判定为「正在行走」（服务里的 gaitWalking）。
         *
         * 为 false 时**永远不判停**：没在走就谈不上「停下」，更谈不上「收起预览」。
         *
         * 修的是日志里的空转：真机日志（2026-11-14，Redmi / HyperOS 4）18 分钟内
         * 「[walk] 静置超时，判定停下 → 收起预览」出现 19 次，而这段时间
         * `[ACTIVE] ✅ 激活行走模式` 一次都没有 —— 也就是说**根本没有预览可收**，
         * 却每秒判一次、每次写一行日志。用户看着像「软件在反复开关小窗」，
         * 实际只是一条毫无意义的判定在空转，还把 512KB 日志文件冲掉一半。
         */
        walking: Boolean = true,
        /**
         * 【5.3.8】最近一次「观测到真实步活动」的时间戳（0 = 尚无观测步）。
         *
         * 与 `lastStepTs` 解耦：后者绑在「凑够合格步」的防误触门槛上，门槛调高
         * 时会永久冻结，导致走路被误判停下；本参数由调用方在 `recordAccelStep`
         * 里刷新，代表「最近一次确实有步活动」。判停依据取二者较新者
         * [effectiveTs]，只影响判停时钟，不触碰任何激活闸门。
         *
         * 置于末尾并带默认值：兼容既有 8 参调用与 `StaticStopPolicyTest`。
         */
        observedStepTs: Long = 0L
    ): Boolean {
        // 【5.3.8】判停时钟 = 合格步时间戳与「最近观测到步活动」时间戳的较新者。
        // lastStepTs 会被 confirmSteps 门槛卡住而冻结；observedStepTs 只要有真实步
        // 活动就会前进。取 maxOf 后，走路时时钟持续推进，不再被误判成静置超时。
        val effectiveTs = maxOf(lastStepTs, observedStepTs)
        // 没在走：没有「停下」这回事，直接不判停
        if (!walking) return false
        // 从未有过任何步活动（合格步/观测步都没有）：不判停，避免每秒刷一条收起日志
        if (effectiveTs == 0L) return false
        // 时钟回拨：一律不判停
        if (now < effectiveTs || (sessionStartTs > 0L && now < sessionStartTs)) return false
        // 刚显示出来的画面不给收：这是「闪一下就消失」的唯一来源
        if (showStartedTs > 0L && now >= showStartedTs && now - showStartedTs < minShowMs) {
            return false
        }

        if (sessionStartTs > 0L && effectiveTs < sessionStartTs) {
            // 旧会话残留时间戳：作废。本会话还没走起来，给宽限；
            // 宽限过后允许收起一次空预览，避免复活后永远挂着一个没有画面的窗。
            return now - sessionStartTs > graceMs
        }
        // 刚进入本会话不久：宽限期内不收
        if (sessionStartTs > 0L && now - sessionStartTs < graceMs) return false
        // 正常静置判停（用生效时钟：合格步时钟被门槛冻结时，靠观测步时钟续命）
        return now - effectiveTs > stopMs
    }
}
