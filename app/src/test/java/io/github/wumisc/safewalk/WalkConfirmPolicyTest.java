package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯加速度「确实在走路」判定器回归测试。
 *
 * 这组用例锁死两件事：
 * ① 门槛必须落在采集层 10 步窗口之内（否则就是永不成立的死条件，
 *    真机 2026-11-10 13:48 起自动跟随全程失效就是它造成的）；
 * ② 正常步行（含慢走）必须放行，平放/间歇抖动/步频异常必须拦住。
 */
public class WalkConfirmPolicyTest {

    /** 构造一串等间隔步时间戳，起点 0，间隔 intervalMs */
    private static List<Long> steps(int count, long intervalMs) {
        List<Long> ts = new ArrayList<>();
        for (int i = 0; i < count; i++) ts.add(i * intervalMs);
        return ts;
    }

    private static WalkConfirmPolicy policy() {
        // 默认设置「连续走 8 步」→ 纯加速度门槛 8 步（5.5.0 起直接映射，不再 ×2）
        return new WalkConfirmPolicy(WalkConfirmPolicy.requiredStepsFor(8));
    }

    // ---------- 门槛可达性 ----------

    @Test
    public void requiredStepsNeverExceedDetectorWindow() {
        for (int pref = 2; pref <= 10; pref++) {
            int need = WalkConfirmPolicy.requiredStepsFor(pref);
            assertTrue("pref=" + pref + " 门槛 " + need + " 超出窗口上限",
                    need <= WalkConfirmPolicy.WINDOW_CAP);
            assertTrue("pref=" + pref + " 门槛 " + need + " 低于抗噪下限",
                    need >= WalkConfirmPolicy.MIN_REQUIRED_STEPS);
        }
        // 5.5.0：直接映射（不再 ×2）。UI 只有 8/9/10 三档，必须逐档生效、
        // 且最高档仍落在采集层 10 步窗口内（否则就是永不成立的死条件）。
        assertTrue("默认档 8 步必须映射成 8 步",
                WalkConfirmPolicy.requiredStepsFor(8) == 8);
        assertTrue("9 步必须映射成 9 步（滑条不能看起来坏了）",
                WalkConfirmPolicy.requiredStepsFor(9) == 9);
        assertTrue("10 步必须映射成窗口上限 10 步",
                WalkConfirmPolicy.requiredStepsFor(10) == WalkConfirmPolicy.WINDOW_CAP);
        // 非法/历史值（老版本存过 2~7）统一夹到下限 8，绝不回到 2 步
        assertTrue("历史值 5 必须夹到 8 步",
                WalkConfirmPolicy.requiredStepsFor(5) == WalkConfirmPolicy.MIN_REQUIRED_STEPS);
        assertTrue("0 必须夹到下限",
                WalkConfirmPolicy.requiredStepsFor(0) == WalkConfirmPolicy.MIN_REQUIRED_STEPS);
        assertTrue("99 必须夹到窗口上限",
                WalkConfirmPolicy.requiredStepsFor(99) == WalkConfirmPolicy.WINDOW_CAP);
    }

    // ---------- 应当放行 ----------

