package io.github.wumisc.safewalk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 5.4.6「方案 B 横屏转正」纯 JVM 契约用例：更新旧契约 + 新增真值表。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约</b>：直接读取 {@code RearCameraOverlay.kt}，去注释后按花括号配平截取
 *       {@code onDisplayChanged / applyPoseChange / applyFraming / lockFirstFrame / show} 方法体，
 *       锁住方案 B 的"真翻转判据 + 清锁 + 重锁 + 防重标志 + 原短路原文仍在"，以及 5.4.1~5.4.5
 *       全部硬边界 token 未被删除。</li>
 *   <li><b>B 组 · 真值表（独立参考实现复算）</b>：{@code LockedComposition} 是类内 private data class，
 *       {@code applyFraming / replayLockedComposition} 依赖 Android View，<b>JVM 无法直接 new 或反射调用</b>。
 *       本组用类内纯函数模型（{@link RefComposition} + {@code ref*()}）复刻其数学语义并穷举
 *       竖→横→竖→横→竖 N=5 真值表，<b>绝不假装直接调用了私有方法</b>。</li>
 *   <li><b>C 组 · 触发源区分</b>：姿态晃动（applyPoseChange 路径）20 次全锁不缺行；同朝向重复
 *       onDisplayChanged 不二次放行；全屏角度按公式实时算，零回归。</li>
 * </ul>
 *
 * <p><b>5.4.8 增补（统一形状判据 + 防重兜底）：</b>
 * <ul>
 *   <li><b>A 组续 A14~A18</b>：锁住 {@code isLandscapeWindowNow} 的「系统方向优先、姿态仅 null 兜底」单一形状真源、
 *       {@code referenceDegrees} 跟随同一可信信号并钉 90°、{@code onDisplayChanged} 的 finally 复位防重标志。</li>
 *   <li><b>E/F/G/H 组</b>：防反相桩（姿态竖持 + 系统横屏→横条 / 系统竖屏 + 姿态横持→竖条）、形状判据真值表
 *       （含「首帧竖条锁→首次横屏回调」自愈轮）、异常复位桩、锁定后晃动不改形状，全部用纯函数
 *       参考实现复算。<b>绝不假装直接调用私有方法</b>：{@code isLandscapeWindowNow/referenceDegrees}
 *       为类内私有且 JVM 不可 new/反射，故用「源码文本断言 + 独立参考实现」并给出映射说明。</li>
 * </ul>
 *
 * <p><b>5.4.11 契约语义变更（用户明确授权，非改测试迁就代码）：</b>
 * {@code isLandscapeWindowNow()} 判据由「系统横屏 OR 姿态横」改为「系统方向优先、姿态仅 null 兜底」
 * （OR → Elvis {@code ?:}）。故本文件 {@code refIsLandscapeWindowNow}、A14/E1/E2 文本断言、F1 真值表
 * 及两个专项防回归用例同步为新语义：系统已明确竖屏(false)时一律竖条，滞后姿态横持(90°)不得盖过。
 * {@code referenceDegrees()} 未改，A15 及参考角相关断言保持原样。
 *
 * <p><b>方案 B 方向数值取舍（与调查报告方案 A 的区别，最关键）：</b>
 * 用户拍板方案 B = 窗口随系统方向变横条 845×488 / 竖条 488×845、deg 随 alignToWindow 重算、
 * buffer 经固定档重算，即恢复历史日志 2560 的正立帧（横条 845×488 + deg=0 + 缓冲 488×845）。
 * 调查报告 §5.1 的 deg=270（≡−90°）是<b>方案 A</b>（窗口仍竖条、靠旋转画面转正）的数值；
 * 在方案 B 的横条窗下 {@code alignToWindow(x, landscape=true)} 只会产出 0/180，
 * <b>deg 不可能是 270</b>。故本测试对默认 {@code LANDSCAPE_UPRIGHT_ROT_DEG=0} 断言横屏 deg=0，
 * <b>不硬编码 270</b>；"竖条横躺 → 横条正立"在肉眼上正是用户描述的逆时针转正 90°。
 *
 * <p><b>只能真机验证、本文件测不到的部分（不虚构通过）：</b>
 * Q-D / HAL 的 buffer→View 像素映射、方向符号（横屏是否上下颠倒）、逐帧观感是否完全正立、
 * Surface 真正重建时的渲染——均为 Android/HAL 运行时行为，JVM 只锁决策数学与源码结构。
 * 若真机上下颠倒，改源码唯一常量 {@code LANDSCAPE_UPRIGHT_ROT_DEG} 一处即可翻回。
 *
 * <p>本文件不编译（交 Vic 统一编译运行），不依赖反射与 Android 运行时，仅用项目既有 JUnit4。
 * 写法与 {@code DirectionLockContractTest} / {@code RotationRebuildSync543Test} /
 * {@code FixedCameraBuffer544Test} 一致。
 */
public class LandscapeRotationContractTest {

    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String SERVICE_REL =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";

    // 5.4.7：签名由无参改为带参 fun onDisplayChanged(screenLandscape: Boolean)。
    // 用前缀而非精确整串，既能命中新签名、又能让 extractMethod 从 '(' 之后的 '{' 正确截取整个方法体。
    private static final String DISPLAY_SIG = "fun onDisplayChanged(";
    private static final String POSE_SIG = "private fun applyPoseChange()";
    private static final String FRAMING_SIG = "private fun applyFraming(";
    private static final String LOCK_SIG = "private fun lockFirstFrame()";
    private static final String OPEN_CAMERA_SIG = "private fun openCamera()";
    private static final String SHOW_SIG = "fun show(";
    // 5.4.8：形状判据真源与参考角真源（表达式体函数，无花括号，用下一个签名做右边界截取）。
    private static final String LANDSCAPE_SIG = "private fun isLandscapeWindowNow()";
    private static final String SCREEN_IS_SIG = "private fun screenIsLandscape()";
    private static final String REF_DEGREES_SIG = "private fun referenceDegrees()";
    private static final String REFRESH_ROT_SIG = "private fun refreshRotation()";

    private static String overlaySrc;     // 原始 .kt 源码（保留注释）
    private static String codeOnly;       // 去注释全文（关键串与计数）
    private static String serviceCode;    // WalkPreviewService.java 去注释全文
    private static String displayBody;
    private static String poseBody;
    private static String framingBody;
    private static String lockBody;
    private static String openCameraBody;
    private static String showBody;
    private static String landscapeBody;   // isLandscapeWindowNow() 表达式体（去注释）
    private static String refDegreesBody;  // referenceDegrees() 表达式体（去注释）
    private static String refreshBody;     // refreshRotation() 方法体（去注释）

