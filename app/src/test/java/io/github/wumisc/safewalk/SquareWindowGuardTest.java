package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * S1「小窗变正方形」修复（5.4.1）的纯 JVM 回归用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组</b>是<b>等价测试</b>：把「是否应早退」抽象成纯函数
 *       {@code shouldSkipFraming(w,h) = w<=0 || h<=0 || w==h}，复刻生产
 *       {@code applyFraming()} 里两段早退判据的合取，穷举真值表。生产函数依赖 Android
 *       View（tv / rotLayer / TextureView），JVM 无法直接跑，故不反射而用等价复刻。</li>
 *   <li><b>B 组</b>是<b>等价测试</b>：用纯函数 {@code selectSize(...)} 复刻
 *       {@code applyFraming()} 里「同一优先级、宽高成对取」的 when 选择逻辑，
 *       穷举四来源 + 错配场景，证明任一来源宽高不全时整源跳过、不会拼出 w 来自 A / h 来自 B。</li>
 *   <li><b>C / D 组</b>是<b>源码契约测试</b>：直接读取 {@code RearCameraOverlay.kt} 文本，
 *       锁住三点改法（正方形早退且顺序正确、宽高同源单一 when、teardown 四项清零）
 *       与方向层未破（landscape 公式、竖屏固定不转、alignToWindow、折中 zoom）。
 *       任何 JVM 都能跑，零环境依赖。此法是因 applyFraming 依赖 Android View 无法在 JVM
 *       反射执行而采取的替代；若将来可重构出「来源选择」纯函数，应把 B/C 组替换为直接调用。</li>
 * </ul>
 *
 * <p><b>只能真机验证、本文件测不到的部分</b>（详见 test-writer-report.md）：
 * 中间态 w==h 是否真在运行时命中、命中后下一帧是否带回稳定尺寸自愈、节流日志是否按预期落盘、
 * 以及修复后小窗在真实横竖切换下不再变方——这些需要真机加日志复现，本文件只锁 JVM 可判定的
 * 判据/结构/等价逻辑。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，不引入新依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。
 */
public class SquareWindowGuardTest {

    /** 相对模块根 app/ 的源码路径；多路径 fallback 适配 repo 根作为工作目录的情况。 */
    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String APPLY_FRAMING_SIG = "private fun applyFraming(";
    private static final String TEARDOWN_SIG = "private fun teardown()";

