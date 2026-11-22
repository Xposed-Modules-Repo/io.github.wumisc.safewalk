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
 * 5.4.5「方向锁定（Direction Lock）方案 A：冻结 + 重放」的纯 JVM 契约用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约</b>：直接读取 {@code RearCameraOverlay.kt} 文本，按花括号配平截取
 *       {@code applyPoseChange / onDisplayChanged / refreshRotation / onSurfaceTextureSizeChanged /
 *       openCamera / buildParams / show / resolveOrientation / applyFraming / lockFirstFrame /
 *       LockedComposition} 方法体，锁住「5 个短路点 return 在 refreshRotation 之前」「重放分支在正方形
 *       防御之后」「小窗引用锁定 W/H 与 buffer」「setDefaultBufferSize / invalidateOutline 唯一计数」
 *       等结构，防止漏改或误删硬边界。</li>
 *   <li><b>B 组 · 真值表（独立参考实现复算）</b>：{@code LockedComposition} 是 {@code RearCameraOverlay}
 *       类内 {@code private data class}，且 {@code applyFraming / replayLockedComposition} 依赖 Android
 *       View，<b>JVM 无法直接 new 或反射调用</b>。故本组用类内独立纯函数参考模型
 *       （{@link RefComposition} + {@code ref*()}）复刻其数学语义并穷举真值表，<b>绝不假装直接调用了
 *       私有方法</b>。映射关系见下。</li>
 *   <li><b>C 组 · 回归护栏</b>：复用既有断言思路，锁住 D1~D4、5.4.4 buffer min/max、HUD -deg、
 *       5.4.3 注释、显隐状态机三 token 未被锁方案打破。</li>
 * </ul>
 *
 * <p><b>B 组参考实现 ↔ 生产源码映射（重要，避免误读为直接调用）：</b>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应（RearCameraOverlay.kt / PreviewOrientationPolicy.kt）</th></tr>
 *   <tr><td>{@link RefComposition} 16 标量 / 9 组</td><td>private data class LockedComposition（:403-422）</td></tr>
 *   <tr><td>{@code capture()}</td><td>applyFraming 首帧判定 + applyFramingZoom + openCamera buffer min/max</td></tr>
 *   <tr><td>{@code replayInto()}</td><td>replayLockedComposition（:1212-1233，只赋值不推导）</td></tr>
 *   <tr><td>{@code refShouldLock()}</td><td>lockFirstFrame 四守卫（:1248-1254）</td></tr>
 *   <tr><td>{@code refRotationDegrees/applyMode/alignToWindow}</td><td>PreviewOrientationPolicy 同名纯函数</td></tr>
 *   <tr><td>{@code refFramingDeg()}</td><td>applyFraming 的 deg when 判定（:1173-1177）</td></tr>
 * </table>
 * 参考模型只复刻<b>可由 JVM 判定的数学语义</b>；源码结构与守卫是否仍在由 A/C 组文本断言兜底。
 * 两组合起来才等价于「锁逻辑正确且真的接上了」。
 *
 * <p><b>调查报告 §4.6 一处笔误的更正</b>：报告写 NIGHT zoom ≈ {@code 1/(1-0.55/200)}，
 * 但源码实为 {@code k = f/200f}（f=55 → k=0.275 → zoom=1/0.725≈1.379）。本测试以源码为准。
 *
 * <p><b>只能真机验证、本文件测不到的部分</b>：HAL 对 buffer→View 的像素映射、锁定后逐帧观感是否
 * 完全一致、首帧横条实际握持观感、Surface 真正重建时的渲染——均为 Android/HAL 运行时行为，
 * JVM 只能锁决策数学与源码结构。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。写法与 {@code RotationRebuildSync543Test} /
 * {@code FixedCameraBuffer544Test} / {@code HudRotationContractTest} / {@code SquareWindowGuardTest} 一致。
 */
public class DirectionLockContractTest {

    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String SERVICE_REL =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";

    private static final String POSE_SIG = "private fun applyPoseChange()";
    private static final String DISPLAY_SIG = "fun onDisplayChanged(";
    private static final String REFRESH_SIG = "private fun refreshRotation()";
    private static final String SURFACE_SIZE_SIG = "override fun onSurfaceTextureSizeChanged";
    private static final String OPEN_CAMERA_SIG = "private fun openCamera()";
    private static final String BUILD_PARAMS_SIG = "private fun buildParams()";
    private static final String SHOW_SIG = "fun show(";
    private static final String RESOLVE_SIG = "private fun resolveOrientation(";
    private static final String FRAMING_SIG = "private fun applyFraming(";
    private static final String REPLAY_SIG = "private fun replayLockedComposition(";
    private static final String LOCK_SIG = "private fun lockFirstFrame()";
    private static final String LOCKED_CLASS_SIG = "private data class LockedComposition(";

