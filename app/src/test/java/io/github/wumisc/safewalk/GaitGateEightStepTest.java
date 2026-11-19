package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 【5.5.0 步态门槛 8 步】验收测试（QA 独立编写，纯 JVM）。
 *
 * <p>用户原话：「连续走 8 步为最低标准；不得误伤夜间手电、不得导致步态判定失效；
 * 治『晃一下 / 伸懒腰 / 翻身就累计步数误触』」。
 *
 * <p>本文件用<b>合成波形</b>把三件事分开验证，避免「改了门槛、顺手把别的判据改坏」：
 * <ol>
 *   <li><b>抗误触真的有效</b>：晃手机（高频随机）、3Hz 抖腿、伸懒腰（&lt;0.5Hz 低频大幅）、
 *       翻身/大瞬态（&gt;8 m/s²）、断续抖动（抖几下停几秒）都必须<b>不能</b>凑满 8 连续步。
 *       这些用例在改门槛之前大多是「5 步就放行」，现在必须仍然不触发。</li>
 *   <li><b>不失效</b>：正常步行 100~130 步/分（间隔 460~600ms）默认门槛 8 步必须触发；
 *       慢走 0.9Hz 也必须触发。门槛抬高最容易引入的回归是「永远攒不满」。</li>
 *   <li><b>手电解耦</b>：{@code onLongStaticDetected(false)} 的调用点在
 *       {@code peakIntervals.size >= confirmSteps && axisStable()} <b>之前</b>，
 *       因此门槛抬到 8（乃至 10）时，走路仍会持续把「静置计时」清零，
 *       不会让手电误判成静置。用「门槛 10 + 连续走 12 秒不得收到 isStatic=true」钉死。</li>
 * </ol>
 *
 * <p>另含服务层常量与「死条件修复」的结构契约：{@code STEP_CONFIRM_MIN=8}、
 * 跨度闸 {@code 8 步 / 3000ms}、{@code min(WINDOW_CAP, confirmSteps×2)} 夹上限。
 */
@RunWith(JUnit4.class)
public class GaitGateEightStepTest {

    /** 50Hz 采样 */
    private static final long FRAME_MS = 20L;
    /** 重力落在 X 轴（竖直握持） */
    private static final float GRAVITY = 9.8f;

    private static final String SERVICE_REL =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
    private static final String DETECTOR_REL =
            "src/main/java/io/github/wumisc/safewalk/GaitDetector.kt";

    private static String serviceSrc;
    private static String detectorSrc;

    @BeforeClass
    public static void loadSources() throws IOException {
        serviceSrc = new String(Files.readAllBytes(locate(SERVICE_REL)), StandardCharsets.UTF_8);
        detectorSrc = new String(Files.readAllBytes(locate(DETECTOR_REL)), StandardCharsets.UTF_8);
    }

    // ==================== 门槛常量 ====================

    @Test
    public void detectorDefaultConfirmStepsIsEight() {
        assertEquals("GaitDetector 默认门槛必须是 8 步（用户要求的最低标准）",
                8, new GaitDetector().getConfirmSteps());
    }

    @Test
    public void serviceConstantsAreEightStepsAndThreeSecondSpan() throws Exception {
        assertEquals("硬件计步确认门槛必须是 8 步",
                8, readInt(WalkPreviewService.class, "STEP_CONFIRM_MIN"));
        assertEquals("跨度闸步数必须是 8", 8, readInt(WalkPreviewService.class, "ACCEL_MIN_SPAN_STEPS"));
        assertEquals("跨度闸时长必须是 3000ms",
                3_000L, readLong(WalkPreviewService.class, "ACCEL_MIN_SPAN_MS"));
        assertEquals("硬件确认窗口保持 10 秒（抬门槛优于拉长窗口）",
                10_000L, readLong(WalkPreviewService.class, "STEP_CONFIRM_WINDOW_MS"));
    }

    @Test
    public void walkConfirmPolicyMapsEightNineTenOneByOne() {
        assertEquals(8, WalkConfirmPolicy.requiredStepsFor(8));
        assertEquals(9, WalkConfirmPolicy.requiredStepsFor(9));
        assertEquals(10, WalkConfirmPolicy.requiredStepsFor(10));
        // 历史值（老版本存过 2~7）读回必须夹到 8，绝不能回到 2 步
        assertEquals(8, WalkConfirmPolicy.requiredStepsFor(2));
        assertEquals(8, WalkConfirmPolicy.requiredStepsFor(7));
    }

