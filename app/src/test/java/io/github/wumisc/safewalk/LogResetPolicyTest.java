package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「何时清空重建日志」纯策略单测。
 *
 * <p>三条证据任一命中就要清：versionCode 变 / APK 字节数变 / 安装指纹变。
 * 取不到的一侧（0）不参与比较，避免「没有记录」被误判成「换过包」而每次启动都清。
 */
public class LogResetPolicyTest {

    private static final long SIZE_A = 3_811_022L;
    private static final long SIZE_B = 3_811_500L;
    private static final long STAMP_A = 1_795_510_355_450L;
    private static final long STAMP_B = 1_795_600_000_000L;

    @Test
    public void firstRecordAlwaysResets() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(-1, 102, 0L, STAMP_A, 0L, SIZE_A, "5.6.15");
        assertTrue(d.getReset());
        assertEquals("首次记录版本", d.getReason());
    }

    @Test
    public void versionBumpResets() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(101, 102, STAMP_A, STAMP_B, SIZE_A, SIZE_B, "5.6.15");
        assertTrue(d.getReset());
        assertEquals("版本升级 101→102", d.getReason());
    }

    @Test
    public void sameVersionButApkReplacedResets() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(102, 102, STAMP_A, STAMP_A, SIZE_A, SIZE_B, "5.6.15");
        assertTrue("同版本号换了包也必须清空重建", d.getReset());
        assertTrue(d.getReason().contains("覆盖安装"));
        assertTrue(d.getReason().contains("5.6.15"));
    }

    @Test
    public void sameVersionSameSizeButStampChangedResets() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(102, 102, STAMP_A, STAMP_B, SIZE_A, SIZE_A, "5.6.15");
        assertTrue("字节数碰巧相同、安装时间戳变了也要清", d.getReset());
        assertTrue(d.getReason().contains("指纹"));
    }

    @Test
    public void nothingChangedKeepsLog() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(102, 102, STAMP_A, STAMP_A, SIZE_A, SIZE_A, "5.6.15");
        assertFalse("同一安装反复启动不得清日志", d.getReset());
        assertEquals("", d.getReason());
    }

    @Test
    public void missingRecordIsNotTreatedAsChanged() {
        // 旧版本只记过 versionCode，指纹/大小为 0：不能据此判定「换过包」而把日志清掉
        LogResetPolicy.Decision d = LogResetPolicy.decide(102, 102, 0L, STAMP_A, 0L, SIZE_A, "5.6.15");
        assertFalse("没有记录 ≠ 换过包", d.getReset());
    }

    @Test
    public void onlyOneSideMissingAlsoKeepsLog() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(102, 102, STAMP_A, 0L, SIZE_A, 0L, "5.6.15");
        assertFalse("当前侧取不到时不得误清", d.getReset());
    }

    @Test
    public void versionNameFallsBackToCodeWhenEmpty() {
        LogResetPolicy.Decision d = LogResetPolicy.decide(102, 102, STAMP_A, STAMP_B, SIZE_A, SIZE_A);
        assertTrue(d.getReason().contains("102"));
    }
}
