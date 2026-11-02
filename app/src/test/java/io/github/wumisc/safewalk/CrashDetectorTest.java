package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 车祸检测灵敏度阈值映射测试。
 *
 * 三档灵敏度对应不同的动态加速度阈值（m/s²），越界档位必须夹回 [0,2]，
 * 否则配置里存了脏值会拿到 NaN 或错误阈值，导致检测器要么永远不触发、要么疯狂误报。
 */
public class CrashDetectorTest {

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
}
