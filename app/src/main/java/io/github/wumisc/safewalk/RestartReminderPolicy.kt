package io.github.wumisc.safewalk

/**
 * 「重启系统界面」提醒的策略（纯逻辑，不碰 Activity / 广播，便于单测）。
 *
 * ## 为什么这条提醒必须存在
 *
 * SafeWalk 的核心（步态判定、悬浮窗、手电联动、车祸检测）**跑在 SystemUI 进程里**，
 * 由 LSPosed 在进程启动时把模块代码注入进去。安装 APK 只是把代码放到了磁盘上，
 * SystemUI 那个进程还在跑旧代码 —— 用户装完直接点开关，看到的是「毫无反应」。
 *
 * 这一步以前只写在文档里，实际用户根本不会去读；结果就变成
 * 「装了没用 / 这软件是假的」。
 *
 * ## 5.3.2 修正：不只「首次安装」，**每次版本变化**都要提醒
 *
 * 5.3.0 的判定是「首次安装 && 没点过重启」。这个判定漏掉了最要命的一种情况：
 * **覆盖升级**。用户从 5.3.0 升到 5.3.1 时引导页标记早已写完，卡片一条都不显示，
 * 而 SystemUI 进程里跑的还是 5.3.0 的类 —— 于是「修好了没」「改了什么」全都测不出来。
 *
 * 真机日志（2026-11-15 13:22，3862 行）就是这么一种：`doShow` 里带着 5.3.x 才有的
 * 方向字段，却**一条方向诊断都没有**（5.3.0 起开相机必打「方向 | 传感器=…」）。
 * 说明拦到的仍然是旧代码。新版装上了、旧代码还在跑，界面上却毫无提示。
 *
 * 所以判定改成「当前安装的 versionCode > 上次确认过的 versionCode」：
 * 首次安装（上次为 0）会提示，每次覆盖升级也会提示，同版本重复进页面不再烦人。
 *
 * ## 为什么分成「能自动重启」和「只能手动」两种说法
 *
 * 重启系统界面有两条通道（见 [SystemUiRestarter]）：
 *  · ① Hook 自尽：模块已经在 SystemUI 里跑着，收到广播就 kill 自己，
 *      系统会立刻把它拉起来。**不需要 root**，但前提是「模块已经生效」。
 *  · ② su 兜底：需要 root 且已授权。
 *
 * 两条都失败时，唯一可靠的办法是用户自己重启手机。所以文案不能写成
 * 「点一下就好」，那样失败后用户只会觉得按钮坏了。
 */
object RestartReminderPolicy {

    /** 一次性标记存在私有 sp "safewalk"（与 onboardingDone 同一份） */
    const val SP_ONEOFF = "safewalk"

    /**
     * 用户上次「确认处理过重启」时，模块的 versionCode。
     *
     * 存版本号而不是布尔值：布尔只能表达「做过一次」，表达不了「这次升级还没做」。
     * 默认 0 等价于「从没确认过」，首次安装天然命中。
     */
    const val KEY_ACK_VERSION = "restartSuiAckedVersionCode"

    /**
     * 旧的布尔标记。只在迁移时读一次：老版本写过 true 的用户，
     * 其 versionCode 无从得知，按 0 处理 —— 也就是升级后**会**再提示一次，
     * 这正是我们要的（他确实没重启过新版）。
     */
    const val KEY_ACK = "restartSuiAcknowledged"

    /**
     * 是否还要显示这张卡片。
     *
     * @param installedVersionCode 当前已安装 APK 的 versionCode
     * @param ackedVersionCode 上次确认重启时的 versionCode（没确认过传 0）
     */
    @JvmStatic
    fun shouldPrompt(installedVersionCode: Int, ackedVersionCode: Int): Boolean =
        installedVersionCode > ackedVersionCode

