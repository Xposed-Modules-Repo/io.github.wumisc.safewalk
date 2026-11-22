package io.github.wumisc.safewalk

/**
 * 「今日步数」的纯逻辑（只算数、不碰 SP / 传感器 / View，便于 JVM 单测）。
 *
 * ## 为什么不是直接读系统步数
 *
 * 想显示「跟微信步数一样的今日总量」，理想数据源是 Health Connect / 厂商健康服务，
 * 但它们要独立授权，且本模块不碰任何账户级数据。能在本地拿到的只有两样：
 *
 * 1. **`TYPE_STEP_COUNTER` 的绝对值** —— 自**设备开机**以来的累计步数，全天都在涨，
 *    唯一缺点是被 ROM 掐过就停止上报（[StepHealthPolicy] 的 STALE 通道），
 *    以及**重启后归零**。
 * 2. **`TYPE_STEP_DETECTOR` 的逐帧回调 / 纯加速度合格步** —— 只有在服务监听期间才有，
 *    覆盖不了「锁屏很久之后才打开预览」的历史步数。
 *
 * 所以策略是「两条腿走路，谁大用谁」：
 *
 * ```
 * 今日 = max(硬件绝对值 - 当天首次见到的基线, 服务内的加速度累计)
 * ```
 *
 * · 硬件可用时，第一项就是全天真实总量（含服务没在跑的时间段）；
 * · 硬件不可用（NONE / DENIED / STALE）时，退回第二项，宁可低估也不显示 0；
 * · 跨天（dayKey 变了）把基线和加速度累计一起清零，重新开始数。
 *
 * ## 重启后归零
 *
 * `TYPE_STEP_COUNTER` 在重启后从 0 重新计。此时 `hwTotal < baseTotal`，
 * 直接减会得到负数；[today] 把这种情况当作「基线失效」处理，改用 `hwTotal` 本身
 * （即 base=0 重新起算），不会出现负数或 0 步。
 */
object TodayStepPolicy {

    /** 无有效基线时的哨兵（与 [WalkPreviewService] 的 `lastTotalSteps` 同款约定） */
    const val NO_BASE = -1L

    /**
     * 今日步数。
     *
     * @param hwTotal     `TYPE_STEP_COUNTER` 当前绝对值；null / <0 表示本机拿不到
     * @param baseTotal   当天首次见到 hwTotal 时的基线；[NO_BASE] 表示还没建立
     * @param accel       当天内由逐帧计步器 / 纯加速度累计的步数（服务内统计）
     */
    @JvmStatic
    fun today(hwTotal: Long?, baseTotal: Long, accel: Long): Long {
        val acc = if (accel < 0L) 0L else accel
        if (hwTotal == null || hwTotal < 0L) return acc
        // 基线未建立 / 设备重启导致绝对值回落：以当前绝对值作为今日的起点（base=0）
        if (baseTotal == NO_BASE || hwTotal < baseTotal) {
            return maxOf(hwTotal, acc)
        }
        return maxOf(hwTotal - baseTotal, acc)
    }

    /**
     * 是否需要跨天重置。
     *
     * dayKey 用「当天 0 点的时间戳」或 `yyyyMMdd` 字符串都可以，只要同一天稳定不变。
     */
    @JvmStatic
    fun shouldReset(dayKey: String?, nowDayKey: String): Boolean =
        dayKey.isNullOrEmpty() || dayKey != nowDayKey

    /**
     * 跨天 / 重启后的新基线。
     *
     * @param hwTotal 当前绝对值；null 表示硬件不可用，基线保持 [NO_BASE]
     */
    @JvmStatic
    fun freshBase(hwTotal: Long?): Long =
        if (hwTotal == null || hwTotal < 0L) NO_BASE else hwTotal

    /**
     * 格式化给 HUD 用：千分位，例如 `12,345`。
     *
     * 刻意不用 `String.format(Locale.US, "%,d")`：部分 ROM 的 ICU 会把它换成
     * 阿拉伯语系数字/分隔符，HUD 上会变成看不懂的字符。这里手写分组，
     * 只保证「数字 + 英文逗号」。
     */
    @JvmStatic
    fun format(steps: Long): String {
        val v = if (steps < 0L) 0L else steps
        val s = v.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder(s.length + s.length / 3)
        val head = s.length % 3
        if (head > 0) sb.append(s, 0, head)
        var i = head
        while (i < s.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(s, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
