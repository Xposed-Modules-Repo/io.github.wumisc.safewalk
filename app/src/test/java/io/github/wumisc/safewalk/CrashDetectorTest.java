package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * 车祸检测测试：原 2 个阈值用例保留，新增融合层用例（速度/姿态/跌倒排除/边界）。
 */
public class CrashDetectorTest {

    private static CrashSample s(float ax, float ay, float az, long t) {
        return new CrashSample(ax, ay, az, t);
    }

    @Test
    public void sensitivity_thresholdMapping() {
        assertEquals(25f, CrashDetector.thresholdFor(0), 0.01f); // 低 ≈2.5g
        assertEquals(20f, CrashDetector.thresholdFor(1), 0.01f); // 中 ≈2.0g
        assertEquals(15f, CrashDetector.thresholdFor(2), 0.01f); // 高 ≈1.5g
    }

    @Test
    public void sensitivity_clampsOutOfRange() {
        assertEquals(25f, CrashDetector.thresholdFor(-1), 0.01f); // 负数夹到 0
        assertEquals(15f, CrashDetector.thresholdFor(99), 0.01f); // 越界夹到 2
    }

    @Test
    public void fusion_deltaV_integratesDynamicAcceleration() {
        // 20m/s² 的横向加速度持续 100ms（4 帧 ×25ms），扣掉 9.8 重力后
        // Δv ≈ (20-9.8) * 0.1 = 1.02 m/s
        List<CrashSample> samples = Arrays.asList(
                s(0f, 9.8f, 0f, 0L),
                s(20f, 9.8f, 0f, 25L),
                s(20f, 9.8f, 0f, 50L),
                s(20f, 9.8f, 0f, 75L),
                s(20f, 9.8f, 0f, 100L)
        );
        float dv = CrashFusionPolicy.deltaV(samples);
        // dynamicMagnitude = ||a|| - g，故横向 20 对应 √(20²+9.8²)-9.8 ≈ 12.47
        assertEquals(1.25f, dv, 0.05f);
    }

    @Test
    public void fusion_staticPhone_doesNotAlert() {
        // 平放在桌上：加速度恒等于重力，动态量=0
        List<CrashSample> samples = Arrays.asList(
                s(0f, 0f, 9.8f, 0L),
                s(0f, 0f, 9.8f, 30L),
                s(0f, 0f, 9.8f, 60L)
        );
        assertEquals(0f, CrashFusionPolicy.deltaV(samples), 0.001f);
        CrashEvent e = CrashFusionPolicy.evaluate(samples, 0f, false, 20f);
        assertTrue(e.getFallExcluded());
        assertFalse(e.shouldAlert());
    }

    @Test
    public void fusion_phoneDrop_fallExcluded() {
        // 手机从口袋跌落：小幅速度突变 + 落地后接近平躺 → 排除，不触发求助
        List<CrashSample> samples = Arrays.asList(
                s(0f, 9.8f, 0f, 0L),
                s(11f, 9.8f, 2f, 30L),
                s(12f, 3f, 8f, 60L),
                s(0f, 0f, 9.8f, 90L)
        );
        float dv = CrashFusionPolicy.deltaV(samples);
        assertTrue("跌落 Δv 应低于跌倒排除上限", dv < CrashFusionPolicy.FALL_DELTA_V_MAX);
        assertTrue(CrashFusionPolicy.excludeFall(samples, 10f, dv));
        CrashEvent e = CrashFusionPolicy.evaluate(samples, 10f, false, 20f);
        assertFalse("跌落不得触发求助", e.shouldAlert());
    }

    @Test
    public void fusion_realCrash_alerts() {
        // 真实撞击：高动态加速度 + 大速度突变 + 姿态偏平躺 + 屏幕熄屏
        List<CrashSample> samples = Arrays.asList(
                s(0f, 9.8f, 0f, 0L),
                s(35f, 6f, 4f, 30L),
                s(38f, 4f, 6f, 60L),
                s(30f, 2f, 8f, 90L)
        );
        float dv = CrashFusionPolicy.deltaV(samples);
        assertTrue("真实撞击 Δv 应显著", dv > 1.5f);
        assertFalse("真实撞击不能被跌倒排除", CrashFusionPolicy.excludeFall(samples, 20f, dv));
        CrashEvent e = CrashFusionPolicy.evaluate(samples, 20f, true, 20f);
        assertTrue("真实撞击必须触发", e.shouldAlert());
        assertTrue("置信度应在 (0,1]", e.getConfidence() > 0f && e.getConfidence() <= 1f);
    }

    @Test
    public void fusion_hardHit_neverExcludedAsFall() {
        // 边界：峰值≥25 时即便姿态平躺也绝不按跌倒排除（不漏报真实事故）
        List<CrashSample> samples = Arrays.asList(
                s(0f, 0f, 9.8f, 0L),
                s(35f, 0f, 0f, 40L),
                s(35f, 0f, 0f, 80L)
        );
        assertFalse(CrashFusionPolicy.excludeFall(samples, 0f, 0.1f));
    }

    @Test
    public void fusion_singleSample_excluded() {
        // 边界：样本不足时宁可排除，也不能凭一帧就发求助
        List<CrashSample> samples = Arrays.asList(s(50f, 9.8f, 0f, 0L));
        assertEquals(0f, CrashFusionPolicy.deltaV(samples), 0.001f);
        assertTrue(CrashFusionPolicy.excludeFall(samples, 90f, 0f));
    }
}
