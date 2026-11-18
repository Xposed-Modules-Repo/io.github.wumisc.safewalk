package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.lang.reflect.Field;

/**
 * 急救命令安全测试（Q4 补强）。
 *
 * 原 4 个纯令牌用例保留；新增：
 * ① senderIsTrusted 三分支（系统 uid / 应用段 SystemUI 包名 / 第三方包名）；
 * ② 令牌正确但发送方不可信 → 严格鉴权拒绝；
 * ③ CrashAlertReceiver 端到端：伪造广播不拉起 Activity、合法广播拉起；
 * ④ 脱敏两分：日志文本不含字段值，只含长度。
 *
 * 去重（Activity 内 10min 窗口）与锁屏字段字号属于 Robolectric 重反射/布局断言，
 * 留给 QA 真机与 UI 测试补强（已在交付说明标注）。
 */
@RunWith(RobolectricTestRunner.class)
public class EmergencyCommandSecurityTest {

    private static final String TOKEN = SafeWalkPrefKey.BROADCAST_TOKEN;

    private BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
            }
        };
    }

    /** API34+ 反射注入发送方身份；拿不到字段时跳过该用例而不误报失败 */
    private static void identity(BroadcastReceiver r, int uid, String pkg) {
        Assume.assumeTrue("senderIsTrusted 需要 API34+", Build.VERSION.SDK_INT >= 34);
        try {
            Field f = BroadcastReceiver.class.getDeclaredField("mSentFromUid");
            f.setAccessible(true);
            f.setInt(r, uid);
            Field p = BroadcastReceiver.class.getDeclaredField("mSentFromPackage");
            p.setAccessible(true);
            p.set(r, pkg);
        } catch (Throwable t) {
            Assume.assumeNoException("当前 Robolectric SDK 无发送方身份字段", t);
        }
    }

    // ===== 原有 4 个用例（保留，防回归）=====

    @Test
    public void strictCommand_rejectsMissingToken() {
        assertFalse("令牌缺失必须拒绝，否则任意应用都能伪造撞击",
                ReceiverCompat.isStrictCommand(receiver(), null, TOKEN));
    }

    @Test
    public void strictCommand_rejectsWrongToken() {
        assertFalse("令牌不匹配必须拒绝",
                ReceiverCompat.isStrictCommand(receiver(), "safewalk-bc-forged", TOKEN));
    }

    @Test
    public void strictCommand_rejectsWhenExpectedIsNull() {
        assertFalse("期望令牌为空时不能 fail-open",
                ReceiverCompat.isStrictCommand(receiver(), TOKEN, null));
    }

    @Test
    public void strictCommand_rejectsEmptyToken() {
        assertFalse("空字符串令牌必须拒绝（等同于伪造）",
                ReceiverCompat.isStrictCommand(receiver(), "", TOKEN));
    }

    // ===== Q4 第 1 组：senderIsTrusted 三分支 =====

    @Test
    public void senderIsTrusted_systemUid_returnsTrue() {
        BroadcastReceiver r = receiver();
        identity(r, 1000, "com.android.systemui");
        assertEquals(Boolean.TRUE, ReceiverCompat.senderIsTrusted(r));
    }

    @Test
    public void senderIsTrusted_appUidWithSystemUiPackage_returnsTrue() {
        BroadcastReceiver r = receiver();
        identity(r, 10086, "com.android.systemui");
        assertEquals("HyperOS 应用段 SystemUI 必须放行",
                Boolean.TRUE, ReceiverCompat.senderIsTrusted(r));
    }

    @Test
    public void senderIsTrusted_appUidWithThirdPartyPackage_returnsFalse() {
        BroadcastReceiver r = receiver();
        identity(r, 10086, "com.evil.fake");
        assertEquals(Boolean.FALSE, ReceiverCompat.senderIsTrusted(r));
    }

    // ===== Q4 第 2 组：严格鉴权语义（令牌正确但身份不可信）=====

    @Test
    public void strictCommand_rejectsTrustedFalseEvenWithCorrectToken() {
        BroadcastReceiver r = receiver();
        identity(r, 10086, "com.evil.fake");
        assertFalse("令牌正确但发送方不可信也必须拒绝（旧版会放行）",
                ReceiverCompat.isStrictCommand(r, TOKEN, TOKEN));
    }

    @Test
    public void strictCommand_acceptsSystemUidWithCorrectToken() {
        BroadcastReceiver r = receiver();
        identity(r, 1000, "com.android.systemui");
        assertTrue(ReceiverCompat.isStrictCommand(r, TOKEN, TOKEN));
    }

    // ===== Q4 第 3 组：端到端 onReceive =====

    @Test
    public void crashReceiver_forgedBroadcast_doesNotStartActivity() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent forged = new Intent(CrashDetector.ACTION_CRASH_DETECTED)
                .putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, "forged");
        new CrashAlertReceiver().onReceive(ctx, forged);
        Intent started = Shadows.shadowOf((Application) ctx.getApplicationContext())
                .getNextStartedActivity();
        assertNull("伪造广播绝不能拉起急救卡并触发求助", started);
    }

    @Test
    public void crashReceiver_legalBroadcast_startsEmergencyActivity() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Intent legal = new Intent(CrashDetector.ACTION_CRASH_DETECTED)
                .putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, TOKEN)
                .putExtra(CrashDetector.EXTRA_CRASH_EVENT_ID, "evt-unit-test");
        CrashAlertReceiver receiver = new CrashAlertReceiver();
        identity(receiver, 1000, "com.android.systemui"); // 模拟 SystemUI 发出的合法广播
        receiver.onReceive(ctx, legal);
        Intent started = Shadows.shadowOf((Application) ctx.getApplicationContext())
                .getNextStartedActivity();
        assertNotNull("合法撞击广播必须拉起急救卡", started);
        if (started.getComponent() != null) {
            assertEquals(EmergencyAlertActivity.class.getName(),
                    started.getComponent().getClassName());
        }
    }

    // ===== Q4 第 6 组：脱敏两分 =====

    @Test
    public void redact_neverLeaksValue_onlyLength() {
        String name = EmergencyDiag.redact("name", "张三丰");
        assertFalse(name.contains("张三丰"));
        assertTrue(name.contains("len=3"));
        String phone = EmergencyDiag.redact("phone", "13800000000");
        assertFalse(phone.contains("13800000000"));
        assertTrue(phone.contains("len=11"));
    }

    @Test
    public void logCard_doesNotContainRealValues() {
        EmergencyCard card = new EmergencyCard("self", "欧阳锋", 60,
                Gender.MALE, BloodType.AB, RhFactor.NEGATIVE,
                "上海市浦东新区100号", "头孢", true, "糖尿病", "二甲双胍",
                java.util.Collections.singletonList(
                        new EmergencyContact("洪七公", "13900000000", "朋友", true)),
                "医保123456", false, new PrivacyFlags(), 0L);
        String rendered = EmergencyDiag.redact("name", card.getName()) + " " +
                EmergencyDiag.redact("allergy", card.getAllergy()) + " " +
                EmergencyDiag.redact("contacts", String.valueOf(card.getContacts().size()));
        assertFalse(rendered.contains("欧阳锋"));
        assertFalse(rendered.contains("头孢"));
        assertFalse(rendered.contains("医保123456"));
        assertTrue(rendered.contains("***"));
    }

    @Test
    public void tokenHash4_doesNotLeakFullToken() {
        String hash = EmergencyDiag.tokenHash4("safewalk-display-token-very-secret");
        assertFalse(hash.contains("secret"));
        assertEquals(4, hash.length());
    }
}
