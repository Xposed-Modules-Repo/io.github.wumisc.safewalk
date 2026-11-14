package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 4.9.0 新增两个页面的「能不能打开」测试。
 *
 * 【为什么值得单独写】
 * 布局错误在编译期完全看不出来：id 拼错、引用了不存在的 style、在 4.9.0 里
 * 删了某个控件但代码还在 findViewById —— 这些只会在用户点进页面的那一刻崩，
 * 而且崩在 onCreate，整页白屏。
 *
 * 这里不测交互细节（那属于人工验收），只保证三件事：
 *   1. 页面能真实 inflate 并走完 onCreate/onStart/onResume；
 *   2. 代码里绑定的每个 id 都真的存在（findViewById 返回非 null）；
 *   3. 初次进入时的默认态正确（文字默认关、尺寸/边距为默认值）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NewPagesTest {

    @Test
    public void previewStyleActivity_opensWithDefaults() {
        PreviewStyleActivity a = Robolectric.buildActivity(PreviewStyleActivity.class)
                .setup().get();

        // 关键控件全部存在：任何一个为 null 都会在真实机型上 NPE
        assertNotNull("缺少小窗宽度滑条", a.findViewById(R.id.seek_width));
        assertNotNull("缺少小窗边距滑条", a.findViewById(R.id.seek_margin));
        assertNotNull("缺少不透明度滑条", a.findViewById(R.id.seek_opacity));
        assertNotNull("缺少状态文字开关", a.findViewById(R.id.sw_hud));
        assertNotNull("缺少文字内容输入框", a.findViewById(R.id.et_hud_text));
        assertNotNull("缺少字号滑条", a.findViewById(R.id.seek_hud_size));
        assertNotNull("缺少对比度开关", a.findViewById(R.id.sw_contrast));
        assertNotNull("缺少对比度强度滑条", a.findViewById(R.id.seek_contrast_level));
        // 5.0.0 取代「形态与位置」的三项新控件
        assertNotNull("缺少一键方案芯片", a.findViewById(R.id.chip_preset_night));
        assertNotNull("缺少取景范围滑条", a.findViewById(R.id.seek_framing));
        assertNotNull("缺少小窗描边芯片", a.findViewById(R.id.chip_border_1));
        assertNotNull("缺少低光增强开关", a.findViewById(R.id.sw_low_light));

        // 5.1.0 智能方案：跟随开关 + 夜间时段两个入口
        assertNotNull("缺少「跟随环境自动切换」开关", a.findViewById(R.id.sw_preset_auto));
        assertNotNull("缺少夜间时段起点按钮", a.findViewById(R.id.tv_night_start));
        assertNotNull("缺少夜间时段终点按钮", a.findViewById(R.id.tv_night_end));

        // 5.1.0 画面增强：这才是「让小窗画面看得清」的那组控件。
        // 上一版把「增强对比度」做成了给文字描边，用户明确说那不是他要的，
        // 所以这里正向断言画面增强存在、反向断言它没有被混进文字那一组。
        assertNotNull("缺少画面增强开关", a.findViewById(R.id.sw_screen_enhance));
        assertNotNull("缺少画面对比度滑条", a.findViewById(R.id.seek_screen_contrast));
        assertNotNull("缺少暗部提亮滑条", a.findViewById(R.id.seek_screen_shadow));
        assertNotNull("缺少饱和度滑条", a.findViewById(R.id.seek_screen_sat));

        // 反向断言：形态与位置已归上一页，本页不应再出现这两个控件。
        // 这条不是洁癖——重复入口会让同一个值有两个写点，改 A 忘 B 就会自相矛盾。
        assertNull("本页不应再有形态芯片（已归行走模式页）",
                a.findViewById(R.id.chip_mode_small));
        assertNull("本页不应再有位置芯片（已归行走模式页）",
                a.findViewById(R.id.chip_pos_br));

        // 默认态：状态文字关（这是本次改动的核心语义，不能被误改成默认开）
        androidx.appcompat.widget.SwitchCompat swHud = a.findViewById(R.id.sw_hud);
        assertTrue("状态文字默认必须是关的 —— 画面上不该出现那句无意义的提示",
                !swHud.isChecked());

        // 默认尺寸/边距：150dp / 12dp，滑条 progress 应为 50 / 12
        SeekBar width = a.findViewById(R.id.seek_width);
        assertEquals("宽度滑条默认位置", 50, width.getProgress());
        SeekBar margin = a.findViewById(R.id.seek_margin);
        assertEquals("边距滑条默认位置", 12, margin.getProgress());

        // 全屏是默认形态，因此小窗尺寸控件应置灰
        assertTrue("全屏模式下宽度滑条应不可用", !width.isEnabled());

        TextView summary = a.findViewById(R.id.tv_style_summary);
        assertNotNull(summary);
        assertTrue("摘要应说明当前形态", summary.getText().toString().length() > 0);

        // 5.1.0 默认态：智能跟随默认开（用户要的就是「它自己知道白天还是晚上」），
        // 画面增强默认关（逐像素后处理不该在用户没要求时悄悄改变画面）。
        androidx.appcompat.widget.SwitchCompat swAuto = a.findViewById(R.id.sw_preset_auto);
        assertTrue("智能跟随默认应为开", swAuto.isChecked());
        androidx.appcompat.widget.SwitchCompat swEnhance = a.findViewById(R.id.sw_screen_enhance);
        assertTrue("画面增强默认应为关", !swEnhance.isChecked());

        // 画面增强关着时，三个强度滑条必须置灰 —— 否则用户拖半天毫无变化，
        // 会以为功能坏了（这正是上一版「文字对比度」被吐槽的模式）。
        SeekBar sc = a.findViewById(R.id.seek_screen_contrast);
        assertTrue("画面增强关闭时对比度滑条应置灰", !sc.isEnabled());

        // 夜间时段默认 22:00 → 06:00，且必须是补零格式
        TextView start = a.findViewById(R.id.tv_night_start);
        TextView end = a.findViewById(R.id.tv_night_end);
        assertEquals("夜间时段起点默认", "22:00", start.getText().toString());
        assertEquals("夜间时段终点默认", "06:00", end.getText().toString());
    }

    /**
     * 权限与无障碍页：版式统一 + 状态栏不被压住。
     *
     * 这一页原来是一整套手写 LinearLayout，根节点没有 fitsSystemWindows、
     * 顶部内边距只有 24dp，标题直接顶在状态栏下面。改成 PageScroll 之后
     * 这两件事由 style 保证，这里把保证钉住。
     */
    @Test
    public void permissionActivity_usesUnifiedPageSkeleton() {
        PermissionOnboardingActivity a = Robolectric.buildActivity(
                PermissionOnboardingActivity.class).setup().get();

        assertNotNull("缺少返回按钮", a.findViewById(R.id.btn_back));
        assertNotNull("缺少副标题", a.findViewById(R.id.tv_perm_summary));
        assertNotNull("缺少本机 UI 说明行", a.findViewById(R.id.tv_perm_rom));
        assertNotNull("缺少清单容器", a.findViewById(R.id.perm_rows));
        assertNotNull("缺少一键申请按钮", a.findViewById(R.id.btn_apply_runtime));
        assertNotNull("缺少逐项开启按钮", a.findViewById(R.id.btn_special));
        assertNotNull("缺少完成按钮", a.findViewById(R.id.btn_done));

        // 清单必须真的插进去了：8 项条目 + 7 条分隔线
        android.view.ViewGroup rows = a.findViewById(R.id.perm_rows);
        assertTrue("清单条目应已插入，实际子视图数 " + rows.getChildCount(),
                rows.getChildCount() >= 8);

        // 状态栏：根节点必须是 ScrollView + fitsSystemWindows，
        // 且顶部内边距 ≥ 40dp（避开状态栏的那个值，与 PageScroll 一致）
        View content = ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        assertTrue("根节点应为 ScrollView", content instanceof ScrollView);
        assertTrue("根节点必须 fitsSystemWindows，否则内容会画到状态栏下面",
                content.getFitsSystemWindows());
        int topPx = content.getPaddingTop();
        float density = a.getResources().getDisplayMetrics().density;
        assertTrue("顶部内边距应 ≥ 40dp 以避开状态栏，实际 " + (topPx / density) + "dp",
                topPx >= Math.round(40 * density) - 2);
    }

    @Test
    public void feedbackActivity_opensAndShowsEnvInfo() {        FeedbackActivity a = Robolectric.buildActivity(FeedbackActivity.class)
                .setup().get();

        assertNotNull("缺少建议输入框", a.findViewById(R.id.et_suggestion));
        assertNotNull("缺少附带信息开关", a.findViewById(R.id.sw_attach));
        assertNotNull("缺少环境信息预览", a.findViewById(R.id.tv_env_preview));
        assertNotNull("缺少提交按钮", a.findViewById(R.id.btn_submit));
        assertNotNull("缺少复制按钮", a.findViewById(R.id.btn_copy));

        androidx.appcompat.widget.SwitchCompat sw = a.findViewById(R.id.sw_attach);
        assertTrue("附带设备信息默认应开启 —— 否则多半要来回问三轮", sw.isChecked());

        TextView env = a.findViewById(R.id.tv_env_preview);
        String text = env.getText().toString();
        assertTrue("环境信息应包含机型", text.contains("机型"));
        assertTrue("环境信息应包含系统版本", text.contains("Android"));
        assertTrue("环境信息应包含应用版本", text.contains("应用"));
    }
}
