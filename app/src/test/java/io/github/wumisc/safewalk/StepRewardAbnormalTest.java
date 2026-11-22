package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 「今日步数」异常输入回归（5.6.8）。
 *
 * <p>正常路径在 {@link TodayStepPolicyTest} / {@link StepRewardRangeTest} 里已经锁住，
 * 这一组专挑**现实里会出现、但一想就觉得不该发生**的脏数据：
 * 基线与硬件值互相矛盾、传感器报负数、时钟被改到下一秒、步数溢到 Long 上限、
 * 跨年跨月、连续重启。任何一条处理错，用户看到的就是「步数跳成天文数字」
 * 或者「走了半天一直是 0」，而这两种反馈最难复现。
 */
@RunWith(JUnit4.class)
public class StepRewardAbnormalTest {

    @Test
    public void baseGreaterThanHardware_dirtyState_fallsBackToLarger() {
        // SP 里残留了昨天的基线（比今天的硬件值还大），直接相减会得到负数
        long today = TodayStepPolicy.today(5_000L, 80_000L, 1_200L);
        assertEquals("基线失效时取 max(硬件当前值, 累计)", 5_000L, today);
        assertTrue("不能出现负数", today >= 0L);
        // 累计比硬件当前值更大时，取累计
        assertEquals(7_000L, TodayStepPolicy.today(5_000L, 80_000L, 7_000L));
    }

    @Test
    public void negativeHardwareValue_treatedAsUnavailable() {
        assertEquals("硬件值为负 → 退回累计",
                4_321L, TodayStepPolicy.today(-17L, 0L, 4_321L));
        assertEquals("累计也为 0 时显示 0",
                0L, TodayStepPolicy.today(-1L, 0L, 0L));
        assertEquals("null 与负数同义",
                4_321L, TodayStepPolicy.today(null, 0L, 4_321L));
    }

    @Test
    public void negativeAccel_isClampedToZero() {
        assertEquals("负累计按 0 处理，不参与取大",
                8_000L, TodayStepPolicy.today(8_000L, 0L, -500L));
        assertEquals("硬件不可用时负累计不得变成负数", 0L,
                TodayStepPolicy.today(null, 0L, -500L));
    }

    @Test
    public void longMaxSteps_doesNotOverflowAndFormatsEveryGroup() {
        long max = Long.MAX_VALUE;
        long today = TodayStepPolicy.today(max, 0L, 0L);
        assertEquals("取最大不回绕", max, today);
        assertEquals("Long.MAX_VALUE 的千分位分组",
                "9,223,372,036,854,775,807", TodayStepPolicy.format(today));
        assertEquals("上限步数必是淡蓝档",
                StepBorderPolicy.TIER_LIGHT_BLUE, StepBorderPolicy.tier(today));
        assertTrue("上限步数挂旗", StepBorderPolicy.showFlag(today));
    }

    @Test
    public void yearBoundary_dayKeyResets() {
        assertTrue("跨年必须重置", TodayStepPolicy.shouldReset("20261231", "20270101"));
        assertTrue("跨月必须重置", TodayStepPolicy.shouldReset("20260131", "20260201"));
        assertFalse("同一天不得重置", TodayStepPolicy.shouldReset("20261122", "20261122"));
        assertTrue("空 dayKey（首次启动 / SP 被清）必须重置",
                TodayStepPolicy.shouldReset("", "20261122"));
        assertTrue("null dayKey 必须重置", TodayStepPolicy.shouldReset(null, "20261122"));
    }

    @Test
    public void consecutiveReboots_eachEstablishNewBase() {
        // 第一次重启：绝对值 900 → 基线 900
        long base1 = TodayStepPolicy.freshBase(900L);
        assertEquals(900L, base1);
        assertEquals("刚重启当天 0 步", 0L, TodayStepPolicy.today(900L, base1, 0L));
        // 走 100 步
        assertEquals(100L, TodayStepPolicy.today(1_000L, base1, 0L));
        // 第二次重启：绝对值归 40，按新值重设基线
        long base2 = TodayStepPolicy.freshBase(40L);
        assertEquals(40L, base2);
        // 旧基线（900）已失效：策略按「基线作废」处理，直接用当前绝对值起算（40），
        // 而不是相减出负数，也不是显示 0 —— 这正是 TodayStepPolicy 的重启语义。
        assertEquals("二次重启后不得沿用旧基线（负数/0），按当前值起算",
                40L, TodayStepPolicy.today(40L, base1, 0L));
        assertEquals("新基线生效后从 0 起算", 7L, TodayStepPolicy.today(47L, base2, 0L));
    }

    @Test
    public void hardwareUnavailable_freshBaseStaysSentinel() {
        assertEquals("硬件不可用时基线保持 -1",
                TodayStepPolicy.NO_BASE, TodayStepPolicy.freshBase(null));
        assertEquals("负值同样不建立基线",
                TodayStepPolicy.NO_BASE, TodayStepPolicy.freshBase(-9L));
        // 此时 today 只能用累计
        assertEquals(2_500L,
                TodayStepPolicy.today(null, TodayStepPolicy.NO_BASE, 2_500L));
    }

    @Test
    public void format_smallAndNegativeInputs() {
        assertEquals("0", TodayStepPolicy.format(0L));
        assertEquals("999", TodayStepPolicy.format(999L));
        assertEquals("1,000", TodayStepPolicy.format(1_000L));
        assertEquals("10,000", TodayStepPolicy.format(10_000L));
        assertEquals("负数显示 0", "0", TodayStepPolicy.format(-1L));
    }

    @Test
    public void rewardOff_stillWhiteEvenAtLongMax() {
        assertEquals("关掉奖励后连上限步数也必须白描边",
                StepBorderPolicy.COLOR_DEFAULT,
                StepBorderPolicy.borderColor(Long.MAX_VALUE, false));
        assertFalse("关掉奖励后上限步数也不挂旗",
                StepBorderPolicy.showFlag(Long.MAX_VALUE, false));
    }
}
