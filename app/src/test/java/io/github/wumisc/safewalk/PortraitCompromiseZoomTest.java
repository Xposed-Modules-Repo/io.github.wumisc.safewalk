package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 竖屏「折中填充」缩放系数 {@code portraitCompromiseZoom} 的纯 JVM 回归用例（5.4.0）。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组</b>用反射调用 {@code RearCameraOverlay} 里真实的 private 函数
 *       {@code portraitCompromiseZoom(int,int):Float}，锁住纯函数真值表。该函数方法体只有
 *       「boxH&lt;=boxW 返回 1f，否则 2.0f」两次比较，不读任何实例字段，因此用
 *       {@code Unsafe.allocateInstance} 绕过需要 Android Context 的构造函数后即可在纯 JVM 调用。
 *       RearCameraOverlay 的 companion object 全是 {@code const val}（编译期常量），
 *       {@code <clinit>} 不会实例化任何 Android 类，类加载安全。
 *       若验证环境的 JDK / mockable-android.jar 导致反射不可用，A 组用例会被
 *       {@link Assume} 标记为 skipped（不是 FAIL），并由 B/C/D 组继续锁住行为。</li>
 *   <li><b>B/C 组</b>是<b>源码契约测试</b>：直接读取 {@code RearCameraOverlay.kt} 文本，锁住
 *       纯函数门控、常量值，以及四组合真值表对应的分支结构，防止后续误改
 *       （例如把折中误叠加到 f&gt;0 主动取景、或删掉横屏 1:1 分支）。这类用例零环境依赖，
 *       任何 JVM 都能跑。</li>
 *   <li><b>D 组</b>是<b>等价测试</b>：在测试里复刻纯函数逻辑跑同一张真值表，作为 B 组
 *       「源码 = 复刻逻辑」之外的行为保底（参考 AutoModeHandoffContractTest 的同类做法）。</li>
 * </ul>
 *
 * <p><b>只能真机验证、本文件测不到的部分</b>（详见 test-writer-report.md）：
 * applyFramingZoom 需要真实 TextureView 才能跑；实际 scaleX/Y 是否写入 2.0、
 * 中心支点是否正确、放大后是否被 clipToOutline 裁成约 17% 窄边、水平是否保留约 50%
 * 视野、2 倍放大画质、以及四组合在真机传感器/HAL 行为下的最终画面——这些必须真机截图核对。
 * 本文件只锁「纯函数数值 + 分支结构源码 + 等价逻辑」这些 JVM 可判定的部分。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，不引入新依赖（JUnit4 为项目既有依赖）。
 */
public class PortraitCompromiseZoomTest {

    /** 相对模块根 app/ 的源码路径；多路径 fallback 适配 repo 根作为工作目录的情况。 */
    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String PURE_FUN_SIG =
            "private fun portraitCompromiseZoom";
    private static final String ZOOM_FUN_SIG =
            "private fun applyFramingZoom";

    private static String overlaySrc;          // 原始 .kt 源码
    private static String pureFunSrc;          // portraitCompromiseZoom 方法体
    private static String zoomFunSrc;          // applyFramingZoom 方法体

    // A 组反射所需；静态初始化失败时记入 reflectErr，由用例级 Assume 跳过 A 组。
    private static Method zoomMethod;
    private static Object overlayInstance;
    private static Throwable reflectErr;

