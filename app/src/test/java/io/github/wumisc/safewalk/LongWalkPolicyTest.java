package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 久走提醒策略回归：锁住「累计而非连续、暂停宽限、冷却不走表、tick 钳制」四个关键判定。
 *
 * 阈值统一压到 10 秒，用 1 秒一步的 tick 模拟真实调用节奏（调用方也是 1 秒一次巡检），
 * 这样测试里的每一步都对应真机上的一个采样点，而不是靠一次大跳变糊过去。
 */
public class LongWalkPolicyTest {

    private static final long WALK = 10_000L;
    private static final long GRACE = 5_000L;
    private static final long COOL = 30_000L;

    private static LongWalkPolicy.State state() {
        return new LongWalkPolicy.State();
    }

    private static boolean step(boolean enabled, boolean walking, long now,
                                LongWalkPolicy.State s) {
        return LongWalkPolicy.evaluate(enabled, walking, now, s, WALK, GRACE, COOL);
    }

    /** 从 t=0 开始每秒走一步，返回真正触发的那一帧时间戳；到 limit 仍未触发返回 -1 */
    private static long runWalking(LongWalkPolicy.State s, long from, long limit, long stepMs) {
        long t = from;
        while (t <= limit) {
            if (step(true, true, t, s)) return t;
            t += stepMs;
        }
        return -1L;
    }

    @Test
    public void firesAfterAccumulatedWalk() {
        LongWalkPolicy.State s = state();
        // 第 0 帧只建立基线（dt 无参照），之后每秒累加 1 秒
        assertFalse(step(true, true, 0L, s));
        long fired = runWalking(s, 1_000L, 60_000L, 1_000L);
        assertEquals("累计走满 10 秒应在 t=10000 触发", 10_000L, fired);
    }

    @Test
    public void shortPauseKeepsAccumulation() {
        LongWalkPolicy.State s = state();
        step(true, true, 0L, s);
        // 走 6 秒
        for (long t = 1_000L; t <= 6_000L; t += 1_000L) {
            assertFalse(step(true, true, t, s));
        }
        // 停 4 秒（未超宽限 5 秒）
        for (long t = 7_000L; t <= 10_000L; t += 1_000L) {
            assertFalse("宽限内的停顿不该触发，也不该清零", step(true, false, t, s));
        }
        // 继续走：只需再 4 秒就够 10 秒
        assertFalse(step(true, true, 11_000L, s));
        assertFalse(step(true, true, 12_000L, s));
        assertFalse(step(true, true, 13_000L, s));
        assertTrue("累计满 10 秒应触发（停顿未超宽限）", step(true, true, 14_000L, s));
    }

    @Test
    public void longPauseResetsAccumulation() {
        LongWalkPolicy.State s = state();
        step(true, true, 0L, s);
        for (long t = 1_000L; t <= 6_000L; t += 1_000L) {
            assertFalse(step(true, true, t, s));
        }
        // 停 7 秒，超过宽限 5 秒 → 清零
        for (long t = 7_000L; t <= 13_000L; t += 1_000L) {
            assertFalse(step(true, false, t, s));
        }
        assertEquals("超宽限后累计应清零", 0L, s.accumMs);
        // 重新走：必须重新攒满 10 秒（t=14000 计入第 1 秒，故在 t=23000 满 10 秒）
        assertFalse(step(true, true, 14_000L, s));
        long fired = runWalking(s, 15_000L, 40_000L, 1_000L);
        assertEquals("重新累计满 10 秒后才触发", 23_000L, fired);
    }

    @Test
    public void clockRollbackNeverFires() {
        LongWalkPolicy.State s = state();
        step(true, true, 100_000L, s);
        // 时间回拨到基线之前：基线作废，本轮不清算
        assertFalse(step(true, true, 50_000L, s));
        assertEquals("回拨后累计清零", 0L, s.accumMs);
    }

    @Test
    public void longTickIsClamped() {
        LongWalkPolicy.State s = state();
        step(true, true, 0L, s);
        // 休眠 10 分钟后唤醒：单帧最多结算 5 秒，不能凭一次跳变直接触发
        assertFalse("一次大跳变不得触发", step(true, true, 600_000L, s));
        assertEquals("单帧累加被钳制到 5 秒", LongWalkPolicy.MAX_TICK_MS, s.accumMs);
    }

    @Test
    public void cooldownWindowDoesNotAccumulate() {
        LongWalkPolicy.State s = state();
        step(true, true, 0L, s);
        long fired = runWalking(s, 1_000L, 60_000L, 1_000L);
        assertEquals(10_000L, fired);

        // 冷却期（30 秒）内继续走：不走表。停在冷却边界前一步，避免边界那帧已开始累计
        for (long t = 11_000L; t <= 39_000L; t += 1_000L) {
            assertFalse("冷却内不得重复提醒", step(true, true, t, s));
        }
        assertEquals("冷却期累计保持 0", 0L, s.accumMs);

        // 冷却结束后：必须重新走满 10 秒（t=40000 计入第 1 秒，故在 t=49000 满 10 秒）
        long fired2 = runWalking(s, 40_000L, 80_000L, 1_000L);
        assertEquals("冷却结束后需重新累计满 10 秒", 49_000L, fired2);
    }

    @Test
    public void disabledNeverFiresAndClears() {
        LongWalkPolicy.State s = state();
        step(true, true, 0L, s);
        for (long t = 1_000L; t <= 20_000L; t += 1_000L) {
            assertFalse("开关关闭时不得提醒", step(false, true, t, s));
        }
        assertEquals("关闭时应清零累计", 0L, s.accumMs);
    }

    @Test
    public void standingStillNeverFires() {
        LongWalkPolicy.State s = state();
        for (long t = 0L; t <= 60_000L; t += 1_000L) {
            assertFalse("没在走就不该提醒", step(true, false, t, s));
        }
        assertEquals(0L, s.accumMs);
    }
}
