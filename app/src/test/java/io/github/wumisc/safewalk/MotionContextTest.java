package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * [MotionContext] 的判定表回归测试。
 *
 * 这组用例锁死 5.6.0 的核心修复：**波形之外必须有一条独立证据**。
 * 场景来自用户原话的三类误触与一类漏判：
 *  · 床上刷视频（静止不动）→ 必须 STILL；
 *  · 开车 / 坐车 → 必须 VEHICLE；
 *  · 兜里走路（平放但真在移动）→ 必须 WALKING，不能因为平放就判死；
 *  · 无定位 / 样本不足 → UNKNOWN，退回旧的纯波形判据，不得擅自改变行为。
 */
public class MotionContextTest {

    /** 1 度纬度 ≈ 111320 米，用来把「米」换算成经度/纬度偏移 */
    private static final double M_PER_DEG = 111_320.0;

    /** 造一个点：向北偏移 northMeters 米 */
    private static MotionContext.Fix fix(long ts, double northMeters, float speed, float acc) {
        return new MotionContext.Fix(ts, 47.2 + northMeters / M_PER_DEG, 123.6, speed, acc);
    }

    /** 原地一堆点：10 分钟、每 30 秒一个，只有 ±5m 的 GPS 漂移 */
    private static List<MotionContext.Fix> stillFor(long minutes) {
        List<MotionContext.Fix> l = new ArrayList<>();
        long n = minutes * 2;   // 每 30 秒一个
        for (long i = 0; i <= n; i++) {
            double jitter = (i % 2 == 0) ? 5.0 : -5.0;
            l.add(fix(i * 30_000L, jitter, 0f, 10f));
        }
        return l;
    }

    /** 匀速走：每 10 秒一个点，共 seconds 秒，速度 speedMps */
    private static List<MotionContext.Fix> move(long seconds, double speedMps) {
        List<MotionContext.Fix> l = new ArrayList<>();
        long n = seconds / 10;
        for (long i = 0; i <= n; i++) {
            l.add(fix(i * 10_000L, i * 10 * speedMps, (float) speedMps, 8f));
        }
        return l;
    }

    private static long endOf(List<MotionContext.Fix> l) {
        return l.get(l.size() - 1).ts;
    }

    // ---------- 床上 / 沙发：原地不动 ----------

    @Test
    public void lyingInBedTenMinutesIsStill() {
        List<MotionContext.Fix> l = stillFor(10);
        MotionContext.Verdict v = MotionContext.verdict(l, endOf(l));
        assertEquals(MotionContext.Verdict.STILL, v);
        assertFalse("静止不应支撑平放走路", MotionContext.allowsFlatWalking(v));
    }

    /** 只有在原地待够 6 分钟才算「静止」——刚坐下来不算，避免一次等红灯就误判 */
    @Test
    public void shortStopIsNotStill() {
        List<MotionContext.Fix> l = stillFor(3);
        assertEquals(MotionContext.Verdict.UNKNOWN, MotionContext.verdict(l, endOf(l)));
    }

    // ---------- 走路 ----------

    @Test
    public void normalWalkingTwoMinutesIsWalking() {
        List<MotionContext.Fix> l = move(120, 1.3);
        MotionContext.Verdict v = MotionContext.verdict(l, endOf(l));
        assertEquals(MotionContext.Verdict.WALKING, v);
        assertTrue("走路必须能支撑平放放行", MotionContext.allowsFlatWalking(v));
    }

    /** 手机在兜里平放不影响结论：判的是位置，不是姿态 */
    @Test
    public void walkingIsWalkingRegardlessOfPhoneAttitude() {
        List<MotionContext.Fix> l = move(120, 1.1);
        assertEquals(MotionContext.Verdict.WALKING, MotionContext.verdict(l, endOf(l)));
    }

