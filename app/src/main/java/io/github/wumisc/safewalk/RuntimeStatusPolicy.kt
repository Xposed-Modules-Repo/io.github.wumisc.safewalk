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

    data class Row(val label: String, val value: String, val level: Int)

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
     * 独立模式下**实测确认用不了**的能力。
     *
     * 只列有依据的三项，不猜：
     *  1. 50 步智能提醒：判定与弹通知都在 SystemUI 的钩子里跑（App 侧只有小窗与步态）；
     *  2. 超级岛 / 焦点通知增强：靠注入 SystemUI 才拿得到岛；
     *  3. 系统 UI 适配（HyperOS 版本选择）：本身就是给钩子用的开关，没钩子等于没有。
     */
    @JvmStatic
    fun standaloneUnavailable(): List<String> = listOf(
        "50 步智能提醒",
        "超级岛 / 焦点通知增强",
        "系统 UI 适配选项"
    )

    /** 首页状态概览的几行。 */
    @JvmStatic
    fun rows(
        everSeen: Boolean,
        hookAgeMs: Long,
        hookVersionName: String,
        anchors: Int,
        framework: String,
        api: Int,
        logEnabled: Boolean,
        stepIssue: String
    ): List<Row> {
        val m = mode(everSeen, hookAgeMs)
        val out = ArrayList<Row>(5)

        out.add(
            Row(
                "运行模式",
                when (m) {
                    MODE_FRAMEWORK -> "框架模式 · 已注入"
                    MODE_FRAMEWORK_STALE -> "框架模式 · 注入失联"
                    else -> "独立模式（未检测到框架）"
                },
                if (m == MODE_FRAMEWORK) LEVEL_OK else if (m == MODE_FRAMEWORK_STALE) LEVEL_BAD else LEVEL_WARN
            )
        )

        out.add(
            Row(
                "注入状态",
                when (m) {
                    MODE_FRAMEWORK -> buildString {
                        append(framework.ifBlank { "SystemUI" })
                        append(" · 锚点 ").append(anchors).append("/3")
                        if (hookVersionName.isNotBlank()) append(" · v").append(hookVersionName)
                        if (api > 0) append(" · API ").append(api)
                        append(" · ").append(ago(hookAgeMs))
                    }
                    MODE_FRAMEWORK_STALE -> "上次注入 ${ago(hookAgeMs)}前，可能已失效（重启系统界面可恢复）"
                    else -> "未注入（没装 LSPosed 或模块没启用）"
                },
                if (m == MODE_FRAMEWORK) LEVEL_OK else if (m == MODE_FRAMEWORK_STALE) LEVEL_BAD else LEVEL_WARN
            )
        )

        out.add(
            Row(
                "诊断日志",
                if (logEnabled) "已开启" else "已关闭（出问题时先打开）",
                if (logEnabled) LEVEL_OK else LEVEL_WARN
            )
        )

        out.add(
            Row(
                "步数源",
                when (stepIssue) {
                    StepSensorPolicy.OK -> "可用（自动跟随正常）"
                    StepSensorPolicy.NO_SENSOR -> "无计步器（自动跟随已关闭，磁贴改手动）"
                    else -> "未授权「身体活动」（授权后自动恢复）"
                },
                if (stepIssue == StepSensorPolicy.OK) LEVEL_OK else LEVEL_WARN
            )
        )

        if (m == MODE_STANDALONE) {
            out.add(
                Row(
                    "不可用",
                    standaloneUnavailable().joinToString("、"),
                    LEVEL_BAD
                )
            )
        }
        return out
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
