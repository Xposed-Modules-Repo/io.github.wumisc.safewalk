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

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 5.4.10「横屏回竖屏后路况预览仍是横条」最小修复的纯 JVM 契约用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约</b>：直接读取 {@code RearCameraOverlay.kt} 文本，按花括号配平截取
 *       {@code onDisplayChanged / applyFraming / buildParams} 方法体，锁住本次修复的<b>结构</b>——
 *       「{@code val lp = buildParams()} 提取 + {@code applyFraming(lp.width, lp.height)} 带参重算」
 *       「带参 lp 同时喂 {@code wm.updateViewLayout}」「D1/D2/D3 三处 {@code stage("方向诊断 ...")}」
 *       「flipped 判据原样 + 带参签名」「防重/清锁/重锁/finally 复位仍在」「硬边界 token 计数」，
 *       防止回退到无参 {@code applyFraming()}（陈旧横条尺寸）或误改方向判据。</li>
 *   <li><b>B 组 · 真值表（独立参考实现复算）</b>：生产逻辑内联在 {@code onDisplayChanged /
 *       applyFraming / buildParams}（私有方法且依赖 Android View/WindowManager），<b>JVM 无法直接调用</b>。
 *       故本组用类内独立纯函数参考模型（{@code refFlipped / refTargetSizeFromBuildParams /
 *       refFramingUsesTarget}）复刻调查报告 SC2 的 flipped 三态与「目标尺寸优先于陈旧尺寸」语义，
 *       <b>绝不假装直接调用了私有方法</b>。映射关系见下表。</li>
 *   <li><b>C 组 · 可证性声明</b>：见本 javadoc 末段「只能真机验证、本文件测不到的部分」——
 *       时序与画面类运行时现象 JVM 一律测不到，只锁可判定的源码结构与纯函数真值。</li>
 * </ul>
 *
 * <p><b>B 组参考实现 ↔ 生产源码映射（重要，避免误读为直接调用）：</b>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应（RearCameraOverlay.kt）</th></tr>
 *   <tr><td>{@code refFlipped(lockSL, screenSL)}</td><td>onDisplayChanged:1495
 *       {@code lockScreenLandscape != null && lockScreenLandscape != screenLandscape}</td></tr>
 *   <tr><td>{@code refTargetSizeFromBuildParams(...)}</td><td>buildParams:1780-1783 / 1795-1800
 *       （清锁走 else → 目标朝向尺寸 finalW/finalH；有锁走锁定尺寸）</td></tr>
 *   <tr><td>{@code refFramingUsesTarget(...)}</td><td>applyFraming:1173-1179
 *       （{@code windowW>0 && windowH>0 -> windowW to windowH} 第一优先级，压过陈旧 view/root 尺寸）</td></tr>
 * </table>
 * 参考模型只复刻<b>可由 JVM 判定的布尔/尺寸语义</b>；修复结构是否真接上、token 是否真未增，
 * 由 A 组文本断言兜底。两组合起来才等价于「判据正确且带参目标尺寸真的喂进了 framing」。
 *
 * <p><b>token 计数口径说明（避免误报，以源码实测为准）：</b>任务书 A6 把
 * {@code FLASH_MODE_TORCH=8}、{@code CONTROL_AE_EXPOSURE_COMPENSATION=2} 列在「stripComments 后」标题下，
 * 但源码实测：{@code stripComments()} 后二者各为 <b>1</b>（唯一真实下发点），含注释全文才为 <b>8 / 2</b>
 * （与 {@code TorchWhiteoutContractTest} 类 javadoc 口径一致）。故 A6 对这两个 token <b>两种口径都断言</b>：
 * 全文口径锁基线 8/2 不增不减，去注释口径锁真实下发点唯一。其余四个 token 全文与去注释计数一致。
 *
 * <p><b>只能真机验证、本文件测不到的部分（C 组可证性声明）：</b>
 * <ol>
 *   <li><b>异步布局时序</b>：{@code wm.updateViewLayout(root, lp)} 是异步的，view/root 宽高真正刷新的时刻、
 *       {@code root.post { invalidateOutline() }} 是否赶在上屏前完成——Android 主线程布局/HAL 运行时行为，JVM 不可证。</li>
 *   <li><b>带参目标尺寸是否真绕开陈旧横条</b>：本测试只证「{@code applyFraming(lp.width, lp.height)} 结构在线、
 *       带参优先级 :1174 高于陈旧尺寸」；lp 本身是否因次因 S（poseDeg 滞后导致
 *       {@code isLandscapeWindowNow()} 仍返回 true）而仍是横条，只能靠真机 D1 的 {@code pose=} 与
 *       {@code screenLandscape}/{@code lockSL} 字段坐实。D1 原 {@code isLand=} 诊断字段已被移除：
 *       因该字段的 {@code isLandscapeWindowNow()} 调用会令契约 a17 的「形状单一真源调用恰 3 处」断言失败，
 *       用户方向契约硬约束优先级高于诊断字段，故不再有 {@code isLand=}，不得再据其坐实次因 S。</li>
 *   <li><b>新锁真机画面</b>：D3 打出的新锁 {@code winW x winH @deg} 是否真为竖条 {@code 488x845@0}、
 *       回放后路面是否真由横向恢复竖向——需真机复现「竖→横→竖」回传 diag.log + 用户观感，JVM 不可证。</li>
 * </ol>
 * 上述均依赖具体 ROM/传感器/HAL 时序；最终验收仍需按调查 O1-O6 真机复现。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。写法与 {@code DirectionLockContractTest} /
 * {@code TorchWhiteoutContractTest} 一致。
 */