    /** 只在屋里挪了 5 米：不算走路，避免「起身倒水」就弹窗 */
    @Test
    public void tinyShuffleIsNotWalking() {
        List<MotionContext.Fix> l = move(120, 0.04);   // 2 分钟 4.8 米
        assertEquals(MotionContext.Verdict.UNKNOWN, MotionContext.verdict(l, endOf(l)));
    }

    /** 骑车 4m/s：超过步行速度上限，不当作走路（避免「骑车自动弹」） */
    @Test
    public void cyclingIsNotWalking() {
        List<MotionContext.Fix> l = move(120, 4.0);
        assertEquals(MotionContext.Verdict.UNKNOWN, MotionContext.verdict(l, endOf(l)));
    }

    // ---------- 开车 / 坐车 ----------

    @Test
    public void carTripOneMinuteIsVehicle() {
        List<MotionContext.Fix> l = move(120, 15.0);   // 15m/s ≈ 54km/h
        MotionContext.Verdict v = MotionContext.verdict(l, endOf(l));
        assertEquals(MotionContext.Verdict.VEHICLE, v);
        assertTrue(MotionContext.vetoesActivation(v));
    }

    /** 瞬时速度已经到 28.8km/h：单个足够新且精度好的点就能定性 */
    @Test
    public void instantSpeedAloneFlagsVehicle() {
        List<MotionContext.Fix> l = new ArrayList<>();
        // 时间戳不用 0：0 在 MotionContext 里代表「无效时间」（Location.getTime() 未设置）
        l.add(fix(1_000L, 0.0, 12f, 8f));
        l.add(fix(31_000L, 0.0, 12f, 8f));
        assertEquals(MotionContext.Verdict.VEHICLE, MotionContext.verdict(l, 31_000L));
    }

    // ---------- 证据不足 ----------

    @Test
    public void singleFixIsUnknown() {
        List<MotionContext.Fix> l = new ArrayList<>();
        l.add(fix(0L, 0.0, 1.2f, 8f));
        assertEquals(MotionContext.Verdict.UNKNOWN, MotionContext.verdict(l, 0L));
    }

    /** 精度 300m 的网络定位点全部丢弃：宁可不判，也不能拿垃圾数据做否决 */
    @Test
    public void coarseFixesAreDiscarded() {
        List<MotionContext.Fix> l = new ArrayList<>();
        for (long i = 0; i <= 6; i++) {
            l.add(fix(i * 60_000L, i * 100.0, 20f, 300f));
        }
        assertEquals(MotionContext.Verdict.UNKNOWN, MotionContext.verdict(l, endOf(l)));
    }

    /** 过期的点（超过 12 分钟）不再参与：长时间没拿到定位时必须退回旧判据 */
    @Test
    public void staleFixesAreIgnored() {
        List<MotionContext.Fix> l = stillFor(10);
        long now = endOf(l) + 20 * 60_000L;   // 又过了 20 分钟，没有任何新点
        assertEquals(MotionContext.Verdict.UNKNOWN, MotionContext.verdict(l, now));
    }

    // ---------- Tracker 行为 ----------

    @Test
    public void trackerDedupesSameTimestamp() {
        MotionContext.Tracker t = new MotionContext.Tracker();
        MotionContext.Fix f = fix(1000L, 0.0, 0f, 8f);
        t.feed(f);
        t.feed(f);
        t.feed(fix(2000L, 0.0, 0f, 8f));
        assertEquals(2, t.size());
    }

    @Test
    public void trackerRejectsCoarseFix() {
        MotionContext.Tracker t = new MotionContext.Tracker();
        t.feed(fix(1000L, 0.0, 0f, 500f));
        assertEquals(0, t.size());
    }

    @Test
    public void distanceIsMeterAccurate() {
        // 纬度偏 0.001 度 ≈ 111.3 米
        double d = MotionContext.distanceMeters(47.2000, 123.6, 47.2010, 123.6);
        assertTrue("应约 111m，实际 " + d, d > 105 && d < 118);
    }
}
