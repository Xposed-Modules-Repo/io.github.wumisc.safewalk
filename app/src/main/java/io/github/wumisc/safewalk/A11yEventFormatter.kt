package io.github.wumisc.safewalk

/**
 * 无障碍调试面板的纯格式化逻辑。
 *
 * ## 为什么单独抽出来
 *
 * 「事件类型叫什么、节点树缩进多少、文本多长要截断」这三件事全是**字符串规则**，
 * 跟 Android 一点关系都没有。抽出来之后可以在 JVM 里直接断言，不用起模拟器。
 * 调试工具最怕的就是「面板自己格式错乱，却让人以为是目标 App 的问题」——
 * 这份规则有测试守着，面板输出的可信度才有底。
 *
 * ## 事件类型的数字从哪来
 *
 * `AccessibilityEvent.TYPE_*` 是 `android.view.accessibility` 里的常量，直接引用
 * 会让本文件在纯 JVM 单测里编译不过（android.jar 的桩方法会抛异常；常量本身其实
 * 是编译期内联的，但为了不依赖那层，这里镜像一份）。数值与 AOSP 一致，改动不了。
 */
object A11yEventFormatter {

    /** 单行里文本的最大显示长度。再长会撑爆面板，也会让日志难读。 */
    const val MAX_TEXT = 60

    /** 节点树最多输出多少行。极端页面（网页、长列表）能有几千个节点。 */
    const val MAX_TREE_LINES = 400

    // ---- 镜像自 AccessibilityEvent.TYPE_* ----
    private const val T_VIEW_CLICKED = 0x00000001
    private const val T_VIEW_LONG_CLICKED = 0x00000002
    private const val T_VIEW_SELECTED = 0x00000004
    private const val T_VIEW_FOCUSED = 0x00000008
    private const val T_VIEW_TEXT_CHANGED = 0x00000010
    private const val T_WINDOW_STATE_CHANGED = 0x00000020
    private const val T_NOTIFICATION_STATE_CHANGED = 0x00000040
    private const val T_VIEW_HOVER_ENTER = 0x00000080
    private const val T_VIEW_HOVER_EXIT = 0x00000100
    private const val T_WINDOW_CONTENT_CHANGED = 0x00000800
    private const val T_VIEW_SCROLLED = 0x00001000
    private const val T_VIEW_TEXT_SELECTION_CHANGED = 0x00002000
    private const val T_ANNOUNCEMENT = 0x00004000
    private const val T_VIEW_ACCESSIBILITY_FOCUSED = 0x00008000
    private const val T_VIEW_ACCESSIBILITY_FOCUS_CLEARED = 0x00010000
    private const val T_TOUCH_EXPLORATION_GESTURE_START = 0x00000200
    private const val T_TOUCH_EXPLORATION_GESTURE_END = 0x00000400
    private const val T_GESTURE_DETECTION_START = 0x00040000
    private const val T_GESTURE_DETECTION_END = 0x00080000

    /**
     * 事件类型 → 可读名字。
     *
     * 不认识的类型输出 `0x00002000` 这样的十六进制，而不是 `UNKNOWN`：
     * 排查新 ROM（尤其 Flyme / 国产定制）时，那个数字能直接拿去 AOSP 源码里查。
     */
    @JvmStatic
    fun eventTypeName(type: Int): String = when (type) {
        T_VIEW_CLICKED -> "CLICK"
        T_VIEW_LONG_CLICKED -> "LONG_CLICK"
        T_VIEW_SELECTED -> "SELECTED"
        T_VIEW_FOCUSED -> "FOCUS"
        T_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
        T_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
        T_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION"
        T_VIEW_HOVER_ENTER -> "HOVER_ENTER"
        T_VIEW_HOVER_EXIT -> "HOVER_EXIT"
        T_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        T_VIEW_SCROLLED -> "SCROLLED"
        T_VIEW_TEXT_SELECTION_CHANGED -> "TEXT_SELECTION"
        T_ANNOUNCEMENT -> "ANNOUNCEMENT"
        T_VIEW_ACCESSIBILITY_FOCUSED -> "A11Y_FOCUS"
        T_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "A11Y_FOCUS_CLEAR"
        T_TOUCH_EXPLORATION_GESTURE_START -> "TOUCH_EXPLORE_START"
        T_TOUCH_EXPLORATION_GESTURE_END -> "TOUCH_EXPLORE_END"
        T_GESTURE_DETECTION_START -> "GESTURE_START"
        T_GESTURE_DETECTION_END -> "GESTURE_END"
        else -> "0x" + Integer.toHexString(type).uppercase().padStart(8, '0')
    }

