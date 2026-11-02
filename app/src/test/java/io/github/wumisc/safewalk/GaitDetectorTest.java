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

    /** 坐着不动：绝不能判成走路 */
    @Test
    public void sittingStill_isNotWalking() {
        long t = settle(0L);
        for (int i = 0; i < 500; i++) {
            gait.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertFalse("静止 10 秒不能判成走路", gait.hasStableGait());
    }

    /** 5Hz 的连续抖动（摇手机 / 抖腿）不在步行步频窗口内，不应算走路 */
    @Test
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
}
