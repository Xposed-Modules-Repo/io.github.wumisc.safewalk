package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 步态判定可信度诊断的回归测试。
 *
 * 背景：真机日志里「计步传感器=有 已出步=0」出现了一整天，用户却完全不知道
 * 自己的手机有一个能给出确定答案的传感器只是没授权。这组用例锁住「什么情况
 * 该提示、什么情况该闭嘴、提示里必须说清怎么做」。
 */
public class StepSensorPolicyTest {

    @Test
    public void healthyDeviceIsSilent() {
        assertEquals(StepSensorPolicy.OK,
                StepSensorPolicy.diagnose(true, true));
        assertFalse("没问题时不该在界面上占一行字",
                StepSensorPolicy.needsAttention(StepSensorPolicy.OK));
    }

    @Test
    public void missingPermissionIsReported() {
        String issue = StepSensorPolicy.diagnose(true, false);
        assertEquals(StepSensorPolicy.NO_PERMISSION, issue);
        assertTrue(StepSensorPolicy.needsAttention(issue));
        String hint = StepSensorPolicy.hint(issue);
        assertTrue("必须说清后果（会误弹）", hint.contains("误弹"));
        assertTrue("必须说清怎么修（授权）", hint.contains("授权"));
    }

    /** 没有硬件计步器是硬件限制，不能误导用户去「授权」——授了也没用 */
    @Test
    public void missingSensorSuggestsBehaviourNotPermission() {
        String issue = StepSensorPolicy.diagnose(false, true);
        assertEquals(StepSensorPolicy.NO_SENSOR, issue);
        String hint = StepSensorPolicy.hint(issue);
        assertTrue("应给出使用建议", hint.contains("口袋") || hint.contains("包里"));
        assertFalse("没有计步器时不能让用户去授权", hint.contains("授权"));
    }

    /** 两者都缺时优先报「没有计步器」：授权也救不了，不能给错误预期 */
    @Test
    public void noSensorWinsOverNoPermission() {
        assertEquals(StepSensorPolicy.NO_SENSOR, StepSensorPolicy.diagnose(false, false));
    }

    @Test
    public void okHintIsEmpty() {
        assertEquals("", StepSensorPolicy.hint(StepSensorPolicy.OK));
    }
}
