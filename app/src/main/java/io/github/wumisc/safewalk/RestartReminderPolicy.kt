package io.github.wumisc.safewalk

/**
 * 「首次安装 → 重启系统界面」提醒的策略（纯逻辑，不碰 Activity / 广播，便于单测）。
 *
 * ## 为什么这条提醒必须存在
 *
 * SafeWalk 的核心（步态判定、悬浮窗、手电联动、车祸检测）**跑在 SystemUI 进程里**，
 * 由 LSPosed 在进程启动时把模块代码注入进去。安装 APK 只是把代码放到了磁盘上，
 * SystemUI 那个进程还在跑旧代码 —— 用户装完直接点开关，看到的是「毫无反应」。
 *
 * 这一步以前只写在文档里，实际用户根本不会去读；结果就变成
 * 「装了没用 / 这软件是假的」。所以要在**首次安装的引导页**上，用一张卡片
 * 明确告诉他：还差这一下。
 *
 * ## 为什么分成「能自动重启」和「只能手动」两种说法
 *
 * 重启系统界面有两条通道（见 [SystemUiRestarter]）：
 *  · ① Hook 自尽：模块已经在 SystemUI 里跑着，收到广播就 kill 自己，
 *      系统会立刻把它拉起来。**不需要 root**，但前提是「模块已经生效」——
 *      这恰恰是首次安装时最不确定的事。
 *  · ② su 兜底：需要 root 且已授权。
 *
 * 两条都失败时，唯一可靠的办法是用户自己重启手机。所以文案不能写成
 * 「点一下就好」，那样失败后用户只会觉得按钮坏了。
 */
object RestartReminderPolicy {

    /** 一次性标记存在私有 sp "safewalk"（与 onboardingDone 同一份） */
    const val SP_ONEOFF = "safewalk"

    /**
     * 用户已经处理过「重启系统界面」这件事（点过重启且成功，或明确选了「稍后」再也没进来）。
     *
     * 单独一个 key、不复用 onboardingDone：引导页可以被跳过，而「模块到底加载了没有」
     * 是另一件事。两者混用会导致「用户跳过了权限引导 → 重启提醒也一起消失」。
     */
    const val KEY_ACK = "restartSuiAcknowledged"

    /**
     * 是否还要显示这张卡片。
     *
     * 只对**首次安装**提示：升级安装时用户早就重启过了，每次更新都弹一遍
     * 「请重启系统界面」会变成狼来了 —— 真正需要重启时（改作用域）用户反而不点了。
     */
    @JvmStatic
    fun shouldPrompt(firstRun: Boolean, acknowledged: Boolean): Boolean =
        firstRun && !acknowledged

    /** 卡片标题：一句话说清「还差什么」 */
    @JvmStatic
    fun title(): String = "首次安装：还要重启一次系统界面"

    /**
     * 正文。
     *
     * @param rootAvailable 是否检测到 su。有 root 时语气更肯定（两条通道都能走）；
     *        没有 root 时要把「手动重启手机」这条退路说清楚，不能让人以为按钮必然生效。
     */
    @JvmStatic
    fun body(rootAvailable: Boolean): String {
        val why = "本应用是 LSPosed 模块，步态判定、小窗预览、手电联动都跑在「系统界面」" +
            "（SystemUI）进程里。刚装完，那个进程还没加载新模块 —— " +
            "现在直接去开开关不会有任何反应。"
        return if (rootAvailable) {
            why + "\n\n点下面的按钮重启系统界面（状态栏会消失 2~5 秒后自动回来），" +
                "不用重启手机。之后再去 LSPosed 里确认作用域勾选了「系统界面」。"
        } else {
            why + "\n\n本机没检测到 root：如果 LSPosed 里已经启用本应用并勾选了「系统界面」，" +
                "点下面的按钮仍然可以让模块自己重启；如果点了没反应，请手动重启手机。"
        }
    }

    /** 主按钮文案 */
    @JvmStatic
    fun actionText(): String = "重启系统界面"

    /** 次要按钮：用户想先去别的地方看看 */
    @JvmStatic
    fun laterText(): String = "稍后再说"

    /**
     * 点「完成」但没重启时的二次确认文案。
     *
     * 不是拦着他，只是把后果说清楚：跳过之后模块大概率不生效，
     * 而用户很可能在几小时后忘记自己跳过过这一步。
     */
    @JvmStatic
    fun skipWarning(): String =
        "还没重启系统界面。现在直接离开的话，模块可能不会生效，" +
            "接下来你会看到「开关打开了但什么都没发生」。\n\n要现在重启吗？"

    /** 重启成功后的回执（区分两条通道，方便用户理解为什么有时一闪就好、有时要等） */
    @JvmStatic
    fun successText(viaRoot: Boolean): String = if (viaRoot) {
        "已通过 root 重启系统界面，等两三秒就好"
    } else {
        "系统界面正在重启，等两三秒就好"
    }

    /** 两条通道都失败：给出可执行的检查项，而不是一句「失败了」 */
    @JvmStatic
    fun failureText(): String =
        "没收到模块响应，也没拿到 root 权限。\n\n请检查：\n" +
            "① LSPosed 里已启用 SafeWalk\n" +
            "② 作用域包含「系统界面」\n\n" +
            "如果都勾了还是没反应，直接手动重启手机最稳。"

    /** 卡片底部状态行的初始文字 */
    @JvmStatic
    fun pendingText(): String = "还没重启：模块尚未加载，所有功能都不会生效"
}
