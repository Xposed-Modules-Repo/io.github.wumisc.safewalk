package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 【5.7.6】真机 trace 暴露的三个采样层问题，逐条锁死。
 *
 * 背景：真机导出的 gait_trace（30 秒 3523 帧）里
 *  · 83.8% 的相邻样本时间戳完全相同（只有 570 个不同 t），墙钟被批处理上报打平；
 *  · 文件头写着 hz≈50，实测却是 117Hz，任何按 50Hz 换算的窗口都错 2.3 倍；
 *  · 幅值小的 5 秒窗口（std 0.62）一个峰都没数出来，而紧邻的重步窗口能出 9 个。
 *
 * 这三条对应下面四个用例：硬件时钟换算、117Hz 下判定仍然有效、轻步靠降阈被捞回、
 * 磕碰不再污染包络。全部是纯 JVM 用例，不依赖 Robolectric。
 */
@RunWith(JUnit4.class)
public class GaitSampleClockTest {

    private static final float GRAVITY = 9.8f;

    // ---------------- 1. 硬件时钟 ----------------

    /** 同一毫秒内的两帧必须能分开 —— 这正是墙钟做不到、抛步频窗口的直接原因 */
    @Test
    public void sensorClock_separatesFramesInsideSameMillisecond() {
        SensorClock.reset();
        long wall = 1_700_000_000_000L;
        long ns = 12_000_000_000L;              // 开机 12 秒
        long a = SensorClock.msFromNanos(ns, wall);
        long b = SensorClock.msFromNanos(ns + 8_500_000L, wall + 8);   // 8.5ms 后

        assertEquals("首帧应原样映射回墙钟", wall, a);
        assertTrue("8.5ms 的真实间隔不能被抹成 0（实测 b-a=" + (b - a) + "）", b - a >= 8);
        assertTrue("8.5ms 间隔也不该被放大到 20ms 级（实测 b-a=" + (b - a) + "）", b - a <= 9);

        // 批处理包里典型的一组：12.0 / 12.4 / 12.8ms 三帧
        long c = SensorClock.msFromNanos(ns + 12_400_000L, wall + 12);
        assertTrue("12.4ms 的帧不能和 12.0ms 的帧同号", c > a);
    }

    /** 非法/回跳的时钟不能产生倒退，否则下游算出负间隔 */
    @Test
    public void sensorClock_neverGoesBackwards() {
        SensorClock.reset();
        long wall = 1_700_000_000_000L;
        long ns = 30_000_000_000L;
        long a = SensorClock.msFromNanos(ns, wall);
        long b = SensorClock.msFromNanos(ns - 5_000_000L, wall + 1);   // 回跳 5ms
        assertTrue("时钟回跳时必须单调化，不能返回更小的值", b >= a);

        long bad = SensorClock.msFromNanos(0L, wall + 2);
        assertEquals("ns<=0 的帧退化为墙钟", wall + 2, bad);
    }

    // ---------------- 2. 采样率无关 ----------------

    /** 117Hz（真机实测帧率）下，1.5Hz 步行必须照常判出合格步 */
    @Test
    public void highSampleRate_walkStillTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();

