package io.github.wumisc.safewalk

import android.content.Context
import android.content.pm.PackageManager

/**
 * 【运行模式】探测「这台机器上到底有没有钩子框架的入口」。
 *
 * 为什么需要它：首页那条「重启系统界面」提示只有当模块真的会注入 SystemUI 时才有意义。
 * 无 root 的纯独立模式里，重启系统界面什么也不会发生——它只会让用户白等一次黑屏，
 * 然后发现「重启了还是那样」，反而把「这版是不是坏了」的怀疑放大。
 *
 * 判据说明（故意用「管理器装没装」而不是「心跳有没有」）：
 *  · 心跳是**运行时**信号，刚装完 APK 时钩子还没跑过，用心跳判会把真正的框架用户
 *    首次安装提示也一起吞掉，那是明确要求不能省的一条。
 *  · 管理器是**静态**信号，装了就在，重启前也在。所以：心跳来过 → 一定有框架；
 *    心跳没来过但装了管理器 → 可能只是还没启用/没重启，照样提示；
 *    两样都没有 → 独立模式，不提示。
 *
 * 名单只放确实会写 SystemUI 的几种入口，宁缺勿错：多判一个「有框架」只是多重启一次，
 * 少判一个「有框架」却会让用户以为开关坏了。
 */
object FrameworkProbe {

    /** 已知的钩子框架 / root 管理入口。逐个查包名，不查权限、不查文件。 */
    private val MANAGER_PACKAGES = listOf(
        "org.lsposed.manager",          // LSPosed
        "me.weishu.kernelsu",           // KernelSU
        "me.bmax.apatch",               // APatch
        "com.topjohnwu.magisk",         // Magisk（Zygisk + LSPosed 的老组合）
        "io.github.vvb2060.magisk",     // Magisk Alpha
        "me.weishu.exp",                // TaiChi / 太极
        "org.lsposed.lspatch"           // LSPatch（本地修补，也会把模块塞进目标进程）
    )

    /** 有没有装任何一个框架/root 管理器入口。 */
    @JvmStatic
    fun anyManagerInstalled(ctx: Context): Boolean {
        if (ctx == null) return false
        val pm = ctx.packageManager ?: return false
        for (pkg in MANAGER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                // 没装，继续查下一个
            } catch (_: Throwable) {
                // 个别 ROM 的 PM 会抛 SecurityException：按「查不出来」处理，但不因此静音提示
                return true
            }
        }
        return false
    }
}
