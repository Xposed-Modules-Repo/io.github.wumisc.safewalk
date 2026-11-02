package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import androidx.appcompat.widget.SwitchCompat;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 二级菜单冒烟测试：真正把 Activity 跑起来，验证控件能绑定、开关能落库、
 * 联动状态正确。不依赖真机，CI 可直接执行。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SubPageTest {

    private Context ctx;
    private SharedPreferences sp;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        sp = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
    }

    private boolean prefBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    /** SeekBar 的监听器是私有字段，测试里用反射触发「松手」回调 */
    private void fireStopTracking(SeekBar sb) throws Exception {
        java.lang.reflect.Field f = SeekBar.class.getDeclaredField("mOnSeekBarChangeListener");
        f.setAccessible(true);
        SeekBar.OnSeekBarChangeListener l = (SeekBar.OnSeekBarChangeListener) f.get(sb);
        assertNotNull("SeekBar 未注册监听器", l);
        l.onStopTrackingTouch(sb);
    }

    // ==================== 智能提醒 ====================

    @Test
    public void smartReminder_bindsAllFiveSwitches() {
        SmartReminderActivity a = Robolectric.buildActivity(SmartReminderActivity.class)
                .create().get();

        assertNotNull(a.findViewById(R.id.sw_battery));
        assertNotNull(a.findViewById(R.id.sw_idcard));
        assertNotNull(a.findViewById(R.id.sw_sit));
        assertNotNull(a.findViewById(R.id.sw_night_torch));
        assertNotNull(a.findViewById(R.id.sw_social));

        // 默认全部开启
        assertTrue(((SwitchCompat) a.findViewById(R.id.sw_battery)).isChecked());
        assertTrue(((SwitchCompat) a.findViewById(R.id.sw_social)).isChecked());
    }

    @Test
    public void smartReminder_toggleWritesPreference() {
        SmartReminderActivity a = Robolectric.buildActivity(SmartReminderActivity.class)
                .create().get();

        SwitchCompat sw = a.findViewById(R.id.sw_battery);
        sw.setChecked(false);

        assertFalse(prefBool(SafeWalkPrefKey.enableBatteryWarning, true));
        // 摘要同步刷新
        String summary = ((TextView) a.findViewById(R.id.hero_summary)).getText().toString();
        assertTrue("摘要应反映数量变化: " + summary, summary.contains("已开启 4 项"));
    }

    @Test
    public void smartReminder_disableAllTurnsOffEverySwitch() {
        SmartReminderActivity a = Robolectric.buildActivity(SmartReminderActivity.class)
                .create().get();

        a.findViewById(R.id.btn_all_off).performClick();

        assertFalse(prefBool(SafeWalkPrefKey.enableBatteryWarning, true));
        assertFalse(prefBool(SafeWalkPrefKey.enableIdReminder, true));
        assertFalse(prefBool(SafeWalkPrefKey.enableSitHealthRemind, true));
        assertFalse(prefBool(SafeWalkPrefKey.enableNightTorch, true));
        assertFalse(prefBool(SafeWalkPrefKey.enableSocialTipCard, true));

        String summary = ((TextView) a.findViewById(R.id.hero_summary)).getText().toString();
        assertTrue(summary.contains("全部关闭"));
    }

    @Test
    public void smartReminder_selectTravelModePersists() {
        SmartReminderActivity a = Robolectric.buildActivity(SmartReminderActivity.class)
                .create().get();

        a.findViewById(R.id.chip_party).performClick();

        assertEquals("聚会", sp.getString(SafeWalkPrefKey.travelMode, null));
    }

    @Test
    public void smartReminder_torchMinutesPersist() throws Exception {
        SmartReminderActivity a = Robolectric.buildActivity(SmartReminderActivity.class)
                .create().get();

        SeekBar seek = a.findViewById(R.id.seek_torch_minutes);
        seek.setProgress(30); // 5 + 30 = 35 分钟
        fireStopTracking(seek);

        assertEquals(35f, sp.getFloat(SafeWalkPrefKey.torchStaticMinutes, 0f), 0.01f);
    }

    // ==================== 行走模式 ====================

    @Test
    public void walkMode_defaultStateIsOffAndAutoLocked() {
        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        SwitchCompat rear = a.findViewById(R.id.sw_rear_cam);
        SwitchCompat auto = a.findViewById(R.id.sw_auto);

        assertFalse(rear.isChecked());
        assertFalse("后置看路关闭时自动开关应为禁用态", auto.isEnabled());
    }

    @Test
    public void walkMode_enableRearCamUnlocksAuto() {
        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        SwitchCompat rear = a.findViewById(R.id.sw_rear_cam);
        rear.setChecked(true);

        assertTrue(prefBool(SafeWalkPrefKey.enableRearCamera, false));
        assertTrue("打开后置看路后自动开关应可用",
                ((SwitchCompat) a.findViewById(R.id.sw_auto)).isEnabled());
    }

    @Test
    public void walkMode_smallWindowAndCornerPersist() {
        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        ((SwitchCompat) a.findViewById(R.id.sw_rear_cam)).setChecked(true);

        a.findViewById(R.id.chip_mode_small).performClick();
        assertEquals("smallwindow", sp.getString(SafeWalkPrefKey.rearCamMode, null));

        a.findViewById(R.id.chip_pos_tl).performClick();
        assertEquals("top_left", sp.getString(SafeWalkPrefKey.rearCamCorner, null));
    }

    @Test
    public void walkMode_previewButtonNoCrashWithoutPermission() {
        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        ((SwitchCompat) a.findViewById(R.id.sw_rear_cam)).setChecked(true);
        a.findViewById(R.id.btn_preview_full).performClick();

        // 无相机/悬浮窗权限时不会启动预览服务，也不该崩溃；
        // 预览已不在 SystemUI 进程，不再写一次性指令 testRearCamAction
        assertNull("预览按钮不应再写一次性指令", sp.getString(SafeWalkPrefKey.testRearCamAction, null));
    }

    @Test
    public void walkMode_stepAndStaticPersist() throws Exception {
        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        SeekBar steps = a.findViewById(R.id.seek_steps);
        steps.setProgress(80); // 20 + 80 = 100 步
        fireStopTracking(steps);
        assertEquals(100, sp.getInt(SafeWalkPrefKey.stepThreshold, 0));

        SeekBar stat = a.findViewById(R.id.seek_static);
        stat.setProgress(25); // 5 + 25 = 30 秒
        fireStopTracking(stat);
        assertEquals(30, sp.getInt(SafeWalkPrefKey.staticResetSec, 0));
    }

    // ==================== 重启系统界面 ====================

    @Test
    public void walkMode_restartEntryExistsAndAsksFirst() {
        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        android.view.View card = a.findViewById(R.id.card_restart_systemui);
        assertNotNull("行走模式底部应有「重启系统界面」入口", card);

        card.performClick();
        // 必须是「先确认再执行」，不能点一下就杀 SystemUI
        assertNotNull("点击后应弹确认框",
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }

    // ==================== 配置类型兼容 ====================

    /**
     * 历史版本如果把数值存成 String / Int 混写，SharedPreferences 的 getInt/getFloat
     * 会直接抛 ClassCastException —— 崩在 onCreate 里，症状就是「二级菜单点开闪退」。
     * 这条用例专门制造脏数据，验证页面仍然能正常打开。
     */
    @Test
    public void walkMode_survivesLegacyPreferenceTypes() {
        sp.edit()
                .putString(SafeWalkPrefKey.rearCamOpacity, "70")
                .putString(SafeWalkPrefKey.stepThreshold, "90")
                .putString(SafeWalkPrefKey.staticResetSec, "35")
                .putString(SafeWalkPrefKey.enableRearCamera, "true")
                .commit();

        WalkModeActivity a = Robolectric.buildActivity(WalkModeActivity.class)
                .create().get();

        assertTrue("字符串形式的 true 也应被识别",
                ((SwitchCompat) a.findViewById(R.id.sw_rear_cam)).isChecked());
        assertEquals(60, ((SeekBar) a.findViewById(R.id.seek_opacity)).getProgress());
        assertEquals(70, ((SeekBar) a.findViewById(R.id.seek_steps)).getProgress());
        assertEquals(30, ((SeekBar) a.findViewById(R.id.seek_static)).getProgress());
    }

    @Test
    public void smartReminder_survivesLegacyIntTorchMinutes() {
        // 老版本可能把「手电静置分钟」存成 int，新版用 getFloat 读会 CCE
        sp.edit().putInt(SafeWalkPrefKey.torchStaticMinutes, 40).commit();

        SmartReminderActivity a = Robolectric.buildActivity(SmartReminderActivity.class)
                .create().get();

        assertEquals(35, ((SeekBar) a.findViewById(R.id.seek_torch_minutes)).getProgress());
    }

    // ==================== 配置跨进程通道 ====================

    @Test
    public void configProvider_exposesWhitelistedKeysOnly() throws Exception {
        sp.edit()
                .putBoolean(SafeWalkPrefKey.enableBatteryWarning, false)
                .putString(SafeWalkPrefKey.travelMode, "旅游")
                .putString(SafeWalkPrefKey.emerName, "张三")
                .commit();

        SafeWalkConfig.exportConfig(ctx);

        ConfigProvider provider = new ConfigProvider();
        provider.attachInfo(ctx, null);

        android.database.Cursor c = provider.query(
                android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY + "/config"),
                null, null, null, null);
        assertNotNull(c);
        assertTrue(c.moveToFirst());

        JSONObject json = new JSONObject(c.getString(0));
        c.close();

        assertFalse(json.optBoolean(SafeWalkPrefKey.enableBatteryWarning, true));
        assertEquals("旅游", json.getString(SafeWalkPrefKey.travelMode));
        // 隐私字段绝不能出现在跨进程通道里
        assertFalse(json.has(SafeWalkPrefKey.emerName));
        assertFalse(json.has(SafeWalkPrefKey.emerAddress));
    }
}
