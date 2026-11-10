package io.github.wumisc.safewalk

/**
 * 首次安装引导页里「逐项开启特殊权限」的步骤名（P1 交付物）。
 *
 * ## 为什么要抽出来
 *
 * 原来这六个名字写死在 `PermissionOnboardingActivity` 的数组里，第 4 项直接叫
 * 「自启动 / 后台活动（HyperOS）」——非小米设备上，用户按这个名字去找，系统里根本没有。
 * 更麻烦的是 Activity 无法在 JVM 单测里实例化，写死的文案没有任何测试能守住。
 *
 * 抽成纯函数后，[labelsFor] 对五个生态各跑一遍，就能锁住「文案里的系统名必须与判定结果
 * 一致」这条约束，不需要真机。
 *
 * ## 为什么 AOSP 那一条要写「可跳过」
 *
 * 原生 Android 没有「自启动」这个概念，也没有对应开关。如果照抄「自启动 / 后台活动」，
 * 用户会一路找到应用详情页然后困惑。写明「本系统无此开关，可跳过」，用户才会继续往下走。
 */
object OnboardingSteps {

    /** 第 4 项的固定位置；[labelsFor] 与引导页的 `launchSpecial` 都依赖它。 */
    const val INDEX_AUTOSTART = 3

    /**
     * 六个步骤名，顺序即引导顺序。
     *
     * 顺序刻意把「悬浮窗」放第一位：它是用户第一次打开行走模式就会用到的能力，
     * 缺了立刻没有画面；自启动则是「过几分钟才被系统杀」，晚一点开不影响首次体验。
     */
    @JvmStatic
    fun labelsFor(id: RomId): List<String> = listOf(
        "悬浮窗",
        "电池优化白名单",
        "所有文件访问",
        autostartLabel(id),
        "后台定位（到达提醒）",
        "无障碍"
    )

    /**
     * 自启动那一步的名字。
     *
     * 有厂商私有页的写成「自启动 / 后台活动（HyperOS）」——**系统名必须来自 [RomId]**，
     * 不能写死，否则就是 3.x 那句「请开启 HyperOS 自启动」在 MagicOS 上的翻版。
     */
    @JvmStatic
    fun autostartLabel(id: RomId): String =
        if (id.hasVendorPages) "自启动 / 后台活动（${id.displayName}）"
        else "自启动 / 后台活动（本系统无此开关，可跳过）"

    /** 权限清单第 ④ 项的说明文字，同样按生态改写。 */
    @JvmStatic
    fun autostartDesc(id: RomId): String =
        if (id.hasVendorPages) "开机或被回收后自动恢复行走模式（${id.displayName} 的自启动开关）"
        else "本系统没有自启动开关，系统按标准策略管理后台，此项可跳过"
}
