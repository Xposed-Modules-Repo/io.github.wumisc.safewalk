package io.github.wumisc.safewalk;

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
 * 5.4.3「旋转重建同步」四处改动的纯 JVM 防回归用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>P1 / P3 / P2-源码契约组</b>：直接读取 {@code RearCameraOverlay.kt} 文本，按花括号配平
 *       截取 {@code applyPoseChange()} / {@code onDisplayChanged()} / {@code buildParams()} /
 *       {@code applyFraming()} 四个方法体，锁住三处改法的<b>结构与写法</b>：
 *       P1 两条重建路径各一次 {@code root.post { root.invalidateOutline() }}（且先提交 lp 后失效）；
 *       P2 高度裁剪行带 {@code maxH > w} 门控；
 *       P3 {@code hudView?.rotation = -deg.toFloat()} 紧随 {@code layer.rotation = deg.toFloat()}。
 *       另设硬边界组，锁住方向公式 / 竖屏固定不转 / 折中 2.0f / 正方形防御 / 5.4.3 注释。</li>
 *   <li><b>P2-纯函数组</b>：从生产裁剪判据抽出等价纯函数
 *       {@code shouldClampHeight(h,maxH,w) = maxH>0 && h>maxH && maxH>w}，穷举真值表。
 *       注意源码里 h / w / maxH <b>同为 px</b>（h=dp(size[1]) 已换算、maxH=screenH*0.62 为 px），
 *       故任务书「260dp / 150dp / maxH=756px」折算到 density≈3.25 为 h=845 / w=488 / maxH=756。</li>
 * </ul>
 *
 * <p><b>只能真机验证、本文件测不到的部分</b>（详见 test-writer-report.md）：
 * View outline 的实际裁剪视觉、post 时机是否等到宽高刷新、180° 时 HUD 文字是否正立可读、
 * 方形窗口的运行时触发帧——均为 Android View 运行时行为，JVM 只能锁源码结构，不能跑行为。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试；零外部依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。写法与 {@code SquareWindowGuardTest} / {@code HudRotationContractTest} /
 * {@code AutoModeHandoffContractTest} 一致。
 */
public class RotationRebuildSync543Test {

    /** 相对模块根 app/ 的源码路径；多路径 fallback 适配 repo 根作为工作目录的情况。 */
    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String POSE_SIG = "private fun applyPoseChange()";
    private static final String DISPLAY_SIG = "fun onDisplayChanged(";
    private static final String BUILD_PARAMS_SIG = "private fun buildParams()";
    private static final String FRAMING_SIG = "private fun applyFraming(";

