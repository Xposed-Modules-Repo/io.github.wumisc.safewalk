package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [DiagThrottle] 回归测试。
 *
 * 锁的是真机 2026-11-11 19:11:38 那次日志洪泛的根因：高频回调里的诊断
 * 必须被稳定间隔压住，同时「原因变了」要能更快重发，方便看状态跳变。
 */
public class DiagThrottleTest {

    private static DiagThrottle.State state() {
        return new DiagThrottle.State();
    }

    @Test
    public void firstLineAlwaysEmits() {
        DiagThrottle.State s = state();
        assertTrue(DiagThrottle.shouldEmit(s, "zero", 0L, 2_000L, 10_000L));
        assertTrue(DiagThrottle.shouldEmit(s, "zero", 1L, 2_000L, 10_000L));
    }

    @Test
    public void stableReasonSuppressedWithinWindow() {
        DiagThrottle.State s = state();
        long now = 100_000L;
        assertTrue(DiagThrottle.shouldEmit(s, "zero", now, 2_000L, 10_000L));
        DiagThrottle.commit(s, "zero", now);

        assertFalse("窗口内同一原因必须抑制",
                DiagThrottle.shouldEmit(s, "zero", now + 9_999L, 2_000L, 10_000L));
        assertTrue("刚好到间隔要放行",
                DiagThrottle.shouldEmit(s, "zero", now + 10_000L, 2_000L, 10_000L));
    }

    @Test
    public void reasonChangeEmitsSooner() {
        DiagThrottle.State s = state();
        long now = 100_000L;
        DiagThrottle.commit(s, "zero", now);

        assertFalse("原因不变，2 秒内不重发",
                DiagThrottle.shouldEmit(s, "zero", now + 1_999L, 2_000L, 10_000L));
        assertTrue("原因变化，2 秒即可重发",
                DiagThrottle.shouldEmit(s, "low-1", now + 2_000L, 2_000L, 10_000L));
    }

    @Test
    public void clockRollbackResets() {
        DiagThrottle.State s = state();
        DiagThrottle.commit(s, "zero", 100_000L);
        assertTrue("时钟回拨后必须重新放行，不能永久静默",
                DiagThrottle.shouldEmit(s, "zero", 50_000L, 2_000L, 10_000L));
    }

    @Test
    public void commitStoresReasonAndTs() {
        DiagThrottle.State s = state();
        DiagThrottle.commit(s, "pass", 42_000L);
        assertEquals(42_000L, s.lastTs);
        assertEquals("pass", s.lastReason);
    }

    @Test
    public void independentStatesPerKey() {
        // DiagLog 按 key 存状态；这里验证两个 State 实例互不影响
        DiagThrottle.State hw = state();
        DiagThrottle.State accel = state();
        long now = 100_000L;
        DiagThrottle.commit(hw, "zero", now);
        assertTrue("另一通道的首条不受影响",
                DiagThrottle.shouldEmit(accel, "原因A", now, 2_000L, 10_000L));
        assertFalse("硬件通道仍在窗口内",
                DiagThrottle.shouldEmit(hw, "zero", now + 1_000L, 2_000L, 10_000L));
    }

    @Test
    public void zeroIntervalAlwaysEmitsAfterCommit() {
        DiagThrottle.State s = state();
        long now = 100_000L;
        DiagThrottle.commit(s, "x", now);
        assertTrue(DiagThrottle.shouldEmit(s, "x", now, 0L, 0L));
    }

    /** 模拟真机洪泛场景：一秒内 80 次同原因调用，只应放行 1 条 */
    @Test
    public void highFrequencyOnlyOneLine() {
        DiagThrottle.State s = state();
        int emitted = 0;
        for (int i = 0; i < 80; i++) {
            long now = 100_000L + i * 12L;   // 约每 12ms 一帧
            if (DiagThrottle.shouldEmit(s, "zero", now, 2_000L, 10_000L)) {
                DiagThrottle.commit(s, "zero", now);
                emitted++;
            }
        }
        assertEquals("一秒内同原因诊断只应有一条", 1, emitted);
    }
}