    private static String overlaySrc;        // 原始 .kt 源码（保留注释，用于 5.4.3 注释断言）
    private static String codeOnly;          // 去注释全文（用于全局关键串与计数）
    private static String serviceCode;       // WalkPreviewService.java 去注释全文（显隐状态机 token）
    private static String poseBody;
    private static String displayBody;
    private static String refreshBody;
    private static String surfaceSizeBody;
    private static String openCameraBody;
    private static String buildParamsBody;
    private static String showBody;
    private static String resolveBody;
    private static String framingBody;
    private static String replayBody;
    private static String lockBody;
    private static String lockedClassBody;

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locate(SOURCE_REL)), StandardCharsets.UTF_8);
        serviceCode = stripComments(new String(Files.readAllBytes(locate(SERVICE_REL)), StandardCharsets.UTF_8));
        codeOnly = stripComments(overlaySrc);
        poseBody = stripComments(extractMethod(overlaySrc, POSE_SIG));
        displayBody = stripComments(extractMethod(overlaySrc, DISPLAY_SIG));
        refreshBody = stripComments(extractMethod(overlaySrc, REFRESH_SIG));
        surfaceSizeBody = stripComments(extractMethod(overlaySrc, SURFACE_SIZE_SIG));
        openCameraBody = stripComments(extractMethod(overlaySrc, OPEN_CAMERA_SIG));
        buildParamsBody = stripComments(extractMethod(overlaySrc, BUILD_PARAMS_SIG));
        showBody = stripComments(extractMethod(overlaySrc, SHOW_SIG));
        resolveBody = stripComments(extractMethod(overlaySrc, RESOLVE_SIG));
        framingBody = stripComments(extractMethod(overlaySrc, FRAMING_SIG));
        replayBody = stripComments(extractMethod(overlaySrc, REPLAY_SIG));
        lockBody = stripComments(extractMethod(overlaySrc, LOCK_SIG));
        lockedClassBody = stripComments(extractMethod(overlaySrc, LOCKED_CLASS_SIG));
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

    /** 从签名起按花括号配平截取单个方法/类文本（含外层花括号），跳过字符串/字符字面量里的花括号。 */
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

    /** 在方法体内截取 startToken → endToken 整段（含 endToken）。 */
    private static String segment(String body, String startToken, String endToken, String tag) {
        int start = body.indexOf(startToken);
        assertTrue(tag + " 找不到起点 " + startToken, start >= 0);
        int end = body.indexOf(endToken, start);
        assertTrue(tag + " 找不到终点 " + endToken, end >= 0);
        return body.substring(start, end + endToken.length());
    }

    /** root.post { root.invalidateOutline() } 文本必须经 post 包裹。 */
    private static final Pattern POST_INVALIDATE =
            Pattern.compile("root\\s*\\.\\s*post\\s*\\{\\s*root\\s*\\.\\s*invalidateOutline\\s*\\(\\s*\\)\\s*\\}");

    private static int countRegex(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int hits = 0;
        while (m.find()) hits++;
        return hits;
    }

    // ==================== B 组：独立参考实现（复刻纯数学语义，非调用私有方法） ====================

    /** 模式参考码：0=小窗，1=全屏（对应 style.mode == MODE_SMALLWINDOW 的真假）。 */
    private static final int MODE_SMALL = 0;
    private static final int MODE_FULL = 1;

    /** 独立快照模型：字段一一镜像 LockedComposition 的 16 标量 / 9 组。 */
    private static final class RefComposition {
        int rotDeg;                 // 组1 旋转角真值 appliedRotDeg
        int winW, winH;             // 组2 窗口尺寸
        int deg;                    // 组3 展示角（竖条恒 0）
        int boxW, boxH;             // 组4 旋转层布局框
        float layerRot, hudRot;     // 组5 角度对（hudRot = -layerRot）
        float pivotX, pivotY;       // 组6 取景锚点
        float scaleX, scaleY;       // 组7 取景缩放
        int bufferW, bufferH;       // 组8 相机 buffer 竖条档
        int framingPct;             // 组9 构图参数
        String rotationMode;
    }

    private static int norm(int deg) {
        return ((deg % 360) + 360) % 360;
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

    /** 复刻 PreviewOrientationPolicy.alignToWindow(deg, landscape)。 */
    private static int refAlignToWindow(int deg, boolean landscape) {
        if (deg == -1) return -1;
        int n = norm(deg);
        boolean swapped = n == 90 || n == 270;
        boolean needSwap = !landscape;
        return swapped == needSwap ? n : (n + 90) % 360;
    }

    /** 复刻 applyFraming 的 deg when 判定：方向未算/竖条 → 0；横条 → alignToWindow(rot,true)。 */
    private static int refFramingDeg(int appliedRotDeg, int w, int h) {
        boolean landscapeWindow = w > h;
        if (appliedRotDeg < 0) return 0;
        if (!landscapeWindow) return 0;
        return refAlignToWindow(appliedRotDeg, true);
    }

    /** 复刻 5.4.4 buffer 竖条档 min/max。 */
    private static int[] refBuffer(int w, int h) {
        return new int[] { Math.min(w, h), Math.max(w, h) };
    }

    /** 复刻 lockFirstFrame 四守卫：无锁/小窗/方向已定型/非正方形 才建锁。 */
    private static boolean refShouldLock(boolean hasLock, int mode, int appliedRotDeg, int w, int h) {
        if (hasLock) return false;
        if (mode != MODE_SMALL) return false;
        if (appliedRotDeg < 0) return false;
        if (w <= 0 || h <= 0 || w == h) return false;
        return true;
    }

    /** 复刻 capture：首帧构图全字段推导（applyFraming + applyFramingZoom + buffer）。 */
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
            // applyFramingZoom f<=0 折中分支：竖条 2.0x，横条 1:1
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

    /** 复刻 replayLockedComposition：只把快照 9 组字段写回当前构图，不做任何推导（幂等）。 */
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

    // ==================== A 组 · 结构 / 源码文本断言 ====================

    /** A1 意图：applyPoseChange 内小窗短路 return 必须出现在 refreshRotation 之前（运行期短路而非删行）。 */
    @Test
    public void a1_applyPoseChange_shortCircuitBeforeRefreshRotation() {
        // 【5.6.1 横屏必横】短路从「无条件 return」升级为「同朝向 return、真翻转放行」：
        // 护栏意图不变——同一朝向内的姿态抖动绝不重建窗口；新增的是横↔竖真翻转必须放行。
        String guard = "if (style.mode == MODE_SMALLWINDOW && smallLock != null) {";
        int g = poseBody.indexOf(guard);
        int sameOrient = poseBody.indexOf("if (wantLandscape == lockedLandscape) return");
        int r = poseBody.indexOf("refreshRotation()");
        assertTrue("A1 applyPoseChange 须含小窗锁定入口", g >= 0);
        assertTrue("A1 同朝向守卫须 return（姿态抖动不重排）", sameOrient > g);
        assertTrue("A1 applyPoseChange 须保留 refreshRotation() 原文", r >= 0);
        assertTrue("A1 守卫 return 必须在 refreshRotation 之前", sameOrient > 0 && sameOrient < r);
        assertTrue("A1 真翻转须清锁", poseBody.contains("smallLock = null"));
        assertTrue("A1 真翻转后须重锁", poseBody.contains("lockFirstFrame()"));
    }

    /** A2 意图：applyPoseChange 的 5.4.3 post 包裹 invalidateOutline 仍在（短路不影响全屏）。 */
    @Test
    public void a2_applyPoseChange_postInvalidateOutlineStillOnce() {
        assertTrue("A2 applyPoseChange 须保留 root.post { root.invalidateOutline() }",
                POST_INVALIDATE.matcher(poseBody).find());
        assertEquals("A2 该方法体内 invalidateOutline 须恰好 1 次",
                1, countOccurrences(poseBody, "invalidateOutline"));
    }

    /**
     * A3 意图（5.4.6 方案 B 更新）：5.4.5 原短路 return 文本原样保留，且仍在 refreshRotation
     * 之前——这部分断言仍然成立，绝不能误删；但方案 B 在该 return **之前**新增了
     * "系统真翻转 → 清 smallLock → 放行整条重算 → 末尾 lockFirstFrame() 重锁"的逻辑。
     * 本用例在原断言基础上补齐放行/重锁/防重文本，防止行为被改回"方向切换一律锁死"。
     */
    @Test
    public void a3_onDisplayChanged_realFlipRelockKeepsOriginalShortCircuit() {
        String guard = "if (style.mode == MODE_SMALLWINDOW && smallLock != null) return";
        int g = displayBody.indexOf(guard);
        int r = displayBody.indexOf("refreshRotation()");
        assertTrue("A3 原短路 return 原文须仍在（5.4.5 未删行）", g >= 0);
        assertTrue("A3 onDisplayChanged 须保留 refreshRotation() 原文", r >= 0);
        assertTrue("A3 原短路 return 须在 refreshRotation 之前（同朝向仍锁）", g < r);

        // 方案 B 新增：原 return 之前的真翻转放行 + 重锁 + 防重（旧契约缺失的过时点）
        assertTrue("A3 须含真翻转判据 flipped", displayBody.contains("val flipped ="));
        assertTrue("A3 真翻转判据须比对建锁朝向与回调传入朝向",
                displayBody.contains("lockScreenLandscape != screenLandscape"));
        assertFalse("A3 真翻转判据不得再读 screenIsLandscape()（5.4.7 已参数化）",
                displayBody.contains("screenIsLandscape()"));
        assertTrue("A3 须含防重条件 if (flipped && !orientationSwitchInProgress)",
                displayBody.contains("if (flipped && !orientationSwitchInProgress)"));
        assertTrue("A3 须含清锁 smallLock = null", displayBody.contains("smallLock = null"));
        assertTrue("A3 须含 willRelock 标志", displayBody.contains("willRelock = true"));
        assertTrue("A3 须含重锁 lockFirstFrame()", displayBody.contains("lockFirstFrame()"));
        assertTrue("A3 须含防重标志复位", displayBody.contains("orientationSwitchInProgress = false"));

        int clear = displayBody.indexOf("smallLock = null");
        int relock = displayBody.indexOf("lockFirstFrame()");
        assertTrue("A3 清锁须在原短路 return 之前（真翻转时穿透）", clear >= 0 && clear < g);
        assertTrue("A3 重锁须在 refreshRotation 之后（先重算再建锁）", relock > r);
        assertTrue("A3 防重标志复位须在重锁之后",
                displayBody.indexOf("orientationSwitchInProgress = false") > relock);
    }

    /** A4 意图：onDisplayChanged 的 5.4.3 post 包裹 invalidateOutline 仍在。 */
    @Test
    public void a4_onDisplayChanged_postInvalidateOutlineStillOnce() {
        assertTrue("A4 onDisplayChanged 须保留 root.post { root.invalidateOutline() }",
                POST_INVALIDATE.matcher(displayBody).find());
        assertEquals("A4 该方法体内 invalidateOutline 须恰好 1 次",
                1, countOccurrences(displayBody, "invalidateOutline"));
    }

    /** A5 意图：refreshRotation 含小窗守卫（钉回锁定角），且全屏路径 appliedRotDeg = newDeg 仍在。 */
    @Test
    public void a5_refreshRotation_guardAndFullscreenPathBothIntact() {
        assertTrue("A5 refreshRotation 须含 appliedRotDeg = smallLock!!.rotDeg 守卫",
                refreshBody.contains("appliedRotDeg = smallLock!!.rotDeg"));
        assertTrue("A5 refreshRotation 全屏路径须保留 appliedRotDeg = newDeg",
                refreshBody.contains("appliedRotDeg = newDeg"));
        assertTrue("A5 守卫须在全屏 newDeg 赋值之前",
                refreshBody.indexOf("smallLock!!.rotDeg") < refreshBody.indexOf("appliedRotDeg = newDeg"));
    }

    /** A6 意图：onSurfaceTextureSizeChanged 不得整体 return，仍调用 applyFraming（靠重放恢复）。 */
    @Test
    public void a6_surfaceSizeChanged_stillCallsApplyFraming() {
        assertTrue("A6 onSurfaceTextureSizeChanged 须仍调用 applyFraming()",
                surfaceSizeBody.contains("applyFraming()"));
        assertFalse("A6 该方法体不得整体 return（会让新 View 丢 transform/pivot/scale）",
                surfaceSizeBody.contains("return"));
    }

    /** A7 意图：全文 setDefaultBufferSize 去注释后恰好 1 处（首帧锁定唯一写入点未被破坏）。 */
    @Test
    public void a7_defaultBufferSizeCalledExactlyOnce() {
        assertEquals("A7 全文 setDefaultBufferSize 须恰好 1 处",
                1, countOccurrences(codeOnly, "setDefaultBufferSize("));
    }

    /** A8 意图：openCamera buffer 段须保留 min/max 原文，且其后用锁定 buffer 覆盖（小窗）。 */
    @Test
    public void a8_openCamera_bufferMinMaxThenLockOverride() {
        String buf = segment(openCameraBody, "val bw0",
                "st.setDefaultBufferSize(actualBufferW, actualBufferH)", "A8 buffer 段");
        assertTrue("A8 须保留 actualBufferW = minOf(bw0, bh0)",
                buf.contains("actualBufferW = minOf(bw0, bh0)"));
        assertTrue("A8 须保留 actualBufferH = maxOf(bw0, bh0)",
                buf.contains("actualBufferH = maxOf(bw0, bh0)"));
        assertTrue("A8 buffer 段须含小窗锁定覆盖 actualBufferW = smallLock!!.bufferW",
                buf.contains("actualBufferW = smallLock!!.bufferW"));
        assertTrue("A8 buffer 段须含小窗锁定覆盖 actualBufferH = smallLock!!.bufferH",
                buf.contains("actualBufferH = smallLock!!.bufferH"));
        assertTrue("A8 min/max 决策须在锁定覆盖之前",
                buf.indexOf("minOf(bw0, bh0)") < buf.indexOf("smallLock!!.bufferW"));
    }

    /** A9 意图：openCamera 须在 buffer 决策后调用 lockFirstFrame（捕获点唯一）。 */
    @Test
    public void a9_openCamera_callsLockFirstFrameAfterFraming() {
        int framing = openCameraBody.lastIndexOf("applyFraming()");
        int lock = openCameraBody.indexOf("lockFirstFrame()");
        int sink = openCameraBody.indexOf("setDefaultBufferSize(");
        assertTrue("A9 openCamera 须调用 lockFirstFrame()", lock >= 0);
        assertTrue("A9 lockFirstFrame 须在 applyFraming 落盘之后", lock > framing);
        assertTrue("A9 lockFirstFrame 须在 setDefaultBufferSize 之前", lock < sink);
    }

    /** A10 意图：buildParams 小窗分支用锁定 W/H（finalW/finalH）构造 LayoutParams。 */
    @Test
    public void a10_buildParams_smallWindowUsesLockedSize() {
        assertTrue("A10 须含 finalW = smallLock!!.winW", buildParamsBody.contains("finalW = smallLock!!.winW"));
        assertTrue("A10 须含 finalH = smallLock!!.winH", buildParamsBody.contains("finalH = smallLock!!.winH"));
        assertTrue("A10 LayoutParams 须用 finalW, finalH",
                Pattern.compile("LayoutParams\\s*\\(\\s*finalW\\s*,\\s*finalH\\s*,")
                        .matcher(buildParamsBody).find());
        assertTrue("A10 锁定尺寸取值须在 maxH 裁剪行之后",
                buildParamsBody.indexOf("smallLock!!.winW") >
                        buildParamsBody.indexOf("if (maxH > 0 && h > maxH && maxH > w) h = maxH"));
    }

    /** A11 意图：buildParams 的 maxH 高度裁剪精确行原样保留（5.4.3 边界）。 */
    @Test
    public void a11_buildParams_maxHClampLineIntact() {
        assertTrue("A11 须保留 if (maxH > 0 && h > maxH && maxH > w) h = maxH",
                Pattern.compile("if\\s*\\(\\s*maxH\\s*>\\s*0\\s*&&\\s*h\\s*>\\s*maxH"
                        + "\\s*&&\\s*maxH\\s*>\\s*w\\s*\\)\\s*h\\s*=\\s*maxH")
                        .matcher(buildParamsBody).find());
    }

    /** A12 意图：全文 invalidateOutline 去注释后恰好 3 处（1 首次 + 2 重建，锁方案不得增删）。 */
    @Test
    public void a12_fileHasExactlyThreeOutlineInvalidations() {
        assertEquals("A12 全文件 invalidateOutline 须恰好 3 次",
                3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A12 全文 post 包裹的 invalidateOutline 须恰好 2 处",
                2, countRegex(codeOnly, POST_INVALIDATE));
    }

    /** A13 意图：show 快路径小窗有锁只 applyBorder，全屏 else 分支保留 refreshRotation + applyFraming。 */
    @Test
    public void a13_showFastPath_smallWindowSkipRotationButFullscreenIntact() {
        String guard = "if (style.mode == MODE_SMALLWINDOW && smallLock != null) {";
        int g = showBody.indexOf(guard);
        assertTrue("A13 show 快路径须含小窗守卫", g >= 0);
        String seg = showBody.substring(g);
        int border = seg.indexOf("applyBorder()");
        int elseIdx = seg.indexOf("} else {");
        assertTrue("A13 小窗分支须保留 applyBorder()", border >= 0);
        assertTrue("A13 须有全屏 else 分支", elseIdx >= 0);
        assertTrue("A13 小窗分支先只 applyBorder，再进 else", border < elseIdx);
        assertTrue("A13 全屏 else 分支须保留 refreshRotation()", seg.indexOf("refreshRotation()") > elseIdx);
        assertTrue("A13 全屏 else 分支须保留 applyFraming()", seg.indexOf("applyFraming()") > elseIdx);
    }

    /** A14 意图：resolveOrientation 保留 alignToWindow 原文，其后加小窗守卫把角度钉回锁定值。 */
    @Test
    public void a14_resolveOrientation_alignToWindowThenLockGuard() {
        String align = "appliedRotDeg = PreviewOrientationPolicy.alignToWindow(rawDeg, viewW > viewH)";
        assertTrue("A14 须保留 alignToWindow 原文（全屏实时计算）", resolveBody.contains(align));
        assertTrue("A14 须含小窗守卫 appliedRotDeg = smallLock!!.rotDeg",
                resolveBody.contains("appliedRotDeg = smallLock!!.rotDeg"));
        assertTrue("A14 守卫须在 alignToWindow 之后",
                resolveBody.indexOf("smallLock!!.rotDeg") > resolveBody.indexOf("alignToWindow"));
    }

    /** A15 意图：applyFraming 重放分支须在正方形防御之后、写 lastWindowW 之前。 */
    @Test
    public void a15_applyFraming_replayAfterSquareGuardBeforeStateWrite() {
        int square = framingBody.indexOf("if (w == h)");
        int replay = framingBody.indexOf("replayLockedComposition(view, layer)");
        int write = framingBody.indexOf("lastWindowW = w");
        assertTrue("A15 须保留正方形防御 if (w == h)", square >= 0);
        assertTrue("A15 须调用 replayLockedComposition(view, layer)", replay >= 0);
        assertTrue("A15 重放分支须在正方形防御之后", replay > square);
        assertTrue("A15 重放分支须在写 lastWindowW 之前", replay < write);
    }

    /** A16 意图：LockedComposition 16 字段齐全（9 组缺一不可），smallLock 字段声明仍在。 */
    @Test
    public void a16_lockedComposition_hasAll16Fields() {
        String[] fields = { "rotDeg", "winW", "winH", "deg", "boxW", "boxH",
                "layerRot", "hudRot", "pivotX", "pivotY", "scaleX", "scaleY",
                "bufferW", "bufferH", "framingPct", "rotationMode" };
        for (String f : fields) {
            assertTrue("A16 LockedComposition 缺字段 " + f,
                    Pattern.compile("\\b" + f + "\\s*:").matcher(lockedClassBody).find());
        }
        assertTrue("A16 须保留 smallLock 字段声明",
                codeOnly.contains("private var smallLock: LockedComposition? = null"));
    }

    /** A17 意图：lockFirstFrame 四守卫齐全（已有锁/非小窗/方向未定型/正方形）。 */
    @Test
    public void a17_lockFirstFrame_fourGuardsIntact() {
        assertTrue("A17 守卫1 已有锁不重建", lockBody.contains("if (smallLock != null) return"));
        assertTrue("A17 守卫2 只锁小窗", lockBody.contains("if (style.mode != MODE_SMALLWINDOW) return"));
        assertTrue("A17 守卫3 方向已定型", lockBody.contains("if (appliedRotDeg < 0) return"));
        assertTrue("A17 守卫4 非正方形", lockBody.contains("if (w <= 0 || h <= 0 || w == h) return"));
        assertTrue("A17 须构造 smallLock = LockedComposition(",
                lockBody.contains("smallLock = LockedComposition("));
    }

    /** A18 意图：replayLockedComposition 成组恢复 9 组字段，且 HUD 用空安全 lock.hudRot。 */
    @Test
    public void a18_replayLockedComposition_restoresAllGroupsWithNullSafeHud() {
        assertTrue("A18 恢复窗口 W/H", replayBody.contains("lastWindowW = lock.winW")
                && replayBody.contains("lastWindowH = lock.winH"));
        assertTrue("A18 恢复 rotDeg", replayBody.contains("appliedRotDeg = lock.rotDeg"));
        assertTrue("A18 恢复角度对", replayBody.contains("layer.rotation = lock.layerRot")
                && replayBody.contains("hudColumn?.rotation = lock.hudRot"));
        assertTrue("A18 恢复锚点/缩放", replayBody.contains("view.pivotX = lock.pivotX")
                && replayBody.contains("view.scaleX = lock.scaleX"));
        assertTrue("A18 恢复 buffer", replayBody.contains("actualBufferW = lock.bufferW")
                && replayBody.contains("actualBufferH = lock.bufferH"));
        assertFalse("A18 HUD 不得用非空断言 hudView!!", replayBody.contains("hudView!!"));
    }

    // ==================== B 组 · 真值表（独立参考实现复算） ====================

    /** B1 意图：首帧竖条快照 deg=0 / layerRot=0 / hudRot=0（竖屏不转硬边界冻结）。 */
    @Test
    public void b1_firstFramePortraitStrip_zeroAngles() {
        RefComposition p = capture(90, 150, 260, 0, "auto");
        assertEquals("B1 竖条 deg 须为 0", 0, p.deg);
        assertEquals("B1 竖条 layerRot 须为 0", 0.0f, p.layerRot, 0.0001f);
        assertEquals("B1 竖条 hudRot 须为 0", 0.0f, p.hudRot, 0.0001f);
    }

    /** B2 意图：首帧横条快照 hudRot == -layerRot（四角 rotDeg 覆盖）。 */
    @Test
    public void b2_firstFrameLandscape_hudRotIsNegativeOfLayerRot() {
        for (int rot : new int[] { 0, 90, 180, 270 }) {
            RefComposition l = capture(rot, 260, 150, 0, "auto");
            assertEquals("B2 横条 rotDeg=" + rot + " 须 hudRot == -layerRot",
                    -l.layerRot, l.hudRot, 0.0001f);
        }
    }

    /** B3 意图：小窗任意 trigger、N=5 次（pose 0/90/180/270/0）后快照全字段 == 首帧（幂等）。 */
    @Test
    public void b3_smallWindow_poseSequenceN5_idempotentSnapshot() {
        RefComposition first = capture(90, 150, 260, 0, "auto");
        int[] poses = { 0, 90, 180, 270, 0 };
        for (int pose : poses) {
            // 每次触发源都试图按实时姿态重算一版"污染"构图，随后被重放覆盖回首帧
            RefComposition polluted = capture(refRotationDegrees(90, pose, false), 260, 150, 55, "flip");
            replayInto(polluted, first);
            assertRefEquals("B3 pose=" + pose, first, polluted);
        }
    }

    /** B4 意图：小窗 × 7 类 trigger × snapshot 全字段（mode=小窗、roundtrip=锁定后）均不变。 */
    @Test
    public void b4_smallWindow_allTriggers_preserveEverySnapshotField() {
        RefComposition first = capture(90, 150, 260, 0, "auto");
        String[] triggers = { "pose0to90", "pose90to0", "pose90to180",
                "displayChanged", "surfaceSizeChanged", "darkModeFraming0to55", "rotationModeChange" };
        for (String t : triggers) {
            RefComposition live = capture(270, 260, 150, 55, "fixed");
            replayInto(live, first);
            assertRefEquals("B4 trigger=" + t, first, live);
        }
    }

    /** B5 意图：全屏角度按 rotationDegrees + applyMode + alignToWindow 公式更新（复刻函数先自校验）。 */
    @Test
    public void b5_fullscreen_angleFollowsPolicyFormula() {
        assertEquals("B5 rotationDegrees(90,0,back)=90", 90, refRotationDegrees(90, 0, false));
        assertEquals("B5 rotationDegrees(90,90,back)=0", 0, refRotationDegrees(90, 90, false));
        assertEquals("B5 rotationDegrees(0,0,back)=0", 0, refRotationDegrees(0, 0, false));
        assertEquals("B5 rotationDegrees(90,0,front)=90", 90, refRotationDegrees(90, 0, true));
        assertEquals("B5 applyMode fixed → 0", 0, refApplyMode(90, "fixed"));
        assertEquals("B5 applyMode flip → +180", 270, refApplyMode(90, "flip"));
        assertEquals("B5 applyMode auto 原样", 90, refApplyMode(90, "auto"));
        assertEquals("B5 alignToWindow(0,横条)=0", 0, refAlignToWindow(0, true));
        assertEquals("B5 alignToWindow(0,竖条)=90", 90, refAlignToWindow(0, false));
        assertEquals("B5 alignToWindow(-1) 哨兵原样", -1, refAlignToWindow(-1, true));
        // 全屏：sensor=90, pose=0(横条) → rotDeg=alignToWindow(90,true)=180，与小窗锁定角 90 不同
        int fullDeg = refAlignToWindow(refApplyMode(refRotationDegrees(90, 0, false), "auto"), true);
        assertEquals("B5 全屏 rotDeg 按公式为 180", 180, fullDeg);
    }

    /** B6 意图：NIGHT framing=55 进 f>0 分支 zoom≈1.379（以源码 k=f/200f 为准）；f=0 走 2.0 折中。 */
    @Test
    public void b6_framing55_entersActiveZoomBranch() {
        RefComposition night = capture(0, 845, 488, 55, "auto");   // 全屏横条 + NIGHT framing
        float expectedZoom = 1f / (1f - 55f / 200f);
        assertEquals("B6 NIGHT framing=55 须 zoom=1/(1-55/200)≈1.379", expectedZoom, night.scaleX, 0.0001f);
        assertEquals("B6 NIGHT zoom 须约 1.379（非折中 2.0）", 1.379f, night.scaleX, 0.001f);
        RefComposition plain = capture(90, 150, 260, 0, "auto");   // 小窗竖条 + framing 0
        assertEquals("B6 framing=0 竖条须走 2.0 折中", 2.0f, plain.scaleX, 0.0001f);
    }

    /** B7 意图：roundtrip 小窗(pose0,D0)→全屏(pose90,D1≠D0)→小窗(pose180) 恢复全字段==D0 快照。 */
    @Test
    public void b7_roundtrip_restoresFirstFrameSnapshot() {
        RefComposition first = capture(90, 150, 260, 0, "auto");       // 小窗首帧竖条，D0=90
        RefComposition full = capture(180, 845, 488, 55, "auto");      // 全屏横条，D1=180
        assertFalse("B7 全屏角度 D1 须不同于小窗 D0", full.rotDeg == first.rotDeg);
        // 回小窗：refreshRotation/resolveOrientation 守卫钉回 rotDeg，applyFraming 走重放
        RefComposition back = capture(270, 845, 488, 55, "auto");      // 实时姿态已变
        replayInto(back, first);
        assertRefEquals("B7 roundtrip", first, back);
        assertEquals("B7 恢复后 rotDeg 须为 D0=90", 90, back.rotDeg);
    }

    /** B8 意图：w==h（以及已有锁/全屏/方向未定型）时不建锁（smallLock 保持 null）。 */
    @Test
    public void b8_squareWindow_doesNotCreateLock() {
        assertFalse("B8 w==h 不得建锁", refShouldLock(false, MODE_SMALL, 90, 488, 488));
        assertFalse("B8 已有锁不得重建", refShouldLock(true, MODE_SMALL, 90, 150, 260));
        assertFalse("B8 全屏不得建锁", refShouldLock(false, MODE_FULL, 90, 150, 260));
        assertFalse("B8 方向未定型(rotDeg<0)不得建锁", refShouldLock(false, MODE_SMALL, -1, 150, 260));
        assertFalse("B8 非法尺寸不得建锁", refShouldLock(false, MODE_SMALL, 90, 0, 260));
        assertTrue("B8 合法小窗首帧应建锁", refShouldLock(false, MODE_SMALL, 90, 150, 260));
    }

    /** B9 意图：任何快照重放后 layerRot + hudRot == 0（四角 0/90/180/270 全覆盖，5.4.3 净朝向）。 */
    @Test
    public void b9_replay_netRotationIsZeroForFourCorners() {
        for (int deg : new int[] { 0, 90, 180, 270 }) {
            RefComposition lock = new RefComposition();
            lock.layerRot = deg;
            lock.hudRot = -deg;
            RefComposition current = new RefComposition();
            current.layerRot = 999f;   // 故意污染
            current.hudRot = 999f;
            replayInto(current, lock);
            assertEquals("B9 deg=" + deg + " 重放后 layer+hud 净旋转须为 0",
                    0.0f, current.layerRot + current.hudRot, 0.0001f);
        }
    }

    /** B10 意图：buffer 竖条档首帧恒定且重放不变（横竖输入均落到 min×max）。 */
    @Test
    public void b10_bufferPortraitTierStableThroughReplay() {
        assertArrayEquals("B10 竖条 buffer 档", new int[] { 150, 260 }, refBuffer(150, 260));
        assertArrayEquals("B10 横条也落同一竖条档", new int[] { 150, 260 }, refBuffer(260, 150));
        RefComposition first = capture(90, 260, 150, 0, "auto");
        RefComposition live = capture(270, 845, 488, 55, "flip");
        replayInto(live, first);
        assertArrayEquals("B10 重放后 buffer 不变",
                new int[] { first.bufferW, first.bufferH }, new int[] { live.bufferW, live.bufferH });
    }

    /** B11 意图：capture 为确定性纯函数——同输入两次得到全字段一致的快照（锁可复现）。 */
    @Test
    public void b11_captureIsDeterministic() {
        assertRefEquals("B11", capture(90, 150, 260, 55, "auto"), capture(90, 150, 260, 55, "auto"));
    }

    /** B12 意图：全屏角度由实时姿态公式计算，不受小窗锁定角影响（进全屏才随方向旋转）。 */
    @Test
    public void b12_fullscreenRotationIndependentOfLock() {
        int lockRot = 90;  // 小窗锁定角
        for (int pose : new int[] { 0, 90, 180, 270 }) {
            int fullRot = refAlignToWindow(refApplyMode(refRotationDegrees(90, pose, false), "auto"), true);
            int fullRotFlip = refAlignToWindow(refApplyMode(refRotationDegrees(90, pose, false), "flip"), true);
            // 全屏值只由姿态/传感器/模式决定，公式里不出现 lockRot
            assertEquals("B12 pose=" + pose + " 全屏角仍按公式算", fullRot,
                    refAlignToWindow(refApplyMode(refRotationDegrees(90, pose, false), "auto"), true));
            assertEquals("B12 pose=" + pose + " flip 模式 +180 后对齐", fullRotFlip,
                    refAlignToWindow((refRotationDegrees(90, pose, false) + 180) % 360, true));
        }
    }

    // ==================== C 组 · 回归护栏（锁方案不得打破既有硬边界） ====================

    /** C1 意图：D1 横竖判据仍是 val landscapeWindow = w > h。 */
    @Test
    public void c1_landscapeFormulaIntact() {
        assertTrue("C1 须保留 val landscapeWindow = w > h",
                framingBody.contains("val landscapeWindow = w > h"));
    }

    /** C2 意图：D2 竖屏固定不转（!landscapeWindow -> 0）仍在。 */
    @Test
    public void c2_portraitNeverRotatesIntact() {
        assertTrue("C2 须保留 !landscapeWindow -> 0", framingBody.contains("!landscapeWindow -> 0"));
    }

    /** C3 意图：D3 5.4.0 竖屏折中固定常量 return 2.0f 仍在。 */
    @Test
    public void c3_compromiseZoomTwoIntact() {
        assertTrue("C3 须保留折中缩放固定 return 2.0f", codeOnly.contains("return 2.0f"));
    }

    /** C4 意图：D4 5.4.1 正方形防御 if (w == h) 仍在。 */
    @Test
    public void c4_squareGuardIntact() {
        assertTrue("C4 须保留正方形防御 if (w == h)", framingBody.contains("if (w == h)"));
    }

    /** C5 意图：方向公式 alignToWindow(appliedRotDeg, true) 原文仍在（横条判据未被锁改写）。 */
    @Test
    public void c5_alignToWindowIntact() {
        assertTrue("C5 须保留 PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)",
                framingBody.contains("PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)"));
    }

    /** C6 意图：5.4.2 HUD 反向旋转 -deg 原文仍在，且紧随 layer.rotation（相邻）。 */
    @Test
    public void c6_hudInverseRotationIntact() {
        Pattern adjacent = Pattern.compile(
                "layer\\.rotation\\s*=\\s*deg\\.toFloat\\(\\)\\s*"
                + "hudColumn\\?\\.rotation\\s*=\\s*-deg\\.toFloat\\(\\)");
        assertTrue("C6 hudColumn?.rotation = -deg.toFloat() 须紧随 layer.rotation",
                adjacent.matcher(framingBody).find());
        assertFalse("C6 不得改成非空断言 hudView!!", framingBody.contains("hudView!!"));
    }

    /** C7 意图：5.4.4 固定 buffer min/max 两行原文仍在（锁定覆盖不得替换掉公式）。 */
    @Test
    public void c7_fixedBufferMinMaxIntact() {
        assertTrue("C7 须保留 minOf(bw0, bh0)", codeOnly.contains("actualBufferW = minOf(bw0, bh0)"));
        assertTrue("C7 须保留 maxOf(bw0, bh0)", codeOnly.contains("actualBufferH = maxOf(bw0, bh0)"));
    }

    /** C8 意图：5.4.3 三处旋转同步注释仍在（applyPoseChange/onDisplayChanged/buildParams/applyFraming）。 */
    @Test
    public void c8_543CommentsIntact() {
        assertTrue("C8 applyPoseChange 须保留 5.4.3 注释",
                extractMethod(overlaySrc, POSE_SIG).contains("5.4.3"));
        assertTrue("C8 onDisplayChanged 须保留 5.4.3 注释",
                extractMethod(overlaySrc, DISPLAY_SIG).contains("5.4.3"));
        assertTrue("C8 buildParams 须保留 5.4.3 注释",
                extractMethod(overlaySrc, BUILD_PARAMS_SIG).contains("5.4.3"));
        assertTrue("C8 applyFraming 须保留 5.4.3 注释",
                extractMethod(overlaySrc, FRAMING_SIG).contains("5.4.3"));
    }

    /** C9 意图：显隐状态机三 token 未改（方向锁定只作用于 View/构图层，正交于显隐判定）。 */
    @Test
    public void c9_visibilityStateMachineTokensIntact() {
        assertTrue("C9 须保留 stepActivityTs", serviceCode.contains("stepActivityTs"));
        assertTrue("C9 须保留 handoffToAutoMode", serviceCode.contains("handoffToAutoMode"));
        assertTrue("C9 须保留 gaitShowTs", serviceCode.contains("gaitShowTs"));
    }

    /** C10 意图：orientationSummary 末尾追加 🔒锁定 诊断字段（真机可确认锁定生效）。 */
    @Test
    public void c10_orientationSummaryHasLockDiagnostic() {
        assertTrue("C10 须含 🔒锁定= 诊断字段", codeOnly.contains("🔒锁定="));
        assertTrue("C10 诊断字段须引用锁定窗口/缓冲",
                codeOnly.contains("lk.winW") && codeOnly.contains("lk.bufferW"));
    }
}
