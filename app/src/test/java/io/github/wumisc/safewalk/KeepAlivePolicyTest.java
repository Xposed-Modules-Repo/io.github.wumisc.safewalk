package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [KeepAlivePolicy] 的纯逻辑回归测试。
 *
 * 这组断言锁的是**安全边界**，不只是算法：
 * 「用户关掉开关后绝不复活」和「心跳通道不通时不会每 90 秒闪一次透明窗」
 * 两条一旦写错，用户看到的就是关不掉的服务和不停闪屏，比不保活更糟。
 */
public class KeepAlivePolicyTest {

    private static final long T = KeepAlivePolicy.HEARTBEAT_TIMEOUT_MS;
    private static final long C = KeepAlivePolicy.REVIVE_COOLDOWN_MS;
    private static final long B = KeepAlivePolicy.BLIND_COOLDOWN_MS;
    private static final long G = KeepAlivePolicy.HOOK_BOOT_GRACE_MS;

    /** 默认场景：Hook 已就绪一小时，之前从未拉起过，也从未收到心跳。 */
    private static boolean revive(boolean desired, long lastHeartbeat, long now,
                                  long lastRevive, int reviveCount) {
        return KeepAlivePolicy.shouldRevive(desired, lastHeartbeat, now, 0L, lastRevive, reviveCount);
    }

    // ---------- 复活许可：用户关了就绝不拉 ----------

    @Test
    public void desiredNeedsBothSwitches() {
        assertTrue(KeepAlivePolicy.desired(true, true));
        assertFalse(KeepAlivePolicy.desired(false, true));
        assertFalse(KeepAlivePolicy.desired(true, false));
        assertFalse(KeepAlivePolicy.desired(false, false));
    }

    @Test
    public void userTurnedOffNeverRevives() {
        long now = 3_600_000L;
        // 无论心跳多久没来、之前拉过几次、是不是开机首次，关了就必须保持关
        assertFalse(revive(false, 0L, now, 0L, 0));
        assertFalse(revive(false, now - T - 1, now, now - B - 1, 5));
        assertFalse(revive(false, 0L, now, now - 10L, 1));
    }

    // ---------- 宽限期 / 首次开机 ----------

    @Test
    public void bootGraceBlocksRevive() {
        long ready = 1_000_000L;
        assertFalse(KeepAlivePolicy.shouldRevive(
                true, 0L, ready + G - 1, ready, 0L, 0));
    }

    @Test
    public void firstLaunchAfterBootIsImmediate() {
        long ready = 1_000_000L;
        assertTrue(KeepAlivePolicy.shouldRevive(
                true, 0L, ready + G, ready, 0L, 0));
    }

    @Test
    public void firstLaunchIgnoresCooldown() {
        // reviveCount==0 时冷却为 0：哪怕刚试过一次失败，开机首拉也不该被挡住
        long now = 3_600_000L;
        assertTrue(revive(true, 0L, now, now - 1L, 0));
    }

    // ---------- 心跳新鲜度 ----------

    @Test
    public void staleNeverReceivedHeartbeat() {
        assertTrue(KeepAlivePolicy.isStale(0L, 3_600_000L));
    }

    @Test
    public void freshHeartbeatDoesNotRevive() {
        long now = 3_600_000L;
        assertFalse(revive(true, now - KeepAlivePolicy.HEARTBEAT_INTERVAL_MS, now, now - C - 1, 3));
    }

    @Test
    public void timeoutBoundaryIsInclusive() {
        long now = 3_600_000L;
        assertFalse("差 1ms 不算超时", KeepAlivePolicy.isStale(now - T + 1, now));
        assertTrue("恰好 90s 必须判死", KeepAlivePolicy.isStale(now - T, now));
        assertTrue(revive(true, now - T, now, now - C, 3));
    }

    @Test
    public void justBeforeTimeoutDoesNotRevive() {
        long now = 3_600_000L;
        assertFalse(revive(true, now - T + 1, now, now - C - 1, 3));
    }

    @Test
    public void clockRollbackNeverCausesFalseRevive() {
        long now = 3_600_000L;
        assertFalse("单调时钟异常时宁可不复活", KeepAlivePolicy.isStale(now + 5_000L, now));
        assertFalse(revive(true, now + 5_000L, now, now - C - 1, 3));
    }

    // ---------- 冷却：正常通道 ----------

    @Test
    public void cooldownBlocksRepeatRevive() {
        long now = 3_600_000L;
        // 曾收到过心跳（通道正常），刚拉起 30s：即使心跳已超时也不许连拉
        assertFalse(revive(true, now - T - 1, now, now - C / 2, 3));
    }

    @Test
    public void cooldownExpiresAllowsRevive() {
        long now = 3_600_000L;
        assertTrue(revive(true, now - T - 1, now, now - C, 3));
    }

    // ---------- 盲区保护：心跳通道完全不通 ----------

    @Test
    public void blindChannelUsesLongerCooldown() {
        long now = 3_600_000L;
        // 从未收到心跳：90s 时按正常冷却本该放行，但盲区要求满 3 分钟
        assertFalse(revive(true, 0L, now, now - T, 1));
        assertFalse(revive(true, 0L, now, now - B + 1, 1));
        assertTrue(revive(true, 0L, now, now - B, 1));
    }

    @Test
    public void constantsAreConsistent() {
        // 超时必须容得下 3 个心跳周期（容忍连续丢 2 条广播）
        assertEquals(3 * KeepAlivePolicy.HEARTBEAT_INTERVAL_MS, T);
        // 复活后给服务的时间要短于判死时间，否则服务刚起来又被判死
        assertTrue("正常冷却必须短于心跳超时", C < T);
        // 盲区冷却必须明显更长，才压得住「通道不通时的每 90s 闪屏」
        assertTrue("盲区冷却必须长于心跳超时", B > T);
        assertTrue("开机宽限必须远短于心跳超时", G < T);
        assertTrue("恢复通知节流必须长于复活冷却",
                KeepAlivePolicy.REVIVE_NOTICE_MIN_INTERVAL_MS > C);
    }

    // ---------- 「已自动恢复」通知节流 ----------

    @Test
    public void reviveNoticeThrottled() {
        long now = 3_600_000L;
        assertTrue("首条必须放行", KeepAlivePolicy.shouldNotifyRevive(0L, now));
        assertFalse("1 分钟内不重复",
                KeepAlivePolicy.shouldNotifyRevive(now - 60_000L, now));
        assertTrue("满 10 分钟可再提示",
                KeepAlivePolicy.shouldNotifyRevive(
                        now - KeepAlivePolicy.REVIVE_NOTICE_MIN_INTERVAL_MS, now));
        assertTrue("时钟回拨要重置节流，不能变成永不提示",
                KeepAlivePolicy.shouldNotifyRevive(now + 5_000L, now));
    }
}
