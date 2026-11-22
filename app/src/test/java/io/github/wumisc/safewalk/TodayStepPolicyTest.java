package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 「今日步数」纯逻辑回归（[TodayStepPolicy]）。
 *
 * 锁死的都是真机上会直接显示错数字的分支：
 *  · 硬件绝对值可用 → 今日 = 绝对值 - 当天基线（含锁屏期间走的步）；
 *  · 硬件不可用 → 退回服务内累计，不显示 0；
 *  · 设备重启导致绝对值回落 → 不得出现负数；
 *  · 跨天 → 基线重置；
 *  · 显示用的千分位格式化不得依赖 ICU。
 */
@RunWith(JUnit4.class)
public class TodayStepPolicyTest {

    // ===== 基本换算 =====

    @Test
    public void hardwareAvailable_countsFromDailyBaseline() {
        // 今天首次看到 8200 步作为基线，现在 12,345 步 → 今日 4,145
        assertEquals(4145L, TodayStepPolicy.today(12345L, 8200L, 0L));
    }

    @Test
    public void hardwareAvailable_takesMaxWithAccelCount() {
        // 硬件通道被掐过一段（只剩 200 步增量），加速度统计到 900 → 取大的
        assertEquals(900L, TodayStepPolicy.today(8400L, 8200L, 900L));
    }

    @Test
    public void noHardware_fallsBackToAccel() {
        assertEquals(377L, TodayStepPolicy.today(null, TodayStepPolicy.NO_BASE, 377L));
        assertEquals(377L, TodayStepPolicy.today(-1L, TodayStepPolicy.NO_BASE, 377L));
    }

    @Test
    public void noBase_usesAbsoluteTotalNotNegative() {
        // 第一次建立基线前就调用：today 直接用绝对值，不出现负数
        assertEquals(5000L, TodayStepPolicy.today(5000L, TodayStepPolicy.NO_BASE, 0L));
    }

    @Test
    public void deviceReboot_totalLowerThanBase_neverNegative() {
        // 昨天基线 9000，设备重启后绝对值从 0 重新计到 120
        assertEquals(120L, TodayStepPolicy.today(120L, 9000L, 0L));
        assertTrue("绝不允许出现负数", TodayStepPolicy.today(0L, 9000L, 0L) >= 0L);
    }

    @Test
    public void negativeAccel_isClampedToZero() {
        assertEquals(0L, TodayStepPolicy.today(null, TodayStepPolicy.NO_BASE, -50L));
        assertEquals(500L, TodayStepPolicy.today(8500L, 8000L, -50L));
    }

    // ===== 跨天 =====

    @Test
    public void dayKeyChange_requiresReset() {
        assertTrue(TodayStepPolicy.shouldReset("20261122", "20261123"));
        assertFalse(TodayStepPolicy.shouldReset("20261122", "20261122"));
        assertTrue("首次启动（无记录）必须重置", TodayStepPolicy.shouldReset(null, "20261122"));
        assertTrue("空串也当作无记录", TodayStepPolicy.shouldReset("", "20261122"));
    }

    @Test
    public void freshBase_usesCurrentTotalOrSentinel() {
        assertEquals(5000L, TodayStepPolicy.freshBase(5000L));
        assertEquals(TodayStepPolicy.NO_BASE, TodayStepPolicy.freshBase(null));
        assertEquals(TodayStepPolicy.NO_BASE, TodayStepPolicy.freshBase(-1L));
    }

    // ===== 格式化 =====

    @Test
    public void format_usesGroupingCommas() {
        assertEquals("0", TodayStepPolicy.format(0L));
        assertEquals("7", TodayStepPolicy.format(7L));
        assertEquals("999", TodayStepPolicy.format(999L));
        assertEquals("1,000", TodayStepPolicy.format(1000L));
        assertEquals("12,345", TodayStepPolicy.format(12345L));
        assertEquals("1,234,567", TodayStepPolicy.format(1234567L));
        assertEquals("0", TodayStepPolicy.format(-12L));
    }
}
