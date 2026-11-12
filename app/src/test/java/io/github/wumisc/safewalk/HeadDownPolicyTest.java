package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 低头提醒策略回归：锁住「必须行走 + 亮屏 + 持续低头」三个前提，
 * 以及冷却、时钟回拨、中途抬头重置这些边界。
 */
public class HeadDownPolicyTest {

    private static final long HOLD = 8_000L;
    private static final long COOL = 180_000L;

    private static HeadDownPolicy.State state() {
        return new HeadDownPolicy.State();
    }

    /** 走到「刚开始低头」的那一帧（返回 false，只建立计时基线） */
    private static void beginDown(HeadDownPolicy.State s, long t) {
        HeadDownPolicy.evaluate(true, true, true, 20f, t, s, 45f, HOLD, COOL);
    }

    private static boolean eval(boolean enabled, boolean walking, boolean screenOn,
                                float tilt, long now, HeadDownPolicy.State s) {
        return HeadDownPolicy.evaluate(enabled, walking, screenOn, tilt, now, s, 45f, HOLD, COOL);
    }

    @Test
    public void disabledNeverFires() {
        HeadDownPolicy.State s = state();
        long t = 1_000L;
        for (int i = 0; i < 20; i++) {
            assertFalse(eval(false, true, true, 10f, t, s));
            t += 1_000L;
        }
    }

    @Test
    public void notWalkingNeverFires() {
        HeadDownPolicy.State s = state();
        long t = 1_000L;
        for (int i = 0; i < 20; i++) {
            assertFalse(eval(true, false, true, 10f, t, s));
            t += 1_000L;
        }
    }

    @Test
    public void screenOffNeverFires() {
        HeadDownPolicy.State s = state();
        long t = 1_000L;
        for (int i = 0; i < 20; i++) {
            assertFalse(eval(true, true, false, 10f, t, s));
            t += 1_000L;
        }
    }

    @Test
    public void unknownTiltNeverFires() {
        HeadDownPolicy.State s = state();
        long t = 1_000L;
        for (int i = 0; i < 20; i++) {
            assertFalse("重力未收敛（-1）必须忽略", eval(true, true, true, -1f, t, s));
            t += 1_000L;
        }
    }

    @Test
    public void uprightPhoneNeverFires() {
        HeadDownPolicy.State s = state();
        long t = 1_000L;
        for (int i = 0; i < 20; i++) {
            assertFalse("手机接近竖直（80°）不算低头", eval(true, true, true, 80f, t, s));
            t += 1_000L;
        }
    }

    @Test
    public void firesOnlyAfterHoldWindow() {
        HeadDownPolicy.State s = state();
        beginDown(s, 10_000L);
        assertFalse("刚低头 1 秒不该提醒", eval(true, true, true, 20f, 11_000L, s));
        assertFalse("7 秒仍在观察期", eval(true, true, true, 20f, 17_000L, s));
        assertTrue("满 8 秒应提醒", eval(true, true, true, 20f, 18_000L, s));
    }

    @Test
    public void cooldownSuppressesRepeat() {
        HeadDownPolicy.State s = state();
        beginDown(s, 10_000L);
        assertTrue("第一次持续低头满 8 秒应提醒", eval(true, true, true, 20f, 18_000L, s));

        // 冷却内一直低头：不能重复提醒（循环停在冷却结束前）
        long t = 19_000L;
        while (t < 18_000L + COOL) {
            assertFalse("冷却内不得重复提醒", eval(true, true, true, 20f, t, s));
            t += 1_000L;
        }

        // 冷却过后：先抬头打断上一轮计时，再重新低头，必须重新累计满 8 秒
        assertFalse("抬头应重置计时", eval(true, true, true, 85f, 200_000L, s));
        assertFalse("重新低头只建立基线", eval(true, true, true, 20f, 201_000L, s));
        assertFalse("7 秒仍在观察期", eval(true, true, true, 20f, 208_000L, s));
        assertTrue("重新满 8 秒、且冷却已过，应再次提醒", eval(true, true, true, 20f, 209_000L, s));
    }

    @Test
    public void lookingUpResetsTimer() {
        HeadDownPolicy.State s = state();
        beginDown(s, 10_000L);
        // 第 5 秒抬头（角度变大）→ 计时清零
        assertFalse(eval(true, true, true, 85f, 15_000L, s));
        // 立刻又低头：重新从 0 计时，7 秒时还不该提醒
        assertFalse(eval(true, true, true, 20f, 16_000L, s));
        assertFalse(eval(true, true, true, 20f, 22_000L, s));
        assertTrue(eval(true, true, true, 20f, 24_000L, s));
    }

    @Test
    public void walkingStopResetsTimer() {
        HeadDownPolicy.State s = state();
        beginDown(s, 10_000L);
        // 停下（walking=false）→ 计时清零
        assertFalse(eval(true, false, true, 20f, 15_000L, s));
        // 重新走并低头：需重新累计满 8 秒
        assertFalse(eval(true, true, true, 20f, 16_000L, s));
        assertFalse(eval(true, true, true, 20f, 23_000L, s));
        assertTrue(eval(true, true, true, 20f, 24_000L, s));
    }

    @Test
    public void clockRollbackDoesNotFire() {
        HeadDownPolicy.State s = state();
        beginDown(s, 100_000L);
        // 时间回拨到基线之前：清空基线，绝不触发
        assertFalse(eval(true, true, true, 20f, 50_000L, s));
        assertFalse(eval(true, true, true, 20f, 50_500L, s));
    }

    @Test
    public void afterFireTimerRestarts() {
        HeadDownPolicy.State s = state();
        beginDown(s, 10_000L);
        assertTrue(eval(true, true, true, 20f, 18_000L, s));
        // 触发后 downSince 归零：紧接着下一帧只是重新建立基线
        assertFalse(eval(true, true, true, 20f, 19_000L, s));
    }

    @Test
    public void boundaryTiltEqualsThresholdCounts() {
        HeadDownPolicy.State s = state();
        beginDown(s, 10_000L);
        assertTrue("夹角正好等于阈值应算低头", eval(true, true, true, 45f, 18_000L, s));
    }
}
