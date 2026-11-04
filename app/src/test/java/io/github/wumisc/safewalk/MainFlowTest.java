package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 首页 → 二级菜单的导航链路测试，以及模板完整度校验。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainFlowTest {

    private Application app;

    @Rule
    public GrantPermissionRule permissionRule = GrantPermissionRule.grant(
            android.Manifest.permission.ACTIVITY_RECOGNITION,
            android.Manifest.permission.POST_NOTIFICATIONS);

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("safewalk_xsp", android.content.Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        // 跳过首次启动引导弹窗
        app.getSharedPreferences("safewalk", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("activated", true).commit();
    }

    @Test
    public void mainActivity_bindsFourTiles() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();

        assertNotNull(a.findViewById(R.id.tile_enable));
        assertNotNull(a.findViewById(R.id.tile_smart));
        assertNotNull(a.findViewById(R.id.tile_walk));
        assertNotNull(a.findViewById(R.id.tile_emergency));

        // 磁贴状态文本随二级页开关变化，必须可绑定
        assertNotNull(a.findViewById(R.id.tile_smart_status));
    }

    @Test
    public void mainActivity_smartTileOpensSubPage() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();

        a.findViewById(R.id.tile_smart).performClick();

        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull("点击智能提醒磁贴应启动二级页", next);
        ComponentName cn = next.getComponent();
        assertNotNull(cn);
        assertEquals(SmartReminderActivity.class.getName(), cn.getClassName());
    }

    @Test
    public void mainActivity_walkTileOpensSubPage() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();

        a.findViewById(R.id.tile_walk).performClick();

        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(WalkModeActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void mainActivity_smartTileSummaryReflectsSwitches() {
        app.getSharedPreferences("safewalk_xsp", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SafeWalkPrefKey.enableBatteryWarning, true)
                .putBoolean(SafeWalkPrefKey.enableIdReminder, true)
                .putBoolean(SafeWalkPrefKey.enableSitHealthRemind, false)
                .putBoolean(SafeWalkPrefKey.enableNightTorch, false)
                .putBoolean(SafeWalkPrefKey.enableSocialTipCard, false)
                .commit();

        MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();

        String text = ((android.widget.TextView) a.findViewById(R.id.tile_smart_status))
                .getText().toString();
        assertTrue("应显示已开启数量: " + text, text.contains("2/5"));
    }

    @Test
    public void travelTemplate_coversFourModes() {
        for (String mode : new String[]{"求职", "约会", "聚会", "旅游"}) {
            assertTrue(mode + " 应有模板", !TravelTemplate.INSTANCE.getTemplate(mode).isEmpty());
        }
        assertTrue("未知模式返回空列表", TravelTemplate.INSTANCE.getTemplate("其他").isEmpty());
    }
}
