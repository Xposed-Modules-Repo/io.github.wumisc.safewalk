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
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 5.4.4「固定相机 buffer 档位」的纯 JVM 防回归用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组 · 纯函数（等价测试）</b>：从生产 {@code openCamera()} 的缓冲决策
 *       {@code actualBufferW = minOf(bw0, bh0) / actualBufferH = maxOf(bw0, bh0)} 抽出等价纯函数
 *       {@code fixedBuffer(w, h) = {min(w,h), max(w,h)}}，穷举竖窗 488×845、横窗 845×488、
 *       正方形 500×500、零宽/零高回退，核心断言对称性 {@code fixedBuffer(w,h) == fixedBuffer(h,w)}
 *       —— 首帧窗口是竖条还是横条都落到同一竖条档，这正是本次改法要消除的"每次不一样"。</li>
 *   <li><b>B 组 · 源码契约</b>：按花括号配平截取 {@code openCamera()} 方法体，再在其中定位
 *       {@code val bw0 → st.setDefaultBufferSize(...)} 缓冲决策段，锁住 min/max 短长边写法、
 *       setDefaultBufferSize 仍在、{@code portraitWindow} 二选一与 {@code previewSrcW/H} 回退链
 *       已从该段移除、回退 view 的兜底仍在、全文 setDefaultBufferSize 仅一处（首帧锁定未破）。</li>
 *   <li><b>C 组 · 硬边界未破</b>：锁住方向公式 / 竖屏固定不转 / 折中 2.0f / 正方形防御 /
 *       HUD 反向旋转 / setTransform(null) / maxH&gt;w 门控 / 两处 post 包裹的 invalidateOutline
 *       均原样保留——本轮只允许改 openCamera 缓冲决策这一段。</li>
 * </ul>
 *
 * <p><b>只能真机验证、本文件测不到的部分</b>（详见 test-writer-report.md）：
 * SurfaceTexture 实际缓冲像素内容、HAL 对竖条 488×845 的映射方式（letterbox/crop/拉伸/旋转）、
 * "每次打开画面一致"的真实观感、横屏非等比拉伸的变形程度、首帧姿态与窗口形状的运行时对应——
 * 均为 Android 运行时/HAL 未定义行为，JVM 只能锁决策纯函数与源码结构，不能跑行为。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试；零外部依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。写法与 {@code SquareWindowGuardTest} /
 * {@code RotationRebuildSync543Test} / {@code AutoModeHandoffContractTest} 一致。
 */
public class FixedCameraBuffer544Test {

    /** 相对模块根 app/ 的源码路径；多路径 fallback 适配 repo 根作为工作目录的情况。 */
    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String OPEN_CAMERA_SIG = "private fun openCamera()";
    private static final String FRAMING_SIG = "private fun applyFraming(";

    private static final String BUF_START = "val bw0";
    private static final String BUF_END = "st.setDefaultBufferSize(actualBufferW, actualBufferH)";

