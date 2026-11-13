package io.github.wumisc.safewalk

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务的公共查表逻辑。
 *
 * ## 为什么抽出来
 *
 * 4.7.0 之前这段代码只在 [SafeWalkA11yService] 里，4.8.0 多了
 * [A11yInspectorService] 之后就需要第二份。复制一份的后果是：将来判断规则一改
 * （比如系统把 `ENABLED_ACCESSIBILITY_SERVICES` 分隔符从 `:` 改成别的），
 * 只改了其中一处，另一个服务就永远显示「未开启」。
 *
 * 抽成无状态工具类，两个服务共用同一份判断。
 */
object A11yServiceUtil {

    /**
     * 指定无障碍服务是否已被用户开启。
     *
     * 必须查 `ENABLED_ACCESSIBILITY_SERVICES` 里有没有**本组件**，不能只看
     * `ACCESSIBILITY_ENABLED` 总开关 —— 那只要开了任意一个第三方无障碍服务
     * （输入法、手势、屏幕朗读）就会返回 true，本应用其实根本没被勾选。
     */
    @JvmStatic
    fun isEnabled(ctx: Context, cls: Class<out AccessibilityService>): Boolean = try {
        val raw = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(ctx, cls)
        val flat = me.flattenToString()
        val short = me.flattenToShortString()
        raw.split(':').any { it.equals(flat, true) || it.equals(short, true) }
    } catch (_: Throwable) {
        false
    }

    /**
     * 打开指定服务的系统详情页。
     *
     * 用字符串 action 而不是 `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`：
     * 后者在 SDK 里是 @hide，编译期拿不到；这个 action 串从 API 26 起稳定存在，
     * 不支持它的 ROM 会在 startActivity 抛异常，由调用方退到无障碍总列表。
     */
    @JvmStatic
    fun detailsIntent(ctx: Context, cls: Class<out AccessibilityService>): Intent =
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(ctx, cls))
        }
}

/**
 * 无障碍调试面板的采集服务（开发者工具，**默认不工作**）。
 *
 * ## 它和 [SafeWalkA11yService] 的分工
 *
 * 主服务刻意关掉了 `canRetrieveWindowContent`（不读屏），只拿前台包名做相机避让。
 * 那是给普通用户用的，隐私面越小越好。调试面板需要读整棵节点树，能力完全不同，
 * 所以单独一个服务、单独一条系统授权记录：用户开的是「调试面板」，不是「主功能」。
 *
 * ## 为什么默认不采集
 *
 * 服务一旦被系统连上，`onAccessibilityEvent` 就会持续回调。如果无条件处理，
 * 用户开了调试服务但没开面板，后台照样在遍历节点树——纯浪费电，还增加误报风险。
 * 所以真正的开关是 [collecting]：面板打开时才置 true，退出一律置 false。
 *
 * ## 数据不出设备
 *
 * 事件只进内存环形缓冲（[A11yEventBus]），不写文件、不联网、不外发。
 * 面板关掉即清空。
 */