    private static String overlaySrc;        // 原始 .kt 源码（保留注释，用于断言 5.4.3 注释）
    private static String codeOnly;          // 去注释全文（用于断言全局关键串与计数）
    private static String poseBody;          // applyPoseChange 方法体（去注释）
    private static String displayBody;       // onDisplayChanged 方法体（去注释）
    private static String buildParamsBody;   // buildParams 方法体（去注释）
    private static String framingBody;       // applyFraming 方法体（去注释）

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locateSource()), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
        poseBody = stripComments(extractMethod(overlaySrc, POSE_SIG));
        displayBody = stripComments(extractMethod(overlaySrc, DISPLAY_SIG));
        buildParamsBody = stripComments(extractMethod(overlaySrc, BUILD_PARAMS_SIG));
        framingBody = stripComments(extractMethod(overlaySrc, FRAMING_SIG));
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
     * P2 等价纯函数：复刻生产裁剪条件 {@code maxH > 0 && h > maxH && maxH > w}。
     * 仅当 maxH 有效、确实高于 h、且不会把 h 压到 <= w 时才裁剪。
     */
    private static boolean shouldClampHeight(int h, int maxH, int w) {
        return maxH > 0 && h > maxH && maxH > w;
    }

    /** P3 等价纯函数：HUD 总旋转 = layer.rotation + hudView.rotation，反向抵消后的净值。 */
    private static float netRotation(int deg) {
        return deg + (float) -deg;
    }

    // ==================== P1 组 · 两条重建路径的 outline 失效 ====================

    /** 正则：root.post { root.invalidateOutline() } —— 必须经 post 包裹，且是唯一一次失效。 */
    private static final Pattern POST_INVALIDATE =
            Pattern.compile("root\\s*\\.\\s*post\\s*\\{\\s*root\\s*\\.\\s*invalidateOutline\\s*\\(\\s*\\)\\s*\\}");

    /** P1-A 意图：applyPoseChange() 内恰好一次 post 包裹的 outline 失效。输入=该方法体；预期命中 1 次且无裸露调用。 */
    @Test
    public void p1a_applyPoseChange_singlePostWrappedInvalidateOutline() {
        assertTrue("P1-A applyPoseChange() 须含 root.post { root.invalidateOutline() }",
                POST_INVALIDATE.matcher(poseBody).find());
        assertEquals("P1-A 该方法体内 invalidateOutline 须恰好 1 次（post 包裹那一次，不得重复叠加）",
                1, countOccurrences(poseBody, "invalidateOutline"));
    }

    /** P1-B 意图：onDisplayChanged() 内恰好一次 post 包裹的 outline 失效。输入=该方法体；预期命中 1 次且无裸露调用。 */
    @Test
    public void p1b_onDisplayChanged_singlePostWrappedInvalidateOutline() {
        assertTrue("P1-B onDisplayChanged() 须含 root.post { root.invalidateOutline() }",
                POST_INVALIDATE.matcher(displayBody).find());
        assertEquals("P1-B 该方法体内 invalidateOutline 须恰好 1 次（post 包裹那一次，不得重复叠加）",
                1, countOccurrences(displayBody, "invalidateOutline"));
    }

    /** P1-C 意图：applyPoseChange() 必须先提交新 lp 再失效 outline。输入=该方法体；预期 updateViewLayout 位置 < post 位置。 */
    @Test
    public void p1c_applyPoseChange_invalidateAfterUpdateViewLayout() {
        int update = poseBody.indexOf("updateViewLayout");
        int post = poseBody.indexOf("root.post");
        assertTrue("P1-C applyPoseChange() 须有 updateViewLayout（前置条件）", update >= 0);
        assertTrue("P1-C outline 失效必须在 updateViewLayout 之后（等新尺寸提交）", post > update);
    }

    /** P1-D 意图：onDisplayChanged() 同样先提交 lp 再失效。输入=该方法体；预期 updateViewLayout 位置 < post 位置。 */
    @Test
    public void p1d_onDisplayChanged_invalidateAfterUpdateViewLayout() {
        int update = displayBody.indexOf("updateViewLayout");
        int post = displayBody.indexOf("root.post");
        assertTrue("P1-D onDisplayChanged() 须有 updateViewLayout（前置条件）", update >= 0);
        assertTrue("P1-D outline 失效必须在 updateViewLayout 之后（等新尺寸提交）", post > update);
    }

    /** P1-E 意图：全文件恰好 3 次失效（首次 apply 1 次 + 两条重建路径各 1 次）。输入=全文；预期计数 3。 */
    @Test
    public void p1e_fileHasExactlyThreeOutlineInvalidations() {
        assertEquals("P1-E 全文件 invalidateOutline 须恰好 3 次（1 首次 + 2 重建），防止漏补或重复叠加",
                3, countOccurrences(codeOnly, "invalidateOutline"));
    }

    // ==================== P2 组 · maxH 门控（纯函数真值表 + 源码契约） ====================

    /** P2-A 意图：正常竖条应裁剪。输入 h=845(maxH=756,w=488)（260dp/150dp@3.25）；预期 true。 */
    @Test
    public void p2a_normalPortraitStrip_shouldClampHeight() {
        assertTrue("P2-A 正常竖条 h=845,w=488,maxH=756 应裁剪", shouldClampHeight(845, 756, 488));
    }

    /** P2-B 意图：危险场景 maxH 小于 w 不裁（否则把 h 压到 <= w 变方）。输入 h=845,w=488,maxH=400；预期 false。 */
    @Test
    public void p2b_maxHBelowWidth_noClamp() {
        assertFalse("P2-B maxH=400 < w=488 时不得裁剪", shouldClampHeight(845, 400, 488));
    }

    /** P2-C 边界意图：maxH 恰等于 w 不裁（门控是严格 >）。输入 h=845,w=488,maxH=488；预期 false。 */
    @Test
    public void p2c_maxHEqualsWidth_noClamp() {
        assertFalse("P2-C 边界 maxH == w 时不得裁剪（否则 h 被压到 <= w）", shouldClampHeight(845, 488, 488));
    }

    /** P2-D 意图：h 未超过 maxH 不裁。输入 h=700,w=488,maxH=756；预期 false。 */
    @Test
    public void p2d_heightWithinMax_noClamp() {
        assertFalse("P2-D h=700 <= maxH=756 时无需裁剪", shouldClampHeight(700, 756, 488));
    }

    /** P2-E 边界意图：maxH 非正（非法屏幕高度）不裁。输入 maxH=0 / -1；预期 false。 */
    @Test
    public void p2e_nonPositiveMaxH_noClamp() {
        assertFalse("P2-E maxH=0 时不得裁剪", shouldClampHeight(845, 0, 488));
        assertFalse("P2-E maxH=-1 时不得裁剪", shouldClampHeight(845, -1, 488));
    }

    /** P2-F 意图：buildParams() 的裁剪必须含 maxH > w 门控。输入=该方法体；预期出现 maxH > w。 */
    @Test
    public void p2f_buildParams_clampLineHasMaxHGuard() {
        assertTrue("P2-F buildParams() 裁剪须含 maxH > w 门控，防止把竖条压成正方形",
                Pattern.compile("maxH\\s*>\\s*w").matcher(buildParamsBody).find());
    }

    /** P2-G 意图：裁剪行整体仍为生产修复的精确写法。输入=该方法体；预期整行匹配。 */
    @Test
    public void p2g_buildParams_exactClampLineIntact() {
        assertTrue("P2-G 须保留 if (maxH > 0 && h > maxH && maxH > w) h = maxH",
                Pattern.compile("if\\s*\\(\\s*maxH\\s*>\\s*0\\s*&&\\s*h\\s*>\\s*maxH"
                        + "\\s*&&\\s*maxH\\s*>\\s*w\\s*\\)\\s*h\\s*=\\s*maxH")
                        .matcher(buildParamsBody).find());
    }

    // ==================== P3 组 · HUD 反向旋转 + 布局参数未动 ====================

    /** 正则：两行相邻（去注释后中间只允许空白），layer.rotation 之后紧跟 hudView?.rotation 反向抵消。 */
    private static final Pattern ADJACENT_ROTATIONS = Pattern.compile(
            "layer\\.rotation\\s*=\\s*deg\\.toFloat\\(\\)\\s*"
            + "hudView\\?\\.rotation\\s*=\\s*-deg\\.toFloat\\(\\)");

    /** P3-A 意图：HUD 反向旋转紧随 layer.rotation 之后（两者相邻，防止被挪走/插队）。输入=applyFraming 体；预期相邻匹配。 */
    @Test
    public void p3a_hudInverseRotationImmediatelyAfterLayerRotation() {
        assertTrue("P3-A hudView?.rotation = -deg.toFloat() 必须紧随 layer.rotation = deg.toFloat() 之后",
                ADJACENT_ROTATIONS.matcher(framingBody).find());
    }

    /** P3-B 意图：HUD 旋转用空安全 ?.，不得改成 !! 或裸点调用。输入=applyFraming 体；预期仅 ?. 写法。 */
    @Test
    public void p3b_hudRotationUsesNullSafeCall() {
        assertTrue("P3-B 须用空安全 hudView?.rotation", framingBody.contains("hudView?.rotation = -deg.toFloat()"));
        assertFalse("P3-B 不得改成 hudView!!.rotation（未创建帧会崩）", framingBody.contains("hudView!!"));
        assertFalse("P3-B 不得改成非空安全 hudView.rotation",
                Pattern.compile("hudView(?!\\?)\\s*\\.\\s*rotation").matcher(framingBody).find());
    }

    /** P3-C 意图：HUD 仍由 buildHudView() 构建，未改构造方式。输入=全文；预期关键串存在。 */
    @Test
    public void p3c_hudStillBuiltByBuildHudView() {
        assertTrue("P3-C 须保留 hudView = buildHudView()", codeOnly.contains("hudView = buildHudView()"));
    }

    /** P3-D 意图：hudLp 的 Gravity 仍是 BOTTOM|CENTER_HORIZONTAL。输入=全文；预期关键串存在。 */
    @Test
    public void p3d_hudLpGravityBottomUnchanged() {
        assertTrue("P3-D 须保留 Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL",
                codeOnly.contains("Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL"));
    }

    /** P3-E 意图：HUD 挂载调用与 hudLp 构造仍未改。输入=全文；预期两个关键串存在。 */
    @Test
    public void p3e_hudMountCallAndLayoutParamsUnchanged() {
        assertTrue("P3-E 须保留 addView(hudView, hudLp)", codeOnly.contains("addView(hudView, hudLp)"));
        assertTrue("P3-E 须保留 val hudLp = FrameLayout.LayoutParams(",
                codeOnly.contains("val hudLp = FrameLayout.LayoutParams("));
    }

    /** P3-F 意图：全文件只有一个 HUD 挂载点（防重复挂载出双份文字）。输入=全文；预期计数 1。 */
    @Test
    public void p3f_hudHasSingleMountPoint() {
        assertEquals("P3-F 全文件 addView(hudView 调用点必须恰好 1 个",
                1, countOccurrences(codeOnly, "addView(hudView"));
    }

    /** P3-G 等价意图：反向旋转后净朝向恒为 0（文字正立）。输入 deg=0/90/180/270；预期净旋转 0。 */
    @Test
    public void p3g_inverseRotationCancelsLayerRotation() {
        assertEquals("P3-G deg=0 净旋转须为 0", 0.0f, netRotation(0), 0.0001f);
        assertEquals("P3-G deg=90 净旋转须为 0", 0.0f, netRotation(90), 0.0001f);
        assertEquals("P3-G deg=180 净旋转须为 0（用户反馈现场）", 0.0f, netRotation(180), 0.0001f);
        assertEquals("P3-G deg=270 净旋转须为 0", 0.0f, netRotation(270), 0.0001f);
    }

    // ==================== 硬边界组 · 方向 / 竖屏不转 / 折中 zoom / 正方形防御 / 5.4.3 注释 ====================

    /** D1 意图：横竖判据仍是 val landscapeWindow = w > h。 */
    @Test
    public void d1_landscapeFormulaIntact() {
        assertTrue("D1 applyFraming 须保留 val landscapeWindow = w > h",
                framingBody.contains("val landscapeWindow = w > h"));
    }

    /** D2 意图：竖屏固定不转（!landscapeWindow -> 0）仍在。 */
    @Test
    public void d2_portraitNeverRotatesIntact() {
        assertTrue("D2 须保留 !landscapeWindow -> 0（竖屏 deg 恒 0）",
                framingBody.contains("!landscapeWindow -> 0"));
    }

    /** D3 意图：5.4.0 竖屏折中固定常量 return 2.0f 仍在。 */
    @Test
    public void d3_compromiseZoomTwoIntact() {
        assertTrue("D3 须保留折中缩放固定 return 2.0f", codeOnly.contains("return 2.0f"));
    }

    /** D4 意图：5.4.1 正方形防御 if (w == h) 仍在（本次改动不得削弱）。 */
    @Test
    public void d4_squareGuardIntact() {
        assertTrue("D4 须保留正方形防御 if (w == h)", framingBody.contains("if (w == h)"));
    }

    /** D5 意图：P1 两处 + P2 一处的 5.4.3 注释仍在（注释是改动可追溯性的锚点）。 */
    @Test
    public void d5_p1p2Three543CommentsIntact() {
        assertTrue("D5 applyPoseChange 须保留 5.4.3 注释",
                extractMethod(overlaySrc, POSE_SIG).contains("5.4.3"));
        assertTrue("D5 onDisplayChanged 须保留 5.4.3 注释",
                extractMethod(overlaySrc, DISPLAY_SIG).contains("5.4.3"));
        assertTrue("D5 buildParams 须保留 5.4.3 注释",
                extractMethod(overlaySrc, BUILD_PARAMS_SIG).contains("5.4.3"));
    }

    /** D6 意图：P3 的 5.4.3 注释仍在 applyFraming 内。 */
    @Test
    public void d6_p3Framing543CommentIntact() {
        assertTrue("D6 applyFraming 须保留 P3 的 5.4.3 注释",
                extractMethod(overlaySrc, FRAMING_SIG).contains("5.4.3"));
    }
}
