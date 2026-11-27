package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 【无障碍】开关 / 滑杆必须有名字。
 *
 * 这条测试的意义：读屏用户聚焦一个开关时，听到的只有「开关，已关闭」——如果没有任何
 * 标签，他根本不知道这是「路况预览」还是「自动跟随步行」，等于这个开关对他不存在。
 * 所以断言不是「有 labelFor 属性」这么表面，而是**能反查到一条非空文字**。
 *
 * 覆盖五张带开关/滑杆的页面布局，逐个真解析、真绑定，避免以后新加控件又漏掉。
 */
@RunWith(RobolectricTestRunner.class)
public class A11yLabelBinderTest {

    private static final int[] PAGES = {
            R.layout.activity_walk_mode,
            R.layout.activity_smart_reminder,
            R.layout.activity_preview_style,
            R.layout.activity_emergency,
            R.layout.activity_feedback
    };

    @Test
    public void everySwitchAndSeekBarHasTextLabel() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(R.style.Theme_Safewalk);
        int total = 0;
        for (int layout : PAGES) {
            View root = LayoutInflater.from(host).inflate(layout, null);
            A11yLabelBinder.bind(root);

            List<View> controls = new ArrayList<>();
            collect(root, controls);
            assertTrue("布局里应该有开关或滑杆: " + layout, controls.size() > 0);
            for (View c : controls) {
                String name = c.getClass().getSimpleName();
                assertTrue(name + " 必须分到 id（labelFor 要指向它）", c.getId() != View.NO_ID);
                TextView label = labelOf(root, c.getId());
                assertNotNull(name + " 在布局 " + host.getResources().getResourceEntryName(layout) + " 里没有文字标签", label);
                assertTrue(name + " 的标签文字不能是空", label.getText().toString().trim().length() > 0);
                total++;
            }
        }
        // 当前仓库的真实数量：28 个开关 + 14 个滑杆。数量变动时这里会提醒重新核对
        assertTrue("绑定到标签的控件数应不少于 40，实际 " + total, total >= 40);
    }

    @Test
    public void bindIsIdempotent() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(R.style.Theme_Safewalk);
        View root = LayoutInflater.from(host).inflate(R.layout.activity_walk_mode, null);
        A11yLabelBinder.bind(root);

        List<View> controls = new ArrayList<>();
        collect(root, controls);
        int[] idsBefore = new int[controls.size()];
        for (int i = 0; i < controls.size(); i++) idsBefore[i] = controls.get(i).getId();

        // 进页面刷新、旋转重建都会再绑一次：不能换 id，也不能把已有的 labelFor 改坏
        A11yLabelBinder.bind(root);
        for (int i = 0; i < controls.size(); i++) {
            assertTrue("重复绑定不能改变控件 id",
                    idsBefore[i] == View.NO_ID || idsBefore[i] == controls.get(i).getId());
        }
    }

    /**
     * 两个曾经接错的控件，钉住结果：
     *  · 「文字描边」开关和「强度」滑杆是上下两段，滑杆一度抢走了开关的标题；
     *  · 手电分钟滑杆的标题是一句带数值的长句，一度被当说明文字跳过。
     * 这两处错法都不会让上面的通用断言失败（有名字就行），所以单独钉。
     */
    @Test
    public void trickyControlsPointAtTheirOwnTitle() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(R.style.Theme_Safewalk);

        View walk = LayoutInflater.from(host).inflate(R.layout.activity_walk_mode, null);
        A11yLabelBinder.bind(walk);
        assertEquals("文字描边",
                labelOf(walk, walk.findViewById(R.id.sw_preview_contrast).getId()).getText().toString());
        assertEquals("强度",
                labelOf(walk, walk.findViewById(R.id.seek_contrast_level).getId()).getText().toString());

        View reminder = LayoutInflater.from(host).inflate(R.layout.activity_smart_reminder, null);
        A11yLabelBinder.bind(reminder);
        String torch = labelOf(reminder, reminder.findViewById(R.id.seek_torch_minutes).getId())
                .getText().toString();
        assertTrue("手电分钟滑杆的标签应是那句带分钟数的说明: " + torch, torch.contains("手电"));
    }

    @Test
    public void nullRootIsSafe() {
        // 首页在 onCreate 里可能先绑一次、视图还没膨胀完：传 null 不能崩
        A11yLabelBinder.bind(null);
    }

    // ---------- 工具 ----------

    private static void collect(View v, List<View> out) {
        if (v instanceof SwitchCompat || v instanceof SeekBar) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** 反查：整棵树里哪个 TextView 声明了 labelFor = 这个控件的 id。 */
    private static TextView labelOf(View root, int controlId) {
        List<TextView> texts = new ArrayList<>();
        collectTexts(root, texts);
        for (TextView t : texts) {
            if (t.getLabelFor() == controlId) return t;
        }
        return null;
    }

    private static void collectTexts(View v, List<TextView> out) {
        if (v instanceof TextView && v.getLabelFor() != View.NO_ID) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out);
        }
    }
}
