package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [StaticStopPolicy] 回归测试。
 *
 * 锁的是「保活复活后 8 秒就被收起预览」这个真机缺陷（19:06:49 复活 →
 * 19:06:57 收起）。核心：上一会话残留的步时间戳必须作废，本会话刚起来
 * 要有宽限；但**正常行走后的静置判停不能失效**，否则预览永远不收。
 */
public class StaticStopPolicyTest {

    private static final long STOP = 10_000L;
    private static final long GRACE = StaticStopPolicy.SESSION_GRACE_MS;

    // ---------- 从未有步 / 时钟异常：不判停 ----------

    @Test
    public void noStepEverDoesNotStop() {
        assertFalse(StaticStopPolicy.shouldStop(0L, 1_000L, 999_999L, STOP));
    }

    @Test
    public void clockRollbackDoesNotStop() {
        assertFalse(StaticStopPolicy.shouldStop(200_000L, 100_000L, 50_000L, STOP));
        assertFalse(StaticStopPolicy.shouldStop(300_000L, 400_000L, 350_000L, STOP));
    }

    // ---------- 会话基线：复活场景 ----------

    @Test
    public void staleTimestampWithinGraceDoesNotStop() {
        // 服务刚复活：sessionStartTs=100_000，残留 lastStepTs=50_000（上会话）
        assertFalse("复活 8 秒内不能立刻收起",
                StaticStopPolicy.shouldStop(50_000L, 100_000L, 108_000L, STOP));
    }

    @Test
    public void staleTimestampAfterGraceAllowsOneEmptyStop() {
        // 宽限过后本会话仍没走出步：允许收起一次空预览，避免永远挂空窗
        assertTrue(StaticStopPolicy.shouldStop(
                50_000L, 100_000L, 100_000L + GRACE + 1, STOP));
    }

    @Test
    public void freshSessionWithinGraceDoesNotStop() {
        // 本会话已有步，但刚进入会话不久，宽限内不收
        assertFalse(StaticStopPolicy.shouldStop(
                101_000L, 100_000L, 100_000L + GRACE - 1, STOP));
    }

    // ---------- 正常静置判停 ----------

    @Test
    public void normalStaticStopAfterStopMs() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        long lastStep = now - STOP - 1;
        assertTrue(StaticStopPolicy.shouldStop(lastStep, session, now, STOP));
    }

    @Test
    public void noStopWhileStillWalking() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        long lastStep = now - STOP / 2;
        assertFalse(StaticStopPolicy.shouldStop(lastStep, session, now, STOP));
    }

    @Test
    public void exactStopBoundaryDoesNotStop() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        assertFalse("刚好 stopMs 不算超时",
                StaticStopPolicy.shouldStop(now - STOP, session, now, STOP));
    }

    // ---------- 无会话基线（旧行为兼容） ----------

    @Test
    public void noSessionBaselineKeepsLegacyBehaviour() {
        // sessionStartTs=0 时退化为原来的 lastStepTs != 0 && now-lastStepTs > stopMs
        assertTrue(StaticStopPolicy.shouldStop(1_000L, 0L, 1_000L + STOP + 1, STOP));
        assertFalse(StaticStopPolicy.shouldStop(1_000L, 0L, 1_000L + STOP, STOP));
    }

    @Test
    public void customGraceIsRespected() {
        assertFalse("自定义宽限 5s 内不判停",
                StaticStopPolicy.shouldStop(50_000L, 100_000L, 104_000L, STOP, 5_000L));
        assertTrue("超过自定义宽限后可收起空窗",
                StaticStopPolicy.shouldStop(50_000L, 100_000L, 105_001L, STOP, 5_000L));
    }
}