    @Test
    public void normalWalkingPasses() {
        // 10 步、每步 550ms ≈ 1.8Hz，跨度 4.95s
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, steps(10, 550L), 5_000L);
        assertTrue("正常步行应放行，实际原因=" + v.reason + " 跨度=" + v.spanMs, v.pass);
    }

    @Test
    public void eightStepWalkAtNormalCadencePasses() {
        // 5.5.0 门槛改成「直接映射 8 步」后最危险的一处：
        // 8 步 × 550ms 的跨度只有 3850ms，整段跨度下限要是还写死 4000，
        // 「每分钟 109 步」的正常步行就会被判成「连续时长不足」而永远不弹预览。
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 8, steps(8, 550L), 4_100L);
        assertTrue("8 步 / 550ms 正常步频必须放行，实际原因=" + v.reason
                + " 跨度=" + v.spanMs, v.pass);
    }

    @Test
    public void eightStepsWithinThreeSecondsBlocked() {
        // 「3 秒内连抖 8 下」：间隔 400ms（刚好在步频窗口内）跨度只有 2800ms。
        // 真人走 8 步最短也要 4 秒多，这种短促抖动必须被跨度闸挡掉。
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 8, steps(8, 400L), 3_000L);
        assertFalse("3 秒内连抖 8 下必须拦，实际原因=" + v.reason + " 跨度=" + v.spanMs,
                v.pass);
        assertTrue("应由跨度闸拦下，实际=" + v.reason, v.reason.contains("连续时长不足"));
    }

    @Test
    public void slowWalkPasses() {
        // 慢走 80 步/分：10 步跨度 6.75s，最大间隔 750ms。
        // 5.1.0 把平均间隔上限收到 950ms（≈63 步/分）之后，原来那条
        // 「间隔 1200ms 也算慢走」的用例必须改 —— 1200ms 是 50 步/分，
        // 已经不是散步是挪步了，真机日志里这个量级全是手持低频晃动。
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, steps(10, 750L), 7_000L);
        assertTrue("慢走应放行，实际原因=" + v.reason, v.pass);
    }

    @Test
    public void pocketJitterWithinTolerancePasses() {
        // 口袋里被裤子带着晃：间隔在 480~700ms 之间抖动，仍属真人步流
        List<Long> ts = new ArrayList<>();
        long[] gaps = {520, 610, 480, 700, 560, 640, 500, 680, 540};
        long t = 0;
        ts.add(t);
        for (long g : gaps) { t += g; ts.add(t); }
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, t + 300);
        assertTrue("口袋抖动应放行，实际原因=" + v.reason, v.pass);
    }

    // ---------- 应当拦截 ----------

    /**
     * 【5.7.3 平放不再一票否决】平着拿手机走路是用户最常见的姿态
     * （真机日志里 LAY_FLAT 占七成以上）；旧版这里恒 false，等于无计步权限机型
     * 永远不弹小窗。现在平放走「更严格的节律阈值」，正常步频必须放行。
     */
    @Test
    public void layFlatNormalWalkingPasses() {
        WalkConfirmPolicy.Verdict v = policy().evaluate(true, 10, steps(10, 550L), 5_000L);
        assertTrue("平放 + 正常步频必须放行，实际原因=" + v.reason, v.pass);
    }

    /** 平放但节奏像「躺床上慢抖」：平均间隔 1000ms 超过平放上限 850ms，必须拦 */
    @Test
    public void layFlatSlowShuffleBlocked() {
        WalkConfirmPolicy.Verdict v = policy().evaluate(true, 10, steps(10, 1000L), 9_500L);
        assertFalse("平放慢抖不得放行，实际原因=" + v.reason, v.pass);
    }

    @Test
    public void insufficientStepsBlocked() {
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 5, steps(5, 550L), 2_500L);
        assertFalse("步数不足必须拦", v.pass);
        assertTrue(v.reason.contains("合格步不足"));
    }

    @Test
    public void intermittentShakingBlocked() {
        // 沙发上抖三下、停 2.5 秒再抖：最大间隔 2.5s > 1.6s
        List<Long> ts = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 10; i++) {
            ts.add(t);
            t += (i % 3 == 2) ? 2_500L : 550L;
        }
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, t);
        assertFalse("间歇抖动必须拦，实际原因=" + v.reason, v.pass);
        assertTrue(v.reason.contains("断裂"));
    }

    @Test
    public void tooFastShakingBlocked() {
        // 快速晃动 4Hz：平均间隔 250ms < 350ms
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, steps(10, 250L), 2_400L);
        assertFalse("过快抖动必须拦，实际原因=" + v.reason, v.pass);
        assertTrue(v.reason.contains("步频过快"));
    }

    @Test
    public void irregularRhythmBlocked() {
        // 一快一慢交替（300ms / 1600ms）：平均间隔看着正常，但节律散乱
        List<Long> ts = new ArrayList<>();
        long t = 0;
        ts.add(t);
        for (int i = 0; i < 9; i++) {
            t += (i % 2 == 0) ? 300L : 1600L;
            ts.add(t);
        }
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, t);
        assertFalse("节律散乱必须拦，实际原因=" + v.reason, v.pass);
    }

    @Test
    public void staleLastStepBlocked() {
        // 步流早就断了（最近一步在 3 秒前），不能拿旧数据放行
        List<Long> ts = steps(10, 550L);
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, ts.get(9) + 3_000L);
        assertFalse("步流已断必须拦，实际原因=" + v.reason, v.pass);
    }

    // ---------- 5.1.0 真机回归：这两条是用户实测误触发的原样数据 ----------

    /**
     * 真机日志原文：
     * `✅ 纯加速度判走放行 | 连续步流可信 连续步=10/10 跨度=7183ms 最大间隔=1352ms 均隔=798ms`
     *
     * 均隔 798ms（≈75 步/分）能过平均间隔关，最大间隔 1352ms 也刚好在 1400 以内。
     * 唯一破绽是间隔忽长忽短 —— 中位数附近的正常步只有 400ms 上下，
     * 而 1352ms 那一下根本不像跨步。收紧后的节律一致性必须把它拦住。
     */
    @Test
    public void realDeviceJitterSample1Blocked() {
        long[] gaps = {400, 1352, 420, 1338, 410, 1300, 415, 1350, 598};
        List<Long> ts = new ArrayList<>();
        long t = 0;
        ts.add(t);
        for (long g : gaps) {
            t += g;
            ts.add(t);
        }
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, t + 250);
        assertFalse("真机抖动样本①必须拦，实际原因=" + v.reason, v.pass);
        assertTrue("应被节律一致性拦下，实际=" + v.reason, v.reason.contains("节律散乱"));
    }

    /**
     * 真机日志原文：
     * `✅ 纯加速度判走放行 | 连续步流可信 连续步=10/10 跨度=9004ms 最大间隔=1530ms 均隔=1000ms`
     *
     * 均隔 1000ms 就是 1Hz / 60 步每分钟，比任何真人步行都慢，
     * 属于手持手机时手臂自然摆动的频率。这一条直接被平均间隔关拦住。
     */
    @Test
    public void realDeviceJitterSample2Blocked() {
        List<Long> ts = new ArrayList<>();
        long t = 0;
        ts.add(t);
        long[] gaps = {1000, 1000, 1530, 850, 1000, 1000, 870, 1000, 754};
        for (long g : gaps) {
            t += g;
            ts.add(t);
        }
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, t + 300);
        assertFalse("真机抖动样本②必须拦，实际原因=" + v.reason, v.pass);
    }

    /** 真人步行步间隔的抖动只有几十毫秒，收紧后仍必须放行（不能误杀） */
    @Test
    public void realWalkingWithSmallJitterPasses() {
        long[] gaps = {545, 560, 540, 555, 548, 562, 538, 556, 550};
        List<Long> ts = new ArrayList<>();
        long t = 0;
        ts.add(t);
        for (long g : gaps) {
            t += g;
            ts.add(t);
        }
        WalkConfirmPolicy.Verdict v = policy().evaluate(false, 10, ts, t + 280);
        assertTrue("真走路小抖动应放行，实际原因=" + v.reason
                + " avg=" + v.avgIntervalMs + " span=" + v.spanMs, v.pass);
    }
}