    private static String overlaySrc;        // 原始 .kt 源码（保留注释）
    private static String codeOnly;          // 去注释全文（用于全局关键串与计数）
    private static String openCameraBody;    // openCamera() 方法体（去注释）
    private static String framingBody;       // applyFraming() 方法体（去注释）
    private static String bufferDecision;    // openCamera() 内 val bw0 → setDefaultBufferSize 段（去注释）

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locateSource()), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
        openCameraBody = stripComments(extractMethod(overlaySrc, OPEN_CAMERA_SIG));
        framingBody = stripComments(extractMethod(overlaySrc, FRAMING_SIG));
        bufferDecision = extractBufferDecision(openCameraBody);
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

    /** 从签名起按花括号配平截取单个方法文本（含外层花括号），跳过字符串/字符字面量里的花括号。 */
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

    /** 在 openCamera() 方法体内定位缓冲决策段：从 val bw0 到 setDefaultBufferSize 调用整行。 */
    private static String extractBufferDecision(String body) {
        int start = body.indexOf(BUF_START);
        assertTrue("openCamera() 内找不到缓冲决策起点 " + BUF_START, start >= 0);
        int end = body.indexOf(BUF_END, start);
        assertTrue("openCamera() 内找不到缓冲决策终点 " + BUF_END, end >= 0);
        return body.substring(start, end + BUF_END.length());
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

    /**
     * 5.4.4 等价纯函数：复刻生产缓冲决策
     * {@code actualBufferW = minOf(bw0, bh0) / actualBufferH = maxOf(bw0, bh0)}。
     * 返回顺序固定为 {短边, 长边}，即竖条档 (w'=min, h'=max)。
     */
    private static int[] fixedBuffer(int w, int h) {
        return new int[] { Math.min(w, h), Math.max(w, h) };
    }

    /** 复刻生产回退：lastWindow 尺寸 >0 取之，否则回退 view 的同名维度。 */
    private static int resolveDim(int last, int viewDim) {
        return last > 0 ? last : viewDim;
    }

    // ==================== A 组 · 纯函数「竖条档」决策（等价测试） ====================

    /** A1 意图：首帧竖窗 488×845 得到短边×长边竖条档 488×845。 */
    @Test
    public void a1_portraitWindow_yieldsPortraitTier() {
        assertArrayEquals("竖窗 488×845 必须得到 488×845 竖条档",
                new int[] { 488, 845 }, fixedBuffer(488, 845));
    }

    /** A2 意图：首帧横窗 845×488（关键）也必须落到同一竖条档 488×845，而非横条档。 */
    @Test
    public void a2_landscapeWindow_yieldsSamePortraitTier() {
        assertArrayEquals("横窗 845×488 必须与竖窗得到同一 488×845 竖条档（本次改法核心）",
                new int[] { 488, 845 }, fixedBuffer(845, 488));
    }

    /** A3 意图：横竖首帧结果直接相等——首帧形状无关，消除"每次不一样"的纯函数保证。 */
    @Test
    public void a3_portraitAndLandscape_yieldIdenticalTier() {
        assertArrayEquals("fixedBuffer(488,845) 与 fixedBuffer(845,488) 必须逐项相等",
                fixedBuffer(488, 845), fixedBuffer(845, 488));
    }

    /** A4 意图：正方形 500×500 退化为 500×500（min=max），不产生非法倒挂。 */
    @Test
    public void a4_squareWindow_yieldsSameSizeTier() {
        assertArrayEquals("正方形 500×500 必须得到 500×500",
                new int[] { 500, 500 }, fixedBuffer(500, 500));
    }

    /** A5 意图：对称性核心断言——任意 (w,h) 与 (h,w) 结果一致，穷举横/竖/方/极端/零值。 */
    @Test
    public void a5_symmetryAcrossAllShapes() {
        int[][] pairs = {
                { 488, 845 }, { 845, 488 }, { 500, 500 },
                { 1080, 1920 }, { 1920, 1080 },
                { 150, 260 }, { 260, 150 },
                { 1, 999 }, { 999, 1 }, { 7, 7 },
                { 0, 845 }, { 845, 0 }, { 0, 0 }
        };
        for (int[] p : pairs) {
            assertArrayEquals("对称性失败：(" + p[0] + "," + p[1] + ") 与 (" + p[1] + "," + p[0] + ") 必须相同",
                    fixedBuffer(p[0], p[1]), fixedBuffer(p[1], p[0]));
        }
    }

    /** A6 意图：档位恒为「宽<=高」的竖条形态，任意输入都不倒挂成横条 (w'>h')。 */
    @Test
    public void a6_widthNeverExceedsHeight() {
        int[][] pairs = { { 488, 845 }, { 845, 488 }, { 500, 500 }, { 1080, 1920 }, { 1, 999 } };
        for (int[] p : pairs) {
            int[] got = fixedBuffer(p[0], p[1]);
            assertTrue("档位宽不得大于高：输入(" + p[0] + "," + p[1] + ") 得到 (" + got[0] + "," + got[1] + ")",
                    got[0] <= got[1]);
        }
    }

    /** A7 意图：零宽回退场景——lastWindowW=0 回退 view.width=488，仍得 488×845 竖条档。 */
    @Test
    public void a7_zeroWidthFallback_yieldsPortraitTier() {
        int bw0 = resolveDim(0, 488);   // lastWindowW 未量出 → view.width
        int bh0 = resolveDim(845, 845); // lastWindowH 正常
        assertArrayEquals("零宽回退后仍须得到 488×845 竖条档",
                new int[] { 488, 845 }, fixedBuffer(bw0, bh0));
    }

    /** A8 意图：宽高都未量出时整对回退 view（845×488 横条 view），仍归一化到 488×845 竖条档。 */
    @Test
    public void a8_bothDimensionsFallback_yieldsPortraitTier() {
        int bw0 = resolveDim(0, 845);
        int bh0 = resolveDim(0, 488);
        assertArrayEquals("回退到横条 view 尺寸也须归一化到 488×845 竖条档",
                new int[] { 488, 845 }, fixedBuffer(bw0, bh0));
    }

    // ==================== B 组 · 源码契约（openCamera 缓冲决策段） ====================

    /** B1 意图：宽取 minOf(bw0, bh0) 短边。输入=缓冲决策段；预期赋值行存在。 */
    @Test
    public void b1_bufferWidthUsesMinOfShortSide() {
        assertTrue("缓冲宽须为 actualBufferW = minOf(bw0, bh0)",
                Pattern.compile("actualBufferW\\s*=\\s*minOf\\s*\\(\\s*bw0\\s*,\\s*bh0\\s*\\)")
                        .matcher(bufferDecision).find());
    }

    /** B2 意图：高取 maxOf(bw0, bh0) 长边。输入=缓冲决策段；预期赋值行存在。 */
    @Test
    public void b2_bufferHeightUsesMaxOfLongSide() {
        assertTrue("缓冲高须为 actualBufferH = maxOf(bw0, bh0)",
                Pattern.compile("actualBufferH\\s*=\\s*maxOf\\s*\\(\\s*bw0\\s*,\\s*bh0\\s*\\)")
                        .matcher(bufferDecision).find());
    }

    /** B3 意图：setDefaultBufferSize(actualBufferW, actualBufferH) 仍写在缓冲决策段内。 */
    @Test
    public void b3_defaultBufferSizeStillApplied() {
        assertTrue("缓冲决策段内须保留 st.setDefaultBufferSize(actualBufferW, actualBufferH)",
                bufferDecision.contains(BUF_END));
    }

    /** B4 意图（关键）：portraitWindow 二选一已从缓冲决策段移除。 */
    @Test
    public void b4_portraitWindowRemovedFromBufferDecision() {
        assertFalse("缓冲决策段不得再出现 portraitWindow（二选一必须已删除）",
                bufferDecision.contains("portraitWindow"));
    }

    /** B5 意图：previewSrcW/H 回退链已从缓冲决策段移除（定义保留供诊断，但不得参与决策）。 */
    @Test
    public void b5_previewSrcFallbackRemovedFromBufferDecision() {
        assertFalse("缓冲决策段不得出现 previewSrcW", bufferDecision.contains("previewSrcW"));
        assertFalse("缓冲决策段不得出现 previewSrcH", bufferDecision.contains("previewSrcH"));
    }

    /** B6 意图：窗口尺寸缺失时仍回退 view 的短长边（零宽/零高回退的源码兜底）。 */
    @Test
    public void b6_windowFallbackToViewIntact() {
        assertTrue("须保留 val bw0 = if (lastWindowW > 0) lastWindowW else view.width",
                Pattern.compile("val\\s+bw0\\s*=\\s*if\\s*\\(\\s*lastWindowW\\s*>\\s*0\\s*\\)"
                        + "\\s*lastWindowW\\s*else\\s*view\\.width").matcher(bufferDecision).find());
        assertTrue("须保留 val bh0 = if (lastWindowH > 0) lastWindowH else view.height",
                Pattern.compile("val\\s+bh0\\s*=\\s*if\\s*\\(\\s*lastWindowH\\s*>\\s*0\\s*\\)"
                        + "\\s*lastWindowH\\s*else\\s*view\\.height").matcher(bufferDecision).find());
    }

    /** B7 意图：全文 setDefaultBufferSize 仅一处，首帧锁定机制未被破坏（调查报告 F1）。 */
    @Test
    public void b7_defaultBufferSizeCalledExactlyOnce() {
        assertEquals("全文 setDefaultBufferSize 调用须恰好 1 处（buffer 首帧锁定，切换不重设）",
                1, countOccurrences(codeOnly, "setDefaultBufferSize("));
    }

    /** B8 意图：openCamera 内保留 5.4.4 固定档位注释（改动可追溯锚点，实现报告 SC5）。 */
    @Test
    public void b8_fixTierComment544Intact() {
        assertTrue("openCamera 须保留 5.4.4 固定档位注释",
                extractMethod(overlaySrc, OPEN_CAMERA_SIG).contains("【5.4.4】固定 buffer 档位"));
    }

    // ==================== C 组 · 硬边界未破 ====================

    /** C1 意图：横竖判据仍是 val landscapeWindow = w > h。 */
    @Test
    public void c1_landscapeFormulaIntact() {
        assertTrue("applyFraming 须保留 val landscapeWindow = w > h",
                framingBody.contains("val landscapeWindow = w > h"));
    }

    /** C2 意图：竖屏固定不转（!landscapeWindow -> 0）仍在。 */
    @Test
    public void c2_portraitNeverRotatesIntact() {
        assertTrue("须保留 !landscapeWindow -> 0（竖屏 deg 恒 0）",
                framingBody.contains("!landscapeWindow -> 0"));
    }

    /** C3 意图：5.4.0 竖屏折中固定常量 return 2.0f 仍在。 */
    @Test
    public void c3_compromiseZoomTwoIntact() {
        assertTrue("须保留折中缩放固定 return 2.0f", codeOnly.contains("return 2.0f"));
    }

    /** C4 意图：5.4.1 正方形防御 if (w == h) 仍在（本轮不得削弱）。 */
    @Test
    public void c4_squareGuardIntact() {
        assertTrue("须保留正方形防御 if (w == h)", framingBody.contains("if (w == h)"));
    }

    /** C5 意图：5.4.2 HUD 反向旋转 hudView?.rotation = -deg.toFloat() 仍在。 */
    @Test
    public void c5_hudInverseRotationIntact() {
        assertTrue("须保留 hudView?.rotation = -deg.toFloat()",
                framingBody.contains("hudView?.rotation = -deg.toFloat()"));
    }

    /** C6 意图：5.4.0 setTransform(null) 矩阵路线原样（不得改走 setTransform(matrix)）。 */
    @Test
    public void c6_setTransformNullIntact() {
        assertTrue("须保留 view.setTransform(null)", framingBody.contains("view.setTransform(null)"));
    }

    /** C7 意图：5.4.3 高度裁剪 maxH > w 门控原样（精确整行）。 */
    @Test
    public void c7_heightClampGuardIntact() {
        assertTrue("须保留 if (maxH > 0 && h > maxH && maxH > w) h = maxH",
                Pattern.compile("if\\s*\\(\\s*maxH\\s*>\\s*0\\s*&&\\s*h\\s*>\\s*maxH"
                        + "\\s*&&\\s*maxH\\s*>\\s*w\\s*\\)\\s*h\\s*=\\s*maxH")
                        .matcher(codeOnly).find());
    }

    /** C8 意图：5.4.3 两条重建路径各一次 post 包裹的 invalidateOutline（"两处"）仍在。 */
    @Test
    public void c8_twoRebuildInvalidateOutlineIntact() {
        Pattern postInvalidate = Pattern.compile(
                "root\\s*\\.\\s*post\\s*\\{\\s*root\\s*\\.\\s*invalidateOutline\\s*\\(\\s*\\)\\s*\\}");
        int hits = 0;
        java.util.regex.Matcher m = postInvalidate.matcher(codeOnly);
        while (m.find()) hits++;
        assertEquals("须保留两处 root.post { root.invalidateOutline() }（applyPoseChange + onDisplayChanged）",
                2, hits);
    }

    /** C9 意图：全文件 invalidateOutline 恰好 3 次（1 首次 + 2 重建），防漏补或重复叠加。 */
    @Test
    public void c9_totalInvalidateOutlineCountIntact() {
        assertEquals("全文件 invalidateOutline 须恰好 3 次（1 首次 + 2 重建）",
                3, countOccurrences(codeOnly, "invalidateOutline"));
    }
}