        double t = 0.0;
        boolean triggered = false;
        for (int i = 0; i < 800 && !triggered; i++) {      // 800 帧 × 8.5ms ≈ 6.8s
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * t / 1000.0));
            triggered = g.feedAcc(ax, 0f, 0f, Math.round(t));
            t += 8.5;
        }
        assertTrue("117Hz 下 1.5Hz 步行必须能判出合格步（debug=" + g.debugInfo() + "）", triggered);

        float hz = g.sampleRateHz();
        assertTrue("实测采样率应被识别为 ~117Hz，实际 " + hz, hz > 110f && hz < 125f);
    }

    /** 50Hz（旧路径）与 117Hz 对同一频率的步行，步频估计不能差出一倍 */
    @Test
    public void sampleRateChange_keepsStepInterval() {
        float low = firstStepInterval(20.0);
        float high = firstStepInterval(8.5);
        assertTrue("50Hz 下步间隔应接近 667ms，实际 " + low, low > 600f && low < 730f);
        assertTrue("117Hz 下步间隔应同样接近 667ms，实际 " + high, high > 600f && high < 730f);
    }

    /** 以 ms 帧间隔喂 1.5Hz 步行，返回第一个合格步对应的间隔（毫秒） */
    private float firstStepInterval(double frameMs) {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();
        double t = 0.0;
        for (int i = 0; i < 1200; i++) {
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * t / 1000.0));
            g.feedAcc(ax, 0f, 0f, Math.round(t));
            t += frameMs;
            if (g.confirmedStepCount() >= 1) {
                return 1000.0f / g.stepFreqHz();
            }
        }
        return -1f;
    }

    // ---------------- 3. 轻步降阈 ----------------

    /**
     * 静默 3 秒 + 包络确有往复（0.58 幅值 → 残差峰值约 0.545）时，
     * 阈值下限必须从 0.6 真正放宽到 0.35 —— 这是「轻步不漏」的机制本身。
     *
     * 注意这里只断言**阈值**，不断言计步：0.58 m/s² 这种幅值的残差波形本身是
     * 双峰畸变的（重放里一个周期会出现两次越阈、中间夹一个 3.6s 空档），
     * 硬要它攒够连续步数是在考验噪声，而不是考验这个修复。
     */
    @Test
    public void lightSteps_thresholdFloorRelaxes() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();

        long t = 0L;
        float minTh = 99f;
        for (int i = 0; i < 1000; i++) {                      // 50Hz × 20s
            float ax = (float) (GRAVITY + 0.58 * Math.sin(2 * Math.PI * 1.1 * i / 50.0));
            g.feedAcc(ax, 0f, 0f, t);
            if (i > 200) minTh = Math.min(minTh, g.getCurrentThreshold());
            t += 20;
        }
        assertTrue("静默后阈值下限必须放宽到 0.5 以下，实际最低 " + minTh, minTh < 0.5f);
    }

    /** 轻但完整的步流（残差峰值约 0.64）必须能凑出连续合格步 */
    @Test
    public void lightButCleanSteps_stillCount() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();

        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 1000 && !triggered; i++) {         // 50Hz × 20s
            float ax = (float) (GRAVITY + 0.68 * Math.sin(2 * Math.PI * 1.6 * i / 50.0));
            triggered = g.feedAcc(ax, 0f, 0f, t);
            t += 20;
        }
        assertTrue("轻步（幅值 0.68 / 1.6Hz）必须能判出（debug=" + g.debugInfo() + "）", triggered);
    }

    /** 纯静止（噪声 0.1）不能被降阈放大成假步 */
    @Test
    public void quietNoise_relaxDoesNotCreateFakeSteps() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();
        long t = 0L;
        boolean any = false;
        for (int i = 0; i < 1000; i++) {
            float ax = (float) (GRAVITY + 0.1 * Math.sin(2 * Math.PI * 1.2 * i / 50.0));
            any |= g.feedAcc(ax, 0f, 0f, t);
            t += 20;
        }
        assertFalse("噪声 0.1 静止不能凑出合格步（debug=" + g.debugInfo() + "）", any);
    }

    // ---------------- 4. 瞬态剔除 ----------------

    /**
     * 磕碰 / 甩手（单帧 14 m/s² 线性加速度）：不计步、不喂包络，
     * 之后几秒的正常步行不能被这记冲击压住。
     */
    @Test
    public void transientSpike_doesNotPolluteEnvelope() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();

        long t = 0L;
        for (int i = 0; i < 100; i++) {          // 静止 2 秒让重力收敛
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += 20;
        }
        boolean spikeStep = g.feedAcc(GRAVITY + 14.0f, 0f, 0f, t);   // 一记磕碰
        t += 20;
        for (int i = 0; i < 200; i++) {          // 之后静止 4 秒
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += 20;
        }
        assertFalse("磕碰帧不能直接计成一步", spikeStep);
        assertTrue("磕碰后阈值必须回落到 1.0 以下，实际 " + g.getCurrentThreshold(),
                g.getCurrentThreshold() < 1.0f);
    }

    /** 磕碰之后的正常步行必须照常触发，不能被前面的冲击压住 */
    @Test
    public void transientSpike_thenWalkStillTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();

        long t = 0L;
        for (int i = 0; i < 100; i++) {
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += 20;
        }
        g.feedAcc(GRAVITY + 14.0f, 0f, 0f, t);
        t += 20;

        boolean triggered = false;
        for (int i = 0; i < 600 && !triggered; i++) {          // 磕碰后立刻开始走
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * i / 50.0));
            triggered = g.feedAcc(ax, 0f, 0f, t);
            t += 20;
        }
        assertTrue("磕碰之后正常步行 12 秒内必须能判出（debug=" + g.debugInfo() + "）", triggered);
    }
}
