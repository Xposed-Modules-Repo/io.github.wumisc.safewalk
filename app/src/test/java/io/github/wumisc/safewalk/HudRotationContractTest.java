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

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Bug B「HUD 文字跟随旋转」（5.4.2）的纯 JVM 源码契约回归用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * 本文件全部为<b>源码契约测试</b>：直接读取 {@code RearCameraOverlay.kt} 文本，
 * 按花括号配平截取 {@code rootView} / {@code rotLayer} 两个 apply 块，锁住四处改法：
 * <ol>
 *   <li>HUD 的 {@code addView(hudView, hudLp)} 归属 {@code rotLayer} 块内，
 *       不再出现在 {@code rootView} 的直属层级；</li>
 *   <li>该调用在 {@code addView(texture, ...)} <b>之后</b>（FrameLayout 后加者在上，HUD 盖住画面）；</li>
 *   <li>{@code hudView = buildHudView()} 与 {@code hudLp} 的 Gravity / bottomMargin 参数一字未动；</li>
 *   <li>方向 / 填充硬边界关键行（landscape 公式、竖屏固定不转、折中 2.0f、正方形防御）
 *       与 openCamera 缓冲已固定为竖条档（minOf/maxOf，5.4.4）仍存在，且全文件仅一个 HUD 挂载点。</li>
 * </ol>
 * 因真实的 View.rotation 继承、FrameLayout z 序与布局落点是 Android View 运行时行为，
 * 纯 JVM 无法直接跑，故以「锁结构」替代「跑行为」；因此把旋转/布局行为交真机验证
 * （见 test-writer-report.md）。此法与 {@code SquareWindowGuardTest} /
 * {@code PortraitCompromiseZoomTest} / {@code AutoModeHandoffContractTest} 一致：零外部依赖，任何 JVM 可跑。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，不引入新依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。
 */
public class HudRotationContractTest {

    /** 相对模块根 app/ 的源码路径；多路径 fallback 适配 repo 根作为工作目录的情况。 */
    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String ROOT_SIG = "rootView = FrameLayout";
    private static final String ROT_SIG = "rotLayer = FrameLayout";

