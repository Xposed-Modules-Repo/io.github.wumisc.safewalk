package io.github.wumisc.safewalk

/**
 * 行走模式「保活 / 自动恢复」的判定策略（**纯逻辑，不依赖 android.\***，可跑 JVM 单测）。
 *
 * ## 为什么是「自动恢复」而不是「防杀」
 *
 * 用户态没有 100% 不被杀的办法：ROM 一键清理、电池优化、用户手动强制停止、
 * 低内存回收都能直接结束进程，且用户显式停止后系统会把应用标成 STOPPED，
 * 所有广播与闹钟都不再投递 —— 这条边界不能也不该绕过。
 *
 * 能做的是**被杀后尽快自己起来**。本项目有一个别的应用没有的优势：
 * LSPosed 把 Hook 注入了 **SystemUI 进程**，它几乎不会被回收，而且本来就每 2 秒
 * 巡检一次。于是把 SystemUI 当「外部监护者」：
 *
 * ```
 * WalkPreviewService（App 进程） --每 30s 心跳广播--> Hook（SystemUI 进程）
 * Hook 巡检：配置期望开 && 心跳超过 90s 没来
 *        → 拉起透明 AutoStartActivity → 合法启动 camera 前台服务
 * ```
 *
 * 透明页是 Android 14/15 上唯一稳定豁免（启动瞬间应用有可见 Activity），
 * 比从广播 / 闹钟里直接 `startForegroundService` 可靠得多。
 *
 * ## 为什么心跳要有「盲区」保护
 *
 * 心跳广播显式投递到 `com.android.systemui`。绝大多数机器没问题，但个别 ROM
 * 把 SystemUI 放在别的包名，Hook 就一条也收不到。若不加限制，看门狗会每 90 秒
 * 拉一次透明页，变成「每隔一分半闪一下」的骚扰。所以**从未收到过心跳**时，
 * 首次之后把冷却从 60 秒拉长到 3 分钟：最多轻微打扰，不会刷屏。
 *
 * ## 时钟
 *
 * 所有时间戳都用 Hook 侧单进程的 `SystemClock.elapsedRealtime()`（单调），
 * 不跨进程传 wall-clock，避免用户改系统时间导致「永不复活」或「反复复活」。
 */
object KeepAlivePolicy {

    /** App → SystemUI：预览服务还活着。Hook 只在分发阶段靠 signature 权限收得到。 */
    const val ACTION_HEARTBEAT = "io.github.wumisc.safewalk.PREVIEW_HEARTBEAT"

    /** App 内部自检（划掉任务 / 闹钟）→ 静态接收器：服务不在就请求恢复。 */
    const val ACTION_REVIVE = "io.github.wumisc.safewalk.PREVIEW_REVIVE"

    /** 这次启动是「被系统杀掉后自动恢复」，不是用户手动开的。用于发一条回执通知。 */
    const val EXTRA_FROM_REVIVE = "keepalive.fromRevive"

    /** 诊断用：是谁发起的恢复（看门狗 / 开机 / 解锁 / 划任务）。 */
    const val EXTRA_REVIVE_SOURCE = "keepalive.source"

    /** App 侧心跳周期。30s 一条本地广播，成本远低于 Hook 的 2s 巡检。 */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** 连续 3 个心跳周期没消息才判定服务已死，容忍偶发丢广播。 */
    const val HEARTBEAT_TIMEOUT_MS = 90_000L

    /** 正常复活冷却：成功拉起后给服务 60s 起来并发第一条心跳，别连环拉。 */
    const val REVIVE_COOLDOWN_MS = 60_000L

    /**
     * 「从没收到过心跳」时的冷却。心跳通道不通时把频率压到 3 分钟一次，
     * 避免每 90s 闪一次透明窗。曾收到过心跳就说明通道正常，仍用 60s。
     */
    const val BLIND_COOLDOWN_MS = 180_000L

    /** SystemUI 刚重启的宽限期：给它时间完成 init 与注册接收器。 */
    const val HOOK_BOOT_GRACE_MS = 5_000L

    /** 用户划掉任务后，延迟多久自检一次（服务可能只是被解绑，还没死）。 */
    const val TASK_REMOVED_CHECK_DELAY_MS = 1_500L

    /** 「已自动恢复」通知的最小间隔，防止反复被杀时刷屏。 */
    const val REVIVE_NOTICE_MIN_INTERVAL_MS = 10 * 60_000L

    /**
     * 用户是否「期望」行走模式在跑。
     *
     * 这是**唯一**的复活许可判据：关总开关或关行走模式时，两条关闭路径
     * （WalkModeActivity 的开关、WalkTileService 的磁贴）都会把
     * `enableRearCamera` 写成 false，所以看门狗不会把一个用户刚关掉的服务拉回来。
     * 用户手动「强制停止」后系统也不再投递广播，天然不会被复活。
     */
    @JvmStatic
    fun desired(masterOn: Boolean, walkOn: Boolean): Boolean = masterOn && walkOn

    /**
     * 心跳是否已过期。
     *
     * [lastHeartbeatAt] == 0 表示从未收到（SystemUI 刚重启 / 通道不通）。
     * [now] 小于心跳时刻是单调时钟异常，宁可判「没过期」也不要误拉。
     */
    @JvmStatic
    fun isStale(lastHeartbeatAt: Long, now: Long): Boolean {
        if (lastHeartbeatAt == 0L) return true
        if (now < lastHeartbeatAt) return false
        return now - lastHeartbeatAt >= HEARTBEAT_TIMEOUT_MS
    }

    /**
     * 看门狗主判定：此刻是否应该拉起透明页代启预览服务。
     *
     * @param desired            配置上是否期望行走模式开着（见 [desired]）
     * @param lastHeartbeatAt    上次收到心跳的 elapsedRealtime，0 = 从未收到
     * @param now                当前 elapsedRealtime
     * @param hookReadyAt        Hook init 完成时刻（elapsedRealtime）
     * @param lastReviveAt       上次发起复活的时刻，0 = 从未
     * @param reviveCount        本 SystemUI 进程已发起的复活次数（首次开机为 0）
     */
    @JvmStatic
    fun shouldRevive(
        desired: Boolean,
        lastHeartbeatAt: Long,
        now: Long,
        hookReadyAt: Long,
        lastReviveAt: Long,
        reviveCount: Int
    ): Boolean {
        if (!desired) return false
        if (now < hookReadyAt || now - hookReadyAt < HOOK_BOOT_GRACE_MS) return false

        // 首次（reviveCount==0）不设冷却：开机 / SystemUI 重启后要尽快恢复，
        // 不能让用户等满 3 分钟才看到小窗回来。
        val cooldown = when {
            reviveCount == 0 -> 0L
            lastHeartbeatAt != 0L -> REVIVE_COOLDOWN_MS
            else -> BLIND_COOLDOWN_MS
        }
        if (lastReviveAt != 0L && now >= lastReviveAt && now - lastReviveAt < cooldown) {
            return false
        }
        return isStale(lastHeartbeatAt, now)
    }

    /**
     * 是否允许弹「行走模式已自动恢复」。
     *
     * 首条永远放行（让用户第一次就看到机制生效），之后 10 分钟最多一条；
     * 时钟被往回拨时重置节流，避免出现「永远不再提示」的死角。
     */
    @JvmStatic
    fun shouldNotifyRevive(lastNoticeAt: Long, now: Long): Boolean {
        if (lastNoticeAt == 0L) return true
        if (now < lastNoticeAt) return true
        return now - lastNoticeAt >= REVIVE_NOTICE_MIN_INTERVAL_MS
    }
}
