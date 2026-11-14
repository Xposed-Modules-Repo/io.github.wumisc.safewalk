package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [StepHealthPolicy] 回归测试。
 *
 * 锁的是 5.1.0 那个**自动跟随全程失效**的真机缺陷（2026-11-14，Redmi / HyperOS 4）：
 *
 * <pre>
 * 18 分钟内 [ACTIVE] ⏸ 达标但被拦截：硬件计步确认=拦   40+ 次
 * [gait] ⛔ 硬件计步器 10 秒 0 步                      27 次
 * [ACTIVE] ✅ 激活行走模式                              0 次
 * </pre>
 *
 * 根因不是「判得严」，是判据不成立：TYPE_STEP_COUNTER 在小米/三星上 10~60 秒才
 * 上报一次，用 10 秒窗口数步必然为 0；而 `n == 0` 分支直接 return false，
 * 再也走不到纯加速度兜底 —— 死锁。
 */
public class StepHealthPolicyTest {

    // ---------- 四档健康度 ----------

    @Test
    public void noSensorIsNone() {
        assertEquals(StepHealthPolicy.Channel.NONE,
                StepHealthPolicy.channelOf(false, false, false, Long.MAX_VALUE));
        // 有传感器标记但注册失败，仍然要落到 DENIED 而不是 NONE
        assertEquals(StepHealthPolicy.Channel.DENIED,
                StepHealthPolicy.channelOf(true, false, false, Long.MAX_VALUE));
    }

    @Test
    public void neverCallBackIsDenied() {
        assertEquals(StepHealthPolicy.Channel.DENIED,
                StepHealthPolicy.channelOf(true, true, false, Long.MAX_VALUE));
        // 出过步但没有时间戳：数据自相矛盾，安全侧按 DENIED 处理
        assertEquals(StepHealthPolicy.Channel.DENIED,
                StepHealthPolicy.channelOf(true, true, true, Long.MAX_VALUE));
    }

    @Test
    public void freshCallbackIsLive() {
        assertEquals(StepHealthPolicy.Channel.LIVE,
                StepHealthPolicy.channelOf(true, true, true, 2_000L));
        // 正好在阈值上：仍算 LIVE（用 > 而不是 >=）
        assertEquals(StepHealthPolicy.Channel.LIVE,
                StepHealthPolicy.channelOf(true, true, true, StepHealthPolicy.STALE_AFTER_MS));
    }

    /**
     * 核心用例：**「刚才还活着、现在没动静」≠「你没走路」**。
     *
     * 真机上这条判断把整段行走判死过：计步器 20~30 秒才上报一次，
     * 中间那 20 秒窗口恒为 0，全都命中「没在走」。
     */
    @Test
    public void staleCallbackIsNotWalkingEvidence() {
        assertEquals(StepHealthPolicy.Channel.STALE,
                StepHealthPolicy.channelOf(true, true, true, StepHealthPolicy.STALE_AFTER_MS + 1));
        assertEquals(StepHealthPolicy.Channel.STALE,
                StepHealthPolicy.channelOf(true, true, true, 120_000L));
    }

    @Test
    public void negativeAgeIsTreatedAsLive() {
        // 时钟回拨 / elapsedRealtime 异常：宁可多拦一次，不因异常值放行
        assertEquals(StepHealthPolicy.Channel.LIVE,
                StepHealthPolicy.channelOf(true, true, true, -5L));
    }

    // ---------- 自适应窗口 ----------

    @Test
    public void windowGrowsWithCallbackInterval() {
        // 未知间隔：用下限 10s
        assertEquals(StepHealthPolicy.MIN_WINDOW_MS, StepHealthPolicy.windowMs(0L));
        // 回调间隔 3 秒 → 窗口 6 秒 → 被抬到下限 10 秒
        assertEquals(StepHealthPolicy.MIN_WINDOW_MS, StepHealthPolicy.windowMs(3_000L));
        // 回调间隔 8 秒（COUNTER 常见）→ 窗口 16 秒，这才是能数到步的窗口
        assertEquals(16_000L, StepHealthPolicy.windowMs(8_000L));
        // 回调间隔 25 秒（省电限流）→ 窗口 50 秒
        assertEquals(50_000L, StepHealthPolicy.windowMs(25_000L));
        // 再稀疏也不超过上限，否则「刚停下」也会被容纳进来
        assertEquals(StepHealthPolicy.MAX_WINDOW_MS, StepHealthPolicy.windowMs(40_000L));
    }

    // ---------- 否决权：只有 LIVE 有 ----------

    @Test
    public void onlyLiveCanVeto() {
        assertTrue("LIVE 且窗口内 0 步 → 可以否决",
                StepHealthPolicy.canVeto(StepHealthPolicy.Channel.LIVE, 0, 2));
        assertFalse("LIVE 且步数够 → 不否决",
                StepHealthPolicy.canVeto(StepHealthPolicy.Channel.LIVE, 2, 2));

        // 其余三档一律没有否决权：必须降级到纯加速度，而不是判死功能
        assertFalse(StepHealthPolicy.canVeto(StepHealthPolicy.Channel.NONE, 0, 2));
        assertFalse(StepHealthPolicy.canVeto(StepHealthPolicy.Channel.DENIED, 0, 2));
        assertFalse("STALE 时 0 步不是「没走路」的证据",
                StepHealthPolicy.canVeto(StepHealthPolicy.Channel.STALE, 0, 2));
    }

    @Test
    public void describeCoversAllChannels() {
        for (StepHealthPolicy.Channel c : StepHealthPolicy.Channel.values()) {
            String s = StepHealthPolicy.describe(c);
            assertTrue("通道 " + c + " 说明不能为空", s != null && s.length() > 0);
        }
    }
}