    private static String overlaySrc;   // 原始 .kt 源码（保留注释）
    private static String codeOnly;     // 去掉 // 与 /* */ 注释后的源码，避免注释文字造成误判

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locateSource()), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
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

    /** 从左花括号起按配平找匹配的右花括号；跳过字符串/字符字面量里的花括号。 */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        char[] cs = s.toCharArray();
        for (int i = open; i < cs.length; i++) {
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
                    return i;
                }
            }
        }
        return -1;
    }

    /** 取「签名后第一个 { 到匹配 }」之间的文本（不含外层花括号）。 */
    private static String blockOf(String src, String signature) {
        int sig = src.indexOf(signature);
        assertTrue("源码中找不到块签名：" + signature, sig >= 0);
        int open = src.indexOf('{', sig);
        assertTrue("块签名后找不到左花括号：" + signature, open >= 0);
        int close = matchingBrace(src, open);
        assertTrue("块花括号不闭合：" + signature, close > open);
        return src.substring(open + 1, close);
    }

    /** 从外层块文本中切除一个嵌套子块（含其花括号），得到「直属层级」文本。 */
    private static String withoutNestedBlock(String outer, String signature) {
        int sig = outer.indexOf(signature);
        assertTrue("外层块内找不到嵌套块签名：" + signature, sig >= 0);
        int open = outer.indexOf('{', sig);
        assertTrue("嵌套块签名后找不到左花括号：" + signature, open >= 0);
        int close = matchingBrace(outer, open);
        assertTrue("嵌套块花括号不闭合：" + signature, close > open);
        return outer.substring(0, sig) + outer.substring(close + 1);
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

    // ============ A 组 · HUD 归属 rotLayer（不再是 rootView 直属子节点） ============

    /** A1 意图：HUD 的 addView 必须落在 rotLayer 的 apply 块内，才能继承画面旋转。 */
    @Test
    public void a1_hudAddedInsideRotLayerBlock() {
        String rotBlock = blockOf(codeOnly, ROT_SIG);
        assertTrue("A1 addView(hudView, hudLp) 必须位于 rotLayer 的 apply 块内",
                rotBlock.contains("addView(hudView, hudLp)"));
    }

    /** A2 意图：rootView 直属层级（切除 rotLayer 子块后）不得再直接 addView HUD。 */
    @Test
    public void a2_hudNotDirectChildOfRootView() {
        String rootBlock = blockOf(codeOnly, ROOT_SIG);
        String rootDirect = withoutNestedBlock(rootBlock, ROT_SIG);
        assertFalse("A2 HUD 不得再挂在 rootView 直属层级（必须已移进 rotLayer）",
                rootDirect.contains("addView(hudView"));
    }

    /** A3 意图：rootView 仍把 rotLayer 加入窗口根，层级链未断（HUD 的新父仍是窗口根子树）。 */
    @Test
    public void a3_rootViewStillAddsRotLayer() {
        String rootBlock = blockOf(codeOnly, ROOT_SIG);
        assertTrue("A3 rootView 仍须 addView(rotLayer,...)（旋转层未被移出）",
                rootBlock.contains("addView(rotLayer,"));
    }

    // ============ B 组 · 盖在画面上（FrameLayout 后加者在上） ============

    /** B4 意图：rotLayer 内 HUD 必须在 texture 之后添加，z 序正确才不被画面遮挡。 */
    @Test
    public void b4_hudAddedAfterTexture() {
        String rotBlock = blockOf(codeOnly, ROT_SIG);
        int tex = rotBlock.indexOf("addView(texture");
        int hud = rotBlock.indexOf("addView(hudView");
        assertTrue("B4 rotLayer 内须先有 addView(texture,...)（前置条件）", tex >= 0);
        assertTrue("B4 addView(hudView,...) 必须在 addView(texture,...) 之后（HUD 盖在画面上）",
                hud > tex);
    }

    // ============ C 组 · hudView / hudLp 参数未动 ============

    /** C1 意图：HUD 仍由 buildHudView() 构建，未改用别的构造方式。 */
    @Test
    public void c1_hudStillBuiltByBuildHudView() {
        assertTrue("C1 须保留 hudView = buildHudView()",
                codeOnly.contains("hudView = buildHudView()"));
    }

    /** C2 意图：hudLp 的 Gravity 未被改动，仍是底部 + 水平居中。 */
    @Test
    public void c2_hudLpGravityUnchanged() {
        assertTrue("C2 hudLp Gravity 须仍为 Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL",
                codeOnly.contains("Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL"));
    }

    /** C3 意图：hudLp 的 bottomMargin 未被改动，仍是 dp(16)。 */
    @Test
    public void c3_hudLpBottomMarginUnchanged() {
        assertTrue("C3 hudLp bottomMargin 须仍为 dp(16)",
                codeOnly.contains("bottomMargin = dp(16)"));
    }

    // ============ D 组 · 方向 / 填充硬边界关键行仍在（防改父节点时误伤） ============

    /** D1 意图：横竖判据仍是 val landscapeWindow = w > h。 */
    @Test
    public void d1_landscapeFormulaIntact() {
        assertTrue("D1 须保留 val landscapeWindow = w > h",
                codeOnly.contains("val landscapeWindow = w > h"));
    }

    /** D2 意图：竖屏固定不转（!landscapeWindow -> 0）仍在。 */
    @Test
    public void d2_portraitNeverRotatesIntact() {
        assertTrue("D2 须保留 !landscapeWindow -> 0（竖屏 deg 恒 0）",
                codeOnly.contains("!landscapeWindow -> 0"));
    }

    /** D3 意图：竖屏 2.0x 折中的固定常量 return 2.0f 仍在。 */
    @Test
    public void d3_compromiseZoomFixedTwoIntact() {
        assertTrue("D3 须保留折中缩放固定 return 2.0f",
                codeOnly.contains("return 2.0f"));
    }

    /** D4 意图：5.4.1 正方形防御 if (w == h) 仍在。 */
    @Test
    public void d4_squareGuardIntact() {
        assertTrue("D4 须保留正方形防御 if (w == h)",
                codeOnly.contains("if (w == h)"));
    }

    /**
     * D5 意图：openCamera 缓冲已固定为 5.4.4 竖条档——宽取短边 minOf(bw0, bh0)、高取长边
     * maxOf(bw0, bh0)；旧的 portraitWindow 二选一已从该决策段移除，固定档仍写回 SurfaceTexture。
     */
    @Test
    public void d5_fixedBufferBranchIntact() {
        String openBlock = blockOf(codeOnly, "private fun openCamera()");
        String sinkCall = "st.setDefaultBufferSize(actualBufferW, actualBufferH)";
        int from = openBlock.indexOf("val bw0");
        assertTrue("D5 openCamera 内须找到缓冲决策段起点 val bw0", from >= 0);
        int to = openBlock.indexOf(sinkCall, from);
        assertTrue("D5 缓冲决策段内须找到 sink 收尾", to >= 0);
        String decision = openBlock.substring(from, to + sinkCall.length());
        assertTrue("D5 须保留 minOf(bw0, bh0)（5.4.4 固定竖条档，宽取短边）",
                decision.contains("minOf(bw0, bh0)"));
        assertTrue("D5 须保留 maxOf(bw0, bh0)（5.4.4 固定竖条档，高取长边）",
                decision.contains("maxOf(bw0, bh0)"));
        assertFalse("D5 缓冲决策段不得再含 val portraitWindow =（5.4.4 已移除二选一）",
                decision.contains("val portraitWindow ="));
        assertEquals("D5 openCamera 内 " + sinkCall + " 须恰好 1 处（固定档仍写回 SurfaceTexture）",
                1, countOccurrences(openBlock, sinkCall));
    }

    // ============ E 组 · 单一挂载点（防重复挂载导致双份 HUD） ============

    /** E1 意图：全文件只有一个 addView(hudView 调用点，防止 HUD 被挂载两次。 */
    @Test
    public void e1_hudHasSingleAddCall() {
        assertEquals("E1 全文件 addView(hudView 调用点必须恰好 1 个",
                1, countOccurrences(codeOnly, "addView(hudView"));
    }

    /** E2 意图：rotLayer 块内恰好一个 HUD 挂载点（强化归属唯一）。 */
    @Test
    public void e2_singleHudAddInsideRotLayer() {
        String rotBlock = blockOf(codeOnly, ROT_SIG);
        assertEquals("E2 rotLayer 块内 addView(hudView 必须恰好 1 个",
                1, countOccurrences(rotBlock, "addView(hudView"));
    }
}
