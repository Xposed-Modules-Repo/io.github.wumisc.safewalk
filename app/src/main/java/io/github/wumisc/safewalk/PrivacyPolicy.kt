package io.github.wumisc.safewalk

import android.content.Intent
import android.content.pm.PackageManager

/**
 * 【5.5.0 隐私】桌面图标隐藏 / 最近任务隐藏的纯策略。
 *
 * 这里只放**无副作用的计算**：状态映射、幂等判定、恢复文案、启动 flag。
 * 真正的 PackageManager / ActivityManager 调用在 [MainActivity] 里，
 * 全部经本对象算出「该设成什么」，再包 try/catch 落到系统 API。
 * （[WalkTileService] 只管「常显 ↔ 自动跟随」切换，不再涉及图标隐藏与恢复。）
 *
 * 之所以把状态计算抽出来：alias 的两态最容易写反（隐藏时反而把带 LAUNCHER 的打开 =
 * 图标还在），而这段逻辑无法靠肉眼 review 保证，必须用单测把四个组合钉死。
 */
object LauncherIconPolicy {

    /** 正常态 alias（带 LAUNCHER，桌面有图标）。相对包名，与 Manifest 的 android:name 对齐。 */
    const val ALIAS_NORMAL = "MainActivityAlias"

    /** 隐藏态 alias（无 LAUNCHER，桌面无图标）。 */
    const val ALIAS_HIDDEN = "MainActivityAliasHidden"

    /**
     * 正常态 alias 是否应启用：hide=false（未隐藏）时启用，hide=true 时禁用。
     * 单测钉死这两个值，防止「隐藏了图标却还在」的反向 bug。
     */
    @JvmStatic
    fun normalAliasEnabled(hide: Boolean): Boolean = !hide

    /** 隐藏态 alias 是否应启用：hide=true 时启用（它本身没有 LAUNCHER，启用也不会长出图标）。 */
    @JvmStatic
    fun hiddenAliasEnabled(hide: Boolean): Boolean = hide

    /** 「是否启用」→ setComponentEnabledSetting 的状态值。用系统常量，不写魔数。 */
    @JvmStatic
    fun componentState(enabled: Boolean): Int =
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /**
     * 幂等守卫：当前状态已等于目标状态 → 不必再调 setComponentEnabledSetting。
     *
     * [current] 取 getComponentEnabledSetting 的返回值。DEFAULT(0) 与预期状态必不相等，
     * 于是首次调用一定会落到显式状态，符合「校准」需求；重复调用则被挡掉，无副作用。
     */
    @JvmStatic
    fun needsChange(current: Int, targetEnabled: Boolean): Boolean =
        current != componentState(targetEnabled)

    /**
     * 隐藏前的确认文案 / 帮助入口共用：把**所有**恢复路径一次讲清。
     *
     * 这是「绝不能隐藏后再也打不开」的核心防线之一。本项目用户都装了 LSPosed，
     * 所以把 LSPosed 作为首推路径——MainActivity 本体保留 MAIN+INFO 且 exported=true，
     * LSPosed 用 getLaunchIntentForPackage 优先匹配 INFO category，隐藏后仍能打开首页：
     *  ① LSPosed 管理器 → 模块 → 安全出行 → 点开进入首页关掉「隐藏桌面图标」（推荐）；
     *  ② 系统设置 → 应用 → 显示图标（系统级，任何 App 都能做到，不依赖本 App 任何组件）；
     *  ③ 下拉通知栏点「行走模式运行中」常驻通知进入首页
     *     （行走模式开启期间该通知 setOngoing，contentIntent 直达 MainActivity）；
     *  ④ adb 显式启动 MainActivity（整机丢失图标时的终极兜底）。
     *
     * 【为什么这里不再写磁贴】5.5.0 曾把控制中心磁贴当作隐藏态的恢复入口，用户明确
     * 要求还原磁贴的纯切换语义（常显 ↔ 自动跟随）。磁贴不再承担恢复职责，
     * 恢复文案也就不能继续引导用户去点磁贴 —— 否则点下去只是切档位，反而更困惑。
     */
    @JvmStatic
    fun recoveryMessage(pkg: String): String =
        "隐藏后桌面上将不再出现「安全出行」图标（防他人随手打开）。\n\n" +
            "恢复方法（任选其一）：\n" +
            "① LSPosed 管理器 → 模块 → 找到「安全出行」→ 点开进入首页 → 关掉「隐藏桌面图标」开关" +
            "（推荐；模块经 INFO 入口启动，不依赖桌面图标）；\n" +
            "② 系统 设置 → 应用 → 安全出行 → 显示/启用图标；\n" +
            "③ 下拉通知栏，点「行走模式运行中」常驻通知进入首页恢复；\n" +
            "④ 电脑执行：adb shell am start -n $pkg/.MainActivity\n\n" +
            "隐藏期间来电/短信、车祸预警等所有功能照常运行。"
}

/**
 * 【5.5.0 隐私】最近任务隐藏的纯策略。
 */
object RecentTaskPolicy {

    /**
     * 启动 MainActivity 时是否附加 FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS。
     *
     * AppTask.setExcludeFromRecents() 只能作用于**已存在**的 task；一个全新 task 若想从
     * 一开始就不进最近任务，必须在启动 Intent 上带这个 flag。两条路径合起来才完整。
     */
    @JvmStatic
    fun launchFlags(hide: Boolean): Int =
        if (hide) Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS else 0

    /** 任务描述里是否追加「隐私」标记，给用户一个可见提示（避免「最近任务突然空了」的困惑）。 */
    @JvmStatic
    fun taskDescriptionLabel(base: String, hide: Boolean): String =
        if (hide) "$base（隐私模式）" else base
}
