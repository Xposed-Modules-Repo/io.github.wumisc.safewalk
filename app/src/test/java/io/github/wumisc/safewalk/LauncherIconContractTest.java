package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * 【5.4.16(81) 图标重画】三层 adaptive launcher icon 的纯 JVM 源码文本契约用例。
 *
 * <p><b>测试性质声明：</b>本类不依赖 Android 运行时、不调用 aapt，只读资源/清单原文做文本断言，
 * 并用 JDK 自带 {@code javax.xml.parsers} 验证三份 drawable 是良构 XML（含命名空间）。
 * 真机各遮罩下的观感与 aapt 编译交 Vic，见报告「未覆盖项」。</p>
 *
 * <p><b>断言口径：</b>所有「已移除旧色/旧图元」断言只针对 <em>去注释后</em> 的生效 XML；
 * 旧色仍允许留在说明注释里（实现报告与 brief 都把旧色写进了注释作对照），
 * 因此另有 {@code i7_commentStrippingKeepsOldColorsOutOfLiveXml} 锁定这一口径。</p>
 *
 * <p><b>覆盖映射：</b>I1 三层结构与 manifest 指向、I2 透明伞、I3 正面笑脸、I4 剪刀手、
 * I5 深色背景、I6 单色剪影、I7 语法/安全区。写法与 {@code ManualOrientationContractTest} 一致。</p>
 */
public class LauncherIconContractTest {

    private static final String FG_REL = "src/main/res/drawable/ic_launcher_foreground.xml";
    private static final String BG_REL = "src/main/res/drawable/ic_launcher_background.xml";
    private static final String MONO_REL = "src/main/res/drawable/ic_launcher_monochrome.xml";
    private static final String LAUNCHER_REL = "src/main/res/mipmap-anydpi/ic_launcher.xml";
    private static final String ROUND_REL = "src/main/res/mipmap-anydpi/ic_launcher_round.xml";
    private static final String MANIFEST_REL = "src/main/AndroidManifest.xml";

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final Pattern COLOR_ATTR =
            Pattern.compile("android:(?:fillColor|strokeColor)=\"#([0-9A-Fa-f]{6})\"");

    private static String fgSrc;
    private static String fgCode;
    private static String bgSrc;
    private static String bgCode;
    private static String monoSrc;
    private static String monoCode;
    private static String launcherCode;
    private static String roundCode;
    private static String manifestCode;

    @BeforeClass
    public static void loadSources() throws IOException {
        fgSrc = read(FG_REL);
        fgCode = stripXmlComments(fgSrc);
        bgSrc = read(BG_REL);
        bgCode = stripXmlComments(bgSrc);
        monoSrc = read(MONO_REL);
        monoCode = stripXmlComments(monoSrc);
        launcherCode = stripXmlComments(read(LAUNCHER_REL));
        roundCode = stripXmlComments(read(ROUND_REL));
        manifestCode = stripXmlComments(read(MANIFEST_REL));
    }

    // ==================== 源码定位与文本工具（复用既有测试风格） ====================

