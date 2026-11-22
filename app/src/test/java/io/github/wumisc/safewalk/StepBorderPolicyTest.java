package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 「步数奖励描边」纯逻辑回归（[StepBorderPolicy]）。
 *
 * 锁死的都是用户会当场看出来「颜色不对」的边界：
 *  · 2999 不变色、3000 变橙（门槛是「达到」，不是「超过」）；
 *  · 7999 还是橙、8000 淡红；
 *  · 19999 淡红、20000 国旗红并挂旗；
 *  · 29999 国旗红、30000 淡蓝 —— 高档位优先判断，两档不会同时命中；
 *  · 所有档位透明度统一 0xCC，只换色相不换明度；
 *  · 小红旗从 2 万一直挂到 3 万及以后。
 */
@RunWith(JUnit4.class)
public class StepBorderPolicyTest {

    private static final int ALPHA = 0xCC000000;

    @Test
    public void belowFirstThreshold_staysDefault() {
        int plain = StepBorderPolicy.COLOR_DEFAULT;
        assertEquals(StepBorderPolicy.TIER_NONE, StepBorderPolicy.tier(0L));
        assertEquals(StepBorderPolicy.TIER_NONE, StepBorderPolicy.tier(2_999L));
        assertEquals(plain, StepBorderPolicy.borderColor(2_999L));
        // 负数按 0 处理，不判奖励
        assertEquals(StepBorderPolicy.TIER_NONE, StepBorderPolicy.tier(-5L));
        assertEquals(plain, StepBorderPolicy.borderColor(-5L));
    }

    @Test
    public void threeThousand_turnsOrange() {
        assertEquals(StepBorderPolicy.TIER_ORANGE, StepBorderPolicy.tier(3_000L));
        assertEquals(StepBorderPolicy.COLOR_ORANGE, StepBorderPolicy.borderColor(3_000L));
        // 7999 仍是橙，不能提前跳到淡红
        assertEquals(StepBorderPolicy.TIER_ORANGE, StepBorderPolicy.tier(7_999L));
        assertEquals(StepBorderPolicy.COLOR_ORANGE, StepBorderPolicy.borderColor(7_999L));
    }

    @Test
    public void eightThousand_turnsLightRed() {
        assertEquals(StepBorderPolicy.TIER_LIGHT_RED, StepBorderPolicy.tier(8_000L));
        assertEquals(StepBorderPolicy.COLOR_LIGHT_RED, StepBorderPolicy.borderColor(8_000L));
        assertEquals(StepBorderPolicy.TIER_LIGHT_RED, StepBorderPolicy.tier(19_999L));
        assertEquals(StepBorderPolicy.COLOR_LIGHT_RED, StepBorderPolicy.borderColor(19_999L));
    }

    @Test
    public void twentyThousand_turnsFlagRedWithFlag() {
        assertEquals(StepBorderPolicy.TIER_FLAG_RED, StepBorderPolicy.tier(20_000L));
        assertEquals(StepBorderPolicy.COLOR_FLAG_RED, StepBorderPolicy.borderColor(20_000L));
        assertTrue(StepBorderPolicy.showFlag(20_000L));
        // 刚过门槛之前不挂旗
        assertFalse(StepBorderPolicy.showFlag(19_999L));
    }

    @Test
    public void thirtyThousand_turnsLightBlue_andFlagStays() {
        assertEquals(StepBorderPolicy.TIER_LIGHT_BLUE, StepBorderPolicy.tier(30_000L));
        assertEquals(StepBorderPolicy.COLOR_LIGHT_BLUE, StepBorderPolicy.borderColor(30_000L));
        // 2 万起一直挂旗，3 万档不收回
        assertTrue(StepBorderPolicy.showFlag(30_000L));
        assertTrue(StepBorderPolicy.showFlag(50_000L));
    }

    @Test
    public void allTiersShareSameAlpha() {
        long[] samples = {0L, 3_000L, 8_000L, 20_000L, 30_000L};
        for (long s : samples) {
            int color = StepBorderPolicy.borderColor(s);
            assertEquals("alpha 必须统一 0xCC：" + s, ALPHA, color & 0xFF000000);
        }
    }

    @Test
    public void adjacentTiersHaveDifferentColors() {
        assertNotEquals(StepBorderPolicy.COLOR_DEFAULT,
                StepBorderPolicy.borderColor(3_000L));
        assertNotEquals(StepBorderPolicy.COLOR_ORANGE,
                StepBorderPolicy.borderColor(8_000L));
        assertNotEquals(StepBorderPolicy.COLOR_LIGHT_RED,
                StepBorderPolicy.borderColor(20_000L));
        assertNotEquals(StepBorderPolicy.COLOR_FLAG_RED,
                StepBorderPolicy.borderColor(30_000L));
    }

    @Test
    public void flagRedMatchesNationalFlagStandard() {
        // GB 12982 标准红 #EE1C25（去掉 0xCC 透明度）
        assertEquals(0xEE1C25, StepBorderPolicy.COLOR_FLAG_RED & 0x00FFFFFF);
    }

    @Test
    public void switchOff_forcesDefaultWhiteAndNoFlag() {
        // 用户在「小窗自定义」里关掉步数奖励后，任何步数都必须是白描边、不挂旗
        assertEquals(StepBorderPolicy.COLOR_DEFAULT, StepBorderPolicy.borderColor(3_000L, false));
        assertEquals(StepBorderPolicy.COLOR_DEFAULT, StepBorderPolicy.borderColor(8_000L, false));
        assertEquals(StepBorderPolicy.COLOR_DEFAULT, StepBorderPolicy.borderColor(20_000L, false));
        assertEquals(StepBorderPolicy.COLOR_DEFAULT, StepBorderPolicy.borderColor(30_000L, false));
        assertFalse(StepBorderPolicy.showFlag(20_000L, false));
        assertFalse(StepBorderPolicy.showFlag(30_000L, false));
        // 开关打开时必须与不带参数的旧行为完全一致，避免两套口径分叉
        for (long steps : new long[]{0L, 3_000L, 8_000L, 20_000L, 30_000L}) {
            assertEquals(StepBorderPolicy.borderColor(steps),
                    StepBorderPolicy.borderColor(steps, true));
            assertEquals(StepBorderPolicy.showFlag(steps),
                    StepBorderPolicy.showFlag(steps, true));
        }
    }

    @Test
    public void tierNameIsNeverEmpty() {
        int[] tiers = {StepBorderPolicy.TIER_NONE, StepBorderPolicy.TIER_ORANGE,
                StepBorderPolicy.TIER_LIGHT_RED, StepBorderPolicy.TIER_FLAG_RED,
                StepBorderPolicy.TIER_LIGHT_BLUE};
        for (int t : tiers) {
            assertFalse(StepBorderPolicy.tierName(t).isEmpty());
        }
    }
}
