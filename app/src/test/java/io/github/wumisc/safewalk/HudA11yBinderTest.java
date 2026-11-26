package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

/**
 * 【无障碍沙盒】HUD 节点绑定（Robolectric，TalkBack 开启态）。
 *
 * 为什么不上真机：浮层要 LSPosed 挂进相机 App 才会出现，本机没有能跑 Xposed 的设备。
 * 而这里要证的东西是 View 层的确定性行为——读屏是否会把这列 HUD 拆成碎片、是否会念旧文案。
 *
 * 沙盒约束（已实测，写下来免得下次误判）：
 * · 必须先把无障碍打开。关闭态下 AUTO 节点全部被判为不重要，任何断言都会假通过。
 * · Robolectric 里 `createAccessibilityNodeInfo()` 的 **childCount 恒为 0**（子节点要靠
 *   AccessibilityInteractionController 才挂上），所以不能用 childCount 证明"只剩一个节点"；
 *   改用系统判定是否进入无障碍树的那个开关本身：节点的
 *   [AccessibilityNodeInfo#isImportantForAccessibility]。它是 TalkBack 过滤节点的真实依据。
 */
@RunWith(RobolectricTestRunner.class)
public class HudA11yBinderTest {

    private static final String HUD = "路况预览中";
    private static final String STEP = "今日 128 步";

    private ActivityController<Activity> controller;

    @Before
    public void enableAccessibility() {
        Context ctx = ApplicationProvider.getApplicationContext();
        AccessibilityManager am =
                (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(am).setEnabled(true);
        assertTrue("沙盒必须处于 TalkBack 开启态，否则节点全被判为不重要", am.isEnabled());
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
    }

    /** 与 RearCameraOverlay 同构的两行 HUD，挂进真窗口。 */
    private LinearLayout buildColumn() {
        Context ctx = controller.get();
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView hud = new TextView(ctx);
        hud.setText(HUD);
        TextView badge = new TextView(ctx);
        badge.setText(STEP);
        column.addView(hud);
        column.addView(badge);
        controller.get().setContentView(column);
        column.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        column.layout(0, 0, 1080, 1920);
        return column;
    }

    private static AccessibilityNodeInfo node(View v) {
        return v.createAccessibilityNodeInfo();
    }

    @Test
    public void beforeBind_bothRowsEnterAccessibilityTree_thatIsTheProblem() {
        LinearLayout column = buildColumn();
        AccessibilityNodeInfo hud = node(column.getChildAt(0));
        AccessibilityNodeInfo badge = node(column.getChildAt(1));
        assertNotNull(hud);
        assertNotNull(badge);
        assertTrue("未做绑定时状态文字是独立节点（会单独念「路况预览中」）",
                hud.isImportantForAccessibility());
        assertTrue("未做绑定时步数也是独立节点（会单独念「今日 128 步」）",
                badge.isImportantForAccessibility());
        assertEquals("路况预览中", String.valueOf(hud.getText()));
        assertEquals("今日 128 步", String.valueOf(badge.getText()));
        assertNull("此时整列还没有合成语义", column.getContentDescription());
    }

    @Test
    public void afterBind_childrenLeaveTree_andColumnCarriesOneSentence() {
        LinearLayout column = buildColumn();
        String desc = HudA11yPolicy.compose(HUD, STEP);
        HudA11yBinder.bind(column, column.getChildAt(0), column.getChildAt(1), desc);

        assertEquals("合成句应一行含两段语义", HUD + "，" + STEP, desc);
        assertEquals(desc, String.valueOf(column.getContentDescription()));
        assertTrue("整列必须进入无障碍树", node(column).isImportantForAccessibility());
        assertFalse("状态文字退出无障碍树，避免念成碎片",
                node(column.getChildAt(0)).isImportantForAccessibility());
        assertFalse("步数退出无障碍树，避免念成碎片",
                node(column.getChildAt(1)).isImportantForAccessibility());
    }

    @Test
    public void rebindSameText_keepsSameDescription() {
        LinearLayout column = buildColumn();
        String desc = HudA11yPolicy.compose(HUD, STEP);
        HudA11yBinder.bind(column, column.getChildAt(0), column.getChildAt(1), desc);
        HudA11yBinder.bind(column, column.getChildAt(0), column.getChildAt(1), desc);
        assertEquals(desc, String.valueOf(column.getContentDescription()));
        assertFalse(node(column.getChildAt(0)).isImportantForAccessibility());
    }

    @Test
    public void clear_removesStaleDescription_soFocusedNodeDoesNotReadOldText() {
        LinearLayout column = buildColumn();
        HudA11yBinder.bind(column, column.getChildAt(0), column.getChildAt(1),
                HudA11yPolicy.compose(HUD, STEP));
        HudA11yBinder.clear(column);
        assertNull("收起后不能留着旧文案让读屏聚焦时念出", column.getContentDescription());
    }

    @Test
    public void stepOnly_keepsStepSemantics() {
        LinearLayout column = buildColumn();
        HudA11yBinder.bind(column, column.getChildAt(0), column.getChildAt(1),
                HudA11yPolicy.compose(null, STEP));
        assertEquals(STEP, String.valueOf(column.getContentDescription()));
        assertTrue(node(column).isImportantForAccessibility());
    }
}