    /** 这次提示是「第一次装」还是「升级之后」——决定标题的第一句话 */
    @JvmStatic
    fun isFirstInstall(ackedVersionCode: Int): Boolean = ackedVersionCode <= 0

    /** 卡片标题：一句话说清「还差什么」 */
    @JvmStatic
    fun title(firstInstall: Boolean): String = if (firstInstall) {
        "首次安装：还要重启一次系统界面"
    } else {
        "已更新：重启系统界面后新版本才生效"
    }

    /**
     * 正文。
     *
     * @param rootAvailable 是否检测到 su。有 root 时语气更肯定（两条通道都能走）；
     *        没有 root 时要把「手动重启手机」这条退路说清楚，不能让人以为按钮必然生效。
     * @param firstInstall 首次安装 / 覆盖升级，两种说法只差第一句的归因
     */
    @JvmStatic
    fun body(rootAvailable: Boolean, firstInstall: Boolean): String {
        val why = if (firstInstall) {
            "本应用是 LSPosed 模块，步态判定、小窗预览、手电联动都跑在「系统界面」" +
                "（SystemUI）进程里。刚装完，那个进程还没加载新模块 —— " +
                "现在直接去开开关不会有任何反应。"
        } else {
            "新版 APK 已经装上了，但「系统界面」（SystemUI）进程里跑的仍然是**上一个版本**" +
                "的代码。模块是在进程启动时注入的，不重启这一步，这次的修改一行都不会生效 —— " +
                "看起来就像「什么都修了，却什么都没变」。"
        }
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
     * 不区分 root 的正文，给首页重启提示用（首次安装 / 覆盖升级各一套说法）。
     *
     * 首页不握手 root 检测（那是引导页的活），所以两种通道并列写：
     * 点了能自尽重启最好，不行就手动重启手机。少一句精确的归因，
     * 换来的是「任何机型都不会说错话」。
     *
     * 「首次安装」必须也走这条提示：刚装完 SystemUI 里根本没有本模块的代码，
     * 不重启就点开关，用户看到的是「毫无反应」——这正是「这软件是假的」的来源。
     */
    @JvmStatic
    @JvmOverloads
    fun bodyNeutral(firstInstall: Boolean = false): String {
        if (firstInstall) {
            return "本应用是 LSPosed 模块，步态判定、小窗预览、手电联动都跑在「系统界面」" +
                "（SystemUI）进程里。刚装完，那个进程还没加载本模块 —— " +
                "现在直接去开开关不会有任何反应。\n\n" +
                "点下面的按钮重启系统界面（状态栏会消失 2~5 秒后自动回来），不用重启手机；" +
                "如果点了没反应，请手动重启手机。"
        }
        return "新版 APK 已经装上，但「系统界面」（SystemUI）进程里跑的仍是上一个版本的代码 —— " +
            "模块在进程启动时注入，不重启这一步，这次的修改一行都不会生效。\n\n" +
            "点下面的按钮重启系统界面（状态栏会消失 2~5 秒后自动回来），不用重启手机。" +
            "如果点了没反应，请手动重启手机。"
    }

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
    fun pendingText(firstInstall: Boolean): String = if (firstInstall) {
        "还没重启：模块尚未加载，所有功能都不会生效"
    } else {
        "还没重启：系统界面里仍是旧版本，这次的修改还没生效"
    }

    /**
     * 日志里怎么描述当前状态。
     *
     * 一行字换掉一整轮「我装的是新版啊」的来回：下次回传日志，
     * 搜 `[PERM] 重启提醒` 就能看出拦到的代码是哪一版。
     */
    @JvmStatic
    fun describe(installedVersionCode: Int, ackedVersionCode: Int): String {
        val state = if (shouldPrompt(installedVersionCode, ackedVersionCode)) {
            "需要重启（系统界面仍在旧版）"
        } else {
            "已确认重启"
        }
        return "安装=$installedVersionCode 上次确认=$ackedVersionCode → $state"
    }
}
