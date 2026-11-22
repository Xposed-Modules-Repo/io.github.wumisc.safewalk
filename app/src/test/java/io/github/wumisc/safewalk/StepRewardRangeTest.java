package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 「今日步数 + 步数奖励」靶场（场景模拟）测试。
 *
 * <p>这不是单点断言，而是把真机上会遇到的一整天按时间轴跑一遍：
 * 起步 → 跨 3000 / 8000 / 20000 / 30000 四道门槛 → 静止 → 跨天 → 重启 → 硬件通道掉线
 * → 用户关掉奖励开关。每一步都同时问「今日步数是多少」和「描边该是什么颜色 / 有没有旗」，
 * 锁住两条策略在长时间轴上的组合行为 —— 单测各管一段时很容易在交界处漏掉问题。
 *
 * <p>纯 JVM，无 Android 依赖；时间推进用「模拟步频」折算，不真的睡觉。
 */
@RunWith(JUnit4.class)
public class StepRewardRangeTest {

    /** 模拟步频：每步 1.5 秒（正常步行 1.0~2.0 步/秒） */
    private static final long STEP_INTERVAL_MS = 1_500L;

    @Test
    public void fullDayTimeline_tiersSwitchExactlyOnceAtThresholds() {
        // 一天走 30,500 步：从 0 起步，硬件绝对值同步增长（等价于开机后第一次建立基线）
        long base = 100_000L;          // 当天首次见到的开机累计值
        long hw = base;                // 硬件绝对值
        long accel = 0L;               // 服务内累计（与硬件同源时会是 0，这里按 DETECTOR 也加）
        long day = 0L;                 // 今日步数

        int lastTier = StepBorderPolicy.TIER_NONE;
        long orangeAt = -1L, lightRedAt = -1L, flagRedAt = -1L, lightBlueAt = -1L;

        for (long i = 1; i <= 30_500L; i++) {
            hw += 1L;
            accel += 1L;
            day = TodayStepPolicy.today(hw, base, accel);
            assertEquals("模拟推进后今日步数必须等于已走步数", i, day);

            int tier = StepBorderPolicy.tier(day);
            if (tier != lastTier) {
                // 档位只能往上跳一级：绝不能从橙直接跳淡蓝，也不能回落
                assertTrue("档位只升不降且不跳级：" + lastTier + "→" + tier,
                        tier == lastTier + 1);
                if (tier == StepBorderPolicy.TIER_ORANGE) orangeAt = day;
                if (tier == StepBorderPolicy.TIER_LIGHT_RED) lightRedAt = day;
                if (tier == StepBorderPolicy.TIER_FLAG_RED) flagRedAt = day;
                if (tier == StepBorderPolicy.TIER_LIGHT_BLUE) lightBlueAt = day;
                lastTier = tier;
            }
        }

        assertEquals("3000 步整点变橙", 3_000L, orangeAt);
        assertEquals("8000 步整点变淡红", 8_000L, lightRedAt);
        assertEquals("20000 步整点变国旗红", 20_000L, flagRedAt);
        assertEquals("30000 步整点变淡蓝", 30_000L, lightBlueAt);

        // 一天结束时的终态
        assertEquals(StepBorderPolicy.TIER_LIGHT_BLUE, StepBorderPolicy.tier(day));
        assertTrue("3 万步以上仍挂小红旗", StepBorderPolicy.showFlag(day));
        assertEquals("终态颜色必须是淡蓝",
                StepBorderPolicy.COLOR_LIGHT_BLUE, StepBorderPolicy.borderColor(day));
        // 时间轴总长：30500 步 × 1.5s ≈ 12.7 小时，是「一天走 3 万步」的合理量级
        assertTrue("30,500 步的模拟时长应落在 12 小时附近",
                Math.abs(30_500L * STEP_INTERVAL_MS - 12.7 * 3600_000L) < 600_000L);
    }

