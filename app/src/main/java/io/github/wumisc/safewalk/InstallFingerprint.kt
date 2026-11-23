package io.github.wumisc.safewalk

import android.content.Context
import java.io.File

/**
 * 「这是一次新的安装事件」的指纹（纯取值，不碰界面，便于单测）。
 *
 * 为什么需要它：模块的代码是在 SystemUI 进程启动时注入的，覆盖安装只换掉磁盘上的
 * APK，那个进程里还是旧类。用户装完不去重启，看到的就是「修了等于没修」。
 *
 * 判定「装过了」有三条证据，缺一条就会漏：
 *  · versionCode —— 只在换了版本号时变；同版本重装、换签名重刷它一点都不变。
 *  · `PackageInfo.lastUpdateTime` —— 正常 ROM 每次安装都会刷新，但个别 ROM
 *    （含部分 HyperOS 版本）覆盖安装后**不刷新**，只看它就会「装了新包却一声不吭」。
 *  · APK 文件（大小 + 落盘时间）—— 只要二进制被替换过，这两个值一定会变。
 *
 * 三条里任意一条变化都算「新安装事件」，因此同版本覆盖安装也必然提示。
 */
object InstallFingerprint {

    /**
     * 安装事件指纹：`lastUpdateTime` 与 APK 落盘时间的较大者。
     *
     * 取两者较大值而不是只用 lastUpdateTime：ROM 不刷新前者时，后者仍会变；
     * 两者都取不到时返回 0，判定退回 versionCode 那一条，不会误判成「永远需要提示」。
     */
    @JvmStatic
    fun stamp(ctx: Context): Long {
        var stamp = 0L
        try {
            stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
        }
        try {
            val apk = File(ctx.packageCodePath)
            if (apk.exists()) stamp = maxOf(stamp, apk.lastModified())
        } catch (_: Throwable) {
        }
        return stamp
    }

    /** 当前 APK 的字节数：换包必然变，比时间戳更硬 */
    @JvmStatic
    fun apkSize(ctx: Context): Long = try {
        File(ctx.packageCodePath).length()
    } catch (_: Throwable) {
        0L
    }

    /**
     * APK 是否被换过（大小变了）。
     *
     * 任一侧为 0 都返回 false：分别是「取不到」和「从没记过」，此时交给
     * versionCode 与时间戳判定，不会把「没有记录」误当成「换过包」而反复弹窗。
     */
    @JvmStatic
    fun changed(currentSize: Long, recordedSize: Long): Boolean =
        currentSize > 0L && recordedSize > 0L && currentSize != recordedSize
}
