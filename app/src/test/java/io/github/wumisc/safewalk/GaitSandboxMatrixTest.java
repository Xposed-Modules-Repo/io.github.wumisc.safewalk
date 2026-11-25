package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 【5.7.3 步态沙盒矩阵】把真机上最常见的几种姿态/场景各合成一段波形，
 * 用**同一份** [GaitDetector] 跑一遍，确认「该过的过、该拦的拦」。
 *
 * 这不是替代真机，而是给「判据改动」一个可重复的回归面：以后调阈值先看这张矩阵，
 * 再去 App 里的「步态沙盒」录真机数据复核。坐标系约定：
 *   · gz≈9.8 → 屏幕朝上平放（LAY_FLAT）
 *   · gy≈9.8 → 竖直握持 / 口袋里（UPRIGHT 系）
 */
@RunWith(JUnit4.class)
public class GaitSandboxMatrixTest {

    private static final long FRAME_MS = 20L;
    private static final float G = 9.8f;

    /** 采样 n 帧，返回是否曾在窗口内攒够 confirm 步 */
    private static boolean run(GaitDetector g, int frames, Wave wave) {
        long t = 0L;
        for (int i = 0; i < 200; i++) {          // 2 秒静止，让姿态稳定器收敛
            g.feedAcc(wave.ax(0), wave.ay(0), wave.az(0), t);
            t += FRAME_MS;
        }
        boolean hit = false;
        for (int i = 0; i < frames; i++) {
            double sec = i / 50.0;
            if (g.feedAcc(wave.ax(sec), wave.ay(sec), wave.az(sec), t)) hit = true;
            if (g.confirmedStepCount() >= g.getConfirmSteps()) return true;
            t += FRAME_MS;
        }
        return hit && g.confirmedStepCount() >= g.getConfirmSteps();
    }

    private interface Wave {
        float ax(double sec);
        float ay(double sec);
        float az(double sec);
    }

    private static GaitDetector detector(int confirm) {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(confirm);
        g.reset();
        return g;
    }

    // ---------------- 应当放行 ----------------

    /** 平着拿手机走路：垂直颠簸 1.2、手臂前后摆 2.0，垂直能量占比只有 ~0.26 */
    @Test
    public void flatHeldWalking_passes() {
        final double f = 1.8;
        boolean ok = run(detector(8), 600, new Wave() {
            public float ax(double s) { return (float) (2.0 * Math.sin(2 * Math.PI * f * s)); }
            public float ay(double s) { return 0f; }
            public float az(double s) { return (float) (G + 1.2 * Math.sin(2 * Math.PI * f * s)); }
        });
        assertTrue("平着拿手机走路必须攒够 8 步", ok);
    }

    /** 兜里 / 竖直握持走路：重力落在 Y 轴，垂直冲击 2.2 */
    @Test
    public void pocketWalking_passes() {
        final double f = 1.8;
        boolean ok = run(detector(8), 600, new Wave() {
            public float ax(double s) { return 0f; }
            public float ay(double s) { return (float) (G + 2.2 * Math.sin(2 * Math.PI * f * s)); }
            public float az(double s) { return 0f; }
        });
        assertTrue("口袋里走路必须攒够 8 步", ok);
    }

    /** 慢走 0.9Hz（约 54 步/分，老人/散步） */
    @Test
    public void slowWalk_passes() {
        final double f = 0.9;
        boolean ok = run(detector(8), 800, new Wave() {
            public float ax(double s) { return 0f; }
            public float ay(double s) { return (float) (G + 2.0 * Math.sin(2 * Math.PI * f * s)); }
            public float az(double s) { return 0f; }
        });
        assertTrue("0.9Hz 慢走必须攒够 8 步", ok);
    }

    /** 快走 / 慢跑 2.4Hz（144 步/分，仍在采集层 2.5Hz 上限内） */
    @Test
    public void fastWalk_passes() {
        final double f = 2.4;
        boolean ok = run(detector(8), 500, new Wave() {
            public float ax(double s) { return 0f; }
            public float ay(double s) { return (float) (G + 2.5 * Math.sin(2 * Math.PI * f * s)); }
            public float az(double s) { return 0f; }
        });
        assertTrue("2.4Hz 快走必须攒够 8 步", ok);
    }

    // ---------------- 应当拦截 ----------------

    /** 平放桌面微震：0.5 m/s²、1.6Hz，频率像步但幅值不够 */
    @Test
    public void flatDeskJitter_blocked() {
        final double f = 1.6;
        boolean ok = run(detector(8), 900, new Wave() {
            public float ax(double s) { return 0f; }
            public float ay(double s) { return 0f; }
            public float az(double s) { return (float) (G + 0.5 * Math.sin(2 * Math.PI * f * s)); }
        });
        assertFalse("桌面微震不得攒够步数", ok);
    }

    /** 躺床上抖腿：3.2Hz，超出步频上限（>2.5Hz），一个峰都不该计 */
    @Test
    public void bedLegShake_blocked() {
        final double f = 3.2;
        boolean ok = run(detector(8), 900, new Wave() {
            public float ax(double s) { return 0f; }
            public float ay(double s) { return 0f; }
            public float az(double s) { return (float) (G + 2.5 * Math.sin(2 * Math.PI * f * s)); }
        });
        assertFalse("3.2Hz 抖腿不得算走路", ok);
    }

    /** 车上颠簸 + 手部挪动：低频、间隔忽长忽短，凑不出连续 8 步 */
    @Test
    public void carBumps_blocked() {
        boolean ok = run(detector(8), 900, new Wave() {
            public float ax(double s) {
                // 0.3~0.7Hz 的不规则起伏，每次持续 1~2 秒
                double f = (Math.floor(s / 2.0) % 2 == 0) ? 0.35 : 0.65;
                return (float) (1.4 * Math.sin(2 * Math.PI * f * s));
            }
            public float ay(double s) { return 0f; }
            public float az(double s) { return (float) (G + 1.0 * Math.sin(2 * Math.PI * 0.4 * s)); }
        });
        assertFalse("车上颠簸不得攒够步数", ok);
    }

    /** 平放手搓屏幕：振动细碎（6Hz），远高于步频上限 */
    @Test
    public void flatFingerScrub_blocked() {
        final double f = 6.0;
        boolean ok = run(detector(8), 900, new Wave() {
            public float ax(double s) { return (float) (0.8 * Math.sin(2 * Math.PI * f * s)); }
            public float ay(double s) { return 0f; }
            public float az(double s) { return (float) (G + 0.6 * Math.sin(2 * Math.PI * f * s)); }
        });
        assertFalse("搓屏高频振动不得攒够步数", ok);
    }
}
