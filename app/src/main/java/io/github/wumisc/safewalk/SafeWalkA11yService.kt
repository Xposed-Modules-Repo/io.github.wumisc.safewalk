package io.github.wumisc.safewalk

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务（可选通道）。
 *
 * 【为什么需要它】
 * 用户在系统「无障碍」里找不到本应用，也就没有从那里打开应用的入口 —— 权限引导页却把
 * 「无障碍」列成一项，点了只能跳到系统总列表，什么也看不到。根因不是跳转写错，而是
 * **应用从未声明过任何 AccessibilityService**，系统列表里自然没有条目。
 *
 * 【它做什么】
 * 只订阅 `TYPE_WINDOW_STATE_CHANGED`，拿前台应用的包名，交给 [WalkPreviewService]：
 * 前台是相机 / 录像 / 视频通话类应用时收起角落小窗，离开后恢复。
 * 这条避让原先只有 Xposed Hook（SystemUI 进程）在做，未激活 LSPosed 或 Hook 未生效时
 * 就没有人管；无障碍服务是**不依赖 Xposed 的备用通道**。
 *
 * 【它不做什么】
 * `canRetrieveWindowContent=false`：不读取屏幕文字、不监听输入、不注入手势。
 * 只读包名，不联网。
 */
class SafeWalkA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        sEnabled = true
        try {
            DiagLog.init(applicationContext)
            DiagLog.log("[a11y] 无障碍服务已连接 → 前台相机应用避让通道可用（不读屏、不联网）")
        } catch (_: Throwable) {
        }
        // 连接瞬间可能已经有相机类应用在前台，先按当前状态评估一次
        WalkPreviewService.onForegroundAppChanged(lastPkg ?: "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 只处理窗口切换；内容变化（TYPE_WINDOW_CONTENT_CHANGED）一律忽略
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = try {
            e.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        if (pkg.isNullOrEmpty() || pkg == lastPkg) return
        lastPkg = pkg
        // 服务没连上预览（未开行走模式）时这是空操作，不会额外耗电
        WalkPreviewService.onForegroundAppChanged(pkg)
    }

    override fun onInterrupt() {
        // 本服务不执行任何反馈动作，无需处理中断
    }

    override fun onUnbind(intent: Intent?): Boolean {
        sEnabled = false
        try {
            DiagLog.log("[a11y] 无障碍服务已断开 → 相机避让回到 Hook 单一通道")
        } catch (_: Throwable) {
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        sEnabled = false
        super.onDestroy()
    }

    companion object {
        /** 服务是否已连接（仅内存态，跨进程/重启后以系统设置为准） */
        @Volatile
        private var sEnabled = false

        private var lastPkg: String? = null

        /**
         * 本应用的无障碍服务是否已被用户开启。
         *
         * 必须查 `ENABLED_ACCESSIBILITY_SERVICES` 里有没有**本组件**，不能像旧版那样只看
         * `ACCESSIBILITY_ENABLED` 总开关 —— 那只要开了任意一个第三方无障碍服务（输入法、手势、
         * 屏幕朗读）就会显示「已开启」，本应用其实根本没被勾选，属于纯粹的状态误报。
         */
        @JvmStatic
        fun isEnabled(ctx: Context): Boolean {
            if (sEnabled) return true
            return try {
                val raw = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val me = ComponentName(ctx, SafeWalkA11yService::class.java)
                val flat = me.flattenToString()
                val short = me.flattenToShortString()
                raw.split(':').any { it.equals(flat, true) || it.equals(short, true) }
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * 直接打开本服务的系统详情页。
         *
         * 用字符串 action 而不是 `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`：
         * 后者在 SDK 里是 @hide，编译期拿不到；这个 action 字符串从 API 26 起就稳定存在，
         * 不支持它的 ROM 会在 startActivity 抛异常，由调用方退到无障碍总列表。
         */
        @JvmStatic
        fun detailsIntent(ctx: Context): Intent =
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName(ctx, SafeWalkA11yService::class.java)
                )
            }
    }
}
