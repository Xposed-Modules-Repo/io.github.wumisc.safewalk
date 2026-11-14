package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * [AttitudeStabilizer] 回归测试。
 *
 * 锁的是真机日志（2026-11-14 14:17~14:35，Redmi / HyperOS 4）里的姿态翻转：
 *
 * <pre>
 * 14:29:14 姿态=TILTED
 * 14:29:17 姿态=LAY_FLAT     ← 手持看屏幕，被当成平放
 * 14:29:22 姿态=TILTED
 * 14:29:24 [walk] 静置超时，判定停下 → 收起预览
 * </pre>
 *
 * 全程 LAY_FLAT 58 次 / TILTED 71 次 / UPRIGHT 9 次。LAY_FLAT 是
 * [WalkConfirmPolicy] 的第一道硬闸，每误判一次就直接判死一次。
 *
 * 重力 9.8 时，gz=8.5 对应倾角 acos(8.5/9.8) ≈ 30°，而手持低头看屏幕
 * 恰好是 30~50° 仰角 —— 旧阈值就卡在用户最常见的握持姿势上。
 */
public class AttitudeStabilizerTest {

    private static final long T0 = 1_000_000L;

    /** 逐帧喂同一个重力向量，返回最终姿态 */
    private static PhoneAttitude hold(AttitudeStabilizer s, float gx, float gy, float gz,
                                      long startTs, int frames, long stepMs) {
        PhoneAttitude last = PhoneAttitude.UNKNOWN;
        for (int i = 0; i < frames; i++) {
            last = s.feed(gx, gy, gz, startTs + i * stepMs);
        }
        return last;
    }

    @Test
    public void firstFrameIsAdopted() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        // 首帧竖直握持：不该等 700ms 才给结论
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 9.7f, 1.2f, T0));
    }

    /**
     * 关键用例：手持 30° 仰角（gz=8.5）**不能**判成平放。
     *
     * 旧实现 `abs(gz) > 8.5` 会把 8.51 判成 LAY_FLAT，而 8.51 对应倾角 29.6°，
     * 正是「低头看导航」的标准姿势。
     */
    @Test
    public void handheldThirtyDegreesIsNotFlat() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        PhoneAttitude a = hold(s, 0f, 5.0f, 8.5f, T0, 30, 20L);
        assertEquals("30° 仰角不能被当成平放", PhoneAttitude.TILTED, a);
    }

    @Test
    public void trulyFlatBecomesFlatAfterDwell() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        // 真平放：gz=9.7
        hold(s, 0f, 0f, 9.7f, T0, 1, 20L);
        // 未满驻留期：仍是首帧结果
        assertEquals(PhoneAttitude.LAY_FLAT, s.feed(0f, 0f, 9.7f, T0 + 1));
        assertEquals("平放是首帧就成立的，驻留期只用于「切换」",
                PhoneAttitude.LAY_FLAT, s.feed(0f, 0f, 9.7f, T0 + 400));
    }

    /**
     * 驻留：竖持 → 平放，必须持续 700ms 才切换。
     *
     * 中间那一帧 `gz=9.7` 只能是「磕到桌角」，不能立刻把状态改成平放。
     */
    @Test
    public void singleFlatFrameDoesNotSwitch() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 9.7f, 1.2f, T0));
        // 一帧异常
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 0f, 9.7f, T0 + 100));
        // 回到竖持：候选被丢弃
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 9.7f, 1.2f, T0 + 200));
    }

    @Test
    public void dwellThenSwitch() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 9.7f, 1.2f, T0));
        // 持续平放，但还没满 700ms
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 0f, 9.7f, T0 + 100));
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 0f, 9.7f, T0 + 690));
        // 满 700ms：切换
        assertEquals(PhoneAttitude.LAY_FLAT, s.feed(0f, 0f, 9.7f, T0 + 800));
    }

    /**
     * 滞回：已经在平放时，轻微翘起（gz=8.3，倾角 32°）不能立刻退出。
     *
     * 没有这一段，手机平放在桌上被手指戳一下就会翻转一次，
     * 日志里 58 次 LAY_FLAT 就是这么来的。
     */
    @Test
    public void hysteresisKeepsFlatWhenSlightlyTilted() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        hold(s, 0f, 0f, 9.7f, T0, 3, 20L);
        // 翘到 8.3（介于 exit=8.0 与 enter=8.8 之间）：仍在死区，保持平放
        assertEquals(PhoneAttitude.LAY_FLAT, hold(s, 0f, 3.0f, 8.3f, T0 + 1_000L, 30, 20L));
    }

    @Test
    public void hysteresisExitsWhenClearlyTilted() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        hold(s, 0f, 0f, 9.7f, T0, 3, 20L);
        // 明显翘起 gz=7.5（倾角 40°）：过 700ms 退出平放
        assertEquals(PhoneAttitude.TILTED,
                hold(s, 0f, 6.3f, 7.5f, T0 + 1_000L, 40, 20L));
    }

    @Test
    public void lowGravityKeepsPreviousState() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 9.7f, 1.2f, T0));
        // 模长 5（自由落体 / 刚启动未收敛）：不改状态
        assertEquals(PhoneAttitude.UPRIGHT, s.feed(0f, 5.0f, 0f, T0 + 100));
    }

    @Test
    public void resetClearsState() {
        AttitudeStabilizer s = new AttitudeStabilizer();
        hold(s, 0f, 0f, 9.7f, T0, 3, 20L);
        s.reset();
        assertEquals(PhoneAttitude.UNKNOWN, s.current());
    }
}
