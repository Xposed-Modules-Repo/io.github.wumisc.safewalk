package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * v1.1 四层安全机制独立验证（QA 补强）。
 *
 * 工程师的 DisplayPayloadStoreTest 已覆盖“一次性 token / TTL / invalidate / JSON 往返”，
 * 本类不重复，而是补其盲区：
 * ① DisplayProvider.isTrustedDisplayCaller 的 uid+包名三分支（系统 uid / 应用段 SystemUI / 第三方）；
 * ② DisplayProvider.query() 恒空，绝不成为第二条配置读取通道；
 * ③ call("take") 端到端：命中焚毁、鉴权拒绝不泄露 payload；
 * ④ token 只以哈希前 4 位进日志；
 * ⑤ EmergencyOverlay.hide() / expireIfDue() 退场清字段副本（单一暴露面）。
 *
 * 【反射 + Assume】callingPackage 是框架私有字段，API 差异大：拿不到即跳过该用例，不误报失败
 * （对齐工程师 identity() 的 Assume 风格）。
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class DisplaySecurityTest {

    private DisplayProvider provider;
    private DisplayFields fields;
    private int originalUid;

    @Before
    public void setUp() {
        DisplayPayloadStore.clear();
        provider = Robolectric.buildContentProvider(DisplayProvider.class).create().get();
        fields = new DisplayFields("张三", "A型", "Rh+", "青霉素", true,
                "心脏病", "李四", "13800000000", 65);
        originalUid = android.os.Binder.getCallingUid();
    }

    @After
    public void tearDown() {
        org.robolectric.shadows.ShadowBinder.setCallingUid(originalUid);
        DisplayPayloadStore.clear();
    }

    /** 通过 Robolectric Shadow 设置 callingPackage（SDK35 的 ContentProvider 无 mCallingPackage 字段） */
    private static void setCallingPackage(ContentProvider p, String pkg) {
        Shadows.shadowOf(p).setCallingPackage(pkg);
    }

    /** 反射调私有鉴权方法 isTrustedDisplayCaller() */
    private static boolean isTrusted(DisplayProvider p) throws Exception {
        Method m = DisplayProvider.class.getDeclaredMethod("isTrustedDisplayCaller");
        m.setAccessible(true);
        return (Boolean) m.invoke(p);
    }

    // ===== ① uid + 包名双重鉴权三分支 =====

    @Test
    public void auth_systemUid_isTrusted() throws Exception {
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.SYSTEM_UID); // 1000
        assertTrue("系统 uid 必须放行（Hook 跑在 SystemUI/系统进程）", isTrusted(provider));
    }

    @Test
    public void auth_ownUid_isTrusted() throws Exception {
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.myUid());
        assertTrue("本应用进程自身调用必须放行", isTrusted(provider));
    }

    @Test
    public void auth_appUidWithSystemUiPackage_isTrusted() throws Exception {
        // HyperOS 3/4：SystemUI 落在应用段 uid（>=10000），靠不可伪造的包名放行
        org.robolectric.shadows.ShadowBinder.setCallingUid(10123);
        setCallingPackage(provider, "com.android.systemui");
        assertTrue("应用段 uid + SystemUI 包名必须放行（HyperOS 3/4）", isTrusted(provider));
    }

    @Test
    public void auth_appUidWithMiuiSystemUiPackage_isTrusted() throws Exception {
        org.robolectric.shadows.ShadowBinder.setCallingUid(10123);
        setCallingPackage(provider, "com.miui.systemui");
        assertTrue("MIUI SystemUI 包名必须放行", isTrusted(provider));
    }

    @Test
    public void auth_appUidWithThirdPartyPackage_isRejected() throws Exception {
        org.robolectric.shadows.ShadowBinder.setCallingUid(10123);
        setCallingPackage(provider, "com.evil.fake");
        assertFalse("应用段 uid + 第三方包名必须拒绝，防伪造显示载荷", isTrusted(provider));
    }

    @Test
    public void auth_systemUidSegmentWrongPackage_isRejected() throws Exception {
        // 系统 uid 段（<10000）但包名不是 SystemUI → 拒绝
        org.robolectric.shadows.ShadowBinder.setCallingUid(2000);
        setCallingPackage(provider, "com.another.app");
        assertFalse("系统 uid 段内非 SystemUI 包名必须拒绝", isTrusted(provider));
    }

    // ===== ② query() 恒空：绝不成为第二条配置读取通道 =====

    @Test
    public void query_alwaysReturnsEmptyCursor() {
        Uri uri = Uri.parse("content://" + SafeWalkPrefKey.DISPLAY_AUTHORITY
                + SafeWalkPrefKey.DISPLAY_PATH);
        Cursor c = provider.query(uri, null, null, null, null);
        assertNotNull("query 必须返回非空 Cursor 对象而非 null", c);
        assertEquals("query 恒空：一行都不能有，杜绝被当配置通道读", 0, c.getCount());
        assertEquals("列名为 json", "json", c.getColumnNames()[0]);
    }

    // ===== ③ call("take") 端到端 =====

    @Test
    public void callTake_trustedCaller_returnsFieldsAndBurnsSlot() {
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.SYSTEM_UID);
        String token = DisplayPayloadStore.publish(fields, "evt-call-1");
        Bundle b = provider.call(DisplayProvider.METHOD_TAKE, token, null);
        assertNotNull("命中必须返回 Bundle", b);
        String payload = b.getString(DisplayProvider.KEY_PAYLOAD);
        assertNotNull("受信调用方需拿到字段载荷", payload);
        assertTrue("载荷须含真实字段值（受控放宽）", payload.contains("青霉素"));
        // 二次 take 必须空
        Bundle again = provider.call(DisplayProvider.METHOD_TAKE, token, null);
        assertFalse("二次 take 不得再有 payload（用后即焚）",
                again.containsKey(DisplayProvider.KEY_PAYLOAD));
    }

    @Test
    public void callTake_untrustedCaller_returnsEmptyAndDoesNotBurnSlot() throws Exception {
        // Arrange：第三方身份
        org.robolectric.shadows.ShadowBinder.setCallingUid(10123);
        setCallingPackage(provider, "com.evil.fake");
        String token = DisplayPayloadStore.publish(fields, "evt-call-2");
        // Act：伪造调用方来取件
        Bundle b = provider.call(DisplayProvider.METHOD_TAKE, token, null);
        // Assert：拿不到字段，且不消费槽（真调用方仍可取）
        assertFalse("第三方取件必须拿不到 payload", b.containsKey(DisplayProvider.KEY_PAYLOAD));
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.SYSTEM_UID);
        Bundle legit = provider.call(DisplayProvider.METHOD_TAKE, token, null);
        assertNotNull("鉴权拒绝不应焚毁槽位", legit.getString(DisplayProvider.KEY_PAYLOAD));
    }

    // ===== ④ token 只以哈希前 4 位进日志 =====

    @Test
    public void callTake_logsOnlyHashedToken_neverFullToken() {
        org.robolectric.shadows.ShadowBinder.setCallingUid(Process.SYSTEM_UID);
        String token = DisplayPayloadStore.publish(fields, "evt-call-3");
        ShadowLog.clear();
        provider.call(DisplayProvider.METHOD_TAKE, token, null);
        StringBuilder logs = new StringBuilder();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) logs.append(item.msg).append('\n');
        String all = logs.toString();
        assertFalse("日志不得出现完整的一次性 token（防重放泄露）", all.contains(token));
        assertTrue("日志须有 [display] 取件事件便于排查", all.contains("[display]"));
        // tokenHash4 输出恰好 4 字符，且不等于完整 token
        String hash = EmergencyDiag.tokenHash4(token);
        assertEquals("tokenHash4 必须为 4 位", 4, hash.length());
        assertFalse("哈希不得等于完整 token", hash.equals(token));
    }

    // ===== ⑤ 退场清字段副本（单一暴露面） =====

    private static void setOverlayField(String name, Object val) throws Exception {
        Field f = EmergencyOverlay.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(EmergencyOverlay.INSTANCE, val);
    }

    private static Object getOverlayField(String name) throws Exception {
        Field f = EmergencyOverlay.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(EmergencyOverlay.INSTANCE);
    }

    @Test
    public void overlay_hide_clearsFieldsCopy() throws Exception {
        // Arrange：模拟覆盖层正在显示字段
        setOverlayField("fieldsCopy", fields);
        setOverlayField("shownAtElapsed", android.os.SystemClock.elapsedRealtime());
        assertEquals("前置条件：字段副本已就绪", 6, EmergencyOverlay.fieldsCountForDiag());

        // Act：Activity 可见 / 用户取消 → hide()（可能 post 到主线程，需 flush）
        EmergencyOverlay.hide();
        org.robolectric.Robolectric.flushForegroundThreadScheduler();

        // Assert：字段副本被清空，单一暴露面不留残余
        assertEquals("hide 后字段副本必须为 0", 0, EmergencyOverlay.fieldsCountForDiag());
        assertFalse("hide 后不得处于显示态", EmergencyOverlay.isShowing());
        assertEquals("hide 后 shownAtElapsed 必须归零", 0L,
                ((Long) getOverlayField("shownAtElapsed")).longValue());
    }

    @Test
    public void overlay_expireIfDue_afterTtl_hidesAndClears() throws Exception {
        // Arrange：用固定的 now 作为基准，shown 设为「超过 20s 前」，避免依赖真实时钟推进
        long fixedNow = 1_000_000L;
        setOverlayField("fieldsCopy", fields);
        setOverlayField("shownAtElapsed",
                fixedNow - SafeWalkPrefKey.DISPLAY_TTL_MS - 1L);
        // Act：tick 兜底检查（传入同一固定 now，差值确定 = TTL+1）
        boolean expired = EmergencyOverlay.expireIfDue(fixedNow);
        org.robolectric.Robolectric.flushForegroundThreadScheduler();
        // Assert：到点必须触发 hide 并清副本
        assertTrue("超过 TTL 必须返回已过期", expired);
        assertEquals("TTL 到期后字段副本必须清空", 0, EmergencyOverlay.fieldsCountForDiag());
    }

    @Test
    public void overlay_expireIfDue_withinTtl_doesNotHide() throws Exception {
        long fixedNow = 1_000_000L;
        setOverlayField("fieldsCopy", fields);
        setOverlayField("shownAtElapsed", fixedNow - 1000L); // 仅过去 1s
        boolean expired = EmergencyOverlay.expireIfDue(fixedNow);
        assertFalse("TTL 内不得误触发 hide", expired);
        assertEquals("TTL 内字段副本应保留", 6, EmergencyOverlay.fieldsCountForDiag());
        // 清理，避免污染其它用例（hide 走主线程 handler，这里直接清字段）
        setOverlayField("fieldsCopy", null);
        setOverlayField("shownAtElapsed", 0L);
    }
}
