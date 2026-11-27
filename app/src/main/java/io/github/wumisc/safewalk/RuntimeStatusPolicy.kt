package io.github.wumisc.safewalk

/**
 * 【运行模式 · 状态概览】纯逻辑：把「框架在不在、注入新不新鲜、日志开没开、步数能不能拿到」
 * 折算成首页那一条状态概览要显示的几行字。
 *
 * 为什么要有它：
 *  · 无框架（没装 LSPosed / 没启用模块）时，App 仍然能跑，但**只有一部分能力**。
 *    用户看不出差别，只会觉得「这版变差了 / 开关没反应」。
 *  · 有框架时也可能出问题：SystemUI 重启后模块没重新注入、热重载失败、版本对不上。
 *    这些以前只在日志里。
 * 所以首页给一条**实时**的状态概览，并在独立模式下把确实用不了的功能写清楚。
 *
 * 判据说明：
 *  · 框架是否在线靠 Hook 主动广播的**心跳**（[HookStatusStore] 记时间戳），
 *    超过 [FRESH_MS] 没新广播即判「注入失联」——SystemUI 重启后模块没回来就是这个样子。
 *  · 「独立模式」与「注入失联」要分开说：前者是没装框架，后者是装了但没生效，
 *    修法完全不同（一个去启用模块，一个去重启系统界面）。
 */
object RuntimeStatusPolicy {

    /** Hook 心跳新鲜期：两个广播周期（60s）+ 半个周期的容错。 */
    const val FRESH_MS = 150_000L

    /** 行状态色：正常 / 需注意 / 有问题 */
    const val LEVEL_OK = 0
    const val LEVEL_WARN = 1
    const val LEVEL_BAD = 2

    const val MODE_FRAMEWORK = "framework"
    const val MODE_FRAMEWORK_STALE = "framework_stale"
    const val MODE_STANDALONE = "standalone"

    /**
     * @param everSeen  是否曾经收到过 Hook 广播（收到过但超时 = 注入失联；从没收到 = 独立模式）
     * @param hookAgeMs 距上次 Hook 广播的毫秒数
     */
    @JvmStatic
    fun mode(everSeen: Boolean, hookAgeMs: Long): String = when {
        !everSeen -> MODE_STANDALONE
        hookAgeMs in 0..FRESH_MS -> MODE_FRAMEWORK
        else -> MODE_FRAMEWORK_STALE
    }

    /**
     * 「重启系统界面」这条提示要不要弹。
     *
     * 只有**可能真的有钩子**时才弹：心跳来过（框架肯定在跑），或者装了框架/root 管理器入口
     * （可能只是还没启用或还没重启）。两样都没有就是纯独立模式——重启系统界面不会有任何
     * 变化，弹窗只会让人白等一次黑屏，所以不弹，改由首页那行状态条显示「独立模式（无框架）」。
     *
     * 之所以不直接看「当前是否新鲜心跳」：刚装完 APK、模块还没启用时心跳本来就是空的，
     * 拿它当判据会把框架用户首次安装的提示一起吞掉（那条不能省）。
     */
    @JvmStatic
    fun allowsRestartPrompt(everSeen: Boolean, managerInstalled: Boolean): Boolean =
        everSeen || managerInstalled

    /**
     * 独立模式下**实测确认用不了**的能力，用于二级页/文案，不用于首页常显。
     *
     * 只列有依据的三项，不猜：
     *  1. 50 步智能提醒：判定与弹通知都在 SystemUI 的钩子里跑（App 侧只有小窗与步态）；
     *  2. 超级岛 / 焦点通知增强：靠注入 SystemUI 才拿得到岛；
     *  3. 系统 UI 适配（HyperOS 版本选择）：本身就是给钩子用的开关，没钩子等于没有。
     * 反过来，步态检测、行走模式、路况小窗、SOS、急救卡、诊断上报都在 App 自己进程里跑，
     * 独立模式实测正常，所以不进这个名单。
     */
    @JvmStatic
    fun standaloneUnavailable(): List<String> = listOf(
        "50 步智能提醒",
        "超级岛 / 焦点通知增强",
        "系统 UI 适配选项"
    )

    /**
     * 首页那**一行**状态条的全部内容：标题（16sp）+ 细节（12sp）+ 颜色级。
     *
     * 为什么只有两行字而不是四行表格：首页每多一行都会把下面的功能入口往下推，
     * 而用户真正需要一眼看到的只有「框架在不在、正常不正常」。日志与步数这类
     * 只要不是异常就不必占位，异常时塞进细节句里就够。
     */
    data class Headline(val title: String, val detail: String, val level: Int)

    /**
     * @param everSeen  是否曾经收到过 Hook 广播（收到过但超时 = 注入失联；从没收到 = 独立模式）
     * @param hookAgeMs 距上次 Hook 广播的毫秒数
     * @param anchors   钩子挂上的锚点数量
     */
    @JvmStatic
    fun headline(
        everSeen: Boolean,
        hookAgeMs: Long,
        anchors: Int,
        logEnabled: Boolean,
        stepIssue: String
    ): Headline {
        val m = mode(everSeen, hookAgeMs)
        val step = when (stepIssue) {
            StepSensorPolicy.OK -> "步数正常"
            StepSensorPolicy.NO_SENSOR -> "无计步器"
            else -> "缺活动权限"
        }
        val log = if (logEnabled) "日志开" else "日志关"

        return when (m) {
            MODE_FRAMEWORK -> {
                val all = anchors >= 3
                Headline(
                    "框架模式 · 注入正常",
                    "锚点 $anchors/3 · ${ago(hookAgeMs)}前 · $log",
                    // 锚点不足三个说明只挂上了一部分（SystemUI 版本变化），值得提醒但不致命
                    if (all && logEnabled) LEVEL_OK else LEVEL_WARN
                )
            }
            MODE_FRAMEWORK_STALE -> Headline(
                "框架模式 · 注入失联",
                "${ago(hookAgeMs)}无心跳，重启系统界面可恢复",
                LEVEL_BAD
            )
            else -> Headline(
                "独立模式（无框架）",
                "$log · $step · 50 步提醒等 3 项不可用",
                LEVEL_WARN
            )
        }
    }

    /** 把毫秒差写成「12 秒 / 3 分钟 / 2 小时」。 */
    @JvmStatic
    fun ago(ms: Long): String = when {
        ms < 0 -> "未知"
        ms < 1_000 -> "刚刚"
        ms < 60_000 -> "${ms / 1000} 秒"
        ms < 3_600_000 -> "${ms / 60_000} 分钟"
        else -> "${ms / 3_600_000} 小时"
    }
}
