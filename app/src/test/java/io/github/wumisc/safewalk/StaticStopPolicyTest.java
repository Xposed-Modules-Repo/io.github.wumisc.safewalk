package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [StaticStopPolicy] 回归测试。
 *
 * 锁的是「保活复活后 8 秒就被收起预览」这个真机缺陷（19:06:49 复活 →
 * 19:06:57 收起）。核心：上一会话残留的步时间戳必须作废，本会话刚起来
 * 要有宽限；但**正常行走后的静置判停不能失效**，否则预览永远不收。
 */
public class StaticStopPolicyTest {

    private static final long STOP = 10_000L;
    private static final long GRACE = StaticStopPolicy.SESSION_GRACE_MS;

    // ---------- 从未有步 / 时钟异常：不判停 ----------

    @Test
    public void noStepEverDoesNotStop() {
        assertFalse(StaticStopPolicy.shouldStop(0L, 1_000L, 999_999L, STOP));
    }

    @Test
    public void clockRollbackDoesNotStop() {
        assertFalse(StaticStopPolicy.shouldStop(200_000L, 100_000L, 50_000L, STOP));
        assertFalse(StaticStopPolicy.shouldStop(300_000L, 400_000L, 350_000L, STOP));
    }

    // ---------- 会话基线：复活场景 ----------

    @Test
    public void staleTimestampWithinGraceDoesNotStop() {
        // 服务刚复活：sessionStartTs=100_000，残留 lastStepTs=50_000（上会话）
        assertFalse("复活 8 秒内不能立刻收起",
                StaticStopPolicy.shouldStop(50_000L, 100_000L, 108_000L, STOP));
    }

    @Test
    public void staleTimestampAfterGraceAllowsOneEmptyStop() {
        // 宽限过后本会话仍没走出步：允许收起一次空预览，避免永远挂空窗
        assertTrue(StaticStopPolicy.shouldStop(
                50_000L, 100_000L, 100_000L + GRACE + 1, STOP));
    }

    @Test
    public void freshSessionWithinGraceDoesNotStop() {
        // 本会话已有步，但刚进入会话不久，宽限内不收
        assertFalse(StaticStopPolicy.shouldStop(
                101_000L, 100_000L, 100_000L + GRACE - 1, STOP));
    }

    // ---------- 正常静置判停 ----------

