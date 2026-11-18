package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** 碰撞融合纯函数单测：速度突变 / 姿态 / 屏幕 / 跌倒排除 / 边界值。 */
public class CrashFusionPolicyTest {

    private static CrashSample s(float ax, float ay, float az, long t) {
        return new CrashSample(ax, ay, az, t);
    }

    @Test
    public void deltaV_zeroOnStaticGravity() {
        List<CrashSample> samples = Arrays.asList(
                s(0f, 0f, 9.8f, 0L), s(0f, 0f, 9.8f, 25L), s(0f, 0f, 9.8f, 50L));
        assertEquals(0f, CrashFusionPolicy.deltaV(samples), 0.0001f);
    }

    @Test
    public void deltaV_windowIsCappedAt200ms() {
        // 两帧间隔 500ms，超过融合窗口，按 200ms 上限积分；
        // dynamicMagnitude=||a||-g = √(19.8²+9.8²)-9.8 ≈ 12.29
        List<CrashSample> samples = Arrays.asList(
                s(0f, 9.8f, 0f, 0L), s(19.8f, 9.8f, 0f, 500L));
        assertEquals(2.46f, CrashFusionPolicy.deltaV(samples), 0.02f);
    }

    @Test
    public void poseAngle_distinguishesFlatAndUpright() {
        assertEquals(0f, CrashFusionPolicy.poseAngleDeg(
                Collections.singletonList(s(0f, 0f, 9.8f, 0L))), 1f);
        assertEquals(90f, CrashFusionPolicy.poseAngleDeg(
                Collections.singletonList(s(0f, 9.8f, 0f, 0L))), 1f);
    }

    @Test
    public void fallExcluded_whenLowDvAndFlatPose() {
        List<CrashSample> samples = Arrays.asList(
                s(0f, 9.8f, 0f, 0L), s(10f, 6f, 4f, 30L), s(0f, 0f, 9.8f, 60L));
        assertTrue(CrashFusionPolicy.excludeFall(samples, 5f, 0.2f));
    }

    @Test
    public void fallNeverExcluded_whenHardHitEvenFlat() {
        List<CrashSample> samples = Arrays.asList(
                s(0f, 0f, 9.8f, 0L), s(35f, 0f, 0f, 30L), s(35f, 0f, 0f, 60L));
        assertFalse(CrashFusionPolicy.excludeFall(samples, 0f, 0.1f));
        CrashEvent e = CrashFusionPolicy.evaluate(samples, 0f, true, 20f);
        assertTrue(e.shouldAlert());
    }

    @Test
    public void screenOff_addsConfidenceButNeverTriggersAlone() {
        List<CrashSample> still = Arrays.asList(
                s(0f, 9.8f, 0f, 0L), s(0f, 9.8f, 0f, 30L));
        assertFalse(CrashFusionPolicy.evaluate(still, 90f, true, 20f).shouldAlert());
    }

    @Test
    public void singleSample_neverAlerts() {
        CrashEvent e = CrashFusionPolicy.evaluate(
                Collections.singletonList(s(60f, 0f, 0f, 0L)), 90f, true, 15f);
        assertFalse(e.shouldAlert());
        assertEquals(0f, e.getConfidence(), 0.0001f);
    }
}