public class PortraitRestoreContractTest {

    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String DISPLAY_SIG = "fun onDisplayChanged(";
    private static final String FRAMING_SIG = "private fun applyFraming(";
    private static final String BUILD_PARAMS_SIG = "private fun buildParams()";

    // D1-D3 完整 stage 文本（逐字取自源码 :1501 / :1544 / :1553，含字符串内部 $ 与 ${...}）
    private static final String D1_STAGE = "stage(\"方向诊断 IN screenLandscape=$screenLandscape "
            + "lastKnown=$lastKnownScreenLandscape lockSL=$lockScreenLandscape flag=$orientationSwitchInProgress "
            + "lock=${smallLock?.winW}x${smallLock?.winH}@${smallLock?.deg} "
            + "root=${rootView?.width}x${rootView?.height} pose=$poseDeg\")";
    private static final String D2_STAGE = "stage(\"方向诊断 FRAMED lastWindow=${lastWindowW}x${lastWindowH} "
            + "appliedRot=$appliedRotDeg layer=${rotLayer?.layoutParams?.width}x${rotLayer?.layoutParams?.height}"
            + "@${rotLayer?.rotation}\")";
    private static final String D3_STAGE = "stage(\"方向诊断 LOCKED lock=${smallLock?.winW}x${smallLock?.winH}"
            + "@${smallLock?.deg} rotDeg=${smallLock?.rotDeg} lockSL=$lockScreenLandscape\")";

