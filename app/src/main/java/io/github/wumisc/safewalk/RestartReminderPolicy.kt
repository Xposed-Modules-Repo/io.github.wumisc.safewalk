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
     * 上次「已处理」的那次安装的 `PackageInfo.lastUpdateTime`（毫秒）。
     *
     * 为什么单靠 versionCode 不够：**同版本覆盖安装**（重装同一个 APK、换签名重刷、
     * adb install -r 同一个包）versionCode 一点没变，`installed > acked` 永远为 false，
     * 于是「装完了但 SystemUI 里还是老代码」这种最典型的情况反而不提示。
     * lastUpdateTime 每次安装都会刷新，正好可以当「这是一次新安装事件」的指纹。
     */
    const val KEY_INSTALL_STAMP = "restartSuiInstallStamp"

    /** 这次安装事件已经弹过提示了吗（避免每次回前台都弹一遍） */
    const val KEY_PROMPTED_STAMP = "restartSuiPromptedStamp"

    /**
     * 上次「已处理」的那份 APK 的字节数（见 [InstallFingerprint.apkSize]）。
     *
     * 第三条证据的落地位：有的 ROM 覆盖安装后 `lastUpdateTime` 与 APK 落盘时间
     * 都可能被复用，但**二进制大小**换了就一定会变 —— 同版本重装一个改了内容
     * 的包，靠这一条即可判定为「新安装事件」。
     */
    const val KEY_INSTALL_SIZE = "restartSuiInstallSize"

    /** 这次安装事件弹过提示时，记下的那份 APK 大小 */
    const val KEY_PROMPTED_SIZE = "restartSuiPromptedSize"

    /**
     * 「刚发生过一次覆盖安装」的待办标记。
     *
     * 由 [KeepAliveReceiver] 收到 `MY_PACKAGE_REPLACED` 时写 true。这是第三条独立证据：
     * 前两条（versionCode 变大、lastUpdateTime 变化）都依赖厂商 ROM 如实上报，
     * 个别 ROM 覆盖安装后 `lastUpdateTime` 不刷新，会出现「装了新包却一声不吭」。
     * 系统广播是确定的，收到就一定会提示一次；提示完（或用户点稍后）清掉。
     */
    const val KEY_REPLACED_PENDING = "restartSuiReplacedPending"

    /**
     * Hook（SystemUI 进程）最近一次成功初始化时，实际加载的模块 versionCode。
     *
     * 这是「重启提示」和真实热重载状态对齐的关键证据：APK 只是装到磁盘上时，
     * 这个值还停留在旧版本；libxposed API 102 热重载成功、新一代 init 完成后，
     * Hook 会主动广播 [ACTION_MODULE_READY]，App 侧把这个值更新成当前版本。
     * 首页只认「版本 + 安装指纹」都匹配的 ready 标记，避免旧标记跨安装误放行。
     */
    const val KEY_RUNNING_VERSION = "restartSuiHookRunningVersionCode"

    /** ready 标记对应的模块版本名，仅用于日志/排查 */
    const val KEY_RUNNING_VERSION_NAME = "restartSuiHookRunningVersionName"

    /** ready 标记对应的安装时间戳（Hook 广播时由 App 侧写入） */
    const val KEY_RUNNING_STAMP = "restartSuiHookRunningStamp"

    /** ready 标记对应的 APK 字节数（Hook 广播时由 App 侧写入） */
    const val KEY_RUNNING_SIZE = "restartSuiHookRunningSize"

    /**
     * Hook → App：模块已经在 SystemUI 里完成初始化/热重载。
     *
     * 这是一条**显式投给本应用**的广播，只在模块真正跑起来后发；装完 APK 但
     * SystemUI 还在旧代码、或热重载失败时不会发。首页据此跳过「重启系统界面」，
     * 不再把「磁盘上已装新版」误判成「进程里还是旧版」。
     */
    const val ACTION_MODULE_READY = "io.github.wumisc.safewalk.MODULE_READY"

    const val EXTRA_MODULE_VERSION_CODE = "module_version_code"
    const val EXTRA_MODULE_VERSION_NAME = "module_version_name"
    const val EXTRA_MODULE_INIT_SOURCE = "module_init_source"

    /**
     * 这个 ready 标记是否代表「当前这次安装」已经由 Hook 跑起来了。
     *
     * 只比 versionCode 不够：同版本覆盖安装时 versionCode 不变，上一次的 ready
     * 标记会残留；所以还要安装指纹（时间戳/大小）对得上。任一指纹缺失时退回
     * 版本判断，保证不会因为 ROM 取不到时间戳而永远弹窗。
     */
    @JvmStatic
    fun moduleReadyForInstall(
        installedVersionCode: Int,
        runningVersionCode: Int,
        currentStamp: Long,
        readyStamp: Long,
        currentSize: Long,
        readySize: Long
    ): Boolean {
        if (runningVersionCode <= 0 || runningVersionCode != installedVersionCode) return false
        if (currentStamp > 0L && readyStamp > 0L && currentStamp != readyStamp) return false
        if (currentSize > 0L && readySize > 0L && currentSize != readySize) return false
        return true
    }

    /**
     * 这次安装事件是不是新的（相对上次已处理的那次）。
     *
     * stamp 取不到（为 0）时一律返回 false，交给 versionCode 判断兜底。
     */
    @JvmStatic
    fun installStampChanged(currentStamp: Long, handledStamp: Long): Boolean =
        currentStamp > 0L && currentStamp != handledStamp

    /** 这个安装事件是否已经弹过提示了（同一个 stamp 只弹一次，点「稍后」也不反复骚扰） */
    @JvmStatic
    fun alreadyPrompted(currentStamp: Long, promptedStamp: Long): Boolean =
        currentStamp > 0L && currentStamp == promptedStamp

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
        "覆盖安装完成：还要重启一次系统界面"
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
            "APK 已经装进磁盘了，但「系统界面」（SystemUI）进程里跑的仍然是**安装之前**" +
                "的那份代码。模块是在进程启动时注入的，不重启这一步，新装进去的东西一行都不会生效 —— " +
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
        return "APK 已经装进磁盘，但「系统界面」（SystemUI）进程里跑的仍是安装之前的那份代码 —— " +
            "模块在进程启动时注入，不重启这一步，新装进去的东西一行都不会生效。\n\n" +
            "覆盖安装同样要重启：换签名重刷、同版本再装一遍、adb install -r 都一样，" +
            "只要 APK 被替换过，SystemUI 里就还是旧类。\n\n" +
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
