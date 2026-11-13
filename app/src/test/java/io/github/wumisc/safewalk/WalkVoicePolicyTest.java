package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 行走语音播报判定规则的回归锁。
 *
 * ## 为什么每一条都要锁
 *
 * 这是给视力障碍用户用的功能，出错的代价不对称：
 * · 少播一条 → 用户错过电话，但那通电话本来也会响铃，只是少了「谁打来的」；
 * · 多播一条 → 用户在过马路时被一段无关的广告打断，听不清环境声。
 *
 * 所以规则全部偏向「宁可不播」，每一条限制都单独有测试守着，避免以后有人
 * 为了「更灵敏」把闸门放松。
 */
public class WalkVoicePolicyTest {

    private static final long T0 = 1_000_000L;
    private static final long GLOBAL = WalkVoicePolicy.GLOBAL_COOLDOWN_MS;
    private static final long SAME = WalkVoicePolicy.SAME_SOURCE_COOLDOWN_MS;

    // ---------------------------------------------------------------- 闸门

    @Test
    public void speaksOnlyWhileWalking() {
        assertFalse("没走路不播", eval(false, true, false, WalkVoicePolicy.Kind.MESSAGE, T0, 0, 0));
        assertTrue(eval(true, true, false, WalkVoicePolicy.Kind.MESSAGE, T0, 0, 0));
    }

    @Test
    public void silentWhenScreenOff() {
        // 熄屏多半在口袋里：这时候突然开口既吓人又泄露隐私
        assertFalse(eval(true, false, false, WalkVoicePolicy.Kind.CALL, T0, 0, 0));
    }

    @Test
    public void respectsDoNotDisturb() {
        assertFalse("勿扰下连来电也不念", eval(true, true, true, WalkVoicePolicy.Kind.CALL, T0, 0, 0));
    }

    @Test
    public void ignoresNonMessageKinds() {
        assertFalse(eval(true, true, false, WalkVoicePolicy.Kind.IGNORE, T0, 0, 0));
    }

    // ---------------------------------------------------------------- 冷却

    @Test
    public void globalCooldownBlocksRapidFire() {
        long last = T0;
        assertFalse("15 秒内不得再播", eval(true, true, false,
                WalkVoicePolicy.Kind.MESSAGE, T0 + 5_000L, last, 0));
        assertTrue("过了全局冷却就能播", eval(true, true, false,
                WalkVoicePolicy.Kind.MESSAGE, T0 + GLOBAL, last, 0));
    }

    @Test
    public void sameSourceCooldownIsLonger() {
        // 全局冷却已过，但同一个人刚发过 → 仍然不念
        long speakAt = T0;
        long now = T0 + GLOBAL + 1_000L;
        assertTrue("此时全局已过", now - speakAt >= GLOBAL);
        assertFalse("同源 60 秒内不重复", eval(true, true, false,
                WalkVoicePolicy.Kind.MESSAGE, now, speakAt, speakAt));
        assertTrue("同源冷却过了就放行", eval(true, true, false,
                WalkVoicePolicy.Kind.MESSAGE, speakAt + SAME, speakAt, speakAt));
    }

    @Test
    public void firstEverSpeakIsNotBlocked() {
        // lastTs=0 表示从未播报，不能被「衰减到 0」误判成刚刚播过
        assertTrue(eval(true, true, false, WalkVoicePolicy.Kind.CALL, T0, 0, 0));
    }

    @Test
    public void clockRollbackDoesNotCauseRepeatSpam() {
        // 系统时间被改早时 now < lastSpeakTs，间隔为负。这里选择「放行一次」而不是
        // 「一直拦」：拦死会让功能在改过时间的机器上永久失效，而那比多播一句严重。
        assertTrue(eval(true, true, false, WalkVoicePolicy.Kind.CALL, T0 - 60_000L, T0, 0));
    }

    // ---------------------------------------------------------------- 分类

    @Test
    public void incallPackageIsCall() {
        assertEquals(WalkVoicePolicy.Kind.CALL,
                WalkVoicePolicy.classify("com.android.incallui", 0x20, "InCallActivity"));
    }

    @Test
    public void incallClassIsCallEvenWithUnknownPackage() {
        // 国产 ROM 的来电界面常挂在拨号包下，只认包名会漏
        assertEquals(WalkVoicePolicy.Kind.CALL,
                WalkVoicePolicy.classify("com.meizu.dialer", 0x20, "com.android.InCallActivity"));
    }

    @Test
    public void wechatNotificationIsMessage() {
        assertEquals(WalkVoicePolicy.Kind.MESSAGE,
                WalkVoicePolicy.classify("com.tencent.mm", 0x40, "android.app.Notification"));
    }

    @Test
    public void wechatWindowSwitchIsNotNewMessage() {
        // 用户自己打开微信，不该念「来自微信的新消息」
        assertEquals(WalkVoicePolicy.Kind.IGNORE,
                WalkVoicePolicy.classify("com.tencent.mm", 0x20, "LauncherUI"));
    }

    @Test
    public void unknownPackageNotificationIsIgnored() {
        assertEquals(WalkVoicePolicy.Kind.IGNORE,
                WalkVoicePolicy.classify("com.example.ads", 0x40, ""));
    }

    @Test
    public void emptyPackageIsIgnored() {
        assertEquals(WalkVoicePolicy.Kind.IGNORE, WalkVoicePolicy.classify("", 0x40, ""));
    }

    // ---------------------------------------------------------------- 文案

    @Test
    public void sourceLabelUsesChineseName() {
        assertEquals("微信", WalkVoicePolicy.sourceLabel("com.tencent.mm"));
        assertEquals("短信", WalkVoicePolicy.sourceLabel("com.android.mms"));
    }

    @Test
    public void sourceLabelFallsBackToLastSegment() {
        assertEquals("unknown", WalkVoicePolicy.sourceLabel("com.foo.unknown"));
    }

    @Test
    public void callPhraseIsShort() {
        assertEquals("来电，来自微信", WalkVoicePolicy.compose(
                WalkVoicePolicy.Kind.CALL, "微信", ""));
    }

    @Test
    public void messagePhraseIncludesBody() {
        assertEquals("来自微信：晚上一起吃饭吗", WalkVoicePolicy.compose(
                WalkVoicePolicy.Kind.MESSAGE, "微信", "晚上一起吃饭吗"));
    }

    @Test
    public void emptyBodyFallsBackToGenericPhrase() {
        assertEquals("来自微信 的新消息", WalkVoicePolicy.compose(
                WalkVoicePolicy.Kind.MESSAGE, "微信", "   "));
    }

    @Test
    public void clipTruncatesLongBody() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('字');
        String out = WalkVoicePolicy.clip(sb.toString());
        assertEquals(WalkVoicePolicy.MAX_SPEAK_CHARS + 1, out.length());
        assertTrue("截断后要有停顿感", out.endsWith("，"));
    }

    @Test
    public void clipFlattensWhitespace() {
        assertEquals("你好 世界", WalkVoicePolicy.clip("你好\n\n世界  "));
    }

    // ---------------------------------------------------------------- 辅助

    private static boolean eval(boolean walking, boolean screenOn, boolean dnd,
                                WalkVoicePolicy.Kind kind, long now,
                                long lastSpeak, long lastSamePkg) {
        return WalkVoicePolicy.shouldSpeak(walking, screenOn, dnd, kind, now, lastSpeak, lastSamePkg);
    }
}
