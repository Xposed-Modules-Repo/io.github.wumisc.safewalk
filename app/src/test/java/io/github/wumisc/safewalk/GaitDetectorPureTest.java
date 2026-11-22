package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 纯 JVM 验证（不依赖 Robolectric / 网络下载）：
 * 锁死「自动跟随步行触发」这条关键路径 —— feedAcc 在 confirmSteps 下必须返回 true。
 *
 * 之前两个真实 bug 让预览永远不弹出：
 *  ① peakIntervals 上限只有 3，confirmSteps=5 时 size 永远到不了 5；
 *  ② 步频下限 0.9Hz 太严 + 断步清空太激进，慢速散步被当成断步每次清空。
 */
@RunWith(JUnit4.class)
public class GaitDetectorPureTest {

    private static final long FRAME_MS = 20L;   // 50Hz
    private static final float GRAVITY = 9.8f;  // 重力（竖直握持落在 X 轴）

    @Test
    public void slowWalk_confirmSteps5_feedAccTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 600; i++) { // 50Hz × 12s，足够散步攒满 5 步
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 0.9 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("0.9Hz 散步 + confirmSteps=5：feedAcc 必须在 12s 内返回 true，"
                + "否则预览永远不弹出（debug=" + g.debugInfo() + "）", triggered);
    }

    @Test
    public void normalWalk_confirmSteps2_feedAccTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 300; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("1.5Hz + confirmSteps=2：feedAcc 必须触发（debug=" + g.debugInfo() + "）", triggered);
    }

    @Test
    public void legShake_confirmSteps5_feedAccNeverTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 400; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 3.0 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertFalse("3Hz 抖腿 + confirmSteps=5：feedAcc 绝不能返回 true（防误触）", triggered);
    }

    @Test
    public void sittingStill_feedAccNeverTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 500; i++) {
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertFalse("静止不动绝不能触发", triggered);
    }

    /** 拿/放/翻转手机的大瞬态（幅值远超步行峰值）：不计步、不得触发 */
    @Test
    public void bigTransient_doesNotCountAsStep() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();
        long t = settleFeed(g, 0L);
        boolean triggered = false;
        int confirmedBefore = 0;
        // 喂 20 个 11~13 m/s² 的大瞬态尖峰（模拟翻手机/拍打）
        for (int i = 0; i < 20; i++) {
            confirmedBefore = g.confirmedStepCount();
            float ax = (i % 2 == 0) ? GRAVITY + 12.0f : GRAVITY + 11.0f;
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
            if (g.confirmedStepCount() > confirmedBefore) break;
        }
        assertFalse("大瞬态（>8 m/s²）绝不能算成步或触发预览（debug=" + g.debugInfo() + "）", triggered);
    }

    /** 大瞬态之后紧接着正常步行：必须仍能触发（验证瞬态不压制后续判定） */
    @Test
    public void transientThenNormalWalk_stillTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = settleFeed(g, 0L);
        // 先来几下大瞬态（翻手机）
        for (int i = 0; i < 10; i++) {
            g.feedAcc(GRAVITY + 12.0f, 0f, 0f, t);
            t += FRAME_MS * 5;
        }
        // 然后正常散步 1.0Hz
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.0 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("瞬态之后正常散步（confirmSteps=5）必须仍能触发，"
                + "说明过滤没有误伤正常步行（debug=" + g.debugInfo() + "）", triggered);
    }

    /**
     * 【5.6.4 垂直轴闸门】重力在 X 轴（手机竖持），晃动发生在水平面（Z 轴）：
     * 幅值与 1.2Hz 间隔都落在「像步」的区间里，但能量不沿重力方向，
     * 必须一个合格步都不产生 —— 这就是「手机在手里晃两下就弹预览」的物理分界。
     */
    @Test
    public void horizontalSway_noQualifiedStep() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = settleFeed(g, 0L);
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float sway = (float) (2.5 * Math.sin(2 * Math.PI * 1.2 * sec));
            if (g.feedAcc(GRAVITY, 0f, sway, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertFalse("水平往复（1.2Hz / 2.5m/s²，能量不沿重力）不得产生合格步；垂直占比="
                + g.verticalRatio(), triggered);
        assertEquals("合格步队列必须保持空（实际=" + g.confirmedStepCount() + "）",
                0, g.confirmedStepCount());
    }

    /**
     * 【5.6.4 高抬/翻转手机】绕水平轴大幅翻转（抬起来拍照找角度）：
     * 大瞬态本就被 MAX_PEAK_VALUE 拦，叠加垂直占比闸门后更不得攒步。
     */
    @Test
    public void raiseAndFlipPhone_noQualifiedStep() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = settleFeed(g, 0L);
        boolean triggered = false;
        for (int i = 0; i < 300; i++) {
            double sec = i / 50.0;
            // 0.8Hz 的绕 Y 轴翻转：X/Z 两轴大幅交替（4m/s²），既不沿重力也不超瞬态上限
            float flip = (float) (4.0 * Math.sin(2 * Math.PI * 0.8 * sec));
            if (g.feedAcc(GRAVITY * 0.6f + flip, 0f, GRAVITY * 0.8f - flip, t)) {
                triggered = true; break;
            }
            t += FRAME_MS;
        }
        assertFalse("举手机/翻转找角度不得算成步；垂直占比=" + g.verticalRatio()
                + " debug=" + g.debugInfo(), triggered);
    }

    private static long settleFeed(GaitDetector g, long t) {
        for (int i = 0; i < 100; i++) {
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        return t;
    }

    /** 手里拿手机乱动：晃动方向随机、主轴来回切换，绝不能触发 */
    @Test
    public void handShake_switchingAxis_notTrigger() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(3);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float shake = (float) (2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            // 主轴在 X/Y 之间来回切换：模拟乱动方向随机
            if ((i / 60) % 2 == 0) {
                if (g.feedAcc(GRAVITY + shake, 0f, 0f, t)) { triggered = true; break; }
            } else {
                if (g.feedAcc(0f, GRAVITY + shake, 0f, t)) { triggered = true; break; }
            }
            t += FRAME_MS;
        }
        assertFalse("主轴来回切换（手机在手里乱动）绝不能触发，否则玩手机又弹预览（debug="
                + g.debugInfo() + "）", triggered);
    }

    /** 走路时主轴固定：必须照常触发（确认门槛不误伤功能） */
    @Test
    public void stableAxisWalking_stillTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = settleFeed(g, 0L);
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.1 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("主轴固定的正常走路（confirmSteps=5）必须仍能触发（debug="
                + g.debugInfo() + "）", triggered);
    }

    // ======================================================================
    // 返工第 1 轮 · B 组：stepActivityTs() 时序回归 —— Tess 纯追加，不改上方 8 条
    //
    // 背景（审查 §3.2 BLOCKER）：上一轮 408 用例全绿却漏掉「真停下窗口永远收不起来」——
    // 服务层伪增量循环每帧把判停时钟刷成 now，effectiveTs≈now → shouldStop 恒 false。
    // 根因是没有任何用例覆盖「传感器时间戳在静止时会不会冻结」这条时序链。
    //
    // Max 返工：判停时钟 = GaitDetector.stepActivityTs()，只读，仅在
    // peakIntervals.add(since)（步频窗口内合格步峰，GaitDetector.kt 约 :317）推进，
    // 且推进点位于 confirmSteps 门槛判断之前；静止/无峰/窗口外抖动一律冻结；reset() 归零。
    //
    // 量纲：全部毫秒。feedAcc 第 4 参 now 是虚拟毫秒时间戳，与 shouldStop 的
    // now / sessionStartTs / stopMs / graceMs 共用同一条时间轴（TS_T0 起算），绝不把秒当毫秒。
    // 不新增 import（复用文件已有的 assertTrue/assertFalse），保持既有 8 条逐字未动。
    // ======================================================================

    /** 虚拟时间轴原点（毫秒）：会话起点，供 feedAcc 的 now 与 shouldStop 共用同一时间轴。 */
    private static final long TS_T0 = 100_000L;
    /** 真机静置门槛 12s（毫秒）。 */
    private static final long TS_STOP_MS = 12_000L;
    /** 自定义小宽限：让组合判停落到「now-effectiveTs>stopMs」出口，而非宽限闸门。 */
    private static final long TS_GRACE_MS = 5_000L;
    /** 规律步行 600 帧 × 20ms = 12_000ms。 */
    private static final int TS_WALK_FRAMES = 600;
    /** 静止 1100 帧 × 20ms = 22_000ms（>= 20s）。 */
    private static final int TS_STILL_FRAMES = 1_100;

    /** 喂正弦步行波形（重力落 X 轴，与既有 slowWalk 用例同构），返回末帧之后的 now。 */
    private static long feedWalkSine(GaitDetector g, long t0, int frames, double freqHz, float amp) {
        return feedWalkSineFrom(g, t0, 0, frames, freqHz, amp);
    }

    /** 喂正弦步行波形，相位按全局帧号 jFrom 连续（用于「走路中途抬门槛」场景）。 */
    private static long feedWalkSineFrom(GaitDetector g, long t0, int jFrom, int frames,
                                        double freqHz, float amp) {
        long t = t0;
        for (int i = 0; i < frames; i++) {
            double sec = (jFrom + i) / 50.0;
            float ax = (float) (GRAVITY + amp * Math.sin(2 * Math.PI * freqHz * sec));
            g.feedAcc(ax, 0f, 0f, t);
            t += FRAME_MS;
        }
        return t;
    }

    /** 喂纯重力无峰帧（静止），返回末帧之后的 now。 */
    private static long feedStillGravity(GaitDetector g, long t0, int frames) {
        long t = t0;
        for (int i = 0; i < frames; i++) {
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        return t;
    }

    /** 平放姿态（重力落 Z 轴）+ X 轴细碎抖动，返回末帧之后的 now。 */
    private static long feedFlatJitter(GaitDetector g, long t0, int frames, double freqHz, float amp) {
        long t = t0;
        for (int i = 0; i < frames; i++) {
            double sec = i / 50.0;
            float ax = (float) (amp * Math.sin(2 * Math.PI * freqHz * sec));
            g.feedAcc(ax, 0f, GRAVITY, t);
            t += FRAME_MS;
        }
        return t;
    }

    /** B1 走路推进：stepActivityTs() 从 0 变正，且随波形单调不减。 */
    @Test
    public void b1_walking_advancesStepActivityTs_fromZeroAndMonotonic() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        assertTrue("B1 起点：reset() 后 stepActivityTs() 必须归零（实际 "
                + g.stepActivityTs() + "）", g.stepActivityTs() == 0L);

        long t = TS_T0;
        long prev = 0L;
        for (int seg = 0; seg < 12; seg++) {            // 12 段 × 50 帧 = 600 帧 = 12_000ms
            t = feedWalkSineFrom(g, t, seg * 50, 50, 0.9, 2.5f);
            long cur = g.stepActivityTs();
            assertTrue("B1 单调不减：第 " + seg + " 段采样不得回退（prev=" + prev
                    + " cur=" + cur + "）", cur >= prev);
            prev = cur;
        }
        assertTrue("B1 走路 12s 后 stepActivityTs() 必须推进为正值（debug="
                + g.debugInfo() + "）", prev > 0L);
    }

    /** B1 关键锁：confirmSteps 抬到 8（攒不满门槛）后，stepActivityTs() 仍推进。 */
    @Test
    public void b1_confirmSteps8_belowGate_stepActivityTsStillAdvances() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = feedWalkSine(g, TS_T0, 200, 0.9, 2.5f);   // 先走 4s（低门槛）
        long tsAfterWalk = g.stepActivityTs();
        assertTrue("B1 前置：低门槛走路须先把 stepActivityTs() 推进为正（debug="
                + g.debugInfo() + "）", tsAfterWalk > 0L);

        g.setConfirmSteps(8);                              // 抬到 8：2.5s 窗口攒不满
        feedWalkSineFrom(g, t, 200, 150, 0.9, 2.5f);      // 连续相位再走 3s

        assertTrue("B1 前置：确认 confirmedStepCount() 确实 <8（门槛攒不满，实际="
                + g.confirmedStepCount() + "）", g.confirmedStepCount() < 8);
        assertTrue("B1 锁 F4 根因：setConfirmSteps(8) 攒不满门槛时 stepActivityTs() 仍须推进"
                + "（write 点在门槛判断之前）；walk=" + tsAfterWalk
                + " gate=" + g.stepActivityTs(), g.stepActivityTs() > tsAfterWalk);
    }

    /** B2（验收底线）：走路后喂 >=20s 纯重力无峰帧，stepActivityTs() 必须冻结。 */
    @Test
    public void b2_stillness_freezesStepActivityTs_atLeast20s() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long stillStartNow = feedWalkSine(g, TS_T0, TS_WALK_FRAMES, 0.9, 2.5f); // 走 12s
        long frozen = g.stepActivityTs();
        assertTrue("B2 前置：走路后 stepActivityTs() 须为正（frozen=" + frozen
                + " debug=" + g.debugInfo() + "）", frozen > 0L);

        long endNow = feedStillGravity(g, stillStartNow, TS_STILL_FRAMES);      // 静止 22s
        assertTrue("B2 前置：静止段虚拟时间须推进 >=20_000ms（实际 "
                + (endNow - stillStartNow) + "ms）", endNow - stillStartNow >= 20_000L);

        long after = g.stepActivityTs();
        assertTrue("B2 验收底线：静止 " + (endNow - stillStartNow) + "ms 后 stepActivityTs() "
                + "必须冻结在 " + frozen + "，绝不随 now 被伪增量刷成 " + endNow
                + "（实际 after=" + after + "）——旧实现这里每帧刷 now 会让判停窗口永远收不起来",
                after == frozen);
    }

    /** B3（验收底线）：用 B2 冻结的 stepActivityTs() 作 shouldStop 第 9 参，组合判停两个面。 */
    @Test
    public void b3_frozenClock_shouldStop_trueAtStillEnd_falseMidway() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long stillStartNow = feedWalkSine(g, TS_T0, TS_WALK_FRAMES, 0.9, 2.5f); // 走 12s
        long frozen = g.stepActivityTs();                                       // B2 的冻结值
        assertTrue("B3 前置：走路后冻结时钟须为正（frozen=" + frozen + "）", frozen > 0L);
        long endNow = feedStillGravity(g, stillStartNow, TS_STILL_FRAMES);     // 静止 22s

        long lastStepTs = frozen;          // 合格步时钟给冻结值
        long observedStepTs = frozen;      // 第 9 参 = B2 冻结的 stepActivityTs()
        long sessionStartTs = TS_T0;       // 会话起点（与虚拟 now 同一毫秒时间轴）
        // 中途 now 相对冻结时钟取 +3s：无论最后一个合格峰落在哪帧，都保证
        // now-effectiveTs = 3_000ms < stopMs(12_000ms)，且已越过会话宽限(5_000ms)。
        long midNow = frozen + 3_000L;
        assertTrue("B3 前置：中途 now 必须落在静止段内（mid=" + midNow
                + " stillStart=" + stillStartNow + " end=" + endNow + "）",
                midNow >= stillStartNow && midNow < endNow);

        // 面①：真停下、now 取静止段末尾 → now-effectiveTs 远超 stopMs → 返回 true（窗口能收起）
        assertTrue("B3 面①（验收底线）：真停下 now=" + endNow + "，now-effectiveTs="
                        + (endNow - frozen) + "ms > stopMs=" + TS_STOP_MS
                        + "，shouldStop 必须返回 true（真停下能收起）",
                StaticStopPolicy.shouldStop(lastStepTs, sessionStartTs, endNow, TS_STOP_MS,
                        TS_GRACE_MS, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        observedStepTs));

        // 面②：静止段中途、没到门槛 → 返回 false（不误收）；宽限已过，false 来自 stopMs 判据本身
        assertFalse("B3 面②：静止中途 now=" + midNow + "，now-effectiveTs="
                        + (midNow - frozen) + "ms < stopMs=" + TS_STOP_MS
                        + "，没到门槛不得误收（返回 false）",
                StaticStopPolicy.shouldStop(lastStepTs, sessionStartTs, midNow, TS_STOP_MS,
                        TS_GRACE_MS, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        observedStepTs));
    }

    /** B4①：低于峰阈值的细碎抖动不产生合格步峰，stepActivityTs() 不推进。 */
    @Test
    public void b4_lowAmplitudeJitter_doesNotAdvanceStepActivityTs() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        feedWalkSine(g, TS_T0, 800, 1.5, 0.3f);   // 幅值 0.3 < 峰阈值下限 0.6
        assertTrue("B4①：低于阈值抖动不得推进 stepActivityTs()（应为 0，实际 "
                        + g.stepActivityTs() + " debug=" + g.debugInfo() + "）",
                g.stepActivityTs() == 0L);
    }

    /** B4②：频率落步频窗口外（3Hz > 2.5Hz）的抖腿不产生合格步峰，stepActivityTs() 不推进。 */
    @Test
    public void b4_outOfWindowShake_doesNotAdvanceStepActivityTs() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        feedWalkSine(g, TS_T0, 800, 3.0, 2.5f);   // 3Hz，与既有 legShake 波形同构
        assertTrue("B4②：3Hz 抖腿（步频窗口外）不得推进 stepActivityTs()（应为 0，实际 "
                        + g.stepActivityTs() + " debug=" + g.debugInfo() + "）",
                g.stepActivityTs() == 0L);
    }

    /** B4③：平放姿态（重力落 Z 轴）+ 低幅值细碎抖动，stepActivityTs() 不推进。 */
    @Test
    public void b4_flatLayJitter_doesNotAdvanceStepActivityTs() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        feedFlatJitter(g, TS_T0, 800, 3.0, 0.3f); // 平放 + 低幅值 + 窗口外，三重不触发
        assertTrue("B4③：平放抖动不得推进 stepActivityTs()（应为 0，实际 "
                        + g.stepActivityTs() + " debug=" + g.debugInfo() + "）",
                g.stepActivityTs() == 0L);
    }

    // ======================================================================
    // 5.6.3 · B5 组：滑动窗口时效 —— 「蹲下后小窗一直不消失」的根因锁定
    //
    // 真机现象（2026-11-22 diag.log，13:11:31 达标触发后到用户截图 13:13 一直显示）：
    // 走几步到厕所蹲下，路况预览不收起。根因是 peakIntervals 只按数量保留 10 个间隔、
    // 没有时间上限 —— 蹲着时躯干/手机微动每隔 1~2s 也能产生一个越阈峰（频率 0.5~2.5Hz
    // 正好在窗口内），只要间隔 <2.5s 就不清空，窗口永久停在满格 10。
    // 于是 countReached / stepTick 恒真、lastStepTs 每帧刷成 now，判停永不触发。
    //
    // 修复：给窗口加 MotionConfig.STEP_WINDOW_MS（18s）时效，按绝对时间戳剔除陈旧峰。
    // 真人走满 10 步 6~10s（慢走 0.55Hz 约 18s），正常步行完全不受影响。
    // ======================================================================

    /** 稀疏微动：每 periodMs 来一个 120ms 的小脉冲（模拟蹲着时身体/手机的轻微动作）。 */
    private static long feedSparseBumps(GaitDetector g, long t0, long periodMs, int bumps, float amp) {
        long t = t0;
        int periodFrames = (int) (periodMs / FRAME_MS);
        for (int k = 0; k < bumps; k++) {
            for (int i = 0; i < periodFrames; i++) {
                float ax = GRAVITY;
                if (i < 6) {
                    // 半正弦脉冲：6 帧 = 120ms，只在这个小窗口里越阈，其余时间纯重力
                    ax = (float) (GRAVITY + amp * Math.sin(Math.PI * (i + 1) / 7.0));
                }
                g.feedAcc(ax, 0f, 0f, t);
                t += FRAME_MS;
            }
        }
        return t;
    }

    /** 同上，但统计「这一帧被判定成合格步（feedAcc 返回 true）」的次数。 */
    private static int countSparseBumpTriggers(GaitDetector g, long t0, long periodMs,
                                               int bumps, float amp) {
        long t = t0;
        int periodFrames = (int) (periodMs / FRAME_MS);
        int triggered = 0;
        for (int k = 0; k < bumps; k++) {
            for (int i = 0; i < periodFrames; i++) {
                float ax = GRAVITY;
                if (i < 6) {
                    ax = (float) (GRAVITY + amp * Math.sin(Math.PI * (i + 1) / 7.0));
                }
                if (g.feedAcc(ax, 0f, 0f, t)) triggered++;
                t += FRAME_MS;
            }
        }
        return triggered;
    }

    /** B5①：正常走路攒满窗口后，蹲着每 2s 一次微动 80s，窗口必须掉出满格。 */
    @Test
    public void b5_sparseBumps_doNotKeepWindowFull() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(10);
        g.reset();
        long t = feedWalkSine(g, TS_T0, 700, 0.9, 2.5f);   // 14s 正常走路，攒满 10 格
        assertTrue("B5 前置：走路 14s 后窗口须满格（实际=" + g.confirmedStepCount()
                + " debug=" + g.debugInfo() + "）", g.confirmedStepCount() >= 10);

        t = feedSparseBumps(g, t, 2_000L, 40, 2.5f);      // 蹲下 80s，每 2s 一次微动
        assertTrue("B5 修复点：稀疏微动 80s 后，15s 时效必须让窗口掉出满格（实际="
                + g.confirmedStepCount() + " debug=" + g.debugInfo()
                + "）；旧实现无时效，窗口会永久停在 10 → 判停永不触发", g.confirmedStepCount() < 10);
    }

    /** B5②：稀疏微动期间不得被判定成「还在走」的合格步（否则 lastStepTs 每帧被刷）。 */
    @Test
    public void b5_sparseBumps_doNotProduceStepTicks() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(10);
        g.reset();
        long t = feedWalkSine(g, TS_T0, 700, 0.9, 2.5f);   // 先攒满
        assertTrue("B5② 前置：走路后窗口须满格（实际=" + g.confirmedStepCount() + "）",
                g.confirmedStepCount() >= 10);

        // 过渡期：刚停下的前 20s，窗口里还留着走路的旧峰，允许这几次仍为 true
        t = feedSparseBumps(g, t, 2_000L, 10, 2.5f);
        // 稳态：旧峰全部过时效后，稀疏微动不得再被判成「合格步」
        int triggered = countSparseBumpTriggers(g, t, 2_000L, 30, 2.5f);
        assertTrue("B5② 修复点：旧峰过期后 30 次稀疏微动期间 feedAcc 不得返回 true（实际触发 "
                + triggered + " 次，debug=" + g.debugInfo()
                + "）；旧实现窗口满格 + 主轴一致，每一帧都会返回 true", triggered == 0);
    }
}
