package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 【无障碍 · 版面契约】两条容易回退的硬约束，钉在测试里：
 *
 * 1. **可点选择块不许用固定小高度**：`36dp` 高的块在系统字体放大后会裁字。
 *    正确写法是 `wrap_content` + `minHeight="48dp"`：常规字号下 48dp（触控下限也够），
 *    放大后跟着长高，字永远看得见。
 * 2. **分区标题必须是 heading**：读屏用户靠「跳到下一个标题」跳过分区，
 *    标题只是普通 TextView 的话，只能一条条听完。
 *
 * 这两条都在样式/布局层，写错了界面看不出问题，只有读屏用户会撞上，所以用测试兜。
 */
@RunWith(RobolectricTestRunner.class)
public class A11yLayoutContractTest {

    private static final int[] CHIP_LAYOUTS = {
            R.layout.activity_smart_reminder,
            R.layout.activity_walk_mode
    };

    /** 需要核对 heading 数量的页面（数量取自布局里 SectionLabel 的实际用量）。 */
    private static final int[][] HEADING_PAGES = {
            {R.layout.activity_smart_reminder, 3},
            {R.layout.activity_walk_mode, 4},
            {R.layout.activity_preview_style, 11},
            {R.layout.activity_feedback, 2},
            {R.layout.activity_thanks, 2}
    };

    @Test
    public void chipsGrowWithFontScaleInsteadOfClipping() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(R.style.Theme_Safewalk);
        int min = (int) (48 * host.getResources().getDisplayMetrics().density);

        for (int layout : CHIP_LAYOUTS) {
            View root = LayoutInflater.from(host).inflate(layout, null);
            List<TextView> chips = new ArrayList<>();
            collectChips(root, chips);
            int n = 0;
            for (TextView chip : chips) {
                ViewGroup.LayoutParams lp = chip.getLayoutParams();
                assertTrue("选择块 " + name(host, chip) + " 的高度必须是 wrap_content"
                                + "（固定高度在字体放大后会裁字），实际=" + lp.height,
                        lp.height == ViewGroup.LayoutParams.WRAP_CONTENT);
                assertTrue("选择块 " + name(host, chip) + " 的 minHeight 必须 >= 48dp 触控下限，实际="
                                + chip.getMinHeight(),
                        chip.getMinHeight() >= min);
                n++;
            }
            // 数量按布局现状核对：改动选择块时这里会先红，提醒同步契约
            int expect = layout == R.layout.activity_smart_reminder ? 4 : 8;
            assertEquals("布局 " + host.getResources().getResourceEntryName(layout)
                    + " 的选择块数量变了，请核对契约是否仍覆盖全", expect, n);
        }
    }

    @Test
    public void sectionLabelsAreHeadings() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(R.style.Theme_Safewalk);
        for (int[] page : HEADING_PAGES) {
            View root = LayoutInflater.from(host).inflate(page[0], null);
            List<TextView> headings = new ArrayList<>();
            collectHeadings(root, headings);
            assertEquals("布局 " + host.getResources().getResourceEntryName(page[0])
                            + " 的分区标题数量不对：读屏靠它跳转，少了就等于没有分区",
                    page[1], headings.size());
            for (TextView h : headings) {
                assertTrue("heading 必须有文字，空标题会让读屏停在一个没有名字的地方",
                        h.getText().toString().trim().length() > 0);
            }
        }
    }

    // ---------- 工具 ----------

    private static String name(Activity host, View v) {
        try {
            return v.getId() == View.NO_ID ? "(无id)" : host.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    /** 选择块 = id 以 chip_ 开头的 TextView（背景/文案都各不相同，只有 id 稳定）。 */
    private static void collectChips(View v, List<TextView> out) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getId() != View.NO_ID) {
                try {
                    if (t.getResources().getResourceEntryName(t.getId()).startsWith("chip_")) out.add(t);
                } catch (Throwable ignored) {
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectChips(g.getChildAt(i), out);
        }
    }

    private static void collectHeadings(View v, List<TextView> out) {
        if (v instanceof TextView && v.isAccessibilityHeading()) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectHeadings(g.getChildAt(i), out);
        }
    }
}