    // ==================== 不失效：正常步行 / 慢走必须触发 ====================

    @Test
    public void normalWalk_100spm_triggersAtDefaultGate() {
        // 100 步/分 = 1.667Hz，间隔 600ms —— 用户口径的下沿
        assertTrue("100 步/分的正常步行必须触发（默认 8 步门槛）",
                walkTriggers(1.667, 2.5, 20_000L));
    }

    @Test
    public void normalWalk_108spm_triggersAtDefaultGate() {
        // 1.8Hz = 108 步/分，间隔 ≈555ms —— 真机日志里最常见的步频
        assertTrue("108 步/分必须触发", walkTriggers(1.8, 2.5, 20_000L));
    }

    @Test
    public void normalWalk_130spm_triggersAtDefaultGate() {
        // 130 步/分 = 2.167Hz，间隔 462ms —— 用户口径的上沿（快走）
        assertTrue("130 步/分的快走必须触发，否则门槛把正常用户判死",
                walkTriggers(2.167, 2.5, 20_000L));
    }

    @Test
    public void slowWalk_0_9Hz_triggersAtDefaultGate() {
        // 0.9Hz = 54 步/分（散步）：间隔 1111ms。既有用例用 confirmSteps=5 覆盖过，
        // 门槛抬到 8 后必须仍然能攒满。
        assertTrue("0.9Hz 慢走必须触发（门槛抬高不得让慢走攒不满）",
                walkTriggers(0.9, 2.5, 30_000L));
    }

    // ==================== 抗误触：五类误触波形都不得触发 ====================

    @Test
    public void legShake_3Hz_neverTriggersAtDefaultGate() {
        assertFalse("3Hz 抖腿落在步频窗口外（上限 2.5Hz），绝不能触发",
                walkTriggers(3.0, 2.5, 30_000L));
    }

    @Test
    public void randomHandShake_neverTriggersAtDefaultGate() {
        // 手持晃动：5Hz 上下 + 频率缓慢漂移（4~6Hz，每 0.5 秒变一次，相位连续），
        // 幅值按 0.4Hz 缓慢起伏（模拟手臂自然的强弱变化）。
        // 物理上合理的抖动不会像白噪声那样逐帧随机，否则会造出假的低频包络。
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(8);
        g.reset();
        long t = 0L;
        double phase = 0.0;
        double freq = 5.0;
        boolean triggered = false;
        long seed = 20261119L;
        for (int i = 0; i < 2000; i++) {       // 40 秒
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double r = ((seed >>> 11) & 0xFFFF) / 65535.0;      // 0~1
            if (i % 25 == 0) freq = 4.0 + 2.0 * r;              // 每 0.5s 漂一次
            phase += 2 * Math.PI * freq * (FRAME_MS / 1000.0);
            double sec = i * (FRAME_MS / 1000.0);
            double amp = 2.0 + 0.8 * Math.sin(2 * Math.PI * 0.4 * sec);   // 缓慢起伏
            float ax = (float) (GRAVITY + amp * Math.sin(phase));
            if (g.feedAcc(ax, 0f, 0f, t)) {
                triggered = true;
                break;
            }
            t += FRAME_MS;
        }
        assertFalse("手持高频晃动绝不能触发（debug=" + g.debugInfo() + "）", triggered);
    }

    @Test
    public void stretch_lowFrequencyLargeAmplitude_neverTriggers() {
        // 伸懒腰/起身：<0.5Hz、幅值大（3.5 m/s²，仍在步行幅值上限 8 以内）。
        // 步频窗口下限是 0.5Hz，这类低频大幅摆动一个合格步都组不成。
        assertFalse("伸懒腰式 <0.5Hz 低频大幅摆动绝不能触发",
                walkTriggers(0.3, 3.5, 40_000L));
    }

