package io.github.wumisc.safewalk

import android.content.Context

/**
 * 「超级岛增强」LSPosed 模块（HyperIsland）探测。
 *
 * 只用于**诊断与提示**，不影响上岛方式：本项目的通知一律照常携带
 * `miui.focus.param` 走原生焦点通知路径。原因是该模块默认就带「破解焦点白名单
 * 和签名验证」开关（方式一 HyperCeiler / 方式二模块内嵌），破解后系统不再校验
 * 白名单，第三方自签名包的原生焦点参数是真生效的——这时把参数去掉反而上不了岛。
 *
 * 模块的另一条能力是拦截任意 App 的标准通知转岛，那是没装模块/没破解时的兜底，
 * 与原生参数并不冲突（按钮同时存在于 notification.actions 里）。
 *
 * 探测依赖清单里的 `<queries>` 声明（Android 11+ 包可见性），不加就永远 false。
 */
object HyperIslandCompat {

    /** 模块包名（LSPosed 仓库 io.github.hyperisland） */
    const val PKG = "io.github.hyperisland"

    /** 是否检测到该模块。任何异常都按「没装」处理，退回原生焦点参数路径。 */
    @JvmStatic
    fun installed(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            ctx.packageManager.getPackageInfo(PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
