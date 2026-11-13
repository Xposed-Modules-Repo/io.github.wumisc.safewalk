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
 * 【它做三件事】
 * 1. **相机避让**：前台是相机 / 录像 / 视频通话类应用时收起角落小窗，离开后恢复。
 *    这条原先只有 Xposed Hook（SystemUI 进程）在做，未激活 LSPosed 或 Hook 未生效时
 *    就没有人管；无障碍服务是**不依赖 Xposed 的备用通道**。
 * 2. **行走语音播报**（4.8.0）：开关打开时额外订阅通知事件，把来电与即时消息交给
 *    [WalkVoiceAnnouncer] 用 TTS 念出来。给视力障碍用户用，避免他们为了看一条消息
 *    而停在路边。
 * 3. **前台包名感知**：上面两件事都只需要包名与事件类型。
 *
 * 【它不做什么】
 * `canRetrieveWindowContent=false`：**不读取屏幕文字**、不监听输入、不注入手势。
 * 语音播报读的是通知事件的文本（系统主动推给无障碍服务的那部分），不是屏幕内容；
 * 想读整棵控件树的是另一个服务 [A11yInspectorService]，需要单独授权。
 *
 * 【为什么第二件事要动态改订阅】
 * XML 里只订阅 `typeWindowStateChanged`（最小权限）。语音播报需要
 * `TYPE_NOTIFICATION_STATE_CHANGED`，但这个能力只在用户打开开关后才该生效，
 * 所以运行时用 [applySubscription] 增删，而不是在 XML 里一次开满。
 */
class SafeWalkA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        sEnabled = true
        inst = this
        try {
            DiagLog.init(applicationContext)
            DiagLog.log("[a11y] 无障碍服务已连接 → 前台相机应用避让通道可用（不读屏、不联网）")
        } catch (_: Throwable) {
        }
        applySubscription()
        // 连接瞬间可能已经有相机类应用在前台，先按当前状态评估一次
        WalkPreviewService.onForegroundAppChanged(lastPkg ?: "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        // ---- 语音播报：先处理，它对「不要漏掉来电」最敏感 ----
        if (e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            handleWalkVoice(e)
        }

        // ---- 相机避让：只处理窗口切换；内容变化（TYPE_WINDOW_CONTENT_CHANGED）一律忽略 ----
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

    /** 把事件交给语音播报；开关关着时立即返回，不做任何字符串处理。 */
    private fun handleWalkVoice(e: AccessibilityEvent) {
        if (!isWalkVoiceOn()) return
        val pkg = try {
            e.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (pkg.isEmpty()) return
        val cls = try {
            e.className?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val kind = WalkVoicePolicy.classify(pkg, e.eventType, cls)
        if (kind == WalkVoicePolicy.Kind.IGNORE) return

        val body = try {
            e.text.joinToString(" ") { it?.toString().orEmpty() }
        } catch (_: Throwable) {
            ""
        }
        WalkVoiceAnnouncer.trySpeak(applicationContext, pkg, kind, body)
    }

    /**
     * 按当前开关同步订阅的事件类型。
     *
     * `or` / `and inv` 都是幂等的：反复调用不会让 eventTypes 越滚越大，
     * 也不会把 XML 里的基础订阅弄丢。
     */
    private fun applySubscription() {
        try {
            val info = serviceInfo ?: return
            val on = isWalkVoiceOn()
            val base = info.eventTypes
            val next = if (on) {
                base or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            } else {
                base and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED.inv()
            }
            if (next != base) {
                info.eventTypes = next
                setServiceInfo(info)
            }
        } catch (e: Throwable) {
            DiagLog.log("[a11y] 更新事件订阅失败：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun isWalkVoiceOn(): Boolean = try {
        val sp = getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
        SafePrefs.getBool(sp, SafeWalkPrefKey.enableWalkVoice, false)
    } catch (_: Throwable) {
        false
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
        if (inst === this) inst = null
        // TTS 持有音频通道，服务销毁必须释放，否则下次播报会双份声音
        WalkVoiceAnnouncer.shutdown()
        super.onDestroy()
    }

    companion object {
        /** 服务是否已连接（仅内存态，跨进程/重启后以系统设置为准） */
        @Volatile
        private var sEnabled = false

        private var lastPkg: String? = null

        /**
         * 当前服务实例。
         *
         * 设置页改完「行走语音播报」开关后，需要让已在运行的服务重新计算订阅的事件类型，
         * 但设置页拿不到服务对象（它由系统托管）。存静态引用是唯一可行的通道；
         * 销毁时比对本实例再置空，避免销毁顺序错乱时把新实例抹掉。
         */
        @Volatile
        private var inst: SafeWalkA11yService? = null

        /**
         * 开关变化后调用，让运行中的服务立即更新订阅。
         *
         * 服务没在运行时是空操作——用户下次开启服务时会走 onServiceConnected 里
         * 同一个 [applySubscription]，结果一致。
         */
        @JvmStatic
        fun refreshSubscription() {
            inst?.applySubscription()
        }

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
            return A11yServiceUtil.isEnabled(ctx, SafeWalkA11yService::class.java)
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