    @Test
    public void normalStaticStopAfterStopMs() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        long lastStep = now - STOP - 1;
        assertTrue(StaticStopPolicy.shouldStop(lastStep, session, now, STOP));
    }

    @Test
    public void noStopWhileStillWalking() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        long lastStep = now - STOP / 2;
        assertFalse(StaticStopPolicy.shouldStop(lastStep, session, now, STOP));
    }

    @Test
    public void exactStopBoundaryDoesNotStop() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        assertFalse("刚好 stopMs 不算超时",
                StaticStopPolicy.shouldStop(now - STOP, session, now, STOP));
    }

    // ---------- 无会话基线（旧行为兼容） ----------

    @Test
    public void noSessionBaselineKeepsLegacyBehaviour() {
        // sessionStartTs=0 时退化为原来的 lastStepTs != 0 && now-lastStepTs > stopMs
        assertTrue(StaticStopPolicy.shouldStop(1_000L, 0L, 1_000L + STOP + 1, STOP));
        assertFalse(StaticStopPolicy.shouldStop(1_000L, 0L, 1_000L + STOP, STOP));
    }

    @Test
    public void customGraceIsRespected() {
        assertFalse("自定义宽限 5s 内不判停",
                StaticStopPolicy.shouldStop(50_000L, 100_000L, 104_000L, STOP, 5_000L));
        assertTrue("超过自定义宽限后可收起空窗",
                StaticStopPolicy.shouldStop(50_000L, 100_000L, 105_001L, STOP, 5_000L));
    }

    // ---------- 5.1.0 显示驻留：小窗不能「闪一下就消失」 ----------

    /**
     * 真机日志里的循环：14:46:29 弹出 → 14:46:41 收起，正好 12 秒。
     * 用户看到的是「在手里经常跳预览小窗」。误判本身已由 WalkConfirmPolicy
     * 收紧解决，这一层保证万一还是判错，画面也稳定存在一段时间。
     */
    @Test
    public void justShownNeverStopsWithinMinShow() {
        long session = 100_000L;
        long shownAt = 200_000L;
        // 显示 1 秒后就没步了，但驻留期（12s）内不能收
        assertFalse("刚显示 1 秒不能收",
                StaticStopPolicy.shouldStop(199_000L, session, shownAt + 1_000L, 200L,
                        GRACE, shownAt));
        assertFalse("第 11.9 秒仍然不能收",
                StaticStopPolicy.shouldStop(199_000L, session, shownAt + 11_900L, 200L,
                        GRACE, shownAt));
    }

    @Test
    public void stopsAfterMinShowElapsed() {
        long session = 100_000L;
        long shownAt = 200_000L;
        // 驻留期一过、仍然没步 → 正常收起，不会把「真的停下来了」永久挂住
        assertTrue("驻留结束后该收就收",
                StaticStopPolicy.shouldStop(199_000L, session,
                        shownAt + StaticStopPolicy.DEFAULT_MIN_SHOW_MS + 1, 200L,
                        GRACE, shownAt));
    }

    /** 手动常显 / 非本会话显示时 showStartedTs=0，驻留逻辑不参与，行为与旧版一致 */
    @Test
    public void manualShowIgnoresDwell() {
        long session = 100_000L;
        long now = session + GRACE + 20_000L;
        assertTrue(StaticStopPolicy.shouldStop(now - 1_000L, session, now, 500L, GRACE, 0L));
    }

    // ---------- 5.2.0 没在走就别判停：日志空转修复 ----------

    /**
     * 真机日志（2026-11-14 14:17~14:35，Redmi / HyperOS 4）里 18 分钟出现 19 次
     * 「[walk] 静置超时，判定停下 → 收起预览」，而同期 `[ACTIVE] ✅ 激活行走模式`
     * **一次都没有** —— 根本没有预览可收，判决却在每秒执行。
     *
     * 条件全满足（有残留步时间戳、会话早过宽限期、静置够久），只把 walking 置 false，
     * 必须一律判 false。
     */
    @Test
    public void notWalkingNeverStops() {
        long session = 100_000L;
        long now = session + GRACE + 60_000L;
        long lastStep = now - 30_000L;   // 早就超 stopMs
        assertFalse("没在走时不该判停",
                StaticStopPolicy.shouldStop(lastStep, session, now, STOP, GRACE, 0L,
                        StaticStopPolicy.DEFAULT_MIN_SHOW_MS, false));
    }

    /** walking=true 时原行为不变，不能被新参数顺手改坏 */
    @Test
    public void walkingKeepsNormalStop() {
        long session = 100_000L;
        long now = session + GRACE + 60_000L;
        long lastStep = now - STOP - 1;
        assertTrue(StaticStopPolicy.shouldStop(lastStep, session, now, STOP, GRACE, 0L,
                StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true));
    }

    // ======================================================================
    // 5.3.8 判停时钟解耦（observedStepTs）回归用例 —— Tess 新增，仅追加，不改上方既有 15 个用例
    //
    // 背景：confirmSteps=8 时合格步门槛永久冻结 lastStepTs（停在激活时刻），
    //       走路 12s 后 now-lastStepTs>stopMs 把预览误判停下收起（真机 16:28:46）。
    // 修复：effectiveTs = maxOf(lastStepTs, observedStepTs)，取两个时钟较新者作判停依据。
    // 量纲：所有时间戳/门槛一律为毫秒(ms)；40s 写 40_000L，12s 写 12_000L。
    // 调用：Java 调 Kotlin @JvmOverloads —— 8 参命中「省略末参」重载，9 参命中全签名。
    // ======================================================================

    /** 真机静置门槛 12s（毫秒），与用户报告一致 */
    private static final long OBS_STOP_MS = 12_000L;
    /** 会话基线；配合 OBS_NOW 使用例越过会话宽限，落到真正的判停出口 */
    private static final long OBS_SESSION = 100_000L;
    /** 统一的「当前时刻」，远离会话宽限(30_000ms)与时钟回拨区间，避免既有闸门干扰 */
    private static final long OBS_NOW = 500_000L;

    // ---------- 核心复现 / 真停下 ----------

    /**
     * SC1 核心复现（本轮 bug 直接回归）。
     * lastStepTs 冻结在 now-40s，observedStepTs 新鲜在 now-3s，stopMs=12s。
     * 修复前 effectiveTs=lastStepTs → now-effectiveTs=40s>12s → 误判 true（收起预览）；
     * 修复后 effectiveTs=observedStepTs → 3s<12s → false（不误收）。
     */
    @Test
    public void coreRepro_frozenLastStepButFreshObservedStep_doesNotStop() {
        long frozenLastStep = OBS_NOW - 40_000L;
        long freshObserved = OBS_NOW - 3_000L;
        assertFalse("合格步时钟冻结但仍有真实步活动，不得误判停下",
                StaticStopPolicy.shouldStop(
                        frozenLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        freshObserved));
    }

    /**
     * SC2 真停下仍能收：两个时钟都旧(now-40s)，取较新者后依然超时 → true。
     * 防止「为了不误收就永远不收」的过度修复。
     */
    @Test
    public void bothClocksStale_reallyStopped_doesStop() {
        long stale = OBS_NOW - 40_000L;
        assertTrue("两个时钟都旧说明真的停下了，应正常收起",
                StaticStopPolicy.shouldStop(
                        stale, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        stale));
    }

    // ---------- maxOf 两个方向 ----------

    /** SC3 方向一：lastStepTs 旧 + observedStepTs 新 → 取 observedStepTs → 不判停 */
    @Test
    public void maxOf_picksNewerObserved_whenQualifiedStale_doesNotStop() {
        long veryOldLastStep = OBS_NOW - 60_000L;
        long veryFreshObserved = OBS_NOW - 1_000L;
        assertFalse("取较新者：观测步时钟很新，不应被冻结的合格步时钟拖停",
                StaticStopPolicy.shouldStop(
                        veryOldLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        veryFreshObserved));
    }

    /** SC3 方向二：lastStepTs 新 + observedStepTs 旧 → 取 lastStepTs → 不判停 */
    @Test
    public void maxOf_picksNewerQualified_whenObservedStale_doesNotStop() {
        long freshLastStep = OBS_NOW - 2_000L;
        long oldObserved = OBS_NOW - 60_000L;
        assertFalse("取较新者：合格步时钟更新时，不应被陈旧的观测步时钟误拖停",
                StaticStopPolicy.shouldStop(
                        freshLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        oldObserved));
    }

    // ---------- 默认参数兼容 / @JvmOverloads 省略重载 ----------

    /**
     * SC4 8 参调用（不传第 9 参）命中 @JvmOverloads 省略重载，observedStepTs 默认 0，
     * lastStepTs 超时时按旧语义正常判停（true）。
     */
    @Test
    public void eightArgCall_omittedObserved_defaultsToZero_keepsLegacyStop() {
        long timedOutLastStep = OBS_NOW - OBS_STOP_MS - 1;
        assertTrue("省略第9参时应与改动前一致（超时仍判停）",
                StaticStopPolicy.shouldStop(
                        timedOutLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true));
    }

    /** SC4 8 参调用且 lastStepTs 新鲜 → 旧语义「还在走不判停」（false） */
    @Test
    public void eightArgCall_freshLastStep_keepsLegacyNoStop() {
        long freshLastStep = OBS_NOW - 2_000L;
        assertFalse("省略第9参时新语义不变（还在走不判停）",
                StaticStopPolicy.shouldStop(
                        freshLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true));
    }

    // ---------- 不破坏既有闸门（全部用 9 参，且刻意让 effectiveTs 超时：无闸门会返回 true） ----------

    /** SC5 walking 闸门：即使两个时钟都超时、且传了第 9 参，walking=false 一律不判停 */
    @Test
    public void walkingGate_notWalking_neverStops_evenWithNinthArg() {
        long stale = OBS_NOW - 40_000L;
        assertFalse("walking=false 时新增判停时钟不得绕过闸门",
                StaticStopPolicy.shouldStop(
                        stale, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, false,
                        stale));
    }

    /** SC5 显示驻留：now-showStartedTs < minShowMs 时不判停，新时钟不得绕过 */
    @Test
    public void displayDwell_withinMinShow_neverStops_evenWithNinthArg() {
        long stale = OBS_NOW - 40_000L;          // 两个时钟都超时：若无驻留闸门会返回 true
        long shownAt = OBS_NOW - 1_000L;         // 显示 1s，远在 12s 驻留期内
        assertFalse("驻留期内不得收起，observedStepTs 不能绕过显示驻留",
                StaticStopPolicy.shouldStop(
                        stale, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, shownAt, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        stale));
    }

    /** SC5 会话宽限：now-sessionStartTs < graceMs 时不判停，新时钟不得绕过 */
    @Test
    public void sessionGrace_withinGrace_neverStops_evenWithNinthArg() {
        long session = 100_000L;
        long now = session + GRACE - 1;          // 宽限内（29_999ms < 30_000ms）
        long lastStep = 101_000L;                // >= session，不落旧会话残留分支
        long observed = 100_500L;                // >= session，同样不落旧会话残留分支
        // effectiveTs = 101_000；now-effectiveTs = 28_999ms > 12_000ms，若无宽限闸门会返回 true
        assertFalse("宽限期内不得判停，observedStepTs 不能绕过会话宽限",
                StaticStopPolicy.shouldStop(
                        lastStep, session, now, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        observed));
    }

    /**
     * SC5 时钟回拨：now < effectiveTs 不判停。两个方向都测，确认 maxOf 之后
     * 回拨判据用的是 effectiveTs（含 observedStepTs），而不是只看 lastStepTs。
     */
    @Test
    public void clockRollback_effectiveTsInFuture_neverStops() {
        long stale = OBS_NOW - 40_000L;
        long future = OBS_NOW + 5_000L;
        // 方向一：观测步时钟跑到 now 之后
        assertFalse("observedStepTs 在 now 之后（回拨）不得判停",
                StaticStopPolicy.shouldStop(
                        stale, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        future));
        // 方向二：合格步时钟跑到 now 之后，观测步时钟陈旧
        assertFalse("lastStepTs 在 now 之后（回拨）不得判停",
                StaticStopPolicy.shouldStop(
                        future, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        stale));
    }

    // ---------- 边界 ----------

    /** SC6 observedStepTs=0（默认哨兵）时不影响：9 参显式传 0 与 8 参省略结果一致 */
    @Test
    public void observedStepTsZeroSentinel_doesNotChangeLegacyOutcome() {
        long timedOutLastStep = OBS_NOW - OBS_STOP_MS - 1;
        boolean viaEightArg = StaticStopPolicy.shouldStop(
                timedOutLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true);
        boolean viaNineArgZero = StaticStopPolicy.shouldStop(
                timedOutLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true, 0L);
        assertTrue("哨兵 0 不得改变原语义：8 参与 9参(显式0) 都应正常判停",
                viaEightArg && viaNineArgZero);
    }

    /** SC6 边界：两个时钟都为 0（从未有步活动）→ effectiveTs==0 → 不判停 */
    @Test
    public void bothClocksZero_neverStops() {
        assertFalse("合格步与观测步都为 0 时不得判停（避免空转刷收起日志）",
                StaticStopPolicy.shouldStop(
                        0L, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        0L));
    }

    /**
     * SC6 严格 > 边界：now - effectiveTs 恰好 == stopMs 时不判停。
     * 特意用 observedStepTs 构造这个边界（lastStepTs 很旧），以证明边界判据用的是 effectiveTs：
     * 若只看 lastStepTs(now-60s)，60s>12s 会误判 true。
     */
    @Test
    public void exactStopBoundaryOnEffectiveTs_doesNotStop() {
        long veryOldLastStep = OBS_NOW - 60_000L;
        long observedAtBoundary = OBS_NOW - OBS_STOP_MS;   // now-effectiveTs == stopMs
        assertFalse("恰好等于门槛不算超时（严格 >）",
                StaticStopPolicy.shouldStop(
                        veryOldLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        observedAtBoundary));
    }

    /** SC6 严格 > 的另一面：now - effectiveTs 刚超过 stopMs 1ms 就判停 */
    @Test
    public void justOverStopBoundary_doesStop() {
        long veryOldLastStep = OBS_NOW - 60_000L;
        long observedJustOver = OBS_NOW - OBS_STOP_MS - 1; // now-effectiveTs == stopMs+1
        assertTrue("超过门槛 1ms 即判停，确认边界是严格 >",
                StaticStopPolicy.shouldStop(
                        veryOldLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        observedJustOver));
    }

    /**
     * 额外加固（响应实现报告 §5.2 建议）：observedStepTs 也落在旧会话残留区间时，
     * 「旧会话残留 + 宽限」分支语义不变。该分支的判据由 lastStepTs 改成了 effectiveTs，
     * 是被改动的代码点；但返回值仍只看 now-sessionStartTs>graceMs，新参不得改坏复活空窗逻辑。
     */
    @Test
    public void staleObservedInOldSession_graceBranchUnchanged() {
        long session = 100_000L;
        long staleQualified = 50_000L;   // 合格步时钟：旧会话残留（< session）
        long staleObserved = 60_000L;    // 观测步时钟：也停留在旧会话（< session）
        // effectiveTs = maxOf(50_000, 60_000) = 60_000 < session → 落旧会话残留分支
        assertFalse("宽限内：残留分支不许收起空预览",
                StaticStopPolicy.shouldStop(
                        staleQualified, session, session + GRACE - 1, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        staleObserved));
        assertTrue("宽限后：残留分支允许收起一次空预览（与既有 4 参用例同语义）",
                StaticStopPolicy.shouldStop(
                        staleQualified, session, session + GRACE + 1, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        staleObserved));
    }

    // ---------- @JvmOverloads：证明第 9 参确实生效（本轮修复关键点） ----------

    /**
     * SC7 铁证：同一组前 8 参（lastStepTs 冻结在 now-40s、已超时），
     *   8 参调用 → observedStepTs 默认 0 → effectiveTs=lastStepTs → 超时 → true（= 修复前误判）；
     *   9 参调用 → 传入新鲜 observedStepTs → effectiveTs 前移 → 未超时 → false（= 修复生效）。
     * 仅因「第 9 参有无」结果相反，即证明 9 参确实绑定到全签名并生效，
     * 而不是被 @JvmOverloads 静默丢弃（对应实现报告 §3.1 的最大陷阱）。
     */
    @Test
    public void ninthArgActuallyTakesEffect_sameInputEightVsNineDiffer() {
        long frozenLastStep = OBS_NOW - 40_000L;
        long freshObserved = OBS_NOW - 3_000L;
        boolean viaEightArg = StaticStopPolicy.shouldStop(
                frozenLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true);
        boolean viaNineArg = StaticStopPolicy.shouldStop(
                frozenLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                freshObserved);
        assertTrue("8 参省略重载应复现修复前的误判停(true)", viaEightArg);
        assertFalse("9 参全签名传入新鲜观测步时钟应不判停(false)", viaNineArg);
    }

    // ======================================================================
    // 返工第 1 轮 · M-1 mixed-session 交叉方向 —— Tess 纯追加，不改上方 31 条
    //
    // :113 判据「effectiveTs < sessionStartTs（旧会话残留作废）」由 lastStepTs 改成
    // effectiveTs 后，直接受影响的是「一个时钟 < sessionStartTs、另一个 >= sessionStartTs」
    // 的两个交叉方向。既有 N9（两时钟均 >= session）、N16（两时钟均 < session）
    // 都不交叉；N2/N3/N4 是「两时钟同侧」的 maxOf，也不覆盖这一步判据本身。
    // 本组用 9 参显式传 observedStepTs，把「maxOf 取较新者 → 不落残留分支」钉死。
    //
    // 判定路径（全部毫秒，复用 OBS_SESSION=100_000 / OBS_NOW=500_000 /
    // OBS_STOP_MS=12_000 / GRACE=30_000）：
    //   now-sessionStartTs=400_000>GRACE → 会话宽限已放行；showStartedTs=0L 关闭驻留闸门；
    //   walking=true。此时若 :113 误用「较旧的那个时钟」(<session) 会落残留分支
    //   return now-sessionStartTs>graceMs = true；而正确实现 effectiveTs=maxOf 取较新者
    //   (>=session) 不落残留分支，走 :121 return now-effectiveTs>stopMs。
    //   故「静置未超 stopMs 断言 false」是能区分两种实现的关键断言。
    // ======================================================================

    /**
     * A1：lastStepTs 旧(< session)、observedStepTs 新(>= session) → maxOf 取新者，
     * 不落残留分支，走正常静置判停。两个面都覆盖：未超 stopMs → false、超 stopMs → true。
     */
    @Test
    public void mixedSession_oldLastStep_newObserved_maxOfSkipsResidualBranch() {
        long staleLastStep = 50_000L;                     // < OBS_SESSION：旧会话残留
        long freshObservedWithin = OBS_NOW - 3_000L;      // 497_000 >= session，仅静置 3s
        assertFalse("A1 宽限外·未超 stopMs：较新 observedStepTs 决定 effectiveTs，"
                        + "不落旧会话残留分支；now-effectiveTs=3s<12s 不判停（若 :113 误用"
                        + " lastStepTs 落残留分支会返回 true）",
                StaticStopPolicy.shouldStop(staleLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true, freshObservedWithin));
        long freshObservedOver = OBS_NOW - OBS_STOP_MS - 1L; // 487_999，静置超门槛 1ms
        assertTrue("A1 宽限外·超 stopMs：同样不落残留分支，now-effectiveTs>12s 判停 true",
                StaticStopPolicy.shouldStop(staleLastStep, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true, freshObservedOver));
    }

    /**
     * A2：lastStepTs 新(>= session)、observedStepTs 旧(< session) → 同样取较新者，
     * 不落残留分支。覆盖「宽限外未超 stopMs → false」与「宽限内 → false」两个面。
     */
    @Test
    public void mixedSession_newLastStep_oldObserved_maxOfSkipsResidualBranch() {
        long staleObserved = 50_000L;                      // < OBS_SESSION：旧会话残留
        long freshLastStepWithin = OBS_NOW - 3_000L;       // 497_000 >= session
        assertFalse("A2 宽限外·未超 stopMs：较新 lastStepTs 决定 effectiveTs，"
                        + "不落旧会话残留分支（若 :113 误用 observedStepTs 或 minOf 会返回 true）",
                StaticStopPolicy.shouldStop(freshLastStepWithin, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true, staleObserved));
        long nowInGrace = OBS_SESSION + GRACE - 1L;        // 129_999：宽限内
        long freshLastStepInGrace = OBS_SESSION + 500L;    // 100_500 >= session
        assertFalse("A2 宽限内：混合时钟下会话宽限仍不判停（mixed-session 宽限面）",
                StaticStopPolicy.shouldStop(freshLastStepInGrace, OBS_SESSION, nowInGrace,
                        OBS_STOP_MS, GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true,
                        staleObserved));
    }

    /**
     * A3：observedStepTs 是唯一新鲜时钟、lastStepTs=0 → 不误触发旧会话残留分支。
     * 同时验证 :105「effectiveTs==0」用的是合并值而非裸 lastStepTs：0 不会让新鲜时钟失效。
     */
    @Test
    public void mixedSession_observedOnlyFresh_lastStepZero_noResidualBranch() {
        long freshObservedWithin = OBS_NOW - 3_000L;       // 497_000：唯一新鲜时钟
        assertFalse("A3 未超 stopMs：lastStepTs=0 时 observedStepTs 顶起 effectiveTs，"
                        + "不落旧会话残留分支、也不被 :105 误判为 0；不判停 false",
                StaticStopPolicy.shouldStop(0L, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true, freshObservedWithin));
        long freshObservedOver = OBS_NOW - OBS_STOP_MS - 1L; // 487_999
        assertTrue("A3 超 stopMs：冻结的观测步时钟确实参与判停，now-effectiveTs>12s 返回 true",
                StaticStopPolicy.shouldStop(0L, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true, freshObservedOver));
    }

    // ---------- C 组：8 参调用（省略第 9 参）@JvmOverloads 兼容性 ----------

    /** C：省略 observedStepTs 的 8 参重载在返工后仍可编译并保持旧语义。 */
    @Test
    public void compat_eightArgCall_omitsObserved_stillCompiles() {
        long timedOut = OBS_NOW - OBS_STOP_MS - 1L;
        assertTrue("C 8 参省略重载仍可编译：lastStepTs 超时按旧语义判停 true",
                StaticStopPolicy.shouldStop(timedOut, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true));
        long fresh = OBS_NOW - 3_000L;
        assertFalse("C 8 参省略重载仍可编译：lastStepTs 新鲜按旧语义不判停 false",
                StaticStopPolicy.shouldStop(fresh, OBS_SESSION, OBS_NOW, OBS_STOP_MS,
                        GRACE, 0L, StaticStopPolicy.DEFAULT_MIN_SHOW_MS, true));
    }
}