    private static String overlaySrc;      // 原始 .kt 源码（保留注释，用于全文 token 基线计数）
    private static String codeOnly;        // 去注释全文（用于去注释口径计数与全局关键串）
    private static String displayBody;     // onDisplayChanged() 方法体（去注释）
    private static String framingBody;     // applyFraming() 方法体（去注释）
    private static String buildParamsBody; // buildParams() 方法体（去注释）

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locate(SOURCE_REL)), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
        displayBody = stripComments(extractMethod(overlaySrc, DISPLAY_SIG));
        framingBody = stripComments(extractMethod(overlaySrc, FRAMING_SIG));
        buildParamsBody = stripComments(extractMethod(overlaySrc, BUILD_PARAMS_SIG));
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

    // ==================== B 组：独立参考实现（复刻纯布尔/尺寸语义，非调用私有方法） ====================

    /**
     * 复刻 onDisplayChanged:1495 的 flipped 判据。
     * lockSL 为建锁时记录的朝向（null = 尚未建锁），screenSL 为本次回调下传的朝向。
     */
    private static boolean refFlipped(Boolean lockSL, boolean screenSL) {
        return lockSL != null && lockSL != screenSL;
    }

    /**
     * 复刻 buildParams:1780-1800：清锁后（smallLockCleared=true，即翻转分支已 smallLock=null）
     * 走 else 分支 finalW=目标宽 / finalH=目标高，横条 w>h、竖条 h>w；未清锁则沿用锁值。
     * 返回 {w, h}。
     */
    private static int[] refTargetSizeFromBuildParams(boolean smallLockCleared, boolean landscape,
            int portraitW, int portraitH, int landscapeW, int landscapeH, int lockedW, int lockedH) {
        if (!smallLockCleared) return new int[] { lockedW, lockedH }; // smallLock != null → 用锁定尺寸
        return landscape ? new int[] { landscapeW, landscapeH } : new int[] { portraitW, portraitH };
    }

    /**
     * 复刻 applyFraming:1173-1179 尺寸来源优先级的核心二分：带参目标尺寸（windowW/windowH>0，第一优先级）
     * 优先于陈旧 view/root 尺寸。useTarget 对应「带参且为正」是否命中，命中则采用 target，否则回退 stale。
     * 返回 framing 实际采用的 {w, h}。
     */
    private static int[] refFramingUsesTarget(boolean useTarget, int staleW, int staleH,
            int targetW, int targetH) {
        if (useTarget && targetW > 0 && targetH > 0) return new int[] { targetW, targetH };
        return new int[] { staleW, staleH };
    }

    // ==================== A 组 · 源码文本契约 ====================

    /**
     * A1 意图（M1 核心 · 防回归）：onDisplayChanged 必须用提取的 val lp 喂带参 applyFraming，
     * 且方法体内不得再有裸 applyFraming() 作为重算调用点——退回到无参即回到陈旧横条 bug。
     */
    @Test
    public void a1_onDisplayChanged_usesTargetSizeForFraming() {
        assertTrue("A1 方法体须提取 val lp = buildParams()",
                displayBody.contains("val lp = buildParams()"));
        assertTrue("A1 方法体须以带参 applyFraming(lp.width, lp.height) 重算",
                displayBody.contains("applyFraming(lp.width, lp.height)"));
        assertTrue("A1 lp 提取须在带参 applyFraming 之前（先算目标尺寸再喂）",
                displayBody.indexOf("val lp = buildParams()")
                        < displayBody.indexOf("applyFraming(lp.width, lp.height)"));
        assertEquals("A1 方法体内不得有裸 applyFraming() 重算调用点（无参=读陈旧尺寸）",
                0, countOccurrences(displayBody, "applyFraming()"));
        assertTrue("A1 全文带参 applyFraming(lp.width, lp.height) 调用须 ≥1",
                countOccurrences(codeOnly, "applyFraming(lp.width, lp.height)") >= 1);
    }

    /** A2 意图：wm.updateViewLayout 用提取的 lp，而非内联 buildParams() 二次计算。 */
    @Test
    public void a2_updateViewLayoutUsesExtractedLp() {
        assertTrue("A2 须用 wm.updateViewLayout(root, lp)（复用提取的 lp）",
                displayBody.contains("wm.updateViewLayout(root, lp)"));
        assertFalse("A2 不得退回内联 wm.updateViewLayout(root, buildParams())",
                displayBody.contains("wm.updateViewLayout(root, buildParams())"));
        assertTrue("A2 lp 提取须在 updateViewLayout 之前",
                displayBody.indexOf("val lp = buildParams()")
                        < displayBody.indexOf("wm.updateViewLayout(root, lp)"));
    }

    /** A3 意图：D1/D2/D3 三处必须用 stage("方向诊断 ...")（非 diag），否则下次日志回传看不到。 */
    @Test
    public void a3_threeStageDiagnosticsPresent() {
        assertEquals("A3 须含完整 D1 stage 文本恰好 1 处", 1, countOccurrences(displayBody, D1_STAGE));
        assertEquals("A3 须含完整 D2 stage 文本恰好 1 处", 1, countOccurrences(displayBody, D2_STAGE));
        assertEquals("A3 须含完整 D3 stage 文本恰好 1 处", 1, countOccurrences(displayBody, D3_STAGE));
        assertTrue("A3 D1 须以 stage 打（非 diag）", displayBody.contains("stage(\"方向诊断 IN "));
        assertTrue("A3 D2 须以 stage 打（非 diag）", displayBody.contains("stage(\"方向诊断 FRAMED "));
        assertTrue("A3 D3 须以 stage 打（非 diag）", displayBody.contains("stage(\"方向诊断 LOCKED "));
        assertFalse("A3 三处诊断不得改用 diag（diag 不进本次回传日志通道）",
                displayBody.contains("diag(\"方向诊断"));
    }

    /** A4 意图：flipped 判据原样保留 + onDisplayChanged 带参签名未被误改（用户方向契约写死不改）。 */
    @Test
    public void a4_flippedJudgeAndSignatureIntact() {
        assertTrue("A4 须保留 onDisplayChanged(screenLandscape: Boolean) 带参签名",
                displayBody.contains("fun onDisplayChanged(screenLandscape: Boolean)"));
        assertTrue("A4 flipped 判据须逐字保留（建锁朝向 != 回调朝向）",
                displayBody.contains("val flipped = lockScreenLandscape != null && lockScreenLandscape != screenLandscape"));
        assertTrue("A4 须保留防重条件 if (flipped && !orientationSwitchInProgress)",
                displayBody.contains("if (flipped && !orientationSwitchInProgress)"));
        assertFalse("A4 判据不得回退到读 screenIsLandscape()（5.4.7 已参数化）",
                displayBody.contains("screenIsLandscape()"));
    }

    /** A5 意图：防重置位 / 清锁 / 重锁 / finally 复位仍在，且顺序正确（54 号框架未被破坏）。 */
    @Test
    public void a5_deguardRelockAndFinallyStillIntact() {
        assertTrue("A5 须含防重置位 orientationSwitchInProgress = true",
                displayBody.contains("orientationSwitchInProgress = true"));
        assertTrue("A5 须含清锁 smallLock = null", displayBody.contains("smallLock = null"));
        assertTrue("A5 须含重锁 lockFirstFrame()", displayBody.contains("lockFirstFrame()"));
        assertTrue("A5 须含 finally 复位 if (willRelock) orientationSwitchInProgress = false",
                displayBody.contains("if (willRelock) orientationSwitchInProgress = false"));
        assertTrue("A5 清锁须在带参 applyFraming 之前（先清锁才走目标尺寸分支）",
                displayBody.indexOf("smallLock = null")
                        < displayBody.indexOf("applyFraming(lp.width, lp.height)"));
        assertTrue("A5 重锁须在带参 applyFraming 之后（先算目标尺寸再建竖条锁）",
                displayBody.indexOf("lockFirstFrame()")
                        > displayBody.indexOf("applyFraming(lp.width, lp.height)"));
    }

    /**
     * A6 意图：硬边界 token 计数不增不减 + 方向判据 token 存在且计数未改。
     * 四个结构 token 全文=去注释计数一致；两个闪光灯/曝光 token 全文锁 8/2 基线、去注释锁真实下发点唯一 1/1
     * （口径见类 javadoc）。
     */
    @Test
    public void a6_hardBoundaryAndDirectionJudgeTokenCountsUnchanged() {
        // 四个结构 token：全文与去注释计数一致
        assertEquals("A6 invalidateOutline 须恰好 3 次", 3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A6 setDefaultBufferSize( 须恰好 1 次", 1, countOccurrences(codeOnly, "setDefaultBufferSize("));
        assertEquals("A6 hudColumn?.rotation = -deg 须恰好 1 次", 1, countOccurrences(codeOnly, "hudColumn?.rotation = -deg"));
        assertEquals("A6 addView(hudView 须恰好 1 次", 1, countOccurrences(codeOnly, "addView(hudView"));
        // 闪光灯/曝光 token：全文锁基线 8/2，去注释锁真实下发点唯一 1/1
        assertEquals("A6 全文 FLASH_MODE_TORCH 须恰好 8 次（基线，未新增 token）",
                8, countOccurrences(overlaySrc, "FLASH_MODE_TORCH"));
        assertEquals("A6 去注释 FLASH_MODE_TORCH 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "FLASH_MODE_TORCH"));
        assertEquals("A6 全文 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 2 次（基线）",
                2, countOccurrences(overlaySrc, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        assertEquals("A6 去注释 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        // 方向判据 / 优先级 token：存在且去注释计数未改（基线取自源码实测，与实现报告 SC-5 行号一致）
        assertTrue("A6 带参 applyFraming(lp.width, lp.height) 全文须 ≥1",
                countOccurrences(codeOnly, "applyFraming(lp.width, lp.height)") >= 1);
        assertEquals("A6 横条判据 val landscapeWindow = w > h 须恰好 1 次",
                1, countOccurrences(codeOnly, "val landscapeWindow = w > h"));
        assertEquals("A6 竖条不转 !landscapeWindow -> 0 须恰好 1 次",
                1, countOccurrences(codeOnly, "!landscapeWindow -> 0"));
        assertEquals("A6 去注释 alignToWindow 须恰好 3 次（方向对齐判据未增删）",
                3, countOccurrences(codeOnly, "alignToWindow"));
        assertEquals("A6 正方形防御 if (w == h) 须恰好 1 次",
                1, countOccurrences(codeOnly, "if (w == h)"));
        assertEquals("A6 去注释 referenceDegrees 须恰好 3 次",
                3, countOccurrences(codeOnly, "referenceDegrees"));
        assertEquals("A6 去注释 LANDSCAPE_UPRIGHT_ROT_DEG 须恰好 4 次（常量=0 未被改）",
                4, countOccurrences(codeOnly, "LANDSCAPE_UPRIGHT_ROT_DEG"));
    }

    /** A7 意图：54 号 M1 曝光互斥守卫与 L1/L4/L5 诊断日志仍在（本次修复不得打破 54 号修复）。 */
    @Test
    public void a7_54FixGuardAndLogsStillIntact() {
        assertTrue("A7 须保留 54 号 M1 曝光互斥守卫 !(style.torchWithPreview && flashAvailable)",
                codeOnly.contains("!(style.torchWithPreview && flashAvailable)"));
        assertTrue("A7 须保留 L1 日志 [cam] preview req", codeOnly.contains("[cam] preview req"));
        assertTrue("A7 L1 判据须保留 aeComp 三要素日志",
                codeOnly.contains("flashAvailable=$flashAvailable aeCompTarget=$aeCompTarget"));
        assertTrue("A7 须保留 L4 能力探测日志 [cam] 能力探测", codeOnly.contains("[cam] 能力探测"));
        assertTrue("A7 须保留 L5 变化检测日志 torchChanged=$torchChanged",
                codeOnly.contains("torchChanged=$torchChanged"));
        assertTrue("A7 须保留 L5 重下发判据",
                codeOnly.contains("if (torchChanged || lowLightChanged) refreshRequestInSession()"));
    }

    // ==================== B 组 · 真值表（独立参考实现复算） ====================

    /**
     * B1 意图：flipped 三态（调查 SC2）——竖建锁→横、横→竖两次都放行；同朝向不放行；首帧 null 不放行。
     */
    @Test
    public void b1_flippedThreeStates() {
        // 步骤②竖→横：首帧竖屏建锁 lockSL=false，横屏回调 screenSL=true → 放行
        assertTrue("B1 竖建锁(false)→横屏(true) 须 flipped=true", refFlipped(false, true));
        // 步骤③横→竖：横屏重锁后 lockSL=true，回竖屏回调 screenSL=false → 仍放行（判据不是失效点）
        assertTrue("B1 横锁(true)→竖屏(false) 须 flipped=true", refFlipped(true, false));
        // 同朝向重复回调：一律不放行，维持锁定
        assertFalse("B1 竖→竖 同朝向须 flipped=false", refFlipped(false, false));
        assertFalse("B1 横→横 同朝向须 flipped=false", refFlipped(true, true));
        // 首帧尚未建锁：lockSL=null → 不放行
        assertFalse("B1 首帧 lockSL=null 须 flipped=false", refFlipped(null, false));
        assertFalse("B1 首帧 lockSL=null（横屏）须 flipped=false", refFlipped(null, true));
    }

    /** B2 意图：清锁后 buildParams 返回目标朝向尺寸——竖屏竖条 h>w、横屏横条 w>h；未清锁用锁值。 */
    @Test
    public void b2_targetSizeFromBuildParams() {
        int[] portrait = refTargetSizeFromBuildParams(true, false, 488, 845, 845, 488, 999, 999);
        assertArrayEquals("B2 清锁+竖屏 → 竖条 (488,845)", new int[] { 488, 845 }, portrait);
        assertTrue("B2 竖条须 h>w", portrait[1] > portrait[0]);

        int[] landscape = refTargetSizeFromBuildParams(true, true, 488, 845, 845, 488, 999, 999);
        assertArrayEquals("B2 清锁+横屏 → 横条 (845,488)", new int[] { 845, 488 }, landscape);
        assertTrue("B2 横条须 w>h", landscape[0] > landscape[1]);

        int[] locked = refTargetSizeFromBuildParams(false, true, 488, 845, 845, 488, 845, 488);
        assertArrayEquals("B2 未清锁 → 用锁值（横条旧尺寸）", new int[] { 845, 488 }, locked);
    }

    /** B3 意图：带参目标尺寸优先于陈旧尺寸（绕开异步未刷新的旧横条 view 宽高）。 */
    @Test
    public void b3_framingPrefersTargetOverStale() {
        assertArrayEquals("B3 带参命中 → 采用目标竖条而非陈旧横条",
                new int[] { 488, 845 }, refFramingUsesTarget(true, 845, 488, 488, 845));
        assertArrayEquals("B3 不带参 → 回退陈旧横条（bug 路径，用于对照）",
                new int[] { 845, 488 }, refFramingUsesTarget(false, 845, 488, 488, 845));
        assertArrayEquals("B3 带参但目标非正 → 回退陈旧尺寸",
                new int[] { 845, 488 }, refFramingUsesTarget(true, 845, 488, 0, 0));
    }

    /**
     * B4 意图（组合）：竖→横→竖序列里，修复后三次 framing 分别用
     * [竖首帧, 横目标, 竖目标]；回竖屏那一帧用的是竖目标 (488,845)，
     * 而非异步未刷新的陈旧横条 (845,488)——这正是本次修复要锁的核心回归点。
     */
    @Test
    public void b4_portraitLandscapePortraitSequence_usesPortraitTargetOnReturn() {
        final int PW = 488, PH = 845;   // 竖条目标 w<h
        final int LW = 845, LH = 488;   // 横条目标 w>h

        // 首帧竖屏建锁：陈旧 view 就是竖条，flipped 未触发，framing 用竖条
        Boolean lockSL = false;
        int[] staleAtFirst = { PW, PH };
        int[] frameFirst = refFramingUsesTarget(true, staleAtFirst[0], staleAtFirst[1], PW, PH);
        assertArrayEquals("B4 第1帧（竖首帧）用竖条", new int[] { PW, PH }, frameFirst);

        // 转横屏：flipped 放行 → 清锁 → buildParams 返回横目标；此刻 view 陈旧仍是竖条
        assertTrue("B4 竖→横 flipped 须放行", refFlipped(lockSL, true));
        int[] targetLandscape = refTargetSizeFromBuildParams(true, true, PW, PH, LW, LH, 0, 0);
        int[] frameLandscape = refFramingUsesTarget(true, staleAtFirst[0], staleAtFirst[1],
                targetLandscape[0], targetLandscape[1]);
        assertArrayEquals("B4 第2帧（转横）用横目标", new int[] { LW, LH }, frameLandscape);
        lockSL = true; // 横屏重锁：lockScreenLandscape=true

        // 回竖屏：flipped 放行 → 清锁 → buildParams 返回竖目标；此刻 view 陈旧是横条（异步未刷新）
        assertTrue("B4 横→竖 flipped 须放行", refFlipped(lockSL, false));
        int[] targetPortrait = refTargetSizeFromBuildParams(true, false, PW, PH, LW, LH, 0, 0);
        int[] staleAtReturn = { LW, LH }; // 陈旧横条（bug 根因里被误读的尺寸）
        int[] frameReturn = refFramingUsesTarget(true, staleAtReturn[0], staleAtReturn[1],
                targetPortrait[0], targetPortrait[1]);
        assertArrayEquals("B4 第3帧（回竖）须用竖目标 (488,845)", new int[] { PW, PH }, frameReturn);
        assertFalse("B4 回竖帧不得再用陈旧横条 (845,488)",
                frameReturn[0] == staleAtReturn[0] && frameReturn[1] == staleAtReturn[1]);
        assertTrue("B4 回竖帧写成竖条 → lastWindow 竖条 → 新锁竖条（h>w）",
                frameReturn[1] > frameReturn[0]);
        lockSL = false; // 竖条重锁：lockScreenLandscape=false
        assertFalse("B4 同朝向（竖→竖）回调不得再放行", refFlipped(lockSL, false));
    }
}
