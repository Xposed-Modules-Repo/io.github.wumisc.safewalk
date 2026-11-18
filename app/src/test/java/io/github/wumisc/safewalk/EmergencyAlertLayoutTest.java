package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Q4 第 5 组补强 + v1.1 双显示面：锁屏字段数 ≥6、关键字段 ≥18sp（工程师留给 QA）。
 *
 * 两个显示面都要验：
 * ① Activity 完整卡面：inflate activity_emergency_alert.xml，逐 TextView 量文字大小；
 * ② SystemUI 覆盖层：反射调 EmergencyOverlay.buildView() 生成真 View 树，同样量字号/红底。
 *
 * 字号换算：TextView.getTextSize() 返回 px；用 TypedValue 把 18sp 换算成 px 再比较，
 * 避免直接拿 sp 数值和 px 比较的口径错误。
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class EmergencyAlertLayoutTest {

    private Context ctx;
    private float min18spPx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        min18spPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f,
                ctx.getResources().getDisplayMetrics());
    }

    /** 递归收集 View 树里的所有 TextView */
    private static List<TextView> textViews(View root) {
        List<TextView> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** 关键医疗字段 TextView 的 id（住址属 Activity 面专有，其余为双显示面共有） */
    private static final int[] KEY_FIELD_IDS = {
            R.id.tv_alert_name, R.id.tv_alert_blood, R.id.tv_alert_allergy,
            R.id.tv_alert_conditions, R.id.tv_alert_contact, R.id.tv_alert_age,
            R.id.tv_alert_address
    };

    @Test
    public void activityLayout_hasAtLeastSixKeyFields() {
        // Arrange：inflate 真实锁屏 Activity 布局
        View root = LayoutInflater.from(ctx).inflate(R.layout.activity_emergency_alert, null);
        // Act：数一数关键医疗字段
        int count = 0;
        for (int id : KEY_FIELD_IDS) {
            if (root.findViewById(id) instanceof TextView) count++;
        }
        // Assert：G3 要求不解锁可见字段 ≥6
        assertTrue("锁屏卡面关键字段必须 ≥6，实际=" + count, count >= 6);
    }

    @Test
    public void activityLayout_keyFields_textSizeAtLeast18sp() {
        View root = LayoutInflater.from(ctx).inflate(R.layout.activity_emergency_alert, null);
        for (int id : KEY_FIELD_IDS) {
            TextView tv = root.findViewById(id);
            assertNotNull("关键字段控件不能缺失 id=" + id, tv);
            float px = tv.getTextSize();
            assertTrue("关键字段字号必须 ≥18sp id=" + id + " 实际sp=" + (px / (min18spPx / 18f)),
                    px >= min18spPx - 0.5f); // 0.5px 容差抵消 sp→px 取整
        }
    }

    @Test
    public void activityLayout_redBackground_highContrast() {
        View root = LayoutInflater.from(ctx).inflate(R.layout.activity_emergency_alert, null);
        assertTrue("根布局背景必须是红底高对比", root.getBackground() instanceof ColorDrawable);
        int color = ((ColorDrawable) root.getBackground()).getColor();
        assertEquals("红底须为 seal_red(#C8392E)", Color.parseColor("#C8392E"), color);
    }

    /** 反射调用 EmergencyOverlay 的私有 buildView，拿到覆盖层真 View 树（不触发 addView） */
    private static View buildOverlayView(Context c, DisplayFields fields) throws Exception {
        java.lang.reflect.Method m = EmergencyOverlay.class.getDeclaredMethod(
                "buildView", Context.class, DisplayFields.class);
        m.setAccessible(true);
        return (View) m.invoke(EmergencyOverlay.INSTANCE, c, fields);
    }

    @Test
    public void overlayView_rendersKeyFields_atLeast18sp_onRedBackground() throws Exception {
        // Arrange：一张关键字段齐全的最小载荷
        DisplayFields fields = new DisplayFields("张三", "A型", "Rh+", "青霉素", true,
                "心脏病", "李四", "13800000000", 65);
        View root = buildOverlayView(ctx, fields);
        assertNotNull(root);

        // Act：收集覆盖层所有.TextView
        List<TextView> tvs = textViews(root);
        StringBuilder allText = new StringBuilder();
        int keyFieldViews = 0;
        for (TextView tv : tvs) {
            allText.append(tv.getText()).append('|');
            String t = tv.getText().toString();
            if (t.contains("姓名") || t.contains("血型") || t.contains("过敏") ||
                    t.contains("基础疾病") || t.contains("联系人") || t.contains("年龄")) {
                keyFieldViews++;
                float px = tv.getTextSize();
                assertTrue("覆盖层关键字段必须 ≥18sp: " + t, px >= min18spPx - 0.5f);
            }
        }
        // Assert：≥5 项关键字段可见（v1.1 T04 覆盖层验收）
        assertTrue("覆盖层关键字段视图 ≥5，实际=" + keyFieldViews, keyFieldViews >= 5);
        // 关键值必须真的渲染出来（不是空壳）
        assertTrue("覆盖层须渲染姓名值", allText.toString().contains("张三"));
        assertTrue("覆盖层须渲染过敏值", allText.toString().contains("青霉素"));
        assertTrue("覆盖层须渲染疾病值", allText.toString().contains("心脏病"));
        assertTrue("覆盖层须渲染主联系人值", allText.toString().contains("李四"));
    }

    @Test
    public void overlayView_excludesNonWhitelistFields() throws Exception {
        // 即便构造了带住址的卡，DisplayFields 类型层也没有这些字段 → 覆盖层不可能渲染
        EmergencyCard card = new EmergencyCard("self", "张三", 65, Gender.MALE,
                BloodType.A, RhFactor.POSITIVE,
                "上海市浦东新区机密住址100号", "青霉素", true, "心脏病", "某药",
                java.util.Collections.singletonList(
                        new EmergencyContact("李四", "13800000000", "朋友", true)),
                "医保X123456", true, new PrivacyFlags(), 0L);
        DisplayFields fields = EmergencyVault.buildDisplayFields(card);
        View root = buildOverlayView(ctx, fields);
        StringBuilder allText = new StringBuilder();
        for (TextView tv : textViews(root)) allText.append(tv.getText()).append('|');
        String text = allText.toString();
        // 住址/医保号/QR 不得出现在覆盖层任何 TextView（白名单最小化 + 类型层无该字段）
        assertFalse("覆盖层不得显示住址", text.contains("机密住址"));
        assertFalse("覆盖层不得显示医保号", text.contains("医保X123456"));
        assertFalse("覆盖层不得显示 QR", text.contains("qrPayload"));
    }

    @Test
    public void overlayAndActivity_renderSameKeyValues() throws Exception {
        // Arrange：同一张卡 → 同一份最小载荷
        EmergencyCard card = new EmergencyCard("self", "王五", 55, Gender.FEMALE,
                BloodType.O, RhFactor.NEGATIVE, "某住址", "头孢", true, "哮喘", "",
                java.util.Collections.singletonList(
                        new EmergencyContact("赵六", "13900000000", "家属", true)),
                "", false, new PrivacyFlags(), 0L);
        DisplayFields fields = EmergencyVault.buildDisplayFields(card);

        // Act：覆盖层渲染
        StringBuilder overlay = new StringBuilder();
        for (TextView tv : textViews(buildOverlayView(ctx, fields))) overlay.append(tv.getText());
        // Activity 面：用同一份卡填充布局的关键 TextView（模拟 loadCard 的显示值）
        View act = LayoutInflater.from(ctx).inflate(R.layout.activity_emergency_alert, null);
        ((TextView) act.findViewById(R.id.tv_alert_name)).setText(fields.getName());
        ((TextView) act.findViewById(R.id.tv_alert_blood)).setText(
                "血型：" + fields.getBloodType() + " " + fields.getRh());
        ((TextView) act.findViewById(R.id.tv_alert_allergy)).setText("过敏：" + fields.getAllergy());
        ((TextView) act.findViewById(R.id.tv_alert_contact)).setText(
                "紧急联系人：" + fields.getPrimaryContactName() + " " + fields.getPrimaryContactPhone());
        StringBuilder activity = new StringBuilder();
        for (TextView tv : textViews(act)) activity.append(tv.getText());

        // Assert：两显示面对同一张卡的关键值渲染一致
        assertTrue("双显示面姓名一致", overlay.toString().contains(fields.getName())
                && activity.toString().contains(fields.getName()));
        assertTrue("双显示面过敏值一致", overlay.toString().contains("头孢")
                && activity.toString().contains("头孢"));
        assertTrue("双显示面主联系人一致", overlay.toString().contains("赵六")
                && activity.toString().contains("赵六"));
    }
}
