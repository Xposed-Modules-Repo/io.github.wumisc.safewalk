package io.github.wumisc.safewalk

import android.view.View

/**
 * 【无障碍 · HUD 节点绑定】把 HUD 整列压成**一个**无障碍节点。
 *
 * 纯 View 层动作，从 `RearCameraOverlay.syncHudA11y()` 抽出来，为的是能被 Robolectric
 * 沙盒测试直接验证出真实的 [android.view.accessibility.AccessibilityNodeInfo] 树 ——
 * 光靠纯逻辑单测只能证明"文案拼对了"，证明不了"读屏真的只看到一个节点"。
 */
object HudA11yBinder {

    /**
     * 整列接管播报，子节点退出无障碍树。
     *
     * @param desc 已经由 [HudA11yPolicy.compose] 合成好的那句话，调用方保证非空白
     */
    @JvmStatic
    fun bind(column: View, hud: View?, badge: View?, desc: String) {
        column.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        hud?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        badge?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (column.contentDescription?.toString() != desc) column.contentDescription = desc
    }

    /** 整列收起或没有可念内容时清空语义，别把旧文案留在树里。 */
    @JvmStatic
    fun clear(column: View) {
        if (column.contentDescription != null) column.contentDescription = null
    }
}
