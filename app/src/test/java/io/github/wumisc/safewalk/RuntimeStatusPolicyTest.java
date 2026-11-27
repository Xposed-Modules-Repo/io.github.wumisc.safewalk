package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 【状态概览条】运行模式判定与那一行两句话的文案。
 *
 * 三个必须分清的状态，混了就会给出错误的修法：
 *  · 框架正常：什么都不用做；
 *  · 注入失联（装过、广播来过、现在超时）→ 重启系统界面；
 *  · 独立模式（从没收到过广播）→ 去启用模块，或者接受「只有 App 内功能」。
 *
 * 另外这条状态和「急救卡」合并成一行，标题必须短到不换行（<= 12 个汉字量级），
 * 细节句里才允许带锚点、新鲜度、日志、步数这些次要信息。
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
    public void frameworkHeadline_isShortAndCarriesAnchorsAndAge() {
        RuntimeStatusPolicy.Headline h =
                RuntimeStatusPolicy.headline(true, 12_000L, 3, true, StepSensorPolicy.OK);
        assertEquals("框架模式 · 注入正常", h.getTitle());
        assertEquals(RuntimeStatusPolicy.LEVEL_OK, h.getLevel());
        // 标题是首页那一行的主句，必须短，不能塞锚点/API
        assertFalse("标题不该带锚点，锚点属于细节句", h.getTitle().contains("锚点"));
        assertTrue(h.getDetail().contains("锚点 3/3"));
        assertTrue(h.getDetail().contains("12 秒"));
        assertTrue(h.getDetail().contains("日志开"));
    }

    @Test
    public void partialAnchors_areWarnedButNotAlarming() {
        RuntimeStatusPolicy.Headline h =
                RuntimeStatusPolicy.headline(true, 5_000L, 2, true, StepSensorPolicy.OK);
        assertEquals("框架模式 · 注入正常", h.getTitle());
        // 只挂上两个锚点：能用，但 SystemUI 可能改版了，给黄色而不是红色
        assertEquals(RuntimeStatusPolicy.LEVEL_WARN, h.getLevel());
    }

    @Test
    public void staleHeadline_saysHowToFix() {
        RuntimeStatusPolicy.Headline h =
                RuntimeStatusPolicy.headline(true, 12L * 60_000, 3, true, StepSensorPolicy.OK);
        assertEquals("框架模式 · 注入失联", h.getTitle());
        assertEquals(RuntimeStatusPolicy.LEVEL_BAD, h.getLevel());
        assertTrue(h.getDetail().contains("12 分钟"));
        assertTrue("失联必须告诉用户怎么修", h.getDetail().contains("重启系统界面"));
    }

    @Test
    public void standaloneHeadline_isOneLineAndPointsAtTheGap() {
        RuntimeStatusPolicy.Headline h =
                RuntimeStatusPolicy.headline(false, 0L, 0, true, StepSensorPolicy.OK);
        assertEquals("独立模式（无框架）", h.getTitle());
        assertEquals(RuntimeStatusPolicy.LEVEL_WARN, h.getLevel());
        assertTrue(h.getDetail().contains("日志开"));
        assertTrue(h.getDetail().contains("步数正常"));
        assertTrue("必须点明还有几项用不了", h.getDetail().contains("50 步提醒"));
    }

    @Test
    public void logOffIsFlaggedInDetail() {
        RuntimeStatusPolicy.Headline h =
                RuntimeStatusPolicy.headline(true, 5_000L, 3, false, StepSensorPolicy.OK);
        assertTrue(h.getDetail().contains("日志关"));
        assertEquals(RuntimeStatusPolicy.LEVEL_WARN, h.getLevel());
    }

    @Test
    public void noStepSourceIsNamed() {
        RuntimeStatusPolicy.Headline h =
                RuntimeStatusPolicy.headline(false, 0L, 0, true, StepSensorPolicy.NO_PERMISSION);
        assertTrue(h.getDetail().contains("缺活动权限"));
        RuntimeStatusPolicy.Headline noSensor =
                RuntimeStatusPolicy.headline(false, 0L, 0, true, StepSensorPolicy.NO_SENSOR);
        assertTrue(noSensor.getDetail().contains("无计步器"));
    }

    @Test
    public void unavailableListOnlyHooksSideFeatures() {
        java.util.List<String> list = RuntimeStatusPolicy.standaloneUnavailable();
        assertEquals(3, list.size());
        assertTrue(list.get(0).contains("50 步智能提醒"));
        // 独立模式实测能用的东西绝不能进这个名单，否则用户会被误导去卸载重装
        for (String s : list) {
            assertFalse("步态检测独立模式可用，不该被禁: " + s, s.contains("步态"));
            assertFalse("日志上云独立模式可用，不该被禁: " + s, s.contains("日志"));
            assertFalse("急救卡独立模式可用，不该被禁: " + s, s.contains("急救卡"));
        }
    }

    @Test
    public void agoFormatting() {
        assertEquals("刚刚", RuntimeStatusPolicy.ago(300L));
        assertEquals("12 秒", RuntimeStatusPolicy.ago(12_000L));
        assertEquals("3 分钟", RuntimeStatusPolicy.ago(3L * 60_000));
        assertEquals("2 小时", RuntimeStatusPolicy.ago(2L * 3_600_000));
    }
}