    static {
        try {
            Class<?> overlayCls = Class.forName("io.github.wumisc.safewalk.RearCameraOverlay");
            // 构造函数需要 Android Context；allocateInstance 只分配对象、不跑构造函数与字段初始化。
            // 该函数不读取任何实例字段，未初始化的实例足以安全调用。全程反射，不 import Unsafe。
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeCls.getMethod("allocateInstance", Class.class);
            overlayInstance = allocateInstance.invoke(unsafe, overlayCls);
            zoomMethod = overlayCls.getDeclaredMethod(
                    "portraitCompromiseZoom", int.class, int.class);
            zoomMethod.setAccessible(true);
        } catch (Throwable t) {
            reflectErr = t;
        }
    }

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locateSource()), StandardCharsets.UTF_8);
        pureFunSrc = extractMethod(overlaySrc, PURE_FUN_SIG);
        zoomFunSrc = extractMethod(overlaySrc, ZOOM_FUN_SIG);
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

    /** 从签名起按花括号配平截取单个方法文本；避开 companion 里 AGSL 三引号字符串干扰。 */
    private static String extractMethod(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("源码中找不到方法签名：" + signature, start >= 0);
        int brace = src.indexOf('{', start);
        assertTrue("方法签名后找不到左花括号：" + signature, brace >= 0);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
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

    /** 反射调用真实纯函数；反射环境不可用时跳过当前用例（只跳过 A 组，不影响 B/C/D）。 */
    private static float realZoom(int boxW, int boxH) {
        Assume.assumeNoException("反射调用真实 portraitCompromiseZoom 的环境不可用", reflectErr);
        try {
            Object result = zoomMethod.invoke(overlayInstance,
                    Integer.valueOf(boxW), Integer.valueOf(boxH));
            return ((Number) result).floatValue();
        } catch (Throwable t) {
            Assume.assumeNoException("反射调用真实 portraitCompromiseZoom 失败", t);
            return -1f; // unreachable: assumeNoException 会抛 AssumptionViolatedException
        }
    }

    /** 等价复刻纯函数（D 组保底用），逻辑与生产函数逐字对应。 */
    private static float referenceZoom(int boxW, int boxH) {
        if (boxH <= boxW) return 1f;
        return 2.0f;
    }

    /** 返回 applyFramingZoom 方法体中「主动取景分支」之后的部分（val k 起）。 */
    private static String activeFramingPart() {
        int k = zoomFunSrc.indexOf("val k = f / 200f");
        assertTrue("applyFramingZoom 中找不到主动取景分支 val k", k >= 0);
        return zoomFunSrc.substring(k);
    }

    /** 返回 f<=0 分支内、主动取景分支之前的部分。 */
    private static String nonActivePart() {
        int f = zoomFunSrc.indexOf("if (f <= 0)");
        int k = zoomFunSrc.indexOf("val k = f / 200f");
        assertTrue("applyFramingZoom 中找不到 f<=0 分支或 val k", f >= 0 && k > f);
        return zoomFunSrc.substring(f, k);
    }

    // ======================= A 组：反射真实纯函数，锁数值真值表 =======================

    @Test
    public void testPortrait488x845ReturnsTwo() {
        assertEquals("竖屏真机尺寸 488x845：boxH>boxW 必须返回折中 2.0f",
                2.0f, realZoom(488, 845), 0.0001f);
    }

    @Test
    public void testLandscape845x488ReturnsOne() {
        assertEquals("横屏真机尺寸 845x488：boxH<boxW 必须返回防御值 1.0f",
                1.0f, realZoom(845, 488), 0.0001f);
    }

    @Test
    public void testSquare500x500ReturnsOne() {
        assertEquals("正方形 500x500：boxH==boxW 走 <= 分支，返回 1.0f",
                1.0f, realZoom(500, 500), 0.0001f);
    }

    @Test
    public void testBoundary485x485ReturnsOne() {
        assertEquals("边界 485x485 相等：必须返回 1.0f（门控是 <= 不是 <）",
                1.0f, realZoom(485, 485), 0.0001f);
    }

    @Test
    public void testBoundary484x485ReturnsTwo() {
        assertEquals("边界 484x485 差 1：boxH 仅大 1 也必须返回 2.0f",
                2.0f, realZoom(484, 485), 0.0001f);
    }

    @Test
    public void testZeroByZeroReturnsOne() {
        assertEquals("极端边界 0x0：boxH<=boxW 成立，返回 1.0f 且不抛异常",
                1.0f, realZoom(0, 0), 0.0001f);
    }

    @Test
    public void testOneByTwoReturnsTwo() {
        assertEquals("极小竖屏 1x2：只要 boxH>boxW 就返回 2.0f（函数与尺寸无关，只看形状）",
                2.0f, realZoom(1, 2), 0.0001f);
    }

    // =================== B 组：源码契约，锁纯函数门控与固定常量 ===================

    @Test
    public void testPureFunGateLandscapeReturnsOne() {
        assertTrue("纯函数必须保留横屏/正方形门控 if (boxH <= boxW) return 1f",
                pureFunSrc.contains("boxH <= boxW") && pureFunSrc.contains("return 1f"));
    }

    @Test
    public void testPureFunPortraitReturnsFixedTwo() {
        assertTrue("纯函数竖屏分支必须返回固定常量 2.0f（不得运行时猜测 HAL / 传感器）",
                pureFunSrc.contains("return 2.0f"));
    }

    @Test
    public void testCompromiseCalledOnceOnlyInNonActiveBranch() {
        // 只数「调用形式」：注释里也提到函数名（几何推导见 portraitCompromiseZoom），
        // 故不能用裸函数名计数，否则会被注释误伤。
        int occurrences = countOccurrences(zoomFunSrc, "portraitCompromiseZoom(boxW, boxH)");
        assertEquals("折中函数在整个 applyFramingZoom 内只能被调用 1 次，"
                + "防止误叠加到 f>0 主动取景分支", 1, occurrences);
        assertTrue("那 1 次调用必须位于 f<=0 分支（主动取景分支之前）",
                nonActivePart().contains("portraitCompromiseZoom"));
    }

    @Test
    public void testLandscapeOneToOneBranchStillPresent() {
        String nonActive = nonActivePart();
        assertTrue("横屏/非竖屏 f=0 必须仍走原 1:1 分支：scaleX/scaleY = 1f 不能被删",
                nonActive.contains("view.scaleX = 1f") && nonActive.contains("view.scaleY = 1f"));
    }

    @Test
    public void testActiveFramingFormulaAndPivotTableUnchanged() {
        String active = activeFramingPart();
        assertTrue("主动取景缩放公式 zoom=1/(1-k) 必须逐字保留",
                active.contains("val zoom = 1f / (1f - k)"));
        assertTrue("主动取景四方向支点表必须保留（90/270/180/else）",
                active.contains("when (") && active.contains("90 ->")
                        && active.contains("270 ->") && active.contains("180 ->")
                        && active.contains("else ->"));
    }

    // ============== C 组：四组合真值表，用源码结构锁住 JVM 可判定部分 ==============

    @Test
    public void testCombo1PortraitFZeroUsesCompromise() {
        String nonActive = nonActivePart();
        assertTrue("组合1 竖屏+f=0：须有 boxH>boxW 门控 + 折中调用 + scale=z（中心支点）",
                nonActive.contains("if (!landscapeWindow && boxH > boxW)")
                        && nonActive.contains("val z = portraitCompromiseZoom(boxW, boxH)")
                        && nonActive.contains("view.scaleX = z")
                        && nonActive.contains("view.scaleY = z")
                        && nonActive.contains("view.pivotX = boxW / 2f")
                        && nonActive.contains("view.pivotY = boxH / 2f"));
    }

    @Test
    public void testCombo2PortraitFPositiveKeepsOriginalFraming() {
        String active = activeFramingPart();
        // 注意不能用 !contains("scaleX = z")：它是 "scaleX = zoom" 的前缀，恒为 true。
        // 「不含折中调用 + 保留 zoom 公式 + 保留 when 支点表」足以证明未叠加折中。
        assertTrue("组合2 竖屏+f>0：主动取景分支不得出现折中调用，"
                + "必须仍按原 k/zoom/when(deg) 取景",
                !active.contains("portraitCompromiseZoom")
                        && active.contains("val zoom = 1f / (1f - k)")
                        && active.contains("when ("));
    }

    @Test
    public void testCombo3LandscapeFZeroStaysOneToOne() {
        String nonActive = nonActivePart();
        assertTrue("组合3 横屏+f=0：boxH>boxW 为假，必须落到 scale=1f 的原 1:1 分支",
                nonActive.contains("if (!landscapeWindow && boxH > boxW)")
                        && nonActive.contains("view.scaleX = 1f")
                        && nonActive.contains("view.scaleY = 1f"));
    }

    @Test
    public void testCombo4LandscapeFPositiveKeepsOriginalFraming() {
        String active = activeFramingPart();
        assertTrue("组合4 横屏+f>0：必须仍走原取景（含 deg=180 的 180 -> 支点），"
                + "不得出现折中调用",
                active.contains("when (") && active.contains("180 ->")
                        && !active.contains("portraitCompromiseZoom"));
    }

    // ============= D 组：等价复刻真值表，零环境依赖保底锁住 5 个指定点 =============

    @Test
    public void testEquivalentReferenceTruthTable() {
        assertEquals("等价复刻：竖屏(488,845)=2.0", 2.0f, referenceZoom(488, 845), 0f);
        assertEquals("等价复刻：横屏(845,488)=1.0", 1.0f, referenceZoom(845, 488), 0f);
        assertEquals("等价复刻：正方形(500,500)=1.0", 1.0f, referenceZoom(500, 500), 0f);
        assertEquals("等价复刻：边界(485,485)=1.0", 1.0f, referenceZoom(485, 485), 0f);
        assertEquals("等价复刻：边界(484,485)=2.0", 2.0f, referenceZoom(484, 485), 0f);
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
}
