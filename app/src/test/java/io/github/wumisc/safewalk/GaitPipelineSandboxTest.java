package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

/**
 * 【步态沙盒 · 端到端】把「检测器 → 确认策略」串起来跑 30 秒，模拟 App 里
 * 「步态沙盒 30 秒录制 → 回放」这条真实链路的判定结果。
 *
 * 矩阵只验检测器；这里验**用户真正看到的那一步**：
 * 平拿 30 秒走路必须出「已确认」，床上抖 30 秒必须「不确认」。
 */
@RunWith(JUnit4.class)
public class GaitPipelineSandboxTest {

    private static final long FRAME_MS = 20L;
    private static final float G = 9.8f;

    private static final class Run {
        GaitDetector detector = new GaitDetector();
        List<Long> stepTs = new ArrayList<>();
        int frames = 1500;                 // 30 秒 × 50Hz
        long now = 0L;

        Run() {
            detector.reset();
            detector.setConfirmSteps(8);
        }

        WalkConfirmPolicy.Verdict verdict() {
            return new WalkConfirmPolicy(8).evaluate(true, stepTs.size(), stepTs,
                    stepTs.isEmpty() ? 0L : stepTs.get(stepTs.size() - 1));
        }
    }

    private static Run feedFlatWalking(double hz, float bounce, float swing) {
        Run r = new Run();
        // 2 秒静止建立重力基线
        for (int i = 0; i < 100; i++) {
            r.detector.feedAcc(0f, 0f, G, r.now);
            r.now += FRAME_MS;
        }
        for (int i = 0; i < r.frames; i++) {
            double s = i / 50.0;
            float ax = (float) (swing * Math.sin(2 * Math.PI * hz * s));
            float az = (float) (G + bounce * Math.sin(2 * Math.PI * hz * s));
            if (r.detector.feedAcc(ax, 0f, az, r.now)) r.stepTs.add(r.now);
            r.now += FRAME_MS;
        }
        return r;
    }

    /** 平拿手机走 30 秒：检测器 + 确认策略都要放行（这是用户抱怨「走两圈没反应」的场景） */
    @Test
    public void flatHeld30s_isConfirmed() {
        Run r = feedFlatWalking(1.8, 1.2f, 2.0f);
        assertTrue("30 秒平拿走路须攒够 8 步（实际=" + r.stepTs.size()
                + " debug=" + r.detector.debugInfo() + "）", r.stepTs.size() >= 8);
        WalkConfirmPolicy.Verdict v = r.verdict();
        assertTrue("30 秒平拿走路必须被判「已确认」（reason=" + v.reason
                + " steps=" + v.steps + " span=" + v.spanMs + "）", v.pass);
    }

    /** 床上抖 30 秒（3.2Hz 平放）：检测器 0 步，策略必然不放行 */
    @Test
    public void bedShake30s_notConfirmed() {
        Run r = new Run();
        for (int i = 0; i < 100; i++) {
            r.detector.feedAcc(0f, 0f, G, r.now);
            r.now += FRAME_MS;
        }
        for (int i = 0; i < r.frames; i++) {
            double s = i / 50.0;
            if (r.detector.feedAcc(0f, 0f,
                    (float) (G + 2.5 * Math.sin(2 * Math.PI * 3.2 * s)), r.now)) {
                r.stepTs.add(r.now);
            }
            r.now += FRAME_MS;
        }
        assertEquals("3.2Hz 抖腿不得产生合格步", 0, r.stepTs.size());
        assertFalse("抖腿不得被判「已确认」", r.verdict().pass);
    }

    /** 走路 10 秒后停 20 秒：停下后不能被确认（窗口时效必须生效） */
    @Test
    public void walkThenStop_notConfirmed() {
        Run r = feedFlatWalking(1.8, 1.2f, 2.0f);
        int walked = r.stepTs.size();
        assertTrue("前置：走路段须确认", walked >= 8);
        // 停 20 秒，不再喂任何峰
        for (int i = 0; i < 1000; i++) {
            r.detector.feedAcc(0f, 0f, G, r.now);
            r.now += FRAME_MS;
        }
        r.stepTs = new ArrayList<>();   // 用检测器当前窗口重新判
        WalkConfirmPolicy.Verdict v = new WalkConfirmPolicy(8).evaluate(
                true, r.detector.confirmedStepCount(), new ArrayList<Long>(), r.now);
        assertFalse("停下 20 秒后不得仍判「已确认」", v.pass);
        assertEquals("停下 20 秒后窗口必须过期", 0, r.detector.confirmedStepCount());
    }
}