    /** 多候选路径定位资源：AGP 单测工作目录通常是模块根 app/，但也可能是 repo 根。 */
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

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(locate(rel)), StandardCharsets.UTF_8);
    }

    /** 去掉 XML 注释（保留注释里的换行结构无关文本），返回「生效 XML」文本。 */
    private static String stripXmlComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int from = 0;
        while (true) {
            int start = src.indexOf("<!--", from);
            if (start < 0) {
                out.append(src, from, src.length());
                return out.toString();
            }
            out.append(src, from, start);
            int end = src.indexOf("-->", start + 4);
            assertTrue("XML 注释未闭合，偏移 " + start, end >= 0);
            from = end + 3;
        }
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

    /** 取包含指定 pathData 片段的单个 {@code <path ... />} 块，避免「颜色在别处」的假阳性。 */
    private static String pathBlock(String src, String pathDataFragment) {
        int from = 0;
        while (true) {
            int start = src.indexOf("<path", from);
            if (start < 0) break;
            int end = src.indexOf("/>", start);
            if (end < 0) break;
            String block = src.substring(start, end + 2);
            if (block.contains(pathDataFragment)) return block;
            from = end + 2;
        }
        throw new AssertionError("找不到含 pathData 片段的 <path>：" + pathDataFragment);
    }

    /** 取 #RRGGBB 中最亮通道，用于「深色系」阈值判断（不做感知亮度精算）。 */
    private static int maxChannel(String rgbHex) {
        assertEquals("颜色须为六位 #RRGGBB", 6, rgbHex.length());
        int r = Integer.parseInt(rgbHex.substring(0, 2), 16);
        int g = Integer.parseInt(rgbHex.substring(2, 4), 16);
        int b = Integer.parseInt(rgbHex.substring(4, 6), 16);
        return Math.max(r, Math.max(g, b));
    }

    /** JDK 自带 XML 解析（namespace aware）：验证三份 drawable 的 aapt 命名空间也写对了。 */
    private static Element parseRoot(String rel) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(read(rel)))).getDocumentElement();
    }

    // ==================== I1 · 三层结构与 manifest 指向 ====================

    /** I1 意图：标准 launcher 的 adaptive-icon 三行引用齐全，缺 monochrome 主题图标会退化。 */
    @Test
    public void i1_launcherXmlDeclaresBackgroundForegroundMonochrome() {
        assertTrue("I1 ic_launcher.xml 须引用背景层",
                launcherCode.contains("<background android:drawable=\"@drawable/ic_launcher_background\" />"));
        assertTrue("I1 ic_launcher.xml 须引用前景层",
                launcherCode.contains("<foreground android:drawable=\"@drawable/ic_launcher_foreground\" />"));
        assertTrue("I1 ic_launcher.xml 须引用单色层",
                launcherCode.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />"));
    }

    /** I1 意图：圆形遮罩版本与标准版三层一致，防止圆图标漏掉 monochrome。 */
    @Test
    public void i1_roundXmlDeclaresBackgroundForegroundMonochrome() {
        assertTrue("I1 ic_launcher_round.xml 须引用背景层",
                roundCode.contains("<background android:drawable=\"@drawable/ic_launcher_background\" />"));
        assertTrue("I1 ic_launcher_round.xml 须引用前景层",
                roundCode.contains("<foreground android:drawable=\"@drawable/ic_launcher_foreground\" />"));
        assertTrue("I1 ic_launcher_round.xml 须引用单色层",
                roundCode.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />"));
    }

    /** I1 意图（本轮未改，锁定不回退）：manifest 的 icon/roundIcon 仍指向 mipmap 入口。 */
    @Test
    public void i1_manifestPointsToMipmapLauncherAndRound() {
        assertTrue("I1 manifest android:icon 须指向 @mipmap/ic_launcher",
                manifestCode.contains("android:icon=\"@mipmap/ic_launcher\""));
        assertTrue("I1 manifest android:roundIcon 须指向 @mipmap/ic_launcher_round",
                manifestCode.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""));
    }

    // ==================== I2 · 透明伞 ====================

    /** I2 意图（核心）：伞面是浅蓝白 + fillAlpha=0.3，深背景能透上来。 */
    @Test
    public void i2_umbrellaFillIsTransparentLightBlue() {
        String umbrella = pathBlock(fgCode, "M26,40 C26,30 33,25 42,25");
        assertTrue("I2 伞面填充色须为 #D6ECFF",
                umbrella.contains("android:fillColor=\"#D6ECFF\""));
        assertTrue("I2 伞面 fillAlpha 须为 0.3（不是不透明）",
                umbrella.contains("android:fillAlpha=\"0.3\""));
    }

    /** I2 意图：两根放射伞骨独立成 path，0.9dp 细线而非平涂。 */
    @Test
    public void i2_umbrellaHasTwoRibs() {
        assertTrue("I2 左侧伞骨 path 须在", fgCode.contains("M42,26 L33.75,41.5"));
        assertTrue("I2 右侧伞骨 path 须在", fgCode.contains("M42,26 L51.25,41.5"));
        assertEquals("I2 从伞顶放射的伞骨须恰 2 根，实际 "
                + countOccurrences(fgCode, "M42,26 L"), 2, countOccurrences(fgCode, "M42,26 L"));
        assertEquals("I2 伞骨线宽 0.9dp 须恰 2 处，实际 "
                + countOccurrences(fgCode, "android:strokeWidth=\"0.9\""),
                2, countOccurrences(fgCode, "android:strokeWidth=\"0.9\""));
    }

    /** I2 意图：伞柄 Q 弯钩（J 形）保留——小尺寸下「伞」的关键识别特征。 */
    @Test
    public void i2_umbrellaHandleHasJHook() {
        String handle = pathBlock(fgCode, "M42,27 L40,55 L40,63 Q40,67 44.5,66.5");
        assertTrue("I2 伞柄末端 J 形弯钩须为 Q40,67 44.5,66.5",
                handle.contains("Q40,67 44.5,66.5"));
        assertTrue("I2 伞柄须圆头收尾", handle.contains("android:strokeLineCap=\"round\""));
        assertEquals("I2 J 弯钩 path 须恰 1 处，实际 "
                + countOccurrences(fgCode, "Q40,67 44.5,66.5"),
                1, countOccurrences(fgCode, "Q40,67 44.5,66.5"));
    }

    /** I2 意图（回归锁）：旧版不透明大蓝伞的两个渐变端点色不得残留在生效 XML。 */
    @Test
    public void i2_oldOpaqueBlueUmbrellaColorsAreGone() {
        assertFalse("I2 旧蓝伞色 #2A68BC 不得在生效 XML 中", fgCode.contains("#2A68BC"));
        assertFalse("I2 旧蓝伞色 #3E92E8 不得在生效 XML 中", fgCode.contains("#3E92E8"));
    }

    // ==================== I3 · 正面笑脸 ====================

    /** I3 意图：脸是暖肤圆脸（8.5 半径），不是旧版纯黑圆点。 */
    @Test
    public void i3_faceUsesWarmSkinColor() {
        String face = pathBlock(fgCode, "M48.5,59.5 A8.5,8.5");
        assertTrue("I3 脸填充须为暖肤 #FFD9B8", face.contains("android:fillColor=\"#FFD9B8\""));
    }

    /** I3 意图：两根上弯 Q 笑眼（^ 形）是「开心」的表情特征。 */
    @Test
    public void i3_eyesAreUpwardQCurves() {
        assertTrue("I3 左笑眼 path 须在", fgCode.contains("M52,60.5 Q53.5,58.7 55,60.5"));
        assertTrue("I3 右笑眼 path 须在", fgCode.contains("M59,60.5 Q60.5,58.7 62,60.5"));
        assertEquals("I3 笑眼 1.7dp 圆头线须恰 2 根，实际 "
                + countOccurrences(fgCode, "android:strokeWidth=\"1.7\""),
                2, countOccurrences(fgCode, "android:strokeWidth=\"1.7\""));
    }

    /** I3 意图：嘴角上扬的 Q 曲线单独成 path，圆头收尾。 */
    @Test
    public void i3_mouthIsUpwardCurve() {
        String mouth = pathBlock(fgCode, "M54,64.5 Q57,67.5 60,64.5");
        assertTrue("I3 上扬嘴角 path 须在", mouth.contains("M54,64.5 Q57,67.5 60,64.5"));
        assertTrue("I3 嘴角须圆头收尾", mouth.contains("android:strokeLineCap=\"round\""));
        assertTrue("I3 嘴角描边色须为 #B4504A",
                mouth.contains("android:strokeColor=\"#B4504A\""));
    }

    /** I3 意图：两团半透明腮红点睛，缺一不成「笑脸」。 */
    @Test
    public void i3_blushHasTwoCheekPaths() {
        assertEquals("I3 腮红 #FF9A8A 须恰 2 团，实际 "
                + countOccurrences(fgCode, "android:fillColor=\"#FF9A8A\""),
                2, countOccurrences(fgCode, "android:fillColor=\"#FF9A8A\""));
        assertEquals("I3 腮红 fillAlpha=0.65 须恰 2 处，实际 "
                + countOccurrences(fgCode, "android:fillAlpha=\"0.65\""),
                2, countOccurrences(fgCode, "android:fillAlpha=\"0.65\""));
    }

    /** I3 意图（回归锁）：旧纯黑头 #23242B 不得残留在生效 XML。 */
    @Test
    public void i3_oldBlackHeadColorIsGone() {
        assertFalse("I3 旧纯黑头色 #23242B 不得在生效 XML 中", fgCode.contains("#23242B"));
    }

    // ==================== I4 · 剪刀手 ====================

    /** I4 意图（核心）：两根独立的 V 形手指，2.8dp 圆头，缩到 48dp 仍是清晰的「二」。 */
    @Test
    public void i4_twoIndependentVFingersWithRound2p8Stroke() {
        assertTrue("I4 左手指 path（M73.5,55.5 L71,47）须在",
                fgCode.contains("M73.5,55.5 L71,47"));
        assertTrue("I4 右手指 path（M74.5,55 L78,46.5）须在",
                fgCode.contains("M74.5,55 L78,46.5"));
        assertEquals("I4 2.8dp 圆头手指须恰 2 根，实际 "
                + countOccurrences(fgCode, "android:strokeWidth=\"2.8\""),
                2, countOccurrences(fgCode, "android:strokeWidth=\"2.8\""));
    }

    /** I4 意图：举手手臂接到头侧，掌根另有一小团暖色表示收拢指节。 */
    @Test
    public void i4_peaceArmAndFoldedKnucklesExist() {
        assertTrue("I4 ✌️ 手臂 path（M65,71 L73,58）须在", fgCode.contains("M65,71 L73,58"));
        String knuckle = pathBlock(fgCode, "M72,57 Q74.5,54.5 77,56");
        assertTrue("I4 收拢指节填充须为 #F7C9A3",
                knuckle.contains("android:fillColor=\"#F7C9A3\""));
    }

    // ==================== I5 · 深色背景 ====================

    /** I5 意图（核心）：渐变端点为深靛蓝→近黑，最亮通道也远低于亮色阈值。 */
    @Test
    public void i5_backgroundStartAndEndColorsAreDark() {
        assertTrue("I5 背景 startColor 须为 #1B2154",
                bgCode.contains("android:startColor=\"#1B2154\""));
        assertTrue("I5 背景 endColor 须为 #05060D",
                bgCode.contains("android:endColor=\"#05060D\""));
        assertTrue("I5 起始色最亮通道须 ≤100（深色系），实际 " + maxChannel("1B2154"),
                maxChannel("1B2154") <= 100);
        assertTrue("I5 结束色最亮通道须 ≤32（近黑），实际 " + maxChannel("05060D"),
                maxChannel("05060D") <= 32);
    }

    /** I5 意图：仍是 angle=270 的自上而下线性渐变，不是纯色平涂。 */
    @Test
    public void i5_backgroundIsVerticalLinearGradient() {
        assertTrue("I5 渐变角度须为 270", bgCode.contains("android:angle=\"270\""));
        assertTrue("I5 渐变类型须为 linear", bgCode.contains("android:type=\"linear\""));
    }

    // ==================== I6 · 单色剪影 ====================

    /** I6 意图（核心）：主题图标只允许纯白 #FFFFFF，方便系统按主题色整体着色。 */
    @Test
    public void i6_monochromeUsesOnlyPureWhite() {
        Matcher m = COLOR_ATTR.matcher(monoCode);
        int found = 0;
        while (m.find()) {
            found++;
            assertEquals("I6 单色层所有 fillColor/strokeColor 须为 FFFFFF，实际 " + m.group(1),
                    "FFFFFF", m.group(1).toUpperCase());
        }
        assertTrue("I6 单色层须有实际着色 path，实际颜色数 " + found, found >= 10);
    }

    /** I6 意图：去掉笑眼/嘴角/腮红/刘海/伞骨/高光的细线与半透明装饰，避免 48dp 糊成噪点。 */
    @Test
    public void i6_monochromeDropsFineStrokeDecorations() {
        String[] fineWidths = { "0.9", "1.3", "1.7", "1.8" };
        for (int i = 0; i < fineWidths.length; i++) {
            assertFalse("I6 单色层不得有 strokeWidth=\"" + fineWidths[i] + "\"",
                    monoCode.contains("android:strokeWidth=\"" + fineWidths[i] + "\""));
        }
        assertFalse("I6 单色层不得有 fillAlpha（半透明会连块）", monoCode.contains("fillAlpha"));
        assertFalse("I6 单色层不得残留伞面浅蓝", monoCode.contains("#D6ECFF"));
        assertFalse("I6 单色层不得残留腮红", monoCode.contains("#FF9A8A"));
        assertFalse("I6 单色层不得残留头发色", monoCode.contains("#5C3A34"));
    }

    /** I6 意图：path 数锁定在约 12（当前恰 12），多画细碎 path 即视为破坏剪影。 */
    @Test
    public void i6_monochromeHasAboutTwelvePaths() {
        int count = countOccurrences(monoCode, "<path");
        assertEquals("I6 monochrome path 数应约 12（锁定 12），实际 " + count, 12, count);
    }

    // ==================== I7 · 语法与安全区 ====================

    /** I7 意图：三份 drawable 都是良构 XML，且 aapt 命名空间声明可被标准解析器识别。 */
    @Test
    public void i7_threeDrawablesParseAsXml() throws Exception {
        assertEquals("I7 前景根元素须为 vector", "vector", parseRoot(FG_REL).getTagName());
        assertEquals("I7 背景根元素须为 shape", "shape", parseRoot(BG_REL).getTagName());
        assertEquals("I7 单色根元素须为 vector", "vector", parseRoot(MONO_REL).getTagName());
    }

    /** I7 意图：两个 vector 均为 108×108 画布（与 adaptive icon 安全区计算口径一致）。 */
    @Test
    public void i7_vectorViewportsAre108By108() throws Exception {
        Element fg = parseRoot(FG_REL);
        Element mono = parseRoot(MONO_REL);
        assertEquals("I7 前景 viewportWidth=108", "108", fg.getAttributeNS(ANDROID_NS, "viewportWidth"));
        assertEquals("I7 前景 viewportHeight=108", "108", fg.getAttributeNS(ANDROID_NS, "viewportHeight"));
        assertEquals("I7 单色 viewportWidth=108", "108", mono.getAttributeNS(ANDROID_NS, "viewportWidth"));
        assertEquals("I7 单色 viewportHeight=108", "108", mono.getAttributeNS(ANDROID_NS, "viewportHeight"));
        assertTrue("I7 前景宽 108dp", fgCode.contains("android:width=\"108dp\""));
        assertTrue("I7 单色高 108dp", monoCode.contains("android:height=\"108dp\""));
    }

    /** I7 意图（文本断言口径）：前景注释里声明了半径 33 的安全圆计算，不做几何精算。 */
    @Test
    public void i7_foregroundDeclaresSafeAreaComment() {
        assertTrue("I7 前景须声明安全区章节",
                fgSrc.contains("## 构图与安全区（>33dp 的部分没有）"));
        assertTrue("I7 前景须声明半径约 33 的安全圆", fgSrc.contains("半径约 33"));
        assertTrue("I7 前景须记录伞顶距心 31.4 的核算", fgSrc.contains("距心 31.4"));
    }

    /** I7 边界用例：旧色允许留在注释里，但不能漏进生效 XML——锁定断言口径本身。 */
    @Test
    public void i7_commentStrippingKeepsOldColorsOutOfLiveXml() {
        assertTrue("I7 背景注释记着旧色 #123C66（便于对照）", bgSrc.contains("#123C66"));
        assertFalse("I7 去注释后旧色 #123C66 不得残留", bgCode.contains("#123C66"));
    }
}