class A11yInspectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        try {
            DiagLog.log("[a11y-inspect] 调试面板服务已连接（采集开关=${if (collecting) "开" else "关"}）")
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!collecting) return
        val e = event ?: return

        // CONTENT_CHANGED 是量最大的一类：列表滚动、进度条刷新都会触发，且绝大多数
        // 不携带任何文本。全收会在一秒内把 300 条缓冲冲干净，真正有价值的 CLICK /
        // FOCUS 全被挤出去。所以只保留"确实带来了文字"的内容变化。
        val text = buildText(e)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && text.isEmpty()) return

        A11yEventBus.add(
            A11yEventRecord(
                ts = System.currentTimeMillis(),
                type = e.eventType,
                pkg = e.packageName?.toString().orEmpty(),
                cls = A11yEventFormatter.shortClass(e.className?.toString().orEmpty()),
                text = text
            )
        )

        // 面板刷新按 300ms 合并：事件本身可能几十赫兹，跟着刷会把主线程拖住
        val now = System.currentTimeMillis()
        if (now - lastNotifyTs >= NOTIFY_INTERVAL_MS) {
            lastNotifyTs = now
            A11yEventBus.notifyChanged()
        }
    }

    override fun onInterrupt() {
        // 本服务不执行任何反馈动作
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 把事件里的文本 / 内容描述拼成一行；都没有就返回空串。 */
    private fun buildText(e: AccessibilityEvent): String {
        val parts = ArrayList<String>(2)
        try {
            for (i in 0 until e.text.size) {
                val s = e.text[i]?.toString()
                if (!s.isNullOrBlank()) parts.add(s)
            }
        } catch (_: Throwable) {
        }
        try {
            val d = e.contentDescription?.toString()
            if (!d.isNullOrBlank()) parts.add("desc=$d")
        } catch (_: Throwable) {
        }
        return parts.joinToString(" / ")
    }

    companion object {
        /** 面板是否打开着。只有 true 时才真正处理事件。 */
        @Volatile
        var collecting: Boolean = false

        /** 服务是否已被系统连上（内存态）。 */
        @Volatile
        private var connected: Boolean = false

        /**
         * 当前服务实例。
         *
         * 面板（Activity）不是服务，拿不到 `rootInActiveWindow`，必须借服务的手。
         * 存静态引用是这里唯一可行的做法：两者同进程，且服务由系统托管，
         * 生命周期比面板长。`onDestroy` 里比对本实例再置空，避免销毁顺序错乱时
         * 把新实例的引用抹掉。
         */
        @Volatile
        private var instance: A11yInspectorService? = null

        private var lastNotifyTs: Long = 0L

        /** 一次 dump 最多走多少个节点。网页类界面能有几千个，必须设上限。 */
        const val MAX_NODES = 400

        /** 节点树最大深度。正常 App 到不了 25 层，超过基本是自引用或异常结构。 */
        private const val MAX_DEPTH = 25

        /** 面板刷新间隔。 */
        private const val NOTIFY_INTERVAL_MS = 300L

        @JvmStatic
        fun isEnabled(ctx: Context): Boolean =
            A11yServiceUtil.isEnabled(ctx, A11yInspectorService::class.java)

        @JvmStatic
        fun detailsIntent(ctx: Context): Intent =
            A11yServiceUtil.detailsIntent(ctx, A11yInspectorService::class.java)

        /** 服务是否已连接（用于面板顶部状态）。 */
        @JvmStatic
        fun isConnected(): Boolean = connected

        /** 抓当前活动窗口的节点树，供面板调用（内部借服务实例）。 */
        @JvmStatic
        @JvmOverloads
        fun dumpActiveTree(limit: Int = MAX_NODES): List<A11yNode> = dumpTree(instance, limit)

        /**
         * 把当前活动窗口的节点树摊平成列表。
         *
         * 返回空列表有三种情况，都不抛异常：服务没开、当前没有活动窗口（开机动画、
         * 部分系统弹窗）、或者这个窗口拒绝暴露内容（银行类 App 常设
         * `FLAG_SECURE`，无障碍读不到——这本身就是排查时要看到的结论）。
         */
        @JvmStatic
        fun dumpTree(service: AccessibilityService?, limit: Int = MAX_NODES): List<A11yNode> {
            val out = ArrayList<A11yNode>(64)
            val root = try {
                service?.rootInActiveWindow
            } catch (_: Throwable) {
                null
            } ?: return out
            try {
                walk(root, 0, out, limit)
            } catch (_: Throwable) {
                // 遍历途中窗口被销毁会抛 IllegalStateException，已收集的部分仍然有效
            }
            return out
        }

        private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<A11yNode>, limit: Int) {
            if (node == null || out.size >= limit || depth > MAX_DEPTH) return
            out.add(toNode(node, depth))
            val n = try {
                node.childCount
            } catch (_: Throwable) {
                return
            }
            for (i in 0 until n) {
                if (out.size >= limit) break
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                }
                walk(child, depth + 1, out, limit)
            }
        }

        private fun toNode(n: AccessibilityNodeInfo, depth: Int): A11yNode {
            val rect = Rect()
            try {
                n.getBoundsInScreen(rect)
            } catch (_: Throwable) {
            }
            return A11yNode(
                depth = depth,
                className = n.className?.toString()?.let { A11yEventFormatter.shortClass(it) } ?: "?",
                text = n.text?.toString().orEmpty(),
                contentDesc = n.contentDescription?.toString().orEmpty(),
                // viewIdResourceName 需要配置里的 flagReportViewIds，否则恒为 null
                viewId = n.viewIdResourceName?.substringAfterLast('/').orEmpty(),
                clickable = n.isClickable,
                focusable = n.isFocusable,
                bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                childCount = n.childCount
            )
        }
    }
}