    @Test
    public void midnight_resetsCountAndColor() {
        // 昨天走到 21,000 步（国旗红 + 旗）
        long yesterday = TodayStepPolicy.today(200_000L, 179_000L, 0L);
        assertEquals(21_000L, yesterday);
        assertEquals(StepBorderPolicy.COLOR_FLAG_RED, StepBorderPolicy.borderColor(yesterday));

        // 跨天：dayKey 变了，基线与加速度清零
        assertTrue(TodayStepPolicy.shouldReset("20261122", "20261123"));
        long base = TodayStepPolicy.freshBase(200_050L);   // 0 点后的硬件绝对值
        long today = TodayStepPolicy.today(200_050L, base, 0L);
        assertEquals("新的一天从 0 起算", 0L, today);
        assertEquals("新的一天回到白描边",
                StepBorderPolicy.COLOR_DEFAULT, StepBorderPolicy.borderColor(today));
        assertFalse("0 步不挂旗", StepBorderPolicy.showFlag(today));
    }

    @Test
    public void reboot_hwTotalDrops_neverShowsNegativeOrZero() {
        // 重启前：今日 12,000 步（淡红）
        long base = 88_000L;
        long beforeReboot = TodayStepPolicy.today(100_000L, base, 0L);
        assertEquals(12_000L, beforeReboot);
        assertEquals(StepBorderPolicy.COLOR_LIGHT_RED, StepBorderPolicy.borderColor(beforeReboot));

        // 重启：硬件绝对值从 100,000 掉到 350，基线失效
        long rebooted = TodayStepPolicy.today(350L, base, 0L);
        assertEquals("按当前绝对值重新起算", 350L, rebooted);
        assertTrue("绝不能出现 0 步或负数", rebooted > 0L);
        assertEquals("350 步仍是白描边",
                StepBorderPolicy.COLOR_DEFAULT, StepBorderPolicy.borderColor(rebooted));

        // 重启后基线重新落地，继续走到 3,100 步 → 橙
        long newBase = TodayStepPolicy.freshBase(350L);
        long afterWalk = TodayStepPolicy.today(3_100L, newBase, 0L);
        assertEquals(2_750L, afterWalk);
        assertEquals(StepBorderPolicy.TIER_NONE, StepBorderPolicy.tier(afterWalk));
        assertEquals("重启后的总计不等于全天总量，必须按新基线算",
                2_750L, TodayStepPolicy.today(3_100L, newBase, 0L));
    }

    @Test
    public void hwChannelGoesStale_fallsBackToAccelAndKeepsColor() {
        // 硬件通道掉线（无 ACTIVITY_RECOGNITION）：hwTotal 传 null，只能靠加速度累计
        long accel = 0L;
        for (int i = 0; i < 9_000; i++) {
            accel += 1L;
        }
        long today = TodayStepPolicy.today(null, TodayStepPolicy.NO_BASE, accel);
        assertEquals("硬件不可用时用服务内累计", 9_000L, today);
        assertEquals("9,000 步 = 淡红", StepBorderPolicy.COLOR_LIGHT_RED,
                StepBorderPolicy.borderColor(today));

        // 同一时刻硬件又回来了，且绝对值远大于累计：取大，不许倒退
        long hw = 120_000L;
        long base = 100_000L;
        long merged = TodayStepPolicy.today(hw, base, accel);
        assertEquals("硬件恢复后取两者较大值", 20_000L, merged);
        assertEquals(StepBorderPolicy.COLOR_FLAG_RED, StepBorderPolicy.borderColor(merged));
        assertTrue(StepBorderPolicy.showFlag(merged));
    }

    @Test
    public void rewardSwitchOff_wholeDayStaysWhite() {
        // 关掉奖励开关后，从 0 到 30,500 步全时间轴都必须是白描边、无旗
        for (long steps = 0L; steps <= 30_500L; steps += 250L) {
            assertEquals("关掉奖励：第 " + steps + " 步仍须白描边",
                    StepBorderPolicy.COLOR_DEFAULT,
                    StepBorderPolicy.borderColor(steps, false));
            assertFalse("关掉奖励：任何步数都不挂旗",
                    StepBorderPolicy.showFlag(steps, false));
        }
    }

    @Test
    public void rewardSwitchOn_matchesRewardTimelineEveryStep() {
        // 开着时，带开关的重载与旧接口必须逐值一致（防止两条口径分叉）
        for (long steps = 0L; steps <= 30_500L; steps += 250L) {
            assertEquals(StepBorderPolicy.borderColor(steps),
                    StepBorderPolicy.borderColor(steps, true));
            assertEquals(StepBorderPolicy.showFlag(steps),
                    StepBorderPolicy.showFlag(steps, true));
        }
    }
}
