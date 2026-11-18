package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 「省电计划」自适应轮询节拍回归测试。
 *
 * 核心保证：放慢轮询绝不牺牲走路/预览的响应速度；只在不做事的静置态省电。
 */
public class PowerPolicyTest {

    @Test
    public void masterOff_usesIdleOffInterval() {
        // 总开关关：无论 walking 残留什么状态，都用最慢档
        assertEquals(PowerPolicy.IDLE_OFF_MS, PowerPolicy.tickIntervalMs(false, false, false));
        assertEquals(PowerPolicy.IDLE_OFF_MS, PowerPolicy.tickIntervalMs(false, true, false));
        assertEquals(PowerPolicy.IDLE_OFF_MS, PowerPolicy.tickIntervalMs(false, false, true));
    }

    @Test
    public void idleMasterOn_usesSlowInterval() {
        // 开着但没走、没预览：无事可做，放慢（这就是省电的来源）
        assertEquals(PowerPolicy.IDLE_ON_MS, PowerPolicy.tickIntervalMs(true, false, false));
    }

    @Test
    public void walking_usesFastInterval() {
        // 正在走：维持 2 秒快节拍，提醒判定不延迟
        assertEquals(PowerPolicy.ACTIVE_MS, PowerPolicy.tickIntervalMs(true, true, false));
    }

    @Test
    public void previewActive_usesFastInterval() {
        // 预览显示中：维持 2 秒快节拍，显隐指令不延迟
        assertEquals(PowerPolicy.ACTIVE_MS, PowerPolicy.tickIntervalMs(true, false, true));
        // 走路 + 预览同时发生，自然是快节拍
        assertEquals(PowerPolicy.ACTIVE_MS, PowerPolicy.tickIntervalMs(true, true, true));
    }

    @Test
    public void fastIsFasterThanIdle() {
        // 契约：快档必须真的比静置档快，否则「省电计划」名存实亡
        org.junit.Assert.assertTrue(PowerPolicy.ACTIVE_MS < PowerPolicy.IDLE_ON_MS);
    }
}
