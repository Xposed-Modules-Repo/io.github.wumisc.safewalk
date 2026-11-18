package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;

/**
 * Q4 第 4 组补强：10min 求助去重 + 先落盘后执行（工程师留给 QA）。
 *
 * 【为什么要反射注入】EmergencyVault.enc 是 EncryptedSharedPreferences，
 * Robolectric 无 AndroidKeyStore，enc 恒为 null → lastSosTs()/markSos() 会退化成空操作。
 * 这里把 enc 反射替换为普通 SharedPreferences，专门验证去重时间戳的落盘语义。
 *
 * 【doSos 覆盖边界】doSos() 耦合 SmsManager/SystemSosLauncher/finish，纯 JVM 无法断言
 * “真的发出短信”。本测试用 Robolectric 调起真实 Activity 并反射调用 doSos()，
 * 验证“10min 内二次求助跳过 → 不拉起拨号盘、不改上次时间戳、给出可见回执”这一整条分支，
 * 真正的短信到达/拨号键按下仍需真机确认（见报告遗留项）。
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class SosDedupTest {

    /** 把普通 SP 注入 EmergencyVault.enc（Kotlin object 的 enc 是实例字段），返回旧值以便还原 */
    private static Object injectEnc(Context ctx, String spName) throws Exception {
        Field f = EmergencyVault.class.getDeclaredField("enc");
        f.setAccessible(true);
        Object previous = f.get(EmergencyVault.INSTANCE);
        android.content.SharedPreferences sp = ctx.getSharedPreferences(spName, Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        f.set(EmergencyVault.INSTANCE, sp);
        return previous;
    }

    private static void restoreEnc(Object previous) throws Exception {
        Field f = EmergencyVault.class.getDeclaredField("enc");
        f.setAccessible(true);
        f.set(EmergencyVault.INSTANCE, previous);
    }

    private static SosAction action(long executedAt) {
        return new SosAction("evt-dedup", SosType.SMS, "", false, false, false, "", executedAt);
    }

    @Test
    public void markSos_writesTimestamp_readBackByLastSosTs() throws Exception {
        // Arrange：注入假 enc
        Context ctx = ApplicationProvider.getApplicationContext();
        Object prev = injectEnc(ctx, "qa_vault_dedup_1");
        try {
            // Act：先落盘（executedAt 显式指定，避免依赖墙上时钟）
            long t = 1_700_000_000_000L;
            EmergencyVault.markSos(action(t));
            // Assert：时间戳已写入加密槽位（这里用普通 SP 替身验证同一逻辑）
            assertEquals("markSos 必须先落盘求助时间戳", t, EmergencyVault.lastSosTs());
        } finally {
            restoreEnc(prev);
        }
    }

    @Test
    public void lastSosTs_emptyVault_returnsZero() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Object prev = injectEnc(ctx, "qa_vault_dedup_2");
        try {
            assertEquals("从未求助过时去重基准为 0", 0L, EmergencyVault.lastSosTs());
        } finally {
            restoreEnc(prev);
        }
    }

    @Test
    public void markSos_executedAtZero_usesWallClock() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Object prev = injectEnc(ctx, "qa_vault_dedup_3");
        try {
            long before = System.currentTimeMillis();
            EmergencyVault.markSos(action(0L));
            long after = System.currentTimeMillis();
            long stored = EmergencyVault.lastSosTs();
            assertTrue("executedAt=0 时取当前时间，不得落盘 0 导致去重失效",
                    stored >= before && stored <= after);
        } finally {
            restoreEnc(prev);
        }
    }

    /**
     * 进程被杀仍不重发：时间戳写入 SP 后，销毁 Activity（模拟进程回收）再重建，
     * lastSosTs 依然保留 → 第二次 doSos 仍命中去重。
     */
    @Test
    public void doSos_withinTenMinutes_skipsWithoutDialing_andKeepsTimestamp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Object prev = injectEnc(ctx, "qa_vault_dedup_4");
        try {
            // Arrange：把上次求助时间设为刚刚
            long now = System.currentTimeMillis();
            EmergencyVault.markSos(action(now));
            assertEquals(now, EmergencyVault.lastSosTs());

            // 先起一个 Activity 再销毁，模拟进程/页面被回收，时间戳应留在 SP 中
            ActivityController<EmergencyAlertActivity> first =
                    Robolectric.buildActivity(EmergencyAlertActivity.class).setup();
            first.pause().stop().destroy();

            // 重建 Activity，反射调用 doSos（10min 窗口内）
            ActivityController<EmergencyAlertActivity> second =
                    Robolectric.buildActivity(EmergencyAlertActivity.class).setup();
            EmergencyAlertActivity activity = second.get();
            java.lang.reflect.Method doSos = EmergencyAlertActivity.class.getDeclaredMethod("doSos");
            doSos.setAccessible(true);
            doSos.invoke(activity); // resolve(true) 的真实求助分支入口

            // Assert 1：去重回执可见（不静默）
            TextView receipt = activity.findViewById(R.id.tv_alert_receipt);
            assertTrue("10min 内重复求助必须给出可见的跳过回执",
                    receipt.getText().toString().contains("刚刚已发送过求助"));

            // Assert 2：没有拉起拨号/短信 —— 去重跳过意味着不产生任何物理求助动作
            // 用 getNextStartedActivity() 逐个排空队列（Robolectric 4.17 无批量 getter）
            boolean sosIntent = false;
            org.robolectric.shadows.ShadowApplication shadowApp =
                    Shadows.shadowOf((Application) ctx.getApplicationContext());
            Intent started;
            while ((started = shadowApp.getNextStartedActivity()) != null) {
                if (Intent.ACTION_DIAL.equals(started.getAction()) ||
                        Intent.ACTION_SENDTO.equals(started.getAction())) {
                    sosIntent = true;
                }
            }
            assertFalse("10min 内二次求助不得拉起拨号/短信 Intent", sosIntent);

            // Assert 3：去重分支不得刷新时间戳（否则 10min 窗口被无限延长）
            assertEquals("跳过分支不得改动上次求助时间", now, EmergencyVault.lastSosTs());
            second.pause().stop().destroy();
        } finally {
            restoreEnc(prev);
        }
    }

    /**
     * 超过 10min：往 Vault 注入一张有主联系人的卡，并把上次求助时间回拨到 11 分钟前，
     * 反射调用 doSos 应真正进入求助分支（发起拨号），且刷新时间戳 —— 与去重分支形成对照。
     */
    @Test
    public void doSos_beyondTenMinutes_executesSosAndRefreshesTimestamp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Object prev = injectEnc(ctx, "qa_vault_dedup_5");
        try {
            // Arrange：写入一张带主联系人的卡，并预置 11 分钟前的求助时间
            EmergencyCard card = new EmergencyCard("self", "张三", 30, Gender.MALE,
                    BloodType.A, RhFactor.POSITIVE, "", "无", false, "", "",
                    java.util.Collections.singletonList(
                            new EmergencyContact("李四", "13800138000", "朋友", true)),
                    "", false, new PrivacyFlags(), 0L);
            EmergencyVault.saveCard(card);
            long elevenMinAgo = System.currentTimeMillis() - 11 * 60 * 1000L;
            EmergencyVault.markSos(action(elevenMinAgo));

            ActivityController<EmergencyAlertActivity> controller =
                    Robolectric.buildActivity(EmergencyAlertActivity.class).setup();
            EmergencyAlertActivity activity = controller.get();
            java.lang.reflect.Method doSos = EmergencyAlertActivity.class.getDeclaredMethod("doSos");
            doSos.setAccessible(true);
            doSos.invoke(activity);

            // Assert 1：真正求助后时间戳被刷新为当前时间（重新开始 10min 计时）——这是
            // “先落盘后执行”的确定性副作用，不依赖 Robolectric 是否装了拨号 App
            assertTrue("执行求助后必须刷新去重时间戳",
                    EmergencyVault.lastSosTs() > elevenMinAgo);

            // Assert 2：回执文本必须是“求助已发送/未发出”这类真实执行文案，而非去重跳过文案
            TextView receipt = activity.findViewById(R.id.tv_alert_receipt);
            String text = receipt.getText().toString();
            assertTrue("超时求助应展示实际执行回执，而非跳过回执: " + text,
                    text.contains("求助短信") || text.contains("短信未发出"));
            assertFalse("超时分支不得出现去重跳过文案", text.contains("刚刚已发送过求助"));
            controller.pause().stop().destroy();
        } finally {
            restoreEnc(prev);
        }
    }
}