    private static String overlaySrc;       // 原始 .kt 源码
    private static String framingBody;      // applyFraming 方法体（去掉注释）
    private static String teardownBody;     // teardown 方法体（去掉注释）

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locateSource()), StandardCharsets.UTF_8);
        framingBody = stripComments(extractMethod(overlaySrc, APPLY_FRAMING_SIG));
        teardownBody = stripComments(extractMethod(overlaySrc, TEARDOWN_SIG));
    }

    /** 多候选路径定位源码：AGP 单测工作目录通常是模块根 app/，但也可能被改成 repo 根。 */
    private static Path locateSource() throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(SOURCE_REL));
        candidates.add(Paths.get("app", SOURCE_REL));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path cwd = Paths.get(userDir);
            candidates.add(cwd.resolve(SOURCE_REL));
            candidates.add(cwd.resolve("app").resolve(SOURCE_REL));
            Path parent = cwd.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("app").resolve(SOURCE_REL));
                candidates.add(parent.resolve(SOURCE_REL));
            }
        }
        List<String> tried = new ArrayList<String>();
        for (Path p : candidates) {
            tried.add(p.toAbsolutePath().toString());
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return p;
            }
        }
        throw new IOException("找不到 RearCameraOverlay.kt，已尝试路径：" + tried);
    }

    /**
     * 从签名起按花括号配平截取单个方法文本；配平时跳过字符串/字符字面量里的花括号，
     * 且只处理目标方法体（避开 companion 里 AGSL 三引号字符串干扰）。
     */
    private static String extractMethod(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("源码中找不到方法签名：" + signature, start >= 0);
        int brace = src.indexOf('{', start);
        assertTrue("方法签名后找不到左花括号：" + signature, brace >= 0);
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
        throw new IllegalStateException("方法花括号不闭合：" + signature);
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
                    if (i < cs.length) {
                        out.append(cs[i]);
                    }
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

    /** Kotlin 无分号：字段「名 = 0」且 0 后为单词边界（不接受 0x / 非零）。 */
    private static boolean resetsToZero(String body, String field) {
        return Pattern.compile(Pattern.quote(field) + "\\s*=\\s*0\\b").matcher(body).find();
    }

    // ============ A 组 · 正方形防御决策真值表（等价测试） ============

    /** 等价复刻：两段早退判据的合取——非法尺寸或正方形都应跳过本帧。 */
    private static boolean shouldSkipFraming(int w, int h) {
        return w <= 0 || h <= 0 || w == h;
    }

    /** A1 意图：竖条 488x845 是合法非方，不应跳过。 */
    @Test
    public void a1_portrait488x845_notSkipped() {
        assertFalse("竖条 488x845 必须继续走取景", shouldSkipFraming(488, 845));
    }

    /** A2 意图：横条 845x488 是合法非方，不应跳过。 */
    @Test
    public void a2_landscape845x488_notSkipped() {
        assertFalse("横条 845x488 必须继续走取景", shouldSkipFraming(845, 488));
    }

    /** A3 意图：本次 bug 核心——正方形 500x500 必须早退。 */
    @Test
    public void a3_square500x500_skipped() {
        assertTrue("正方形 500x500 必须跳过", shouldSkipFraming(500, 500));
    }

    /** A4 意图：零宽 0x845 是非法尺寸，必须早退。 */
    @Test
    public void a4_zeroWidth_skipped() {
        assertTrue("零宽 0x845 必须跳过", shouldSkipFraming(0, 845));
    }

    /** A5 意图：非法负宽 -1x488 必须早退。 */
    @Test
    public void a5_negativeWidth_skipped() {
        assertTrue("非法 -1x488 必须跳过", shouldSkipFraming(-1, 488));
    }

    /** A6 意图：零高 488x0 是非法尺寸，必须早退（覆盖 h<=0 分支）。 */
    @Test
    public void a6_zeroHeight_skipped() {
        assertTrue("零高 488x0 必须跳过", shouldSkipFraming(488, 0));
    }

    /** A7 意图：0x0 极端值必须早退。 */
    @Test
    public void a7_zeroByZero_skipped() {
        assertTrue("0x0 必须跳过", shouldSkipFraming(0, 0));
    }

    /** A8 意图：极小正方形 1x1 也要早退——判据是相等而非达到某个尺寸阈值。 */
    @Test
    public void a8_tinySquare1x1_skipped() {
        assertTrue("极小正方形 1x1 必须跳过", shouldSkipFraming(1, 1));
    }

    /** A9 意图：差 1 的非方 484x485 不应被误杀——只拦相等，不拦接近。 */
    @Test
    public void a9_nearSquare484x485_notSkipped() {
        assertFalse("差 1 的非方 484x485 不应跳过", shouldSkipFraming(484, 485));
    }

    // ============ B 组 · 宽高同源来源选择（等价测试） ============

    /** 一个来源的宽高对；用对象引用代表来源身份，便于断言「整源被采用」。 */
    private static final class Size {
        final int w;
        final int h;

        Size(int w, int h) {
            this.w = w;
            this.h = h;
        }
    }

    /**
     * 等价复刻 applyFraming 的四级来源 when：选中第一个「宽高都 > 0」的来源后整源返回，
     * 任一来源只有单边有效就跳过、绝不把不同来源的宽高拼在一起。
     */
    private static Size selectSize(Size explicit, Size root, Size last, Size view) {
        if (explicit.w > 0 && explicit.h > 0) return explicit;
        if (root != null && root.w > 0 && root.h > 0) return root;
        if (last.w > 0 && last.h > 0) return last;
        if (view.w > 0 && view.h > 0) return view;
        return new Size(0, 0);
    }

    /** B1 意图：显式来源宽高都有效时，宽高一起取自显式来源（最高优先级）。 */
    @Test
    public void b1_explicitBothValid_takenAsPair() {
        Size explicit = new Size(488, 845);
        assertSame("显式宽高都有效必须一起取自显式来源",
                explicit, selectSize(explicit, new Size(500, 500), new Size(1, 2), new Size(3, 4)));
    }

    /** B2 意图：显式只有宽有效（单边），整源跳过，回退到 rootView——不采信半对显式值。 */
    @Test
    public void b2_explicitHalfValid_skipsWholeSource() {
        Size root = new Size(488, 845);
        assertSame("显式单边有效必须整源跳过并取 rootView",
                root, selectSize(new Size(488, 0), root, new Size(1, 2), new Size(3, 4)));
    }

    /** B3 意图：显式无效时，rootView 宽高都有效则一起取自 rootView。 */
    @Test
    public void b3_rootBothValid_takenAsPair() {
        Size root = new Size(845, 488);
        assertSame("显式无效 + rootView 宽高都有效应取 rootView",
                root, selectSize(new Size(0, 0), root, new Size(1, 2), new Size(3, 4)));
    }

    /** B4 意图：显式、rootView 都无效时，回退 lastWindow 也成对取。 */
    @Test
    public void b4_lastWindowBothValid_takenAsPair() {
        Size last = new Size(488, 845);
        assertSame("回退 lastWindow 必须宽高成对取",
                last, selectSize(new Size(0, 0), null, last, new Size(3, 4)));
    }

    /** B5 意图：前三级都无效时，回退 view 也成对取。 */
    @Test
    public void b5_viewBothValid_takenAsPair() {
        Size view = new Size(845, 488);
        assertSame("回退 view 必须宽高成对取",
                view, selectSize(new Size(0, 0), null, new Size(0, 0), view));
    }

    /** B6 意图：错配防护——显式只有宽、root 只有高、last 只有宽、view 只有高，
     *  任一来源都不全时不得拼出 (488,845)，必须回退 (0,0)。这是 w 来自 A / h 来自 B 的核心回归点。 */
    @Test
    public void b6_everySourcePartial_neverMismatches_fallsBackToZero() {
        Size got = selectSize(
                new Size(488, 0),    // 仅宽
                new Size(0, 845),    // 仅高
                new Size(500, 0),    // 仅宽
                new Size(0, 500));   // 仅高
        assertEquals("错配场景下宽必须为 0（不得采信任何单边来源）", 0, got.w);
        assertEquals("错配场景下高必须为 0（不得拼出跨来源的错配尺寸）", 0, got.h);
    }

    /** B7 意图：四个来源全无效时回退 (0,0)，交给上层 w<=0||h<=0 早退。 */
    @Test
    public void b7_allSourcesInvalid_fallsBackToZero() {
        Size got = selectSize(new Size(0, 0), null, new Size(-1, -1), new Size(0, 0));
        assertEquals("全无效回退宽必须为 0", 0, got.w);
        assertEquals("全无效回退高必须为 0", 0, got.h);
    }

    // ============ C 组 · 三点改法源码契约 ============

    /** C1 意图：正方形早退必须在 w<=0||h<=0 之后、写 lastWindowW 之前（早退在任何污染前）。 */
    @Test
    public void c1_squareGuardOrderingBeforeStateWrite() {
        int guard = framingBody.indexOf("if (w <= 0 || h <= 0) return");
        int square = framingBody.indexOf("if (w == h)");
        int write = framingBody.indexOf("lastWindowW = w");
        assertTrue("须保留 if (w <= 0 || h <= 0) return 防御", guard >= 0);
        assertTrue("须在防御之后加 if (w == h) 早退", square > guard);
        assertTrue("正方形早退必须早于写 lastWindowW（不得污染缓存）", write > square);
        assertTrue("正方形早退分支内必须有 return（跳过本次取景）",
                framingBody.substring(square, write).contains("return"));
    }

    /** C2 意图：正方形命中时走统一节流出口打指定日志，且用 try/catch 防御包裹。 */
    @Test
    public void c2_squareGuardLogsViaThrottle() {
        assertTrue("正方形命中须打 [cam] 正方形中间态已拦截日志",
                framingBody.contains("[cam] 正方形中间态已拦截 w=h=$w"));
        assertTrue("日志必须走 DiagLog.logThrottled(\"cam.square\", ...) 节流通道",
                framingBody.contains("DiagLog.logThrottled(\"cam.square\""));
    }

    /** C3 意图：宽高由单一 when 同时解构取值，不得再有两个独立 val/var w、h 赋值。 */
    @Test
    public void c3_sizeResolutionIsSingleWhen() {
        assertTrue("必须是单一来源 val (w, h) = when",
                framingBody.contains("val (w, h) = when"));
        assertFalse("不得再分离声明 val w = ...",
                Pattern.compile("\\bval\\s+w\\s*=").matcher(framingBody).find());
        assertFalse("不得再分离声明 val h = ...",
                Pattern.compile("\\bval\\s+h\\s*=").matcher(framingBody).find());
        assertFalse("不得再分离声明 var w = ...",
                Pattern.compile("\\bvar\\s+w\\s*=").matcher(framingBody).find());
        assertFalse("不得再分离声明 var h = ...",
                Pattern.compile("\\bvar\\s+h\\s*=").matcher(framingBody).find());
    }

    /** C4 意图：四级来源每一级都要求宽高成对 >0，末级回退 (0,0)——杜绝错配取源。 */
    @Test
    public void c4_everySourceRequiresBothDimensions() {
        assertTrue("显式来源须宽高都 >0", framingBody.contains("windowW > 0 && windowH > 0"));
        assertTrue("rootView 须宽高都 >0",
                framingBody.contains("rv.width > 0 && rv.height > 0"));
        assertTrue("lastWindow 须宽高都 >0",
                framingBody.contains("lastWindowW > 0 && lastWindowH > 0"));
        assertTrue("view 须宽高都 >0",
                framingBody.contains("view.width > 0 && view.height > 0"));
        assertTrue("末级回退必须是 0 to 0", framingBody.contains("else -> 0 to 0"));
    }

    /** C5 意图：teardown 必须清零四个尺寸状态，切断跨会话正方形污染。 */
    @Test
    public void c5_teardownClearsFourSizeStates() {
        assertTrue("teardown 缺 lastWindowW=0", resetsToZero(teardownBody, "lastWindowW"));
        assertTrue("teardown 缺 lastWindowH=0", resetsToZero(teardownBody, "lastWindowH"));
        assertTrue("teardown 缺 actualBufferW=0", resetsToZero(teardownBody, "actualBufferW"));
        assertTrue("teardown 缺 actualBufferH=0", resetsToZero(teardownBody, "actualBufferH"));
    }

    // ============ D 组 · 方向层未破（源码契约 + 等价真值表） ============

    /** D1 意图：横竖判定仍是 w>h，正方形判 false（方向公式未被改）。 */
    @Test
    public void d1_landscapeFormulaIntact() {
        assertTrue("须保留 val landscapeWindow = w > h",
                framingBody.contains("val landscapeWindow = w > h"));
    }

    /** D2 意图：竖屏固定不转（!landscapeWindow -> 0 deg）分支未被这次改动破坏。 */
    @Test
    public void d2_portraitNeverRotatesIntact() {
        assertTrue("须保留 !landscapeWindow -> 0（竖屏 deg 恒 0）",
                framingBody.contains("!landscapeWindow -> 0"));
    }

    /** D3 意图：横屏方向对齐仍走 PreviewOrientationPolicy.alignToWindow。 */
    @Test
    public void d3_alignToWindowIntact() {
        assertTrue("横屏仍须调用 PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)",
                framingBody.contains("PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)"));
    }

    /** D4 意图：5.4.0 折中 zoom 调用链仍在（applyFramingZoom 调用 + 纯函数固定 2.0f）。 */
    @Test
    public void d4_compromiseZoomChainIntact() {
        assertTrue("applyFraming 仍须调用 applyFramingZoom(view, boxW, boxH, deg, landscapeWindow)",
                framingBody.contains("applyFramingZoom(view, boxW, boxH, deg, landscapeWindow)"));
        assertTrue("portraitCompromiseZoom 竖屏分支须仍返回固定 2.0f",
                overlaySrc.contains("private fun portraitCompromiseZoom")
                        && overlaySrc.contains("return 2.0f"));
    }

    /** 等价方向决策：landscapeWindow = w > h（与生产一致）。 */
    private static boolean isLandscape(int w, int h) {
        return w > h;
    }

    /** D5 意图：横屏 845x488 判横、竖屏 488x845 判竖、正方形 500x500 判非横——方向决策未变。 */
    @Test
    public void d5_orientationTruthTableUnchanged() {
        assertTrue("845x488 必须判横屏", isLandscape(845, 488));
        assertFalse("488x845 必须判竖屏", isLandscape(488, 845));
        assertFalse("500x500 判非横（正方形会被 A 组早退拦截）", isLandscape(500, 500));
    }
}