    /**
     * 事件一行摘要，形如：
     * `12:03:41.120 CLICK  com.tencent.mm  TextView  '微信'`
     */
    @JvmStatic
    fun oneLine(
        hhmmss: String,
        type: Int,
        pkg: String,
        className: String,
        text: String
    ): String {
        val sb = StringBuilder(64)
        sb.append(hhmmss).append(' ')
        sb.append(eventTypeName(type)).append(' ')
        sb.append(pkg.ifEmpty { "-" }).append(' ')
        sb.append(shortClass(className))
        val t = clip(text)
        if (t.isNotEmpty()) sb.append("  '").append(t).append('\'')
        return sb.toString()
    }

    /**
     * 节点树格式化成缩进文本。
     *
     * 超长截断时在末尾补一行 `… 还有 N 个节点未显示`——静悄悄截断会让人以为
     * 「这页面就这么点东西」，从而漏掉真正的问题节点。
     */
    @JvmStatic
    @JvmOverloads
    fun formatTree(nodes: List<A11yNode>, maxLines: Int = MAX_TREE_LINES): String {
        if (nodes.isEmpty()) return "(空节点树)"
        val sb = StringBuilder(4096)
        var shown = 0
        for (n in nodes) {
            if (shown >= maxLines) break
            if (shown > 0) sb.append('\n')
            sb.append(nodeLine(n))
            shown++
        }
        if (nodes.size > shown) {
            sb.append("\n… 还有 ").append(nodes.size - shown).append(" 个节点未显示")
        }
        return sb.toString()
    }

    /** 单个节点一行，缩进 2 空格 × depth，再带 `#深度` 便于对照。 */
    @JvmStatic
    fun nodeLine(n: A11yNode): String {
        val sb = StringBuilder(96)
        repeat(n.depth.coerceAtLeast(0)) { sb.append("  ") }
        sb.append('#').append(n.depth).append(' ')
        sb.append(n.className.ifEmpty { "?" })
        val t = clip(n.text)
        if (t.isNotEmpty()) sb.append(" text='").append(t).append('\'')
        val d = clip(n.contentDesc)
        if (d.isNotEmpty()) sb.append(" desc='").append(d).append('\'')
        if (n.viewId.isNotEmpty()) sb.append(" id=").append(n.viewId)
        if (n.clickable) sb.append(" [点击]")
        if (n.focusable) sb.append(" [焦点]")
        if (n.bounds.isNotEmpty()) sb.append(' ').append(n.bounds)
        return sb.toString()
    }

    /**
     * 「这个节点到底有没有可读内容」——读屏增强要解决的正是这个问题。
     *
     * 一个可点击的 ImageView 既无 text 也无 desc，TalkBack 只会念「按钮」，
     * 用户完全不知道点了会发生什么。调试面板把它标红，读屏增强层据此补描述。
     */
    @JvmStatic
    fun hasNoLabel(n: A11yNode): Boolean =
        n.text.isBlank() && n.contentDesc.isBlank() && n.viewId.isBlank()

    /**
     * 截断到 [MAX_TEXT]，超出用 `…` 收尾。
     *
     * 同时把换行、制表、连续空格全部压成单个空格：一条事件必须占一行，
     * 否则面板里三行是同一个事件，眼睛根本无法按行扫。
     */
    @JvmStatic
    fun clip(raw: String): String {
        val flat = raw.split(' ', '\n', '\r', '\t')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (flat.length <= MAX_TEXT) return flat
        return flat.substring(0, MAX_TEXT - 1) + "…"
    }

    /** `android.widget.TextView` → `TextView`；已短名或空则原样返回。 */
    @JvmStatic
    fun shortClass(fqcn: String): String {
        if (fqcn.isEmpty()) return "-"
        val i = fqcn.lastIndexOf('.')
        return if (i < 0) fqcn else fqcn.substring(i + 1)
    }
}
