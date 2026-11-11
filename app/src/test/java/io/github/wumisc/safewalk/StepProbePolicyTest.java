package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * [StepProbePolicy] 回归测试。
 *
 * 核心安全边界：**「传感器有原始回调、只是没走出合格步」绝不能被报成
 * 「传感器静默失效」** —— 真机 19:07:33 / 19:08:19 那两条 ❌ 就是这么误报的，
 * 还误导用户去步态自检。另外每个启动会话只报一次，不许 45 秒复刷。
 */
public class StepProbePolicyTest {

    private static final long W = 45_000L;

    private static StepProbePolicy.Verdict eval(
            boolean masterOn, long initTs, long now, long raw, int steps, boolean reported) {
        return StepProbePolicy.evaluate(masterOn, initTs, now, W, raw, steps, reported);
    }

    // ---------- 未到窗口 / 不该报 ----------

    @Test
    public void beforeWindowNotReady() {
        assertEquals(StepProbePolicy.Verdict.NOT_READY,
                eval(true, 1_000L, 1_000L + W - 1, 0L, 0, false));
    }

    @Test
    public void notInitializedNotReady() {
        assertEquals(StepProbePolicy.Verdict.NOT_READY,
                eval(true, 0L, 999_999L, 0L, 0, false));
    }

    @Test
    public void masterOffNotReady() {
        assertEquals(StepProbePolicy.Verdict.NOT_READY,
                eval(false, 1_000L, 1_000L + W + 1, 0L, 0, false));
    }

    @Test
    public void alreadyReportedNeverReportsAgain() {
        assertEquals("同一会话只报一次",
                StepProbePolicy.Verdict.NOT_READY,
                eval(true, 1_000L, 1_000L + W * 3, 0L, 0, true));
    }

    @Test
    public void clockRollbackNotReady() {
        assertEquals(StepProbePolicy.Verdict.NOT_READY,
                eval(true, 100_000L, 50_000L, 0L, 0, false));
    }

    // ---------- 关键分级 ----------

    @Test
    public void sensorAliveButNoQualifiedStep_isInfoNotError() {
        // 真机现场：加速度计兜底在跑、原始回调一大堆，只是静止没凑出合格步
        assertEquals(StepProbePolicy.Verdict.SENSOR_ALIVE_NO_STEP,
                eval(true, 1_000L, 1_000L + W + 1, 3_842L, 0, false));
    }

    @Test
    public void oneRawCallbackIsEnoughToProveSensorAlive() {
        assertEquals(StepProbePolicy.Verdict.SENSOR_ALIVE_NO_STEP,
                eval(true, 1_000L, 1_000L + W + 1, 1L, 0, false));
    }

    @Test
    public void zeroRawCallbacksIsRealSilence() {
        assertEquals("连一次原始回调都没有，才是真静默失效",
                StepProbePolicy.Verdict.SENSOR_SILENT,
                eval(true, 1_000L, 1_000L + W + 1, 0L, 0, false));
    }

    @Test
    public void qualifiedStepsAreHealthy() {
        assertEquals(StepProbePolicy.Verdict.OK,
                eval(true, 1_000L, 1_000L + W + 1, 0L, 7, false));
    }

    @Test
    public void qualifiedStepsWinOverRawCount() {
        assertEquals(StepProbePolicy.Verdict.OK,
                eval(true, 1_000L, 1_000L + W + 1, 5L, 1, false));
    }

    @Test
    public void exactWindowBoundaryReports() {
        assertEquals(StepProbePolicy.Verdict.SENSOR_SILENT,
                eval(true, 1_000L, 1_000L + W, 0L, 0, false));
    }

    @Test
    public void graceDelaysProbe() {
        assertEquals(StepProbePolicy.Verdict.NOT_READY,
                StepProbePolicy.evaluate(true, 1_000L, 1_000L + W + 1, W,
                        0L, 0, false, 10_000L));
        assertEquals(StepProbePolicy.Verdict.SENSOR_SILENT,
                StepProbePolicy.evaluate(true, 1_000L, 1_000L + W + 10_001L, W,
                        0L, 0, false, 10_000L));
    }
}