    @Test
    public void flipPhone_bigTransient_neverTriggers() {
        // 翻身/拿起手机：>8 m/s² 的瞬态一律不计峰、不污染包络
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(8);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 400; i++) {         // 8 秒、每 200ms 一下 12 m/s² 尖峰
            float ax = (i % 10 < 2) ? GRAVITY + 12.0f : GRAVITY;
            if (g.feedAcc(ax, 0f, 0f, t)) {
                triggered = true;
                break;
            }
            t += FRAME_MS;
        }
        assertFalse("翻身/大瞬态（>8 m/s²）绝不能累成步数（debug=" + g.debugInfo() + "）",
                triggered);
    }

    @Test
    public void intermittentShaking_neverAccumulatesEightSteps() {
        Run r = intermittentShake(8);
        assertFalse("断续抖动绝不能触发", r.triggered);
        assertTrue("断续抖动不得累积到 8 步（实际峰值=" + r.maxCount + "）", r.maxCount < 8);
    }

    /**
     * 对照实验（本用例的存在意义是证明「抬门槛」确实治了用户痛点，而不是只改了个常数）：
     * 同一段「抖三下 → 停 4 秒」的波形，在旧门槛 2 步下会触发（用户抱怨的误触），
     * 在 8 步门槛下不触发。若哪天有人把门槛改回去、或把断步清空逻辑弄坏，这条会第一个报警。
     */
    @Test
    public void intermittentShake_triggersAtLegacyTwoStepGate_butNotAtEight() {
        Run legacy = intermittentShake(2);
        assertTrue("门槛 2 步时该波形应当触发（这是用户投诉的误触原型），实际峰值="
                + legacy.maxCount, legacy.triggered);
        assertFalse("门槛 8 步后同一波形必须不再触发", intermittentShake(8).triggered);
    }

    /** 断续抖动波形：每轮 3 下（间隔 500ms）+ 静 4 秒，共 12 轮 */
    private static final class Run {
        final boolean triggered;
        final int maxCount;

        Run(boolean triggered, int maxCount) {
            this.triggered = triggered;
            this.maxCount = maxCount;
        }
    }

    private static Run intermittentShake(int confirmSteps) {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(confirmSteps);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        int maxSeen = 0;
        for (int round = 0; round < 12; round++) {
            for (int k = 0; k < 60; k++) {          // 1.2s 抖三下（0/500/1000ms 三个上穿）
                double sec = k / 50.0;
                float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 2.0 * sec));
                if (g.feedAcc(ax, 0f, 0f, t)) triggered = true;
                maxSeen = Math.max(maxSeen, g.confirmedStepCount());
                t += FRAME_MS;
            }
            for (int k = 0; k < 200; k++) {         // 静 4 秒（无峰，强制断步）
                if (g.feedAcc(GRAVITY, 0f, 0f, t)) triggered = true;
                maxSeen = Math.max(maxSeen, g.confirmedStepCount());
                t += FRAME_MS;
            }
        }
        return new Run(triggered, maxSeen);
    }

    @Test
    public void shortBurstManyShakes_stillBelowGateForQuantityOrSpan() {
        // 收敛前的最危险形态：「3 秒内连抖 8 下」。
        // 采集层 2.5Hz 上限下 8 个合格步理论最短 7×400=2800ms，
        // 所以这类抖动能在 <3 秒内凑满 8 步 —— 数量闸拦不住，必须靠服务层跨度闸。
        // 这里验证两层各司其职：采集层确实能到 8 步，而策略层用跨度把它拦下。
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(8);
        g.reset();
        long t = 0L;
        int maxCount = 0;
        for (int i = 0; i < 400; i++) {             // 8 秒 2.5Hz（步频窗口边界）
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 2.5 * sec));
            g.feedAcc(ax, 0f, 0f, t);
            maxCount = Math.max(maxCount, g.confirmedStepCount());
            t += FRAME_MS;
        }
        assertTrue("2.5Hz 抖动在数量上确实能凑满 8 步（所以服务层跨度闸是必需的），"
                + "实际峰值=" + maxCount, maxCount >= 8);

        // 服务层跨度闸等价语义：8 步 / 2800ms 必须判「连续时长不足」
        WalkConfirmPolicy policy = new WalkConfirmPolicy(WalkConfirmPolicy.requiredStepsFor(8));
        List<Long> ts = new ArrayList<Long>();
        for (int i = 0; i < 8; i++) ts.add(i * 400L);
        WalkConfirmPolicy.Verdict v = policy.evaluate(false, 8, ts, 2_900L);
        assertFalse("3 秒内连抖 8 下必须被跨度闸拦下，实际原因=" + v.reason, v.pass);
        assertTrue("应由「连续时长不足」拦下，实际=" + v.reason,
                v.reason.contains("连续时长不足"));
    }

    // ==================== 手电解耦（关键） ====================

    @Test
    public void torchStaticSignal_firesBeforeGate_walkingNeverLooksStatic() {
        // 门槛设成最大档 10：整段 12 秒步行都攒不满 10 步的「闸门放行」，
        // 但只要 onLongStaticDetected(false) 仍在门槛判断之前，静置计时就每步清零，
        // 手电绝不会误判成静置。若调用点被移到门槛之后，这条用例会收到 isStatic=true。
        final boolean[] sawStatic = {false};
        final boolean[] sawNonStatic = {false};
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(10);
        g.setGaitStateCallback(isStatic -> {
            if (isStatic) {
                sawStatic[0] = true;
            } else {
                sawNonStatic[0] = true;
            }
        });
        g.reset();
        long t = 0L;
        for (int i = 0; i < 600; i++) {             // 12 秒 1.5Hz 连续走
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            g.feedAcc(ax, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertTrue("门槛未放行时也必须持续发出「不算静置」信号", sawNonStatic[0]);
        assertFalse("连续走路 12 秒绝不能被判成静置（否则夜间手电会被误关/误提醒）",
                sawStatic[0]);
    }

    @Test
    public void torchStaticSignal_stillFiresWhenReallyStill() {
        // 反向验证：真静置 5 秒必须判静置 —— 说明这条链路本身没被改坏
        final boolean[] sawStatic = {false};
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(8);
        g.setGaitStateCallback(isStatic -> {
            if (isStatic) sawStatic[0] = true;
        });
        g.reset();
        long t = 0L;
        for (int i = 0; i < 300; i++) {             // 6 秒完全静止
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertTrue("真静置超过 3 秒必须判静置（手电静置提醒依赖它）", sawStatic[0]);
    }

    @Test
    public void detectorSource_callsStaticResetBeforeConfirmGate() {
        int resetCall = detectorSrc.indexOf("gaitStateCallback?.onLongStaticDetected(false)");
        int gate = detectorSrc.indexOf("peakIntervals.size >= confirmSteps && axisStable()");
        assertTrue("必须能找到 onLongStaticDetected(false) 调用点", resetCall > 0);
        assertTrue("必须能找到 confirmSteps 门槛", gate > 0);
        assertTrue("手电「不算静置」信号必须在门槛判断之前触发（门槛抬高才不会误伤手电）",
                resetCall < gate);
        // 静置阈值本身不得被本次改动影响
        assertTrue("SENSOR_STATIC_THRESHOLD 必须仍是 3000ms",
                detectorSrc.contains("SENSOR_STATIC_THRESHOLD = 3000L"));
    }

    // ==================== 「死条件」修复的结构契约 ====================

    @Test
    public void sustainedStepsExpressionIsCappedByWindowCap() {
        // 确认步数 8 → 8×2 = 16 > 队列上限 10，不夹上限就是恒不成立的死条件
        int sustainedAtEight = Math.min(WalkConfirmPolicy.WINDOW_CAP, 8 * 2);
        assertEquals("默认档必须夹到队列上限 10", 10, sustainedAtEight);
        assertEquals("必须与抬门槛前（5×2=10）逐位等价，不放松也不收紧",
                10, 5 * 2);

        int capped = countOccurrences(serviceSrc, "Math.min(WalkConfirmPolicy.WINDOW_CAP,");
        int doubled = countOccurrences(serviceSrc, "getConfirmSteps() * 2");
        assertEquals("两处 ×2（hardwareStepConfirm / shouldShowNow）都必须夹上限", 2, doubled);
        assertTrue("每处 ×2 都要有 min(WINDOW_CAP, ...) 包裹，实际出现 " + capped + " 次",
                capped >= 2);
        assertFalse("不得再出现未夹上限的 `>= gait.getConfirmSteps() * 2`",
                serviceSrc.contains(">= gait.getConfirmSteps() * 2"));
    }

    @Test
    public void spanGate_isInstalledBetweenQualifiedAndStreakUpdate() {
        assertTrue("必须存在跨度闸方法定义", serviceSrc.contains("private boolean accelStreamLongEnough()"));
        assertEquals("跨度闸只能有一处调用（定义之外恰好一次）",
                1, countOccurrences(serviceSrc, "accelStreamLongEnough()") - 1);
        String gateCall = "if (qualified && !accelStreamLongEnough())";
        assertTrue("跨度闸必须挂在 qualified 上（数量达标后再看时长）",
                serviceSrc.contains(gateCall));
        // 否决必须发生在「刷新步流时间戳」之前，否则抖动已经污染了 lastStepTs
        assertTrue("跨度闸必须在 qualified 维护步流之前生效",
                serviceSrc.indexOf(gateCall) < serviceSrc.indexOf("if (qualified) {"));
        assertTrue("样本不足 8 条时不得否决（宁可少拦，不可误杀走路）",
                serviceSrc.contains("if (accelStepTs.size() < ACCEL_MIN_SPAN_STEPS) return true;"));
    }

    @Test
    public void walkModeUi_mapsSliderZeroToTwoOntoEightToTenSteps() throws Exception {
        // 滑条改了 max 却没改 ±偏移，就会出现「拖到最右还是 2 步」或「拖到最左变 10 步」。
        // 这里同时锁资源（max=2）与代码（progress+8 / steps-8），两者必须一致。
        String layout = new String(Files.readAllBytes(
                locate("src/main/res/layout/activity_walk_mode.xml")), StandardCharsets.UTF_8);
        int seek = layout.indexOf("seek_gait_steps");
        assertTrue("布局里必须存在 seek_gait_steps", seek > 0);
        String snippet = layout.substring(seek, Math.min(layout.length(), seek + 260));
        assertTrue("滑条上限必须是 2（对应 8~10 三档），实际片段=" + snippet,
                snippet.contains("android:max=\"2\""));

        String activity = new String(Files.readAllBytes(
                locate("src/main/java/io/github/wumisc/safewalk/WalkModeActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue("读回默认必须是 8", activity.contains("SafeWalkPrefKey.gaitConfirmSteps, 8"));
        assertTrue("读回必须把历史值夹到 8 以上", activity.contains("Math.max(8, Math.min(10, steps))"));
        assertTrue("滑条位置 = 步数 - 8", activity.contains("seekGaitSteps.setProgress(steps - 8)"));
        assertTrue("提交值 = 滑条位置 + 8", activity.contains("getProgress() + 8"));
        assertTrue("文案必须显示真实步数（progress + 8）", activity.contains("(progress + 8)"));
    }

    // ==================== 波形工具 ====================

    /**
     * 喂正弦波形，返回是否在 maxMs 内触发（feedAcc 返回 true）。
     *
     * @param freqHz 步频（Hz），一次上穿 = 一步
     * @param amp    线性加速度幅值（m/s²）
     */
    private static boolean walkTriggers(double freqHz, double amp, long maxMs) {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(8);
        g.reset();
        long t = 0L;
        long frames = maxMs / FRAME_MS;
        for (long i = 0; i < frames; i++) {
            double sec = i * (FRAME_MS / 1000.0);
            float ax = (float) (GRAVITY + amp * Math.sin(2 * Math.PI * freqHz * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) return true;
            t += FRAME_MS;
        }
        return false;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return count;
            count++;
            from = idx + needle.length();
        }
    }

    private static int readInt(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long readLong(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static Path locate(String rel) throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(rel));
        candidates.add(Paths.get("app", rel));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path cwd = Paths.get(userDir);
            candidates.add(cwd.resolve(rel));
            candidates.add(cwd.resolve("app").resolve(rel));
            Path parent = cwd.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("app").resolve(rel));
                candidates.add(parent.resolve(rel));
            }
        }
        List<String> tried = new ArrayList<String>();
        for (Path p : candidates) {
            tried.add(p.toAbsolutePath().toString());
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return p;
            }
        }
        throw new IOException("找不到源码 " + rel + "，已尝试：" + tried);
    }
}
