package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 步态判定的合成波形测试。
 *
 * 这里锁死的是一个「不看代码根本发现不了、真机上也只会表现为『什么都不提醒』」的 bug：
 *
 * 加速度计以约 50Hz 回调，一个脚步的冲击会连续 4~6 帧都高于阈值。
 * 原实现每帧都刷新「上一个峰的时间」，于是算出来的步间隔永远是 20ms（50Hz），
 * 永远落不进 1.4~2.2Hz 的步行窗口，peakIntervals 一个都进不去，
 * hasStableGait() 恒为 false —— 状态机走完了 VERIFYING，却永远判不出「在走路」。
 *
 * 测试用虚拟时间喂波形，不依赖真实时钟，也不会真的跑三秒。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GaitDetectorTest {

    /** 与 SENSOR_DELAY_GAME 一致：约 50Hz */
    private static final long FRAME_MS = 20L;
    /** 手机竖直握持时的重力（落在 X 轴） */
    private static final float GRAVITY = 9.8f;
    /** 一步的冲击幅度 */
    private static final float STEP_PEAK = 6.0f;

    private GaitDetector gait;

    @Before
    public void setUp() {
        gait = new GaitDetector();
        gait.reset();
    }

    /** 先喂一段静止，让重力低通滤波器收敛，否则前几帧的初值会被当成巨峰 */
    private long settle(long t) {
        for (int i = 0; i < 100; i++) {
            gait.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        return t;
    }

    @Test
    public void normalWalking_isRecognised() {
        long t = settle(0L);
        // 1.786Hz（560ms 一步），落在 1.4~2.2Hz 的正常步行区间
        int framesPerStep = (int) (560L / FRAME_MS);
        for (int step = 0; step < 6; step++) {
            for (int f = 0; f < framesPerStep; f++) {
                float ax = (f == 0) ? GRAVITY + STEP_PEAK : GRAVITY;
                gait.feedAcc(ax, 0f, 0f, t);
                t += FRAME_MS;
            }
        }
        assertTrue("正常步行 1.8Hz 必须被判定为走路，否则所有出门提醒都不会触发",
                gait.hasStableGait());
    }

    /**
     * 散步（1.2Hz）也必须算走路。
     *
     * 旧步频窗口是 1.4~2.2Hz，只覆盖快走；真实散步大约 1.1~1.4Hz，
     * 每一步都被判成「不合格」直接丢掉，peakIntervals 永远是空的。
     * 现场表现就是「走了半天一次都不提醒」，日志里却什么都看不出来。
     */
    @Test
    public void slowWalking_isRecognised() {
        long t = settle(0L);
        // 833ms 一步 ≈ 1.2Hz，正常散步速度
        int framesPerStep = (int) (833L / FRAME_MS);
        for (int step = 0; step < 8; step++) {
            for (int f = 0; f < framesPerStep; f++) {
                float ax = (f == 0) ? GRAVITY + STEP_PEAK : GRAVITY;
                gait.feedAcc(ax, 0f, 0f, t);
                t += FRAME_MS;
            }
        }
        assertTrue("散步 1.2Hz 必须算走路，否则慢走的人收不到任何提醒",
                gait.hasStableGait());
    }

    /** 3Hz 抖腿：高于 2.4Hz 上限，不能被当成步行 */
    @Test
    public void legShaking_threeHz_isNotWalking() {
        long t = settle(0L);
        int framesPerPulse = (int) (333L / FRAME_MS);
        for (int i = 0; i < 20; i++) {
            for (int f = 0; f < framesPerPulse; f++) {
                float ax = (f == 0) ? GRAVITY + STEP_PEAK : GRAVITY;
                gait.feedAcc(ax, 0f, 0f, t);
                t += FRAME_MS;
            }
        }
        assertFalse("3Hz 抖腿不能被当成步行", gait.hasStableGait());
    }

    /**
     * 修复回归（预览不弹出的真凶）：confirmSteps=5 且真实散步正弦，
     * feedAcc 必须在连续步内返回 true。
     *
     * 之前两个 bug 让它永远不触发：
     *  ① peakIntervals 上限只有 3，而 confirmSteps=5 → size 永远到不了 5；
     *  ② 步频下限 0.9Hz 太严 + 断步清空太激进，慢速散步被当成断步每次清空。
     * 现在上限 10、步频下限 0.5Hz、断步阈值独立 2.5s，散步必须能触发。
     */
    @Test
    public void confirmSteps5_slowWalkSinusoid_feedAccTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 600; i++) { // 50Hz × 12 秒，足够散步攒满 5 步
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 0.9 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("confirmSteps=5、0.9Hz 散步正弦：feedAcc 必须返回 true，否则预览永远不弹出（debug="
                + g.debugInfo() + "）", triggered);
    }

    /** 3Hz 抖腿在 confirmSteps=5 下绝不能触发 feedAcc（守住抗误触） */
    @Test
    public void confirmSteps5_legShake_feedAccNeverTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 400; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 3.0 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertFalse("3Hz 抖腿在 confirmSteps=5 下绝不能让 feedAcc 返回 true", triggered);
    }

    /** 坐着不动：绝不能判成走路 */
    @Test
    public void sittingStill_isNotWalking() {        long t = settle(0L);
        for (int i = 0; i < 500; i++) {
            gait.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertFalse("静止 10 秒不能判成走路", gait.hasStableGait());
    }

    /**
     * 真实步行是「连续正弦」，不是单帧阶跃尖峰。
     *
     * 这条用例存在的唯一目的：锁死重力滤波系数。
     * 旧实现 `gravity = 0.2*gravity + 0.8*acc` 让重力估计瞬间跟着信号跑，
     * 减完的残差只剩幅值的 1%（仿真里 1.5Hz 步行平均只有 0.07 m/s²），
     * 任何阈值都不可能触发 —— 真机上表现就是「走了半天一次都不提醒」。
     * 而上面那些用阶跃尖峰写的用例，在错误系数下依然能通过，所以一直没发现。
     */
    @Test
    public void realisticSinusoid_1_5Hz_isWalking() {
        long t = settle(0L);
        for (int i = 0; i < 400; i++) { // 50Hz × 8 秒
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            gait.feedAcc(ax, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertTrue("连续正弦（真实步行 1.5Hz）必须被识别，否则所有出门提醒都不会触发",
                gait.hasStableGait());
    }

    /** 正弦形式的 3Hz 抖腿：步频在窗口外，不能算走路 */
    @Test
    public void realisticSinusoid_3Hz_isNotWalking() {
        long t = settle(0L);
        for (int i = 0; i < 400; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 3.0 * sec));
            gait.feedAcc(ax, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertFalse("3Hz 正弦抖动不能被当成步行", gait.hasStableGait());
    }

    /**
     * 手机平放在包里：重力全落在 Z 轴，晃动发生在 X 轴。
     * 固定只看某一轴的实现在这里会完全失效，必须靠主轴自适应。
     */
    @Test
    public void walkingWithGravityOnZAxis_isRecognised() {
        long t = 0L;
        for (int i = 0; i < 100; i++) { // 让重力收敛到 Z 轴
            gait.feedAcc(0f, 0f, GRAVITY, t);
            t += FRAME_MS;
        }
        for (int i = 0; i < 400; i++) {
            double sec = i / 50.0;
            float ax = (float) (2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            gait.feedAcc(ax, 0f, GRAVITY, t);
            t += FRAME_MS;
        }
        assertTrue("重力落在 Z 轴、晃动在 X 轴时也必须能识别（主轴自适应）",
                gait.hasStableGait());
    }

    /** 5Hz 的连续抖动（摇手机 / 抖腿）不在步行步频窗口内，不应算走路 */    @Test
    public void fastShaking_isNotWalking() {
        long t = settle(0L);
        int framesPerShake = 4; // 4 帧 = 80ms ≈ 12.5Hz 的上冲，远超 2.2Hz
        for (int i = 0; i < 40; i++) {
            for (int f = 0; f < framesPerShake; f++) {
                float ax = (f == 0) ? GRAVITY + STEP_PEAK : GRAVITY;
                gait.feedAcc(ax, 0f, 0f, t);
                t += FRAME_MS;
            }
        }
        assertFalse("高频抖动不能被当成步行（步频窗口应该把它滤掉）", gait.hasStableGait());
    }

    /** 一个长波峰只能算一步：否则「上电梯/开车过减速带」会被算成一串步 */
    @Test
    public void singleLongPulse_countsAtMostOnce() {
        long t = settle(0L);
        // 连续 20 帧（400ms）都在阈值之上，然后回落
        for (int i = 0; i < 20; i++) {
            gait.feedAcc(GRAVITY + 3.0f, 0f, 0f, t);
            t += FRAME_MS;
        }
        for (int i = 0; i < 100; i++) {
            gait.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertFalse("单个长波峰不足以构成稳定步态", gait.hasStableGait());
    }

    /**
     * 真实步行不是节拍器：步间隔会在 500~1000ms 之间漂。
     *
     * 这条用例锁的是把提醒全部卡死的那条判据 —— `hasStableGait()` 原来要求
     * 每个间隔与均值的差都 < 250ms。手机在口袋里被裤子带着晃，峰位前后
     * 漂一两百毫秒是常态；4 个样本里只要有一个漂到 300ms，整轮就被判成
     * 「步态不稳」，步数攒够了也永远不触发，日志里每轮都是「差一点」。
     *
     * 2.9.4 把容差放宽到 400ms，并且不再让这个判断决定要不要提醒。
     */
    @Test
    public void driftingStepInterval_stillCountsAsWalking() {
        long t = settle(0L);
        // 三个节奏交替：500ms(2.0Hz) / 600ms(1.67Hz) / 1000ms(1.0Hz)
        // 最后 4 个间隔约 500/600/1000/500，均值 650，最大偏差 350ms
        double[] freqs = {2.0, 1.67, 1.0};
        double phase = 0;
        int k = 0;
        for (int i = 0; i < 500; i++) { // 10 秒
            phase += 2 * Math.PI * freqs[k % freqs.length] * (FRAME_MS / 1000.0);
            if (phase >= 2 * Math.PI) {
                phase -= 2 * Math.PI;
                k++;
            }
            float ax = (float) (GRAVITY + 2.5 * Math.sin(phase));
            gait.feedAcc(ax, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertTrue("间隔抖动 350ms 的真实步行必须仍算「稳定」，"
                        + "否则步数达标也不会触发提醒（2.9.3 及之前就是这样）",
                gait.hasStableGait());
    }

    /**
     * 慢走 + 4 秒采样窗口：这是验证环节最容易翻车的地方。
     *
     * Hook 原来只采 4 秒做二次确认。0.9Hz 的慢走 4 秒只有 3.6 步，
     * 第一个峰只用来定位、不产生间隔，剩下能用的样本只有 2 个 —— 刚好卡在
     * `peakIntervals.size < 2` 的边界上。再被主轴切换吞掉一个，就永远是 false。
     */
    @Test
    public void slowWalk_within4sWindow_hasEnoughSamples() {
        // 关键：这里**不能**先 settle()。
        // 真机是 reset() 之后立刻开始采样，没有那 2 秒缓冲。先 settle 会掩盖
        // 「重力从 0 收敛」造成的启动瞬态——而那个瞬态正是真机上永远判不出的原因。
        long t = 0L;
        for (int i = 0; i < 200; i++) { // 50Hz × 4 秒
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 0.9 * sec));
            gait.feedAcc(ax, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertTrue("4 秒慢走（0.9Hz）至少要有 2 个可用间隔，否则二次确认永远不通过；实际 "
                        + gait.debugInfo(), gait.hasStableGait());
    }
}
