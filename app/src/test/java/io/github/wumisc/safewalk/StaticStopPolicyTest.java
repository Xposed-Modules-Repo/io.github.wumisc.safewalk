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

    // ---------- 5.1.0 显示驻留：小窗不能「闪一下就消失」 ----------

    /**
     * 真机日志里的循环：14:46:29 弹出 → 14:46:41 收起，正好 12 秒。
     * 用户看到的是「在手里经常跳预览小窗」。误判本身已由 WalkConfirmPolicy
     * 收紧解决，这一层保证万一还是判错，画面也稳定存在一段时间。
     */
    @Test
    public void justShownNeverStopsWithinMinShow() {
        long session = 100_000L;
        long shownAt = 200_000L;
        // 显示 1 秒后就没步了，但驻留期（12s）内不能收
        assertFalse("刚显示 1 秒不能收",
                StaticStopPolicy.shouldStop(199_000L, session, shownAt + 1_000L, 200L,
                        GRACE, shownAt));
        assertFalse("第 11.9 秒仍然不能收",
                StaticStopPolicy.shouldStop(199_000L, session, shownAt + 11_900L, 200L,
                        GRACE, shownAt));
    }

    @Test
    public void stopsAfterMinShowElapsed() {
        long session = 100_000L;
        long shownAt = 200_000L;
        // 驻留期一过、仍然没步 → 正常收起，不会把「真的停下来了」永久挂住
        assertTrue("驻留结束后该收就收",
                StaticStopPolicy.shouldStop(199_000L, session,
                        shownAt + StaticStopPolicy.DEFAULT_MIN_SHOW_MS + 1, 200L,
                        GRACE, shownAt));
    }

    /** 手动常显 / 非本会话显示时 showStartedTs=0，驻留逻辑不参与，行为与旧版一致 */
    @Test
    public void manualShowIgnoresDwell() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        assertTrue(StaticStopPolicy.shouldStop(now - 1_000L, session, now, 500L, GRACE, 0L));
    }

    // ---------- 5.2.0 没在走就别判停：日志空转修复 ----------

    /**
     * 真机日志（2026-11-14 14:17~14:35，Redmi / HyperOS 4）里 18 分钟出现 19 次
     * 「[walk] 静置超时，判定停下 → 收起预览」，而同期 `[ACTIVE] ✅ 激活行走模式`
     * **一次都没有** —— 根本没有预览可收，判决却在每秒执行。
     *
     * 条件全满足（有残留步时间戳、会话早过宽限期、静置够久），只把 walking 置 false，
     * 必须一律判 false。
     */
    @Test
    public void notWalkingNeverStops() {
        long session = 100_000L;
        long now = session + GRACE + 60_000L;
        long lastStep = now - 30_000L;   // 早就超 stopMs
        assertFalse("没在走时不该判停",
                StaticStopPolicy.shouldStop(lastStep, session, now, STOP, GRACE, 0L,
                        StaticStopPolicy.DEFAULT_MIN_SHOW_MS, false));
    }

    /** walking=true 时原行为不变，不能被新参数顺手改坏 */
    @Test
    public void walkingKeepsNormalStop() {
        long session = 100_000L;
        long now = session + GRACE + 60_000L;
        long lastStep = now - STOP - 1;
        assertTrue(StaticStopPolicy.shouldStop(lastStep, session, now, STOP, GRACE, 0L,
                StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true));
    }
}
