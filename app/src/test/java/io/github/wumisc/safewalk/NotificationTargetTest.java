package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * 通知与跨进程跳转的回归测试。
 *
 * 这两处是最容易「编译通过、真机点了没反应」的地方：
 *   ① 通知的 PendingIntent 用了 SystemUI 的 Context 去 new Intent(ctx, MainActivity)，
 *      会拼出 com.android.systemui/...MainActivity，点击必然打不开；
 *   ② 手电按钮发的是隐式广播，而 Hook 用 RECEIVER_NOT_EXPORTED 注册，永远收不到。
 * 两个问题都不会崩溃，只会静默失效，所以必须用测试锁死。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationTargetTest {

    private Context ctx;
    private NotificationManager nm;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 普通提醒：点通知必须能打开本应用，而不是 SystemUI */
    @Test
    public void reminderContentIntent_opensOwnApp() {
        SafeWalkNotificationManager m = new SafeWalkNotificationManager(ctx);
        m.idReminder();

        Notification n = shadowOf(nm).getNotification(SafeWalkNotificationManager.ID_IDCARD);
        assertNotNull("证件提醒通知没有发出", n);

        ShadowPendingIntent spi = shadowOf(n.contentIntent);
        assertTrue("contentIntent 必须是 Activity 类型", spi.isActivityIntent());

        Intent saved = spi.getSavedIntent();
        assertNotNull(saved.getComponent());
        assertEquals("io.github.wumisc.safewalk",
                saved.getComponent().getPackageName());
        assertEquals("io.github.wumisc.safewalk.MainActivity",
                saved.getComponent().getClassName());
    }

    /** 夜间手电：按钮广播必须显式投递到 SystemUI，并带令牌 */
    @Test
    public void torchAction_targetsSystemUiWithToken() {
        SafeWalkNotificationManager m = new SafeWalkNotificationManager(ctx);
        m.flashlightSuggest();

        Notification n = shadowOf(nm).getNotification(SafeWalkNotificationManager.ID_TORCH);
        assertNotNull("夜间照明通知没有发出", n);
        assertNotNull("夜间照明通知缺少按钮", n.actions);
        assertTrue("夜间照明通知没有动作按钮", n.actions.length > 0);

        ShadowPendingIntent spi = shadowOf(n.actions[0].actionIntent);
        assertTrue("手电按钮必须是广播", spi.isBroadcastIntent());

        Intent saved = spi.getSavedIntent();
        assertEquals("io.github.wumisc.safewalk.OPEN_TORCH", saved.getAction());
        assertEquals("广播必须显式指定 SystemUI 包名，否则 Hook 收不到",
                "com.android.systemui", saved.getPackage());
        assertEquals("缺少令牌会让接收器拒绝执行",
                SafeWalkPrefKey.BROADCAST_TOKEN,
                saved.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN));
    }

    /** 所有通知都必须带 contentIntent，否则点通知主体毫无反应 */
    @Test
    public void everyNotification_hasContentIntent() {
        SafeWalkNotificationManager m = new SafeWalkNotificationManager(ctx);
        m.batteryWarning();
        m.idReminder();
        m.sitRemind();
        m.socialTip("测试");
        m.flashlightSuggest();

        int[] ids = {
                SafeWalkNotificationManager.ID_BATTERY,
                SafeWalkNotificationManager.ID_IDCARD,
                SafeWalkNotificationManager.ID_SIT,
                SafeWalkNotificationManager.ID_SOCIAL,
                SafeWalkNotificationManager.ID_TORCH
        };
        for (int id : ids) {
            Notification n = shadowOf(nm).getNotification(id);
            assertNotNull("通知 " + id + " 未发出", n);
            assertNotNull("通知 " + id + " 缺少 contentIntent", n.contentIntent);
        }
    }

    /** ConfigProvider 只应放行本应用与 SystemUI */
    @Test
    public void provider_whitelistCoversHookKeys() {        // Hook 端读的每个 key 都必须在白名单里，否则开关永远是默认值
        String[] usedByHook = {
                SafeWalkPrefKey.masterEnable,
                SafeWalkPrefKey.enableBatteryWarning,
                SafeWalkPrefKey.enableIdReminder,
                SafeWalkPrefKey.enableSitHealthRemind,
                SafeWalkPrefKey.enableSocialTipCard,
                SafeWalkPrefKey.enableNightTorch,
                SafeWalkPrefKey.enableRearCamera,
                SafeWalkPrefKey.rearCamMode,
                SafeWalkPrefKey.rearCamAuto,
                SafeWalkPrefKey.rearCamOpacity,
                SafeWalkPrefKey.rearCamCorner,
                SafeWalkPrefKey.travelMode,
                SafeWalkPrefKey.stepThreshold,
                SafeWalkPrefKey.staticResetSec,
                SafeWalkPrefKey.torchStaticMinutes,
                SafeWalkPrefKey.testRearCamAction,
                SafeWalkPrefKey.testRearCamTs
        };
        for (String k : usedByHook) {
            assertTrue("Hook 使用的配置键不在白名单: " + k,
                    ConfigProvider.Companion.getWHITELIST().contains(k));
        }

        // 隐私字段绝不能出现在白名单里
        String[] privateKeys = {
                SafeWalkPrefKey.emerName,
                SafeWalkPrefKey.emerBlood,
                SafeWalkPrefKey.emerAddress,
                SafeWalkPrefKey.emerContactName,
                SafeWalkPrefKey.emerContactPhone
        };
        for (String k : privateKeys) {
            assertTrue("急救卡隐私字段被暴露给了跨进程: " + k,
                    !ConfigProvider.Companion.getWHITELIST().contains(k));
        }
    }

    // ==================== Provider 的调用方判定 ====================

    /**
     * 外部应用（uid >= 10000）必须什么都读不到。
     *
     * 这条锁的是原实现的 fail-open：`callingPackage == null` 时直接放行。
     * 一旦某次 Binder 身份取不到（或换个调用方式），白名单就等于没写，
     * 任何应用都能读到你的全部功能开关。
     */
    @Test
    public void provider_rejectsForeignUid() {
        ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
                .edit().putBoolean(SafeWalkPrefKey.masterEnable, true).commit();

        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        android.database.Cursor c;

        try {
            org.robolectric.shadows.ShadowBinder.setCallingUid(10123);
            c = provider.query(android.net.Uri.parse(
                    "content://" + ConfigProvider.AUTHORITY + "/config"), null, null, null, null);
            assertFalse("普通应用不应该能读到配置", c != null && c.moveToFirst());
        } finally {
            org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid());
        }
    }

    /** 本应用自己读必须正常，否则 Hook 拿不到开关 */
    @Test
    public void provider_allowsOwnUid() {
        ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
                .edit().putBoolean(SafeWalkPrefKey.masterEnable, true).commit();

        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid());

        android.database.Cursor c = provider.query(android.net.Uri.parse(
                "content://" + ConfigProvider.AUTHORITY + "/config"), null, null, null, null);
        assertNotNull(c);
        assertTrue("本应用应能读到配置", c.moveToFirst());
        assertTrue("配置 JSON 里应包含总开关",
                c.getString(0).contains(SafeWalkPrefKey.masterEnable));
    }
}
