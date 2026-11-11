package io.github.wumisc.safewalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 保活兜底的**静态**接收器（Hook 看门狗之外的第二条腿）。
 *
 * ## 为什么还需要它
 *
 * 看门狗跑在 SystemUI 进程，只有 LSPosed 已激活且作用域正确时存在。
 * 用户没装框架、作用域没勾、或 SystemUI 自己也在重启的空窗期，就没人盯着 App 进程。
 * 静态接收器由 system_server 投递，能覆盖这些场景：
 *
 * | 时机 | 作用 |
 * | --- | --- |
 * | `BOOT_COMPLETED` | 开机后恢复（原逻辑只靠 Hook，框架没加载就永远不恢复） |
 * | `MY_PACKAGE_REPLACED` | 覆盖安装会杀进程，更新完自动接回来 |
 * | `USER_PRESENT` | 解锁是最常见的「发现被清理了」的时刻，也是后台启动限制最松的用户操作 |
 * | [KeepAlivePolicy.ACTION_REVIVE] | 划掉任务后延迟自检，服务真不在才恢复 |
 *
 * ## 明确的边界（不要写成「保证不杀」）
 *
 * - **用户主动关掉开关**：[KeepAlivePolicy.desired] 为 false，一律不恢复。
 * - **用户手动「强制停止」**：系统把应用标成 STOPPED，静态接收器收不到任何广播，
 *   这是系统语义，不绕过。
 * - **Android 10+ 后台启动 Activity 限制**：从广播里拉透明页可能被拦（尤其闹钟触发的
 *   自检）。`try/catch` 记日志即可，主力仍是 Hook 看门狗。
 * - **`USER_PRESENT` 是尽力而为**：Android 8+ 对静态接收器的隐式广播有限制，部分 ROM 上
 *   这条收不到；开机、覆盖安装与划任务自检仍有效。
 * - 开机类广播是系统豁免的隐式广播，不需要 exported 之外的额外配置；
 *   但自定义 action 因为 receiver 必须 exported，所以**必须校验令牌**，
 *   否则任何应用都能反复把摄像头拉起来。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (action == KeepAlivePolicy.ACTION_REVIVE &&
            intent.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
            != SafeWalkPrefKey.BROADCAST_TOKEN
        ) {
            return
        }

        val appCtx = ctx.applicationContext
        try {
            DiagLog.init(appCtx)
            // 开机 / 覆盖安装后主界面从未打开过：先从外部备份恢复配置再判断，
            // 否则会按一份空 SP 的默认值（行走模式=关）直接放弃恢复。
            // USER_PRESENT 每次解锁都来，没必要重复跑一遍文件 I/O。
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
            ) {
                ConfigBackup.restoreIfNeeded(appCtx)
            }
        } catch (_: Throwable) {
        }

        // 直接启动（未解锁）阶段 SP 属于 credential-encrypted 存储，访问会抛异常。
        // 这里整体兜住：收不到就安静退出，等解锁后的 BOOT_COMPLETED / 看门狗处理。
        val desired = try {
            val sp = appCtx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
            KeepAlivePolicy.desired(
                SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true),
                SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)
            )
        } catch (_: Throwable) {
            false
        }
        if (!desired) return

        // 自检而不是无条件重启：划掉任务时服务有可能仍然活着（stopWithTask=false），
        // 重复 startForegroundService 只会让前台通知闪一下。
        if (WalkPreviewService.isAlive()) return

        try {
            DiagLog.log("[keep] 收到 $action，预览服务不在 → 请求恢复")
        } catch (_: Throwable) {
        }

        try {
            val i = Intent(appCtx, AutoStartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                // 开机 / 解锁 / 覆盖安装属于「本来就没进程」，静默恢复即可；
                // 只有划掉任务后的自检（以及 Hook 看门狗）是真正的「被回收又回来」，
                // 才值得弹一条回执，否则每次开机都打扰一次。
                putExtra(KeepAlivePolicy.EXTRA_FROM_REVIVE,
                    action == KeepAlivePolicy.ACTION_REVIVE)
                putExtra(KeepAlivePolicy.EXTRA_REVIVE_SOURCE, action)
            }
            appCtx.startActivity(i)
        } catch (t: Throwable) {
            try {
                DiagLog.log("[keep] 恢复请求被系统拦截（后台启动限制）：${t.javaClass.simpleName}")
            } catch (_: Throwable) {
            }
        }
    }
}
