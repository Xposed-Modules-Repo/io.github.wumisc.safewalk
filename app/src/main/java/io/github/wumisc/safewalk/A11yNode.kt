package io.github.wumisc.safewalk

/**
 * 无障碍节点树的一行快照（纯数据）。
 *
 * ## 为什么不直接用 `AccessibilityNodeInfo`
 *
 * 那个类必须由系统构造，本地 JVM 单测一碰就抛 "not mocked"。把节点先摊平成这个
 * 纯 Kotlin 数据类，[A11yEventFormatter] 就能在单测里断言「缩进对不对、空字段会不会
 * 输出成 `null`、超长文本有没有被截断」——这些都是调试面板真正会踩的坑。
 *
 * 真正从 `AccessibilityNodeInfo` 到这里的转换放在
 * [A11yInspectorService.toNode] 里，那是唯一需要 Android 运行时的地方。
 *
 * @param depth      层级，0 是窗口根节点
 * @param className  控件类名，已取短名（`android.widget.TextView` → `TextView`）
 * @param text       `getText()`，无则空串
 * @param contentDesc `getContentDescription()`，无则空串
 * @param viewId     资源 id 名（`com.tencent.mm:id/title` → `title`），无则空串
 * @param clickable  是否可点击
 * @param focusable  是否可获取无障碍焦点
 * @param bounds     屏幕坐标 `[l,t][r,b]`，无则空串
 * @param childCount 子节点数（用于判断「这个容器是不是空的」）
 */
data class A11yNode @JvmOverloads constructor(
    val depth: Int,
    val className: String,
    val text: String = "",
    val contentDesc: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val bounds: String = "",
    val childCount: Int = 0
)
