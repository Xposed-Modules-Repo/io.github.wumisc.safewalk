package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 【状态概览】运行模式判定与文案。
 *
 * 三个必须分清的状态，混了就会给出错误的修法：
 *  · 框架正常：什么都不用做；
 *  · 注入失联（装过、广播来过、现在超时）→ 重启系统界面；
 *  · 独立模式（从没收到过广播）→ 去启用模块，或者接受「只有 App 内功能」。
 */
public class RuntimeStatusPolicyTest {

    @Test
    public void neverSeen_isStandalone() {
        assertEquals(RuntimeStatusPolicy.MODE_STANDALONE,
                RuntimeStatusPolicy.mode(false, 0L));
        assertEquals(RuntimeStatusPolicy.MODE_STANDALONE,
                RuntimeStatusPolicy.mode(false, 999_999L));
    }

    @Test
    public void freshPing_isFramework() {
        assertEquals(RuntimeStatusPolicy.MODE_FRAMEWORK,
                RuntimeStatusPolicy.mode(true, 0L));
        assertEquals(RuntimeStatusPolicy.MODE_FRAMEWORK,
                RuntimeStatusPolicy.mode(true, RuntimeStatusPolicy.FRESH_MS - 1));
    }

    @Test
    public void stalePing_isFrameworkStale_notStandalone() {
        assertEquals(RuntimeStatusPolicy.MODE_FRAMEWORK_STALE,
                RuntimeStatusPolicy.mode(true, RuntimeStatusPolicy.FRESH_MS + 1));
        // 再久也不会被判成「没装框架」：这两件事的修法完全不同
        assertEquals(RuntimeStatusPolicy.MODE_FRAMEWORK_STALE,
                RuntimeStatusPolicy.mode(true, 24L * 3600 * 1000));
    }

    @Test
    public void frameworkRows_carryAnchorsAndAge() {
        List<RuntimeStatusPolicy.Row> rows = RuntimeStatusPolicy.rows(
                true, 12_000L, "5.7.9-lx102", 3, "LSPosed", 102, true, StepSensorPolicy.OK);
        assertEquals(4, rows.size());
        assertEquals("运行模式", rows.get(0).getLabel());
        assertEquals("框架模式 · 已注入", rows.get(0).getValue());
        assertEquals(RuntimeStatusPolicy.LEVEL_OK, rows.get(0).getLevel());

        assertTrue("注入行要带锚点数与新鲜度",
                rows.get(1).getValue().contains("锚点 3/3"));
        assertTrue(rows.get(1).getValue().contains("12 秒"));
        assertTrue(rows.get(1).getValue().contains("v5.7.9-lx102"));

        assertEquals("已开启", rows.get(2).getValue());
        assertTrue(rows.get(3).getValue().contains("可用"));
    }

    @Test
    public void standaloneRows_listUnavailableAndDoNotClaimInjected() {
        List<RuntimeStatusPolicy.Row> rows = RuntimeStatusPolicy.rows(
                false, 0L, "", 0, "", 0, true, StepSensorPolicy.OK);
        assertEquals("独立模式（未检测到框架）", rows.get(0).getValue());
        assertEquals("未注入（没装 LSPosed 或模块没启用）", rows.get(1).getValue());
        assertTrue("独立模式必须多一行，说清哪些用不了",
                rows.size() == 5);
        assertTrue(rows.get(4).getValue().contains("50 步智能提醒"));
        assertEquals(RuntimeStatusPolicy.LEVEL_BAD, rows.get(4).getLevel());
    }

    @Test
    public void logOffIsFlagged() {
        List<RuntimeStatusPolicy.Row> rows = RuntimeStatusPolicy.rows(
                false, 0L, "", 0, "", 0, false, StepSensorPolicy.OK);
        assertEquals("诊断日志", rows.get(2).getLabel());
        assertTrue(rows.get(2).getValue().contains("已关闭"));
        assertEquals(RuntimeStatusPolicy.LEVEL_WARN, rows.get(2).getLevel());
    }

    @Test
    public void noStepSourceSaysHowToFix() {
        List<RuntimeStatusPolicy.Row> rows = RuntimeStatusPolicy.rows(
                true, 5_000L, "1", 3, "LSPosed", 102, true, StepSensorPolicy.NO_PERMISSION);
        assertTrue(rows.get(3).getValue().contains("身体活动"));
        assertEquals(RuntimeStatusPolicy.LEVEL_WARN, rows.get(3).getLevel());
    }

    @Test
    public void agoFormatting() {
        assertEquals("刚刚", RuntimeStatusPolicy.ago(300L));
        assertEquals("12 秒", RuntimeStatusPolicy.ago(12_000L));
        assertEquals("3 分钟", RuntimeStatusPolicy.ago(3L * 60_000));
        assertEquals("2 小时", RuntimeStatusPolicy.ago(2L * 3_600_000));
    }
}