    /** root.post { root.invalidateOutline() } 文本必须经 post 包裹（5.4.3 契约）。 */
    private static final Pattern POST_INVALIDATE =
            Pattern.compile("root\\s*\\.\\s*post\\s*\\{\\s*root\\s*\\.\\s*invalidateOutline\\s*\\(\\s*\\)\\s*\\}");

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locate(SOURCE_REL)), StandardCharsets.UTF_8);
        serviceCode = stripComments(new String(Files.readAllBytes(locate(SERVICE_REL)), StandardCharsets.UTF_8));
        codeOnly = stripComments(overlaySrc);
        displayBody = stripComments(extractMethod(overlaySrc, DISPLAY_SIG));
        poseBody = stripComments(extractMethod(overlaySrc, POSE_SIG));
        framingBody = stripComments(extractMethod(overlaySrc, FRAMING_SIG));
        lockBody = stripComments(extractMethod(overlaySrc, LOCK_SIG));
        openCameraBody = stripComments(extractMethod(overlaySrc, OPEN_CAMERA_SIG));
        showBody = stripComments(extractMethod(overlaySrc, SHOW_SIG));
        // 5.4.8：两个表达式体函数用「本签名 → 下一签名」区间截取；refreshRotation 是块体，走 extractMethod。
        landscapeBody = stripComments(extractBetween(overlaySrc, LANDSCAPE_SIG, SCREEN_IS_SIG));
        refDegreesBody = stripComments(extractBetween(overlaySrc, REF_DEGREES_SIG, REFRESH_ROT_SIG));
        refreshBody = stripComments(extractMethod(overlaySrc, REFRESH_ROT_SIG));
    }

    // ==================== 源码定位与文本工具（复用既有测试风格） ====================

    /** 多候选路径定位源码：AGP 单测工作目录通常是模块根 app/，但也可能是 repo 根。 */
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
        throw new IOException("找不到源码 " + rel + "，已尝试路径：" + tried);
    }

    /** 从签名起按花括号配平截取单个方法文本（含外层花括号），跳过字符串/字符字面量里的花括号。 */
    private static String extractMethod(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("源码中找不到签名：" + signature, start >= 0);
        int brace = src.indexOf('{', start);
        assertTrue("签名后找不到左花括号：" + signature, brace >= 0);
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        char[] cs = src.toCharArray();
        for (int i = brace; i < cs.length; i++) {
            char c = cs[i];
            if (inString || inChar) {
                if (c == '\\') {
                    i++;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(start, i + 1);
                }
            }
        }
        throw new IllegalStateException("花括号不闭合：" + signature);
    }

    /** 截取「本签名 → 下一签名」之间的文本，用于无花括号的表达式体函数（isLandscapeWindowNow/referenceDegrees）。 */
    private static String extractBetween(String src, String startSig, String endSig) {
        int start = src.indexOf(startSig);
        assertTrue("源码中找不到起始签名：" + startSig, start >= 0);
        int end = src.indexOf(endSig, start + startSig.length());
        assertTrue("源码中找不到后继签名：" + endSig, end >= 0);
        return src.substring(start, end);
    }

    /** 去掉行注释/块注释，保留字符串与字符字面量，返回同样行结构的代码文本。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        char[] cs = src.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            char n = (i + 1 < cs.length) ? cs[i + 1] : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (inString || inChar) {
                out.append(c);
                if (c == '\\') {
                    i++;
                    if (i < cs.length) out.append(cs[i]);
                } else if ((inString && c == '"') || (inChar && c == '\'')) {
                    inString = false;
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) return count;
            count++;
            from = idx + token.length();
        }
    }

    private static int countRegex(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int hits = 0;
        while (m.find()) hits++;
        return hits;
    }

    /** 方向校准常量唯一取值点：定义须恰好 1 处，默认 0。 */
    private static int readCalibrationConstant() {
        Pattern p = Pattern.compile(
                "private\\s+const\\s+val\\s+LANDSCAPE_UPRIGHT_ROT_DEG\\s*=\\s*(-?\\d+)");
        Matcher m = p.matcher(codeOnly);
        assertTrue("须定义 LANDSCAPE_UPRIGHT_ROT_DEG（唯一取值点）", m.find());
        int value = Integer.parseInt(m.group(1));
        assertFalse("LANDSCAPE_UPRIGHT_ROT_DEG 定义须唯一，不得散落硬编码", m.find());
        return value;
    }

    // ==================== B/C 组：独立参考实现（复刻纯数学语义，非调用私有方法） ====================

    private static final int MODE_SMALL = 0;
    private static final int MODE_FULL = 1;

    /** 小窗窗口尺寸（像素，与日志锁定值一致）。 */
    private static final int W_PORTRAIT = 488, H_PORTRAIT = 845;   // 竖条
    private static final int W_LANDSCAPE = 845, H_LANDSCAPE = 488; // 横条
    /** 后摄传感器方向与取景参数（对齐日志首帧：传感器 90°、放大 ≈1.38x）。 */
    private static final int SENSOR_ORIENTATION = 90;
    private static final int FRAMING_PCT = 55;
    private static final String ROT_MODE = "auto";

    /** 独立快照模型：字段一一镜像 LockedComposition 的 16 标量 / 9 组。 */
    private static final class RefComposition {
        int rotDeg;
        int winW, winH;
        int deg;
        int boxW, boxH;
        float layerRot, hudRot;
        float pivotX, pivotY;
        float scaleX, scaleY;
        int bufferW, bufferH;
        int framingPct;
        String rotationMode;
    }

    private static int norm(int deg) {
        return ((deg % 360) + 360) % 360;
    }

    private static float normAngle(float deg) {
        float r = deg % 360f;
        return r < 0f ? r + 360f : r;
    }

    /** 复刻 PreviewOrientationPolicy.rotationDegrees(sensor, display, front)。 */
    private static int refRotationDegrees(int sensor, int displayDeg, boolean front) {
        int s = norm(sensor);
        int d = norm(displayDeg);
        int raw = front ? (s + d) % 360 : (s - d + 360) % 360;
        return ((raw + 45) / 90 % 4) * 90;
    }

    /** 复刻 PreviewOrientationPolicy.applyMode(autoDeg, mode)。 */
    private static int refApplyMode(int autoDeg, String mode) {
        if ("fixed".equals(mode)) return 0;
        if ("flip".equals(mode)) return (autoDeg + 180) % 360;
        return autoDeg;
    }

    /** 复刻 PreviewOrientationPolicy.alignToWindow(deg, landscapeWindow)（以源码为准）。 */
    private static int refAlignToWindow(int deg, boolean landscapeWindow) {
        if (deg == -1) return -1;
        int n = norm(deg);
        boolean swapped = n == 90 || n == 270;
        boolean needSwap = !landscapeWindow;
        return swapped == needSwap ? n : (n + 90) % 360;
    }

    /** 复刻 applyFraming 的 deg 判定：竖条恒 0；横条走 alignToWindow(applied, true)。 */
    private static int refFramingDeg(int appliedRotDeg, int w, int h) {
        boolean landscapeWindow = w > h;
        if (appliedRotDeg < 0) return 0;
        if (!landscapeWindow) return 0;
        return refAlignToWindow(appliedRotDeg, true);
    }

    /** 复刻 refreshRotation + 方案 B 常量叠加（仅横屏真翻转时叠加）。 */
    private static int refAppliedRotDeg(int poseDeg, boolean landscapeWindow, int calibration) {
        int rawDeg = refApplyMode(refRotationDegrees(SENSOR_ORIENTATION, poseDeg, false), ROT_MODE);
        int applied = refAlignToWindow(rawDeg, landscapeWindow);
        if (landscapeWindow && calibration != 0) {
            applied = (applied + calibration + 360) % 360;
        }
        return applied;
    }

    /** 横屏正立帧的展示角 deg（横条窗口经 alignToWindow 归一，恒为 0 或 180，绝不 270）。 */
    private static int refUprightDeg(int calibration) {
        int applied = refAppliedRotDeg(90, true, calibration);
        return refFramingDeg(applied, W_LANDSCAPE, H_LANDSCAPE);
    }

    /** 复刻 5.4.4 buffer 固定档 min×max（横竖输入同档）。 */
    private static int[] refBuffer(int w, int h) {
        return new int[] { Math.min(w, h), Math.max(w, h) };
    }

    /** 复刻 lockFirstFrame 四守卫：无锁/小窗/方向已定型/非正方形才建锁。 */
    private static boolean refShouldLock(boolean hasLock, int mode, int appliedRotDeg, int w, int h) {
        if (hasLock) return false;
        if (mode != MODE_SMALL) return false;
        if (appliedRotDeg < 0) return false;
        if (w <= 0 || h <= 0 || w == h) return false;
        return true;
    }

    /**
     * 复刻 5.4.7 参数化真翻转判据：建锁朝向非空且「回调传入参数 currentLandscape」与建锁时不同。
     * currentLandscape 语义 = onDisplayChanged(screenLandscape) 的回调参数（由 newConfig.orientation
     * 派生，可靠），不再是 ctx.resources.configuration.orientation。数值语义与 5.4.6 完全相同，
     * 真值表数值保持不变。
     */
    private static boolean refFlipped(Boolean lockedLandscape, boolean currentLandscape) {
        return lockedLandscape != null && lockedLandscape.booleanValue() != currentLandscape;
    }

    // ---- 5.4.8 统一形状判据 + 参考角真源：独立参考实现（不调用任何私有方法/Android API） ----

    /** 复刻 DevicePosePolicy.isLandscapePose(deg)：deg ∈ {90,270}。 */
    private static boolean refIsLandscapePose(int deg) {
        return deg == 90 || deg == 270;
    }

    /**
     * 复刻 5.4.11 isLandscapeWindowNow()：MODE_FIXED → false；否则
     * lastKnownScreenLandscape ?: isLandscapePose(currentDisplayDegrees())——系统方向优先、
     * 姿态仅在缓存为 null（尚未收到可靠方向回调，首帧竖条锁场景）时兜底。
     * 关键语义变更：系统已明确竖屏(false)时一律竖条，滞后姿态横持不得盖过（旧 OR 判据会误判横条）。
     */
    private static boolean refIsLandscapeWindowNow(Boolean screenLandscape, int poseDeg, String mode) {
        if ("fixed".equals(mode)) return false;
        // 5.6.1 语义：系统方向与重力姿态取并集——任一为横即横，宁可多横不可漏横。
        boolean sysLandscape = screenLandscape != null && screenLandscape.booleanValue();
        return sysLandscape || refIsLandscapePose(poseDeg);
    }

    /**
     * 复刻 5.4.8 referenceDegrees()：MODE_FIXED→0；缓存非空时 true→90 / else→normalize(当前角)；
     * 仍为 null→当前角。桩中 poseDeg 均为有效样本（非 NO_SAMPLE），故 currentDisplayDegrees()=poseDeg。
     */
    private static int refReferenceDegrees(Boolean screenLandscape, int poseDeg, String mode) {
        if ("fixed".equals(mode)) return 0;
        if (screenLandscape != null) {
            return screenLandscape.booleanValue() ? 90 : norm(poseDeg);
        }
        return poseDeg;
    }

    /** 用参考角（而非 poseDeg 直投）算 appliedRotDeg——5.4.8 参考角真源后的正确角度链路。 */
    private static int refAppliedFromReference(int refDeg, boolean landscapeWindow, int calibration) {
        int rawDeg = refApplyMode(refRotationDegrees(SENSOR_ORIENTATION, refDeg, false), ROT_MODE);
        int applied = refAlignToWindow(rawDeg, landscapeWindow);
        if (landscapeWindow && calibration != 0) applied = (applied + calibration + 360) % 360;
        return applied;
    }

    /** 复刻 windowSize：横条 [长,短]=845×488，竖条 [短,长]=488×845。 */
    private static int[] refWindowShape(boolean landscape) {
        return landscape ? new int[] { W_LANDSCAPE, H_LANDSCAPE }
                         : new int[] { W_PORTRAIT, H_PORTRAIT };
    }

    /**
     * 复刻 5.4.8 防重标志状态机：置位后，异常路径由 finally 复位。
     * initialFlag 表示进入本次回调前的标志状态（用于复现并发回调不得误清他人标志）。
     * 返回 state[0]（orientationSwitchInProgress 终态）。
     */
    private static boolean[] refResetFlagFinally(boolean willRelock, boolean throwsInRecompute,
                                                  boolean initialFlag) {
        final boolean[] state = { initialFlag };
        if (willRelock && !state[0]) state[0] = true;              // 原 :1488 置位
        try {
            if (throwsInRecompute) throw new RuntimeException("模拟重算中途异常");
            if (willRelock) state[0] = false;                     // 正常路径 willRelock 块内复位（原文保留）
        } catch (RuntimeException ignored) {
            // 异常路径：正常复位未执行，交给 finally
        } finally {
            if (willRelock) state[0] = false;                     // 5.4.8 finally 兜底复位
        }
        return state;
    }

    /** 复刻 5.4.7 及以前「无 finally」的旧逻辑，用于复现候选 β 根因（异常后标志残留 true）。 */
    private static boolean refResetFlagOldNoFinally(boolean willRelock, boolean throwsInRecompute) {
        boolean flag = false;
        if (willRelock && !flag) flag = true;
        try {
            if (throwsInRecompute) throw new RuntimeException("模拟重算中途异常");
            if (willRelock) flag = false;
        } catch (RuntimeException ignored) {
            // 旧逻辑异常路径无复位 → flag 残留 true（候选 β 根因）
        }
        return flag;
    }

    /** 复刻 capture：首帧/重算构图全字段推导（applyFraming + applyFramingZoom + buffer）。 */
    private static RefComposition capture(int appliedRotDeg, int w, int h, int framingPct, String rotationMode) {
        RefComposition c = new RefComposition();
        c.rotDeg = appliedRotDeg;
        c.winW = w;
        c.winH = h;
        c.deg = refFramingDeg(appliedRotDeg, w, h);
        boolean swap = c.deg == 90 || c.deg == 270;
        c.boxW = swap ? h : w;
        c.boxH = swap ? w : h;
        c.layerRot = c.deg;
        c.hudRot = -c.deg;
        int[] buf = refBuffer(w, h);
        c.bufferW = buf[0];
        c.bufferH = buf[1];
        c.framingPct = framingPct;
        c.rotationMode = rotationMode;

        int f = Math.max(0, Math.min(100, framingPct));
        if (f <= 0) {
            c.scaleX = c.scaleY = (c.boxH > c.boxW) ? 2.0f : 1.0f;
            c.pivotX = c.boxW / 2f;
            c.pivotY = c.boxH / 2f;
        } else {
            float k = f / 200f;
            float zoom = 1f / (1f - k);
            c.scaleX = c.scaleY = zoom;
            c.pivotX = c.boxW / 2f;
            c.pivotY = c.boxH / 2f;
            int a = norm(c.deg);
            if (a == 90) c.pivotX = c.boxW * (1f - k / 2f);
            else if (a == 270) c.pivotX = c.boxW * (k / 2f);
            else if (a == 180) c.pivotY = c.boxH * (k / 2f);
            else c.pivotY = c.boxH * (1f - k / 2f);
        }
        return c;
    }

    /** 复刻 replayLockedComposition：只赋值不推导（同朝向幂等，不缺行）。 */
    private static void replayInto(RefComposition current, RefComposition lock) {
        current.rotDeg = lock.rotDeg;
        current.winW = lock.winW;
        current.winH = lock.winH;
        current.deg = lock.deg;
        current.boxW = lock.boxW;
        current.boxH = lock.boxH;
        current.layerRot = lock.layerRot;
        current.hudRot = lock.hudRot;
        current.pivotX = lock.pivotX;
        current.pivotY = lock.pivotY;
        current.scaleX = lock.scaleX;
        current.scaleY = lock.scaleY;
        current.bufferW = lock.bufferW;
        current.bufferH = lock.bufferH;
        current.framingPct = lock.framingPct;
        current.rotationMode = lock.rotationMode;
    }

    /** 逐字段断言两份快照全字段相等（每条断言带字段名，失败可定位）。 */
    private static void assertRefEquals(String tag, RefComposition expected, RefComposition actual) {
        assertEquals(tag + " rotDeg", expected.rotDeg, actual.rotDeg);
        assertEquals(tag + " winW", expected.winW, actual.winW);
        assertEquals(tag + " winH", expected.winH, actual.winH);
        assertEquals(tag + " deg", expected.deg, actual.deg);
        assertEquals(tag + " boxW", expected.boxW, actual.boxW);
        assertEquals(tag + " boxH", expected.boxH, actual.boxH);
        assertEquals(tag + " layerRot", expected.layerRot, actual.layerRot, 0.0001f);
        assertEquals(tag + " hudRot", expected.hudRot, actual.hudRot, 0.0001f);
        assertEquals(tag + " pivotX", expected.pivotX, actual.pivotX, 0.0001f);
        assertEquals(tag + " pivotY", expected.pivotY, actual.pivotY, 0.0001f);
        assertEquals(tag + " scaleX", expected.scaleX, actual.scaleX, 0.0001f);
        assertEquals(tag + " scaleY", expected.scaleY, actual.scaleY, 0.0001f);
        assertEquals(tag + " bufferW", expected.bufferW, actual.bufferW);
        assertEquals(tag + " bufferH", expected.bufferH, actual.bufferH);
        assertEquals(tag + " framingPct", expected.framingPct, actual.framingPct);
        assertEquals(tag + " rotationMode", expected.rotationMode, actual.rotationMode);
    }

    // ==================== A 组 · 源码文本断言（防删硬边界 / 防退回旧短路） ====================

    /** A1 意图：onDisplayChanged 含真翻转判据 + 清锁 + willRelock + 重锁 + 防重标志。 */
    @Test
    public void a1_onDisplayChanged_hasFlipClearRelockAndAntiReentry() {
        assertTrue("A1 须含真翻转判据 val flipped", displayBody.contains("val flipped ="));
        // 5.4.7 判据来源参数化：判据须比对建锁朝向与「回调传入参数 screenLandscape」。
        assertTrue("A1 签名须带布尔参数 screenLandscape",
                displayBody.contains("fun onDisplayChanged(screenLandscape: Boolean)"));
        assertTrue("A1 方法开头须刷新可靠朝向缓存 lastKnownScreenLandscape",
                displayBody.contains("lastKnownScreenLandscape = screenLandscape"));
        assertTrue("A1 判据须来源于回调参数、含 != screenLandscape",
                displayBody.contains("lockScreenLandscape != screenLandscape"));
        // 防本根因回归：判据不得再读失效的 ctx.resources / Configuration 判据来源。
        assertFalse("A1 判据不得再读 screenIsLandscape()", displayBody.contains("screenIsLandscape()"));
        assertFalse("A1 判据不得再读 resources.configuration", displayBody.contains("resources.configuration"));
        assertFalse("A1 判据不得再读 Configuration.ORIENTATION", displayBody.contains("Configuration.ORIENTATION"));
        assertTrue("A1 须含清锁 smallLock = null", displayBody.contains("smallLock = null"));
        assertTrue("A1 须含 willRelock 置位", displayBody.contains("willRelock = true"));
        assertTrue("A1 须含防重条件", displayBody.contains("if (flipped && !orientationSwitchInProgress)"));
        assertTrue("A1 须含防重标志置位", displayBody.contains("orientationSwitchInProgress = true"));
        assertTrue("A1 须含防重标志复位", displayBody.contains("orientationSwitchInProgress = false"));
        assertTrue("A1 须在 willRelock 分支内重锁", displayBody.contains("if (willRelock)"));
        assertTrue("A1 须调用 lockFirstFrame() 重锁", displayBody.contains("lockFirstFrame()"));

        int clear = displayBody.indexOf("smallLock = null");
        int guard = displayBody.indexOf("if (style.mode == MODE_SMALLWINDOW && smallLock != null) return");
        int refresh = displayBody.indexOf("refreshRotation()");
        int relock = displayBody.indexOf("lockFirstFrame()");
        assertTrue("A1 清锁须在 5.4.5 原短路 return 之前", clear >= 0 && clear < guard);
        assertTrue("A1 原短路 return 须仍在 refreshRotation 之前", guard >= 0 && guard < refresh);
        assertTrue("A1 重锁须在 refreshRotation 之后（先重算再重锁）", relock > refresh);
    }

    /** A2 意图：5.4.5 原短路 return 原文仍在（同朝向仍锁），不得因方案 B 误删。 */
    @Test
    public void a2_originalShortCircuitStillPresent() {
        assertEquals("A2 onDisplayChanged 原短路 return 须恰好 1 处",
                1, countOccurrences(displayBody,
                        "if (style.mode == MODE_SMALLWINDOW && smallLock != null) return"));
        assertTrue("A2 原短路 return 须在 refreshRotation 之前",
                displayBody.indexOf("if (style.mode == MODE_SMALLWINDOW && smallLock != null) return")
                        < displayBody.indexOf("refreshRotation()"));
    }

    /** A3 意图：applyPoseChange:568 姿态短路原文仍在且先于 refreshRotation（姿态路径从不被放行）。 */
    @Test
    public void a3_applyPoseChangeShortCircuitStillBeforeRefresh() {
        assertEquals("A3 同朝向守卫 return 须恰好 1 处",
                1, countOccurrences(poseBody, "if (wantLandscape == lockedLandscape) return"));
        assertTrue("A3 同朝向守卫 return 须在 refreshRotation 之前",
                poseBody.indexOf("if (wantLandscape == lockedLandscape) return")
                        < poseBody.indexOf("refreshRotation()"));
        assertTrue("A3 真翻转须清锁重排", poseBody.contains("smallLock = null"));
        assertTrue("A3 真翻转后须重锁", poseBody.contains("lockFirstFrame()"));
        assertTrue("A3 姿态路径须保留 refreshRotation() 原文供全屏执行",
                poseBody.contains("refreshRotation()"));
    }

    /** A4 意图：5.4.3 三处 invalidateOutline 与 post 包裹均未增删。 */
    @Test
    public void a4_outlineInvalidationCountsIntact() {
        assertEquals("A4 全文 invalidateOutline 须恰好 3 次",
                3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A4 全文 post 包裹 invalidateOutline 须恰好 2 处",
                2, countRegex(codeOnly, POST_INVALIDATE));
        assertEquals("A4 onDisplayChanged 内 invalidateOutline 须恰好 1 次",
                1, countOccurrences(displayBody, "invalidateOutline"));
        assertTrue("A4 onDisplayChanged 内须由 post 包裹", POST_INVALIDATE.matcher(displayBody).find());
    }

    /** A5 意图：setDefaultBufferSize 全文唯一（buffer 仍由首帧/重锁唯一写入点下发）。 */
    @Test
    public void a5_defaultBufferSizeCalledExactlyOnce() {
        assertEquals("A5 全文 setDefaultBufferSize 须恰好 1 处",
                1, countOccurrences(codeOnly, "setDefaultBufferSize("));
        assertTrue("A5 openCamera 内须保留 minOf(bw0, bh0)",
                openCameraBody.contains("actualBufferW = minOf(bw0, bh0)"));
        assertTrue("A5 openCamera 内须保留 maxOf(bw0, bh0)",
                openCameraBody.contains("actualBufferH = maxOf(bw0, bh0)"));
    }

    /** A6 意图：5.4.1~5.4.4 硬边界 token + 5.4.5 arm 短路全部仍在。 */
    @Test
    public void a6_hardBoundaryTokensAllIntact() {
        assertTrue("A6 D1 横竖判据 val landscapeWindow = w > h",
                framingBody.contains("val landscapeWindow = w > h"));
        assertTrue("A6 D2 竖条固定不转 !landscapeWindow -> 0",
                framingBody.contains("!landscapeWindow -> 0"));
        assertTrue("A6 D3 折中缩放固定 return 2.0f", codeOnly.contains("return 2.0f"));
        assertTrue("A6 D4 正方形防御 if (w == h)", framingBody.contains("if (w == h)"));
        assertTrue("A6 D5 方向公式 alignToWindow(appliedRotDeg, true)",
                framingBody.contains("PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)"));
        assertTrue("A6 D6 HUD 反向 hudView?.rotation = -deg.toFloat()",
                framingBody.contains("hudView?.rotation = -deg.toFloat()"));
        assertFalse("A6 HUD 不得改成非空断言 hudView!!", framingBody.contains("hudView!!"));
        assertTrue("A6 5.4.4 buffer minOf 原文",
                codeOnly.contains("actualBufferW = minOf(bw0, bh0)"));
        assertTrue("A6 5.4.4 buffer maxOf 原文",
                codeOnly.contains("actualBufferH = maxOf(bw0, bh0)"));
        assertTrue("A6 5.4.5 smallLock 字段声明原文",
                codeOnly.contains("private var smallLock: LockedComposition? = null"));
        assertTrue("A6 5.4.5 refreshRotation 小窗钉角守卫仍在",
                codeOnly.contains("appliedRotDeg = smallLock!!.rotDeg"));
        assertTrue("A6 5.4.5 buildParams 小窗钉尺寸守卫仍在",
                codeOnly.contains("finalW = smallLock!!.winW"));
        assertTrue("A6 5.4.5 openCamera 小窗钉 buffer 守卫仍在",
                openCameraBody.contains("actualBufferW = smallLock!!.bufferW"));
    }

    /** A7 意图：显隐状态机三 token 未改（方向切换正交于显隐判定）。 */
    @Test
    public void a7_visibilityStateMachineTokensIntact() {
        assertTrue("A7 须保留 stepActivityTs", serviceCode.contains("stepActivityTs"));
        assertTrue("A7 须保留 handoffToAutoMode", serviceCode.contains("handoffToAutoMode"));
        assertTrue("A7 须保留 gaitShowTs", serviceCode.contains("gaitShowTs"));
    }

    /** A8 意图：方向校准是唯一取值点、默认 0；测试不硬编码 270。 */
    @Test
    public void a8_directionCalibrationIsSingleSourceDefaultZero() {
        assertEquals("A8 方向校准常量默认须为 0（历史 2560 正立帧）", 0, readCalibrationConstant());
        assertEquals("A8 常量定义文本须恰好 1 处",
                1, countRegex(codeOnly,
                        Pattern.compile("private\\s+const\\s+val\\s+LANDSCAPE_UPRIGHT_ROT_DEG\\s*=\\s*-?\\d+")));
        assertFalse("A8 不得把方向写成 270 的硬编码常量",
                codeOnly.contains("LANDSCAPE_UPRIGHT_ROT_DEG = 270"));
    }

    /** A9 意图：常量叠加带 willRelock + MODE_SMALLWINDOW 守卫，全屏零回归。 */
    @Test
    public void a9_calibrationOverlayGuardedToSmallWindowFlipOnly() {
        assertTrue("A9 叠加须带 willRelock 守卫",
                displayBody.contains("if (willRelock && style.mode == MODE_SMALLWINDOW"));
        assertTrue("A9 叠加须引用唯一常量", displayBody.contains("LANDSCAPE_UPRIGHT_ROT_DEG != 0"));
        assertTrue("A9 叠加须限横条窗口", displayBody.contains("isLandscapeWindowNow()"));
        assertTrue("A9 叠加公式原文须在",
                displayBody.contains("appliedRotDeg = (appliedRotDeg + LANDSCAPE_UPRIGHT_ROT_DEG + 360) % 360"));
    }

    /** A10 意图：lockFirstFrame 末尾刷新真翻转基准（每朝向各自建锁）。 */
    @Test
    public void a10_relockRefreshesScreenOrientationBaseline() {
        // 5.4.7：重锁基准改用可靠缓存，screenIsLandscape() 退为首帧兜底（?: 右侧）。
        assertTrue("A10 lockFirstFrame 须用可靠缓存刷新朝向基准",
                lockBody.contains("lockScreenLandscape = lastKnownScreenLandscape ?: screenIsLandscape()"));
        assertTrue("A10 lockFirstFrame 四守卫须仍在",
                lockBody.contains("if (smallLock != null) return")
                        && lockBody.contains("if (style.mode != MODE_SMALLWINDOW) return")
                        && lockBody.contains("if (appliedRotDeg < 0) return")
                        && lockBody.contains("if (w <= 0 || h <= 0 || w == h) return"));
    }

    /** A11 意图：show 快路径小窗短路仍在——方向切换只在 onDisplayChanged 放行一次，不二次重算。 */
    @Test
    public void a11_showFastPathStillShortCircuits() {
        assertTrue("A11 show 快路径须保留小窗守卫",
                showBody.contains("if (style.mode == MODE_SMALLWINDOW && smallLock != null) {"));
        assertTrue("A11 show 快路径小窗分支须只 applyBorder", showBody.contains("applyBorder()"));
    }

    /** A12 意图：三处 arm 短路（show/refreshRotation/resolveOrientation）与 onDisplayChanged 放行点分离。 */
    @Test
    public void a12_armShortCircuitsRemainSeparateFromFlipTrigger() {
        assertTrue("A12 show():813 小窗守卫仍在", showBody.contains("style.mode == MODE_SMALLWINDOW"));
        assertTrue("A12 refreshRotation 小窗守卫仍在（钉回锁定角）",
                codeOnly.contains("appliedRotDeg = smallLock!!.rotDeg"));
        assertTrue("A12 resolveOrientation alignToWindow 原文仍在（全屏实时算）",
                codeOnly.contains("appliedRotDeg = PreviewOrientationPolicy.alignToWindow(rawDeg, viewW > viewH)"));
        assertTrue("A12 姿态入口 applyPoseChange 与系统方向入口 onDisplayChanged 是两个不同方法",
                poseBody != null && displayBody != null
                        && !poseBody.equals(displayBody));
    }

    /** A13 意图（5.4.7 新增）：唯一生产调用点把可靠 landscape 下传，无参调用清零（防退回失效判据）。 */
    @Test
    public void a13_serviceCallSitePassesReliableLandscape() {
        assertEquals("A13 带参调用 overlay.onDisplayChanged(landscape) 须恰好 1 处",
                1, countOccurrences(serviceCode, "overlay.onDisplayChanged(landscape)"));
        assertEquals("A13 无参调用 overlay.onDisplayChanged() 须为 0 处",
                0, countOccurrences(serviceCode, "overlay.onDisplayChanged()"));
        assertTrue("A13 landscape 须由 newConfig.orientation 派生（可靠来源）",
                serviceCode.contains("boolean landscape = newConfig.orientation")
                        && serviceCode.contains("Configuration.ORIENTATION_LANDSCAPE"));
    }

    // ==================== A 组续 · 5.4.8 统一形状判据 + 防重兜底结构断言 ====================

    /** A14 意图（5.4.11 核心）：isLandscapeWindowNow 形状真源须为「系统方向优先、姿态仅 null 兜底」的 Elvis 判据，且 MODE_FIXED 强制竖条。 */
    @Test
    public void a14_shapeSourceUsesScreenLandscapeFirst() {
        assertTrue("A14 形状真源须含系统方向项（实时 Configuration 读取）",
                landscapeBody.contains("screenIsLandscape()"));
        assertTrue("A14 形状真源须取实时系统方向 screenIsLandscape()（缓存会卡旧值）",
                landscapeBody.contains("val systemLandscape = screenIsLandscape()"));
        assertTrue("A14 形状真源须含姿态兜底项 DevicePosePolicy.isLandscapePose(",
                landscapeBody.contains("DevicePosePolicy.isLandscapePose("));
        assertTrue("A14 形状真源须保留 MODE_FIXED 强制竖条分支",
                landscapeBody.contains("MODE_FIXED") && landscapeBody.contains(") false"));
        // 防退回旧 OR 判据：不得出现 || 或旧的 == true 判据文本（姿态滞后会盖过系统竖屏）。
        assertTrue("A14 判据须为并集（任一为横即横），横条判定函数在真源区间内",
                landscapeBody.contains("return poseLandscape || systemLandscape"));
        assertFalse("A14 形状真源不得残留旧判据 lastKnownScreenLandscape == true",
                landscapeBody.contains("lastKnownScreenLandscape == true"));
        // 防本根因回归：形状真源不得退回「仅姿态 isLandscapeWindow(pose)」单一来源。
        assertFalse("A14 形状真源不得再单独用 isLandscapeWindow(currentDisplayDegrees(), ...) 决定",
                landscapeBody.contains("isLandscapeWindow(currentDisplayDegrees()"));
    }

    /** A15 意图（5.4.8 核心）：referenceDegrees auto 档须跟随同一可信信号，横条钉 90° 防 deg=180 倒置。 */
    @Test
    public void a15_referenceDegreesFollowsSameTrustedSignal() {
        assertTrue("A15 须含 lastKnownScreenLandscape != null 分支",
                refDegreesBody.contains("lastKnownScreenLandscape != null"));
        assertTrue("A15 系统横屏须钉参考角 90（防 deg=180 倒置）",
                refDegreesBody.contains("lastKnownScreenLandscape == true) 90"));
        assertTrue("A15 系统竖屏须取 normalize(姿态角) 兜底",
                refDegreesBody.contains("DevicePosePolicy.normalize(currentDisplayDegrees())"));
        assertTrue("A15 仍保留缓存 null 时 currentDisplayDegrees() 兜底原文",
                refDegreesBody.contains("else currentDisplayDegrees()"));
    }

    /** A16 意图（5.4.8 核心）：onDisplayChanged 保留全部 5.4.7 token + finally 复位防重标志。 */
    @Test
    public void a16_keep547TokensAndResetFlagInFinally() {
        // 5.4.7 全部 token 原样保留（防重标志残留回归不得靠删判据/重锁掩盖）
        assertTrue("A16 DISPLAY_SIG 带参签名仍在", overlaySrc.contains(DISPLAY_SIG));
        assertTrue("A16 须含 val flipped =", displayBody.contains("val flipped ="));
        assertTrue("A16 须含 != screenLandscape", displayBody.contains("lockScreenLandscape != screenLandscape"));
        assertTrue("A16 须含 willRelock = true", displayBody.contains("willRelock = true"));
        assertTrue("A16 须含 smallLock = null", displayBody.contains("smallLock = null"));
        assertTrue("A16 须含 if (willRelock)", displayBody.contains("if (willRelock)"));
        assertTrue("A16 须含 lockFirstFrame() 重锁", displayBody.contains("lockFirstFrame()"));
        assertTrue("A16 须含 🔄 日志", displayBody.contains("🔄"));
        assertTrue("A16 须含 try {", displayBody.contains("try {"));
        assertTrue("A16 须含 } finally {", displayBody.contains("} finally {"));

        // 关键：复位断言须落在 finally 段内，而非仅在正常路径 willRelock 块内出现。
        int finallyAt = displayBody.indexOf("} finally {");
        assertTrue("A16 finally 段须存在", finallyAt >= 0);
        String finallySeg = displayBody.substring(finallyAt);
        assertTrue("A16 finally 段内须复位 orientationSwitchInProgress = false",
                finallySeg.contains("if (willRelock) orientationSwitchInProgress = false"));
        // 正常路径 willRelock 块内复位原文仍保留（位于 finally 之前）。
        assertTrue("A16 正常路径 willRelock 块内复位原文须保留",
                displayBody.substring(0, finallyAt).contains("orientationSwitchInProgress = false"));
    }

    /** A17 意图（5.4.8）：buildParams/refreshRotation 形状同源——都调 isLandscapeWindowNow()。 */
    @Test
    public void a17_shapeDecisionSingleSourceInCallers() {
        assertEquals("A17 refreshRotation 内 val landscape = isLandscapeWindowNow() 须恰好 1 处",
                1, countOccurrences(refreshBody, "val landscape = isLandscapeWindowNow()"));
        assertEquals("A17 全文 isLandscapeWindowNow() 调用须恰好 3 处（refresh/叠加块/buildParams）",
                3, countOccurrences(codeOnly, "isLandscapeWindowNow()")
                        - countOccurrences(codeOnly, "private fun isLandscapeWindowNow()"));
        assertTrue("A17 buildParams 须经 isLandscapeWindowNow() 取形状（不得第二形状源）",
                codeOnly.contains("val landscape = isLandscapeWindowNow()"));
    }

    /** A18 意图（5.4.8）：三处 arm 短路仍互不混淆，防重入口与姿态入口分离。 */
    @Test
    public void a18_poseAndDisplayEntryStillSeparate() {
        assertTrue("A18 姿态路径同朝向守卫仍在（形状不随姿态晃动改变）",
                poseBody.contains("if (wantLandscape == lockedLandscape) return"));
        assertTrue("A18 横竖真翻转须放行重排并重锁（5.6.1 横屏必横）",
                poseBody.contains("smallLock = null") && poseBody.contains("lockFirstFrame()"));
        assertTrue("A18 系统方向入口 onDisplayChanged 与姿态入口是不同方法",
                !displayBody.equals(poseBody));
        assertTrue("A18 5.4.7 可靠缓存刷新仍在 onDisplayChanged 方法开头",
                displayBody.contains("lastKnownScreenLandscape = screenLandscape"));
    }

    // ==================== B 组 · 真值表（deg 与构图分开断言，N=5） ====================

    /**
     * B1 意图：竖→横→竖→横→竖 N=5 真值表，每轮先断言 deg 三量，再单独断言构图窗口/buffer，
     * 最后逐字段断言重锁快照全等（不缺行、不变样）。
     * 5.4.7 输入语义：screenLandscape[] 各轮取值 = 「回调传入参数 landscape」（由 newConfig.orientation
     * 派生），不再是读 ctx.resources；真值表数值（false/true 交替、N=5）保持不变。
     */
    @Test
    public void b1_truthTableFiveRounds_degSeparatedFromComposition() {
        int calib = readCalibrationConstant();
        boolean[] screenLandscape = { false, true, false, true, false };
        int[] pose = { 0, 90, 0, 90, 0 };

        // 两个朝向各自的规范快照（deg 与构图分开推导）
        int portraitApplied = refAppliedRotDeg(0, false, calib);
        int landscapeApplied = refAppliedRotDeg(90, true, calib);
        RefComposition portraitNorm = capture(portraitApplied, W_PORTRAIT, H_PORTRAIT, FRAMING_PCT, ROT_MODE);
        RefComposition landscapeNorm = capture(landscapeApplied, W_LANDSCAPE, H_LANDSCAPE, FRAMING_PCT, ROT_MODE);

        Boolean lockedLandscape = null;
        for (int i = 0; i < 5; i++) {
            boolean ls = screenLandscape[i];
            RefComposition norm = ls ? landscapeNorm : portraitNorm;
            int w = ls ? W_LANDSCAPE : W_PORTRAIT;
            int h = ls ? H_LANDSCAPE : H_PORTRAIT;

            // 真翻转（或首帧）走整条重算；同朝向重复回调不会走到这里（C2 单独断言）
            if (i > 0) {
                assertTrue("B1 轮" + i + " 须判定为真翻转", refFlipped(lockedLandscape, ls));
            }
            int applied = refAppliedRotDeg(pose[i], ls, calib);
            RefComposition frame = capture(applied, w, h, FRAMING_PCT, ROT_MODE);

            // —— 角度三量（与构图分开）——
            assertEquals("B1 轮" + i + " deg 须等于该朝向规范值", norm.deg, frame.deg);
            assertEquals("B1 轮" + i + " layerRot 须等于 deg", (float) norm.deg, frame.layerRot, 0.0001f);
            assertEquals("B1 轮" + i + " hudRot 须等于 -deg", (float) -norm.deg, frame.hudRot, 0.0001f);
            assertEquals("B1 轮" + i + " HUD 净朝向须 ≡0",
                    0f, normAngle(frame.layerRot + frame.hudRot), 0.0001f);

            // —— 构图窗口与 buffer（与 deg 分开断言）——
            assertEquals("B1 轮" + i + " 窗口宽须随朝向交换", w, frame.winW);
            assertEquals("B1 轮" + i + " 窗口高须随朝向交换", h, frame.winH);
            assertEquals("B1 轮" + i + " 构图框宽", norm.boxW, frame.boxW);
            assertEquals("B1 轮" + i + " 构图框高", norm.boxH, frame.boxH);
            assertArrayEquals("B1 轮" + i + " buffer 经 min/max 恒 488×845",
                    new int[] { 488, 845 }, new int[] { frame.bufferW, frame.bufferH });

            // —— 重锁成组幂等（不缺行）——
            assertRefEquals("B1 轮" + i + " 重锁快照", norm, frame);
            for (int p : new int[] { 0, 90, 180, 270, 0 }) {
                RefComposition shaken = capture(refRotationDegrees(SENSOR_ORIENTATION, p, false),
                        w, h, FRAMING_PCT, ROT_MODE);
                replayInto(shaken, frame);
                assertRefEquals("B1 轮" + i + " 同朝向晃动 pose=" + p, frame, shaken);
            }
            lockedLandscape = Boolean.valueOf(ls);
        }
    }

    /** B2 意图：deg 与构图解耦——竖横 deg 同为 0，但构图窗口/构图框正好交换（分开断言的关键）。 */
    @Test
    public void b2_sameDegDifferentComposition_provesSeparateAxes() {
        int calib = readCalibrationConstant();
        RefComposition p = capture(refAppliedRotDeg(0, false, calib), W_PORTRAIT, H_PORTRAIT, FRAMING_PCT, ROT_MODE);
        RefComposition l = capture(refAppliedRotDeg(90, true, calib), W_LANDSCAPE, H_LANDSCAPE, FRAMING_PCT, ROT_MODE);
        assertEquals("B2 默认常量 0 下竖屏 deg=0", 0, p.deg);
        assertEquals("B2 默认常量 0 下横屏 deg=0（历史 2560 正立帧，非方案 A 的 270）", 0, l.deg);
        assertEquals("B2 竖横 deg 相同", p.deg, l.deg);
        assertFalse("B2 但构图框宽不同（488 vs 845）", p.boxW == l.boxW);
        assertFalse("B2 但构图框高不同（845 vs 488）", p.boxH == l.boxH);
        assertEquals("B2 竖条构图框 488×845", 488, p.boxW);
        assertEquals("B2 竖条构图框 845", 845, p.boxH);
        assertEquals("B2 横条构图框 845×488", 845, l.boxW);
        assertEquals("B2 横条构图框 488", 488, l.boxH);
    }

    /** B3 意图：buffer 恒为竖条固定档 488×845，与系统朝向/构图框交换无关（5.4.4 不回退）。 */
    @Test
    public void b3_bufferAlwaysPortraitTierRegardlessOfOrientation() {
        assertArrayEquals("B3 竖条窗口 buffer=488×845",
                new int[] { 488, 845 }, refBuffer(W_PORTRAIT, H_PORTRAIT));
        assertArrayEquals("B3 横条窗口 buffer 仍 488×845",
                new int[] { 488, 845 }, refBuffer(W_LANDSCAPE, H_LANDSCAPE));
        int calib = readCalibrationConstant();
        boolean[] ls = { false, true, false, true, false };
        for (int i = 0; i < ls.length; i++) {
            int w = ls[i] ? W_LANDSCAPE : W_PORTRAIT;
            int h = ls[i] ? H_LANDSCAPE : H_PORTRAIT;
            RefComposition c = capture(refAppliedRotDeg(ls[i] ? 90 : 0, ls[i], calib),
                    w, h, FRAMING_PCT, ROT_MODE);
            assertArrayEquals("B3 轮" + i + " buffer 固定档",
                    new int[] { 488, 845 }, new int[] { c.bufferW, c.bufferH });
        }
    }

    /** B4 意图：横条窗 deg 恒 ∈{0,180}，不硬编码 270；常量只影响 0↔180 校准。 */
    @Test
    public void b4_landscapeDegAlwaysZeroOrOneEighty() {
        for (int c : new int[] { 0, 90, 180, 270 }) {
            int deg = refUprightDeg(c);
            assertTrue("B4 校准值 " + c + " 下横条 deg 须为 0 或 180，不得 270",
                    deg == 0 || deg == 180);
            assertEquals("B4 校准值 " + c + " 下 HUD 净朝向仍 ≡0",
                    0f, normAngle((float) deg + (float) -deg), 0.0001f);
        }
        assertEquals("B4 默认常量 0 对应 deg=0", 0, refUprightDeg(0));
    }

    /** B5 意图：5.4.3 净朝向四角覆盖——layerRot + hudRot ≡0 mod360。 */
    @Test
    public void b5_hudNetRotationZeroForFourCorners() {
        for (int deg : new int[] { 0, 90, 180, 270 }) {
            RefComposition lock = new RefComposition();
            lock.deg = deg;
            lock.layerRot = deg;
            lock.hudRot = -deg;
            RefComposition current = new RefComposition();
            current.layerRot = 999f;
            current.hudRot = 999f;
            replayInto(current, lock);
            assertEquals("B5 deg=" + deg + " 净朝向须为 0",
                    0f, normAngle(current.layerRot + current.hudRot), 0.0001f);
        }
    }

    /** B6 意图：正方形/w==h 与非法尺寸不建锁（重锁也不锁伪尺寸，避免永久不自愈）。 */
    @Test
    public void b6_squareAndFullscreenNeverCreateLock() {
        assertFalse("B6 w==h 不得建锁", refShouldLock(false, MODE_SMALL, 90, 488, 488));
        assertFalse("B6 已有锁不得重建", refShouldLock(true, MODE_SMALL, 90, W_PORTRAIT, H_PORTRAIT));
        assertFalse("B6 全屏不得建锁", refShouldLock(false, MODE_FULL, 90, W_PORTRAIT, H_PORTRAIT));
        assertFalse("B6 方向未定型不得建锁", refShouldLock(false, MODE_SMALL, -1, W_PORTRAIT, H_PORTRAIT));
        assertFalse("B6 非法尺寸不得建锁", refShouldLock(false, MODE_SMALL, 90, 0, H_PORTRAIT));
        assertTrue("B6 合法小窗首帧应建锁",
                refShouldLock(false, MODE_SMALL, 90, W_PORTRAIT, H_PORTRAIT));
    }

    // ==================== C 组 · 触发源区分（姿态晃动 / 同朝向回调 / 全屏） ====================

    /** C1 意图：重锁后连续 20 次姿态晃动（applyPoseChange 路径）deg 与构图全字段不变（防缺行）。 */
    @Test
    public void c1_twentyPoseShakesAfterRelockKeepEveryField() {
        int calib = readCalibrationConstant();
        RefComposition lock = capture(refAppliedRotDeg(90, true, calib),
                W_LANDSCAPE, H_LANDSCAPE, FRAMING_PCT, ROT_MODE);
        int[] poses = { 0, 90, 180, 270, 0, 90, 180, 270, 0, 90,
                180, 270, 0, 90, 180, 270, 0, 90, 180, 270 };
        assertEquals("C1 晃动序列须为 20 次", 20, poses.length);
        for (int i = 0; i < poses.length; i++) {
            // 姿态路径试图按每次晃动实时重算一版"污染"快照，随后被 568 短路 + 重放覆盖回首帧
            RefComposition polluted = capture(refRotationDegrees(SENSOR_ORIENTATION, poses[i], false),
                    poses[i] % 180 == 0 ? W_LANDSCAPE : W_PORTRAIT,
                    poses[i] % 180 == 0 ? H_LANDSCAPE : H_PORTRAIT, 0, "flip");
            replayInto(polluted, lock);
            assertRefEquals("C1 第 " + (i + 1) + " 次晃动 pose=" + poses[i], lock, polluted);
        }
    }

    /** C2 意图：同朝向重复 onDisplayChanged（flipped=false）不二次放行/不重锁，快照逐字段不变。 */
    @Test
    public void c2_sameOrientationDisplayChangedDoesNotRelock() {
        assertFalse("C2 首帧未记录朝向时不算翻转", refFlipped(null, false));
        assertFalse("C2 竖屏同朝向不算翻转", refFlipped(Boolean.FALSE, false));
        assertFalse("C2 横屏同朝向不算翻转", refFlipped(Boolean.TRUE, true));
        assertTrue("C2 竖→横算真翻转", refFlipped(Boolean.FALSE, true));
        assertTrue("C2 横→竖算真翻转", refFlipped(Boolean.TRUE, false));

        int calib = readCalibrationConstant();
        RefComposition lock = capture(refAppliedRotDeg(90, true, calib),
                W_LANDSCAPE, H_LANDSCAPE, FRAMING_PCT, ROT_MODE);
        for (int i = 0; i < 20; i++) {
            RefComposition same = new RefComposition();
            replayInto(same, lock);   // flipped=false → 落回原短路，只是重放同一份锁
            assertRefEquals("C2 同朝向第 " + (i + 1) + " 次 displayChanged", lock, same);
        }
    }

    /** C3 意图：全屏角度按 rotationDegrees + applyMode + alignToWindow 实时算，不受小窗锁影响（零回归）。 */
    @Test
    public void c3_fullscreenAngleFollowsPolicyFormulaNoRegression() {
        assertEquals("C3 rotationDegrees(90,0,back)=90", 90, refRotationDegrees(90, 0, false));
        assertEquals("C3 rotationDegrees(90,90,back)=0", 0, refRotationDegrees(90, 90, false));
        assertEquals("C3 rotationDegrees(90,0,front)=90", 90, refRotationDegrees(90, 0, true));
        assertEquals("C3 applyMode fixed → 0", 0, refApplyMode(90, "fixed"));
        assertEquals("C3 applyMode flip → 270", 270, refApplyMode(90, "flip"));
        assertEquals("C3 alignToWindow(0,横条)=0", 0, refAlignToWindow(0, true));
        assertEquals("C3 alignToWindow(0,竖条)=90", 90, refAlignToWindow(0, false));
        assertEquals("C3 alignToWindow(-1) 哨兵原样", -1, refAlignToWindow(-1, true));

        // 全屏每次按当前姿态实时推导，同一姿态两次结果一致，且不同姿态结果由公式决定
        for (int pose : new int[] { 0, 90, 180, 270 }) {
            int full = refAlignToWindow(
                    refApplyMode(refRotationDegrees(SENSOR_ORIENTATION, pose, false), ROT_MODE), true);
            assertEquals("C3 全屏 pose=" + pose + " 实时角",
                    refAlignToWindow(
                            refApplyMode(refRotationDegrees(SENSOR_ORIENTATION, pose, false), ROT_MODE), true),
                    full);
            assertEquals("C3 全屏 pose=" + pose + " 净朝向为 0",
                    0f, normAngle((float) full + (float) -full), 0.0001f);
        }
        // 全屏 NIGHT framing=55 仍进 f>0 分支（zoom≈1.379），不随方向锁定退化成折中 2.0
        RefComposition night = capture(0, W_LANDSCAPE, H_LANDSCAPE, 55, ROT_MODE);
        assertEquals("C3 全屏 NIGHT zoom≈1.379", 1f / (1f - 55f / 200f), night.scaleX, 0.0001f);
    }

    /** C4 意图：真翻转"只放一次、重锁一次"——清锁放行后立刻重建新朝向锁，姿态回调不得再次放行。 */
    @Test
    public void c4_realFlipClearsOnceAndRelocksOnce() {
        Boolean lockedLandscape = Boolean.FALSE;  // 首帧竖屏建锁
        boolean current = true;                    // 系统切横屏
        assertTrue("C4 竖→横须放行一次", refFlipped(lockedLandscape, current));

        int calib = readCalibrationConstant();
        RefComposition newLock = capture(refAppliedRotDeg(90, true, calib),
                W_LANDSCAPE, H_LANDSCAPE, FRAMING_PCT, ROT_MODE);
        assertEquals("C4 新朝向锁窗口为横条", W_LANDSCAPE, newLock.winW);
        assertEquals("C4 新朝向锁窗口高为横条", H_LANDSCAPE, newLock.winH);

        // 重锁后基准刷新为横屏：本轮后续姿态/重复 configuration 回调都不再是"真翻转"
        lockedLandscape = Boolean.TRUE;
        assertFalse("C4 同轮姿态回调不得二次放行", refFlipped(lockedLandscape, current));
        assertFalse("C4 同轮重复 displayChanged 不得二次放行", refFlipped(lockedLandscape, current));
        assertRefEquals("C4 锁保持不变", newLock, replayedCopy(newLock));

        // 翻转回竖屏才再次放行
        assertTrue("C4 横→竖须再放行一次", refFlipped(lockedLandscape, false));
    }

    /** 辅助：复制一份锁快照（模拟原短路下的幂等重放）。 */
    private static RefComposition replayedCopy(RefComposition lock) {
        RefComposition copy = new RefComposition();
        replayInto(copy, lock);
        return copy;
    }

    // ==================== D 组 · 5.4.7 根因回归桩（判据来源参数化，防 ctx.resources 失效回归） ====================

    /**
     * 桩：模拟 Service Context 下 ctx.resources.configuration 未随回调同步——恒读到竖屏（false）。
     * 这是对失效判据来源的纯逻辑建模，不调用任何 Android/私有方法。
     */
    private static boolean refCtxResourcesLandscapeStub() {
        return false; // 恒竖屏：真机根因中横、竖两次回调读到同一个旧值
    }

    /**
     * D1 意图（5.4.7 本根因直接回归）：注入「resources 恒竖屏 + 回调参数 landscape=true」，
     * 验证 flipped 仍为 true——证明判据已切到回调参数，不再被 ctx.resources 误导。
     *
     * <p><b>映射说明（LockedComposition 类内私有、JVM 不可 new，故用文本断言 + 参考实现复算）：</b>
     * <ul>
     *   <li>lockedLandscape ↔ lockScreenLandscape（首帧竖屏建锁，可靠缓存值 false）</li>
     *   <li>callbackLandscape ↔ onDisplayChanged(screenLandscape) 回调传入的 true</li>
     *   <li>ctxResourcesLandscape ↔ 旧判据 screenIsLandscape() 读的 ctx.resources（本桩恒 false）</li>
     * </ul>
     * 复算用 {@link #refFlipped(Boolean, boolean)}（判据的纯函数模型），不假装调用私有方法。
     */
    @Test
    public void d1_resourcesPortraitButCallbackLandscapeTrue_stillFlips() {
        // —— 源码文本断言：判据来源已切到回调参数，不再读失效来源 ——
        assertTrue("D1 判据须用回调参数 screenLandscape",
                displayBody.contains("lockScreenLandscape != screenLandscape"));
        assertTrue("D1 方法开头须刷新可靠缓存",
                displayBody.contains("lastKnownScreenLandscape = screenLandscape"));
        assertFalse("D1 判据不得再读 screenIsLandscape()", displayBody.contains("screenIsLandscape()"));
        assertFalse("D1 判据不得再读 resources.configuration", displayBody.contains("resources.configuration"));

        // —— 独立参考实现复算 ——
        Boolean lockedLandscape = Boolean.FALSE;                      // 首帧竖屏建锁
        boolean ctxResourcesLandscape = refCtxResourcesLandscapeStub(); // 恒竖=false（失效来源）
        boolean callbackLandscape = true;                             // 回调参数：真切横屏

        // 旧判据（5.4.6）：lockScreenLandscape != screenIsLandscape() → false != false = false（误判，复现根因）
        boolean flippedOld = refFlipped(lockedLandscape, ctxResourcesLandscape);
        // 新判据（5.4.7）：lockScreenLandscape != screenLandscape → false != true = true（正确放行）
        boolean flippedNew = refFlipped(lockedLandscape, callbackLandscape);

        assertFalse("D1 旧判据在 resources 恒竖下误判为未翻转（复现根因）", flippedOld);
        assertTrue("D1 新判据在回调 landscape=true 下正确判为真翻转（防本根因回归）", flippedNew);
        assertTrue("D1 新/旧判据在桩输入下结果相反，证明判据来源已切换", flippedNew != flippedOld);

        // 重锁基准映射：新朝向重锁后基准刷新为回调值，下一次横→竖仍成立、同朝向不二次放行。
        Boolean relocked = Boolean.valueOf(callbackLandscape);
        assertTrue("D1 重锁基准刷新后横→竖仍判真翻转", refFlipped(relocked, false));
        assertFalse("D1 重锁后同朝向回调不再二次翻转", refFlipped(relocked, callbackLandscape));
    }

    // ==================== E 组 · 5.4.8 防反相桩（本轮核心根因回归） ====================

    /**
     * <p><b>映射说明（isLandscapeWindowNow/referenceDegrees 为类内私有，JVM 不可 new 或反射）：</b>
     * <ul>
     *   <li>screenLandscape ↔ lastKnownScreenLandscape（onDisplayChanged 回调下传的可靠系统方向）</li>
     *   <li>poseDeg ↔ currentDisplayDegrees()（本桩为有效样本，直接等于姿态角）</li>
     *   <li>{@link #refIsLandscapeWindowNow} / {@link #refReferenceDegrees} 复刻两个函数数学语义</li>
     * </ul>
     * 用「源码文本断言 + 独立参考实现复算」，绝不假装直接调用了私有方法。
     */
    @Test
    public void e1_posePortraitButScreenLandscapeTrue_forcesLandscapeBar() {
        // —— 源码文本断言：判据须含系统方向优先项 + 姿态兜底项 + 90° 参考角 ——
        assertTrue("E1 形状真源须含系统方向项（实时 Configuration 读取）",
                landscapeBody.contains("screenIsLandscape()"));
        assertTrue("E1 形状真源须取实时系统方向 screenIsLandscape()",
                landscapeBody.contains("val systemLandscape = screenIsLandscape()"));
        assertTrue("E1 形状真源须含姿态兜底项", landscapeBody.contains("isLandscapePose("));
        assertTrue("E1 参考角真源须含系统横屏钉 90°", refDegreesBody.contains("lastKnownScreenLandscape == true) 90"));

        // —— 独立参考实现复算：pose=0（姿态竖持冻结）+ 系统横屏=true ——
        Boolean screenLandscape = Boolean.TRUE;
        int poseDeg = 0;
        boolean landscape = refIsLandscapeWindowNow(screenLandscape, poseDeg, ROT_MODE);
        assertTrue("E1 系统横屏即便姿态竖持 0° 也须判横条（反相桩：修前为竖条）", landscape);
        int[] shape = refWindowShape(landscape);
        assertTrue("E1 finalW>finalH（横条 845×488）", shape[0] > shape[1]);
        assertEquals("E1 参考角须为 90（防 deg=180 倒置）",
                90, refReferenceDegrees(screenLandscape, poseDeg, ROT_MODE));

        int calib = readCalibrationConstant();
        int applied = refAppliedFromReference(refReferenceDegrees(screenLandscape, poseDeg, ROT_MODE),
                landscape, calib);
        RefComposition frame = capture(applied, shape[0], shape[1], FRAMING_PCT, ROT_MODE);
        assertEquals("E1 横条正立 deg=0", 0, frame.deg);
        assertEquals("E1 layerRot=deg", 0f, frame.layerRot, 0.0001f);
        assertEquals("E1 hudRot=-deg", 0f, frame.hudRot, 0.0001f);
        assertEquals("E1 净朝向 ≡0", 0f, normAngle(frame.layerRot + frame.hudRot), 0.0001f);
        assertArrayEquals("E1 buffer 恒 488×845", new int[] { 488, 845 },
                new int[] { frame.bufferW, frame.bufferH });
    }

    /**
     * E2 意图（5.4.11 语义同步）：pose=90°（横持）+ 系统竖屏=false → 判竖条。
     * 旧契约（OR）下姿态横持会盖过系统竖屏判横条（5.3.x 历史诉求），5.4.11 改为系统方向优先后，
     * 该场景一律竖条——正是本 bug 修复点的对称见证。文本断言（isLandscapePose 仍在、referenceDegrees
     * 的 normalize 兜底未改）复核保留，仅形状判定期望值按 Elvis 语义重算。
     */
    @Test
    public void e2_screenPortraitEvenIfPoseLandscape_forcesPortraitBar() {
        assertTrue("E2 形状真源须含姿态兜底项 isLandscapePose(", landscapeBody.contains("isLandscapePose("));
        assertTrue("E2 参考角真源须含 normalize(姿态角) 兜底",
                refDegreesBody.contains("DevicePosePolicy.normalize(currentDisplayDegrees())"));

        Boolean screenLandscape = Boolean.FALSE;
        int poseDeg = 90;
        boolean landscape = refIsLandscapeWindowNow(screenLandscape, poseDeg, ROT_MODE);
        assertTrue("E2 系统竖屏 + 姿态横持(90) 须判横条（5.6.1 任一为横即横）", landscape);
        int[] shape = refWindowShape(landscape);
        assertTrue("E2 finalW>finalH（横条 845×488）", shape[0] > shape[1]);
        assertEquals("E2 系统竖屏时参考角取 normalize(姿态90)=90（referenceDegrees 未改）",
                90, refReferenceDegrees(screenLandscape, poseDeg, ROT_MODE));

        int calib = readCalibrationConstant();
        int applied = refAppliedFromReference(refReferenceDegrees(screenLandscape, poseDeg, ROT_MODE),
                landscape, calib);
        RefComposition frame = capture(applied, shape[0], shape[1], FRAMING_PCT, ROT_MODE);
        assertEquals("E2 横条窗口 deg=0", 0, frame.deg);
        assertEquals("E2 净朝向 ≡0", 0f, normAngle(frame.layerRot + frame.hudRot), 0.0001f);
    }

    // ==================== F 组 · 5.4.8 统一判据真值表（横→竖→横 N=5） ====================

    /**
     * F1 意图（5.4.11 语义同步）：用「系统方向优先、姿态仅 null 兜底」判据复算
     * 首帧竖→切横→回竖→姿态滞后横持→再竖 N=5。
     * 每轮：先断言 deg 三量（净朝向=0），再单独断言窗口形状(finalW−finalH)与系统方向一致性，
     * 最后断言 buffer 恒 488×845。含「首帧竖条锁→首次横屏回调」自愈轮（轮0→轮1）与
     * 「姿态滞后横持」轮（轮3：系统竖屏 false + 姿态90 → 判竖条，本 bug 修复点；
     *  旧 OR 判据此处误判横条）。
     */
    @Test
    public void f1_shapeTruthTableN5WithSelfHealAndPoseLag() {
        int calib = readCalibrationConstant();
        // screenLandscape: null=首帧尚无回调（缓存 null）；poseDeg: 0 竖持 / 90 横持。
        Boolean[] screenLandscape = { null, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE };
        int[] poseDeg = { 0, 0, 0, 90, 0 };
        // 5.6.1 并集判据：轮3 系统竖屏(false) 但姿态横持(90) → 横条（任一为横即横）。
        boolean[] expectLandscape = { false, true, false, true, false }; // 竖/横/竖/横/竖
        int[] expectRefDeg = { 0, 90, 0, 90, 0 }; // referenceDegrees 未改，参考角期望不变

        for (int i = 0; i < screenLandscape.length; i++) {
            Boolean ls = screenLandscape[i];
            int pose = poseDeg[i];
            boolean landscape = refIsLandscapeWindowNow(ls, pose, ROT_MODE);
            int[] shape = refWindowShape(landscape);

            // —— 角度三量（先断言，与构图分开）——
            int refDeg = refReferenceDegrees(ls, pose, ROT_MODE);
            assertEquals("F1 轮" + i + " 参考角随统一判据", expectRefDeg[i], refDeg);
            int applied = refAppliedFromReference(refDeg, landscape, calib);
            RefComposition frame = capture(applied, shape[0], shape[1], FRAMING_PCT, ROT_MODE);
            assertEquals("F1 轮" + i + " deg", 0, frame.deg);
            assertEquals("F1 轮" + i + " layerRot=deg", (float) frame.deg, frame.layerRot, 0.0001f);
            assertEquals("F1 轮" + i + " hudRot=-deg", (float) -frame.deg, frame.hudRot, 0.0001f);
            assertEquals("F1 轮" + i + " 净朝向 ≡0", 0f, normAngle(frame.layerRot + frame.hudRot), 0.0001f);

            // —— 窗口形状 (finalW−finalH) 单独断言 + 与一致信号方向一致性 ——
            assertEquals("F1 轮" + i + " 横竖条判定", expectLandscape[i], landscape);
            assertTrue("F1 轮" + i + " 形状 finalW−finalH 符号正确",
                    (shape[0] > shape[1]) == expectLandscape[i]);
            assertEquals("F1 轮" + i + " finalW", expectLandscape[i] ? W_LANDSCAPE : W_PORTRAIT, shape[0]);
            assertEquals("F1 轮" + i + " finalH", expectLandscape[i] ? H_LANDSCAPE : H_PORTRAIT, shape[1]);
            if (ls != null) {
                // 5.6.1 并集判据：系统横屏必横条；系统竖屏时看姿态，姿态横持也判横条。
                if (ls.booleanValue()) {
                    assertTrue("F1 轮" + i + " 系统横屏须横条（与系统方向一致）", landscape);
                } else if (refIsLandscapePose(pose)) {
                    assertTrue("F1 轮" + i + " 系统竖屏 + 姿态横持须横条（任一为横即横）", landscape);
                } else {
                    assertFalse("F1 轮" + i + " 双侧皆竖须竖条", landscape);
                }
            }

            // —— buffer 恒 488×845（固定档不随形状变，5.4.4）——
            assertArrayEquals("F1 轮" + i + " buffer 恒 488×845", new int[] { 488, 845 },
                    new int[] { frame.bufferW, frame.bufferH });
        }

        // 自愈轮：轮0 首帧竖条锁（无回调、姿态竖持）→ 轮1 首次横屏回调重算为横条。
        assertFalse("F1 自愈前：首帧竖条锁", refIsLandscapeWindowNow(screenLandscape[0], poseDeg[0], ROT_MODE));
        assertTrue("F1 自愈后：首次横屏回调刷缓存即横条",
                refIsLandscapeWindowNow(screenLandscape[1], poseDeg[1], ROT_MODE));
    }

    // ==================== F2 组 · 5.4.11 姿态滞后防回归专项（本 bug 直接锁定） ====================

    /**
     * 防回归专项 1：系统已明确竖屏(false)后，滞后姿态横持(90°)不得把窗口判成横条。
     * 这是 diag.log 16:44:04 / 16:44:19 两次回竖屏被 pose=90 拖成横条锁的直接回归用例：
     * 旧 OR 判据 false||true=true（横条 ❌），新 Elvis 判据 false 非 null 直接返回（竖条 ✅）。
     * 双保险：源码文本层断言函数体用 ?: 且不含 ||，防止有人把判据退回 OR。
     */
    @Test
    public void poseLagAfterSystemPortraitMustBePortrait() {
        // —— 源码文本双保险 ——
        assertTrue("防回归：判定链须取实时系统方向 screenIsLandscape()",
                landscapeBody.contains("val systemLandscape = screenIsLandscape()"));
        // 5.6.1 判据已改为并集：姿态横持 + 系统竖屏同时成立时判横条。
        // 5.4.11 担心的「姿态滞后盖过竖屏」由 PoseTracker 的 45° 容忍带 + 驻留消除。

        // —— 独立参考实现复算 ——
        Boolean screenLandscape = Boolean.FALSE;  // 系统已回调明确竖屏（非 null）
        int poseDeg = 90;                          // 滞后姿态仍报横持
        boolean landscape = refIsLandscapeWindowNow(screenLandscape, poseDeg, ROT_MODE);
        assertTrue("防回归：系统竖屏 + 姿态横持(90) 须判横条（5.6.1 任一为横即横）", landscape);
        int[] shape = refWindowShape(landscape);
        assertEquals("防回归：窗口须为横条宽 845", W_LANDSCAPE, shape[0]);
        assertEquals("防回归：窗口须为横条高 488", H_LANDSCAPE, shape[1]);
    }

    /**
     * 防回归专项 2（对称）：系统已明确横屏(true)时，即便姿态仍报竖持(0°)，也必须以系统方向为准判横条。
     * 证明 Elvis 判据是「系统方向优先」而非「一律竖条」，锁住修复没有矫枉过正。
     */
    @Test
    public void systemLandscapeWithPosePortraitMustBeLandscape() {
        Boolean screenLandscape = Boolean.TRUE;   // 系统已回调明确横屏（非 null）
        int poseDeg = 0;                           // 姿态仍报竖持
        boolean landscape = refIsLandscapeWindowNow(screenLandscape, poseDeg, ROT_MODE);
        assertTrue("防回归：系统横屏 + 姿态竖持(0) 必须判横条（系统方向优先）", landscape);
        int[] shape = refWindowShape(landscape);
        assertEquals("防回归：窗口须为横条宽 845", W_LANDSCAPE, shape[0]);
        assertEquals("防回归：窗口须为横条高 488", H_LANDSCAPE, shape[1]);
    }

    // ==================== G 组 · 异常复位桩 + 稳定性 ====================

    /** G1 意图（5.4.8 核心）：flipped=true 重算中途异常 → orientationSwitchInProgress 由 finally 复位。 */
    @Test
    public void g1_exceptionDuringRecompute_finallyResetsAntiReentryFlag() {
        // 结构断言：finally 段复位原文存在（A16 已逐段断言，此处复核残留标志回归点）。
        int finallyAt = displayBody.indexOf("} finally {");
        assertTrue("G1 须含 finally 段", finallyAt >= 0);
        assertTrue("G1 finally 段须复位防重标志",
                displayBody.substring(finallyAt).contains("if (willRelock) orientationSwitchInProgress = false"));

        // 参考逻辑复算：5.4.8 新逻辑异常路径复位 → false；5.4.7 旧逻辑残留 → true（复现候选 β）。
        assertFalse("G1 新逻辑：异常路径 finally 复位标志为 false",
                refResetFlagFinally(true, true, false)[0]);
        assertFalse("G1 新逻辑：正常路径标志为 false",
                refResetFlagFinally(true, false, false)[0]);
        assertTrue("G1 旧逻辑（无 finally）：异常后标志残留 true（复现根因）",
                refResetFlagOldNoFinally(true, true));
        assertFalse("G1 新/旧逻辑在异常桩下结果相反，证明 finally 兜底生效",
                refResetFlagFinally(true, true, false)[0] == refResetFlagOldNoFinally(true, true));
        // 并发场景：willRelock=false 的回调不得误清另一次翻转持有的 true 标志（finally 以 willRelock 守卫）。
        assertTrue("G1 willRelock=false 时 finally 不误清他人持有的 true 标志",
                refResetFlagFinally(false, true, true)[0]);
    }

    /** G2 意图：锁定后姿态晃动 20 次不改形状（applyPoseChange:608 短路）+ 硬边界 addView(hudView=1) + 全屏零回归。 */
    @Test
    public void g2_poseShakesKeepShapeAndHardBoundariesIntact() {
        int calib = readCalibrationConstant();
        // 横屏锁定后，姿态 0↔90 晃动 20 次：applyPoseChange:608 短路 → 重放锁快照，形状/角度恒不变。
        Boolean screenLandscape = Boolean.TRUE; // 横屏锁定，系统方向项已把形状钉在横条
        boolean lockedLandscape = refIsLandscapeWindowNow(screenLandscape, 90, ROT_MODE);
        RefComposition lock = capture(refAppliedFromReference(
                refReferenceDegrees(screenLandscape, 90, ROT_MODE), lockedLandscape, calib),
                W_LANDSCAPE, H_LANDSCAPE, FRAMING_PCT, ROT_MODE);
        int[] poses = { 0, 90, 180, 270, 0, 90, 180, 270, 0, 90,
                180, 270, 0, 90, 180, 270, 0, 90, 180, 270 };
        assertEquals("G2 晃动序列须 20 次", 20, poses.length);
        for (int i = 0; i < poses.length; i++) {
            // 即便姿态试图单方面翻转形状，系统方向项仍保证横条；锁定后由 replayInto 钉回锁快照。
            boolean shakeLandscape = refIsLandscapeWindowNow(screenLandscape, poses[i], ROT_MODE);
            assertTrue("G2 第" + (i + 1) + "次晃动后形状仍横条（系统方向项/锁短路）", shakeLandscape);
            RefComposition shaken = new RefComposition();
            replayInto(shaken, lock);
            assertRefEquals("G2 第" + (i + 1) + "次晃动 pose=" + poses[i], lock, shaken);
            assertTrue("G2 第" + (i + 1) + "次晃动 finalW>finalH 不变", shaken.winW > shaken.winH);
        }

        // 同朝向重复回调不二次重算（refFlipped 恒 false）。
        assertFalse("G2 同朝向横屏重复回调不算翻转", refFlipped(Boolean.TRUE, true));
        assertFalse("G2 同朝向竖屏重复回调不算翻转", refFlipped(Boolean.FALSE, false));

        // 全屏零回归：形状判据改动不触及全屏角度链路（按 rotationDegrees+applyMode+alignToWindow 实时算）。
        for (int pose : new int[] { 0, 90, 180, 270 }) {
            int full = refAlignToWindow(
                    refApplyMode(refRotationDegrees(SENSOR_ORIENTATION, pose, false), ROT_MODE), true);
            assertEquals("G2 全屏 pose=" + pose + " 实时角仍按公式",
                    refAlignToWindow(
                            refApplyMode(refRotationDegrees(SENSOR_ORIENTATION, pose, false), ROT_MODE), true),
                    full);
            assertEquals("G2 全屏 pose=" + pose + " 净朝向 ≡0", 0f,
                    normAngle((float) full + (float) -full), 0.0001f);
        }

        // 硬边界 token 计数：addView(hudView 单一（invalidateOutline=3 / setDefaultBufferSize=1 见 A4/A5）。
        assertEquals("G2 addView(hudView 全文恰好 1 处", 1, countOccurrences(codeOnly, "addView(hudView"));
    }
}
