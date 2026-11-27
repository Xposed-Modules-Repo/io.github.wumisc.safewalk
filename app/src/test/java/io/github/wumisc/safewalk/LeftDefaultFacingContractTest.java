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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 【5.4.17(81) 后摄默认 left】配置读取 fallback 由 FACING_AUTO 改为 FACING_LEFT 的专项契约用例。
 *
 * <p><b>本轮生产改动只有 3 处 fallback + 版本字段：</b></p>
 * <ol>
 *   <li>{@code WalkPreviewService.java:901}（渲染生效点）{@code rearCameraFacing} 读取的
 *       {@code SafePrefs.getString} 第三参数 {@code FACING_AUTO → FACING_LEFT}；</li>
 *   <li>{@code PreviewStyleActivity.java:793}（paintChip 回显）、{@code :946}（诊断摘要）各一处；</li>
 *   <li>{@code build.gradle} 80/5.4.15 → 81/5.4.17。</li>
 * </ol>
 *
 * <p><b>锁死边界（本轮"只改读取层 fallback"）：</b>A2/A7/A4 契约点、
 * {@code normalizeFacing} / {@code facingDegrees} / {@code shapeIsLandscape} /
 * {@code PreviewOrientationPolicy.alignToWindow} 四纯函数一行未动。
 * 因此本测试用 L3 逐一验证哨兵仍在，避免"换默认"被误做成"改语义"。</p>
 *
 * <p><b>测试性质声明（与 {@code ManualOrientationContractTest} 同范式）：</b></p>
 * <ul>
 *   <li><b>L1/L2/L3/L5 · 源码文本契约</b>：读生产源码原文与 stripComments 去注释文本，
 *       以"键 + fallback"组合正则精确锁定唯一读取点，防止误判别处的 FACING_AUTO；</li>
 *   <li><b>L4 · 纯函数真值表（独立参考实现）</b>：JVM 单测源码目录只有 .java，
 *       {@code ManualOrientation} 为 Kotlin object，不能直接 import 调用；本类用类内
 *       {@code refNormalizeFacing / refFacingDegrees / refDegWhen / refFinalDeg} 复刻真值表，
 *       <b>不假装调用生产函数</b>，由 L3 文本断言兜底"参考实现确实接上源码"；</li>
 *   <li><b>真机肉眼效果</b>（开箱 270° 观感、横竖屏切换）JVM 不可证，交 Vic/真机。</li>
 * </ul>
 *
 * <p>本文件只新增测试，不改任何实现代码、配置或既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不运行完整构建（交 Vic）。</p>
 */
public class LeftDefaultFacingContractTest {

    private static final String SERVICE_REL =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
    private static final String ACTIVITY_REL =
            "src/main/java/io/github/wumisc/safewalk/PreviewStyleActivity.java";
    private static final String STORE_REL =
            "src/main/java/io/github/wumisc/safewalk/SafeWalkDataStore.kt";
    private static final String OVERLAY_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String GRADLE_REL = "build.gradle";

    private static final String MANUAL_OBJ_SIG = "object ManualOrientation {";
    private static final String APPLY_FRAMING_SIG =
            "private fun applyFraming(windowW: Int = 0, windowH: Int = 0)";

    /**
     * 精确锁定 {@code SafePrefs.getString(sp, SafeWalkPrefKey.rearCameraFacing, <fallback>)}：
     * 键名与第三参数必须紧邻，\\s* 吸收换行与缩进，避免把别处 FACING_AUTO 误当作 fallback。
     */
    private static final String RE_READ_FACING_ANY =
            "SafePrefs\\.getString\\(sp,\\s*SafeWalkPrefKey\\.rearCameraFacing\\s*,\\s*"
                    + "ManualOrientation\\.(FACING_[A-Z]+)\\s*\\)";
    private static final String RE_READ_FACING_LEFT =
            "SafePrefs\\.getString\\(sp,\\s*SafeWalkPrefKey\\.rearCameraFacing\\s*,\\s*"
                    + "ManualOrientation\\.FACING_LEFT\\s*\\)";
    private static final String RE_READ_FACING_AUTO =
            "SafePrefs\\.getString\\(sp,\\s*SafeWalkPrefKey\\.rearCameraFacing\\s*,\\s*"
                    + "ManualOrientation\\.FACING_AUTO\\s*\\)";
    private static final String RE_FACING_READ_WRAPPED =
            "ManualOrientation\\.normalizeFacing\\(\\s*SafePrefs\\.getString\\(sp,\\s*"
                    + "SafeWalkPrefKey\\.rearCameraFacing\\s*,";

    private static String serviceCode;   // WalkPreviewService.java 去注释
    private static String activityCode;  // PreviewStyleActivity.java 去注释
    private static String manualCode;    // ManualOrientation object 块（去注释）
    private static String overlayCode;   // RearCameraOverlay.kt 去注释
    private static String applyCode;     // applyFraming() 方法体（去注释）
    private static String gradleSrc;     // build.gradle 原文

    @BeforeClass
    public static void loadSource() throws IOException {
        serviceCode = stripComments(new String(
                Files.readAllBytes(locate(SERVICE_REL)), StandardCharsets.UTF_8));
        activityCode = stripComments(new String(
                Files.readAllBytes(locate(ACTIVITY_REL)), StandardCharsets.UTF_8));
        String storeSrc = new String(
                Files.readAllBytes(locate(STORE_REL)), StandardCharsets.UTF_8);
        manualCode = stripComments(extractBlock(storeSrc, MANUAL_OBJ_SIG));
        String overlaySrc = new String(
                Files.readAllBytes(locate(OVERLAY_REL)), StandardCharsets.UTF_8);
        overlayCode = stripComments(overlaySrc);
        applyCode = stripComments(extractBlock(overlaySrc, APPLY_FRAMING_SIG));
        gradleSrc = new String(Files.readAllBytes(locate(GRADLE_REL)), StandardCharsets.UTF_8);
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

    /** 从签名起按花括号配平截取单个块（object 或函数，含外层花括号），跳过字符串/字符字面量里的花括号。 */
    private static String extractBlock(String src, String signature) {
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

    /** 正则命中计数：DOTALL 让 \\s* 跨行吸收缩进，用于"键+fallback 紧邻"的精确断言。 */
    private static int countRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    // ==================== L4：独立参考实现（复刻四纯函数真值表，未改） ====================

    private static final String FACING_AUTO = "auto";
    private static final String FACING_UP = "up";
    private static final String FACING_RIGHT = "right";
    private static final String FACING_DOWN = "down";
    private static final String FACING_LEFT = "left";

    /** 复刻 normalizeFacing()：白名单只认 up/right/down/left，其余落 auto（本轮未动）。 */
    private static String refNormalizeFacing(String v) {
        if (FACING_UP.equals(v)) return FACING_UP;
        if (FACING_RIGHT.equals(v)) return FACING_RIGHT;
        if (FACING_DOWN.equals(v)) return FACING_DOWN;
        if (FACING_LEFT.equals(v)) return FACING_LEFT;
        return FACING_AUTO;
    }

    /** 复刻 facingDegrees()：right=90/down=180/left=270；up 与 auto/拼错走 else=0（本轮未动）。 */
    private static int refFacingDegrees(String v) {
        String n = refNormalizeFacing(v);
        if (FACING_RIGHT.equals(n)) return 90;
        if (FACING_DOWN.equals(n)) return 180;
        if (FACING_LEFT.equals(n)) return 270;
        return 0;
    }

    /** 复刻 5.4.14 的 deg when（本轮未改）：首帧/未算→0；竖条→0；小窗横条→90；全屏兜底 align。 */
    private static int refDegWhen(boolean isSmall, boolean landscapeWindow,
                                  int appliedRotDeg, boolean firstFrame) {
        if (firstFrame || appliedRotDeg < 0) return 0;
        if (!landscapeWindow) return 0;
        if (isSmall) return 90;
        return refAlign(appliedRotDeg, true);
    }

    /** 复刻 PreviewOrientationPolicy.alignToWindow()（本轮未改）：全屏横条输出域 ⊆{0,180}。 */
    private static int refAlign(int deg, boolean landscapeWindow) {
        if (deg == -1) return -1;
        int normalized = ((deg % 360) + 360) % 360;
        boolean swapped = normalized == 90 || normalized == 270;
        boolean needSwap = !landscapeWindow;
        return swapped == needSwap ? normalized : (normalized + 90) % 360;
    }

    /** 复刻 finalDeg（RearCameraOverlay.kt:1266-1269，本轮未改）：小窗+横条+显式→手动角，否则 deg。 */
    private static int refFinalDeg(boolean isSmall, boolean landscapeWindow,
                                   String facing, int appliedRotDeg, boolean firstFrame) {
        int deg = refDegWhen(isSmall, landscapeWindow, appliedRotDeg, firstFrame);
        String manualFacing = refNormalizeFacing(facing);
        boolean manualActive = isSmall && landscapeWindow && !FACING_AUTO.equals(manualFacing);
        return manualActive ? refFacingDegrees(manualFacing) : deg;
    }

    // ==================== L1 · 渲染生效点 fallback ====================

    /** L1 意图：渲染生效点唯一读取 rearCameraFacing 的 getString，fallback 为 FACING_LEFT。 */
    @Test
    public void l1_renderPointFallbackIsFacingLeftAndOnlyOnce() {
        assertEquals("L1 渲染生效点 rearCameraFacing getString 读取点须恰好 1 处",
                1, countRegex(serviceCode, RE_READ_FACING_ANY));
        assertEquals("L1 该读取点 fallback 须为 FACING_LEFT",
                1, countRegex(serviceCode, RE_READ_FACING_LEFT));
        assertEquals("L1 该读取点不得残留 FACING_AUTO fallback",
                0, countRegex(serviceCode, RE_READ_FACING_AUTO));
        assertTrue("L1 读取仍经 normalizeFacing 包裹（归一未动）",
                serviceCode.contains("String rearCameraFacing = ManualOrientation.normalizeFacing("));
        assertEquals("L1 normalizeFacing 包裹该键的调用须恰好 1 处",
                1, countRegex(serviceCode, RE_FACING_READ_WRAPPED));
    }

    // ==================== L2 · UI 回显两处 fallback ====================

    /** L2 意图：paintChip 回显与诊断摘要两处 fallback 均为 FACING_LEFT，且仍归一包裹。 */
    @Test
    public void l2_uiTwoFallbacksAreFacingLeft() {
        assertEquals("L2 UI rearCameraFacing getString 读取点须恰好 2 处",
                2, countRegex(activityCode, RE_READ_FACING_ANY));
        assertEquals("L2 UI 两处 fallback 均须为 FACING_LEFT",
                2, countRegex(activityCode, RE_READ_FACING_LEFT));
        assertEquals("L2 UI 不得残留 FACING_AUTO fallback",
                0, countRegex(activityCode, RE_READ_FACING_AUTO));
        assertEquals("L2 UI 两处读取仍经 normalizeFacing 包裹（归一未动）",
                2, countRegex(activityCode, RE_FACING_READ_WRAPPED));
    }

    // ==================== L3 · 哨兵未破 ====================

    /** L3a 意图（A2 基石）：FACING_AUTO 常量与 normalizeFacing 的 else 兜底逐字未动。 */
    @Test
    public void l3a_autoSentinelAndNormalizeFallbackIntact() {
        assertTrue("L3a const val FACING_AUTO = \"auto\" 须仍在",
                manualCode.contains("const val FACING_AUTO = \"auto\""));
        assertTrue("L3a normalizeFacing 的 else -> FACING_AUTO 须仍在",
                manualCode.contains("else -> FACING_AUTO"));
        assertTrue("L3a normalizeFacing 白名单仍含 FACING_LEFT -> FACING_LEFT",
                manualCode.contains("FACING_LEFT -> FACING_LEFT"));
        assertTrue("L3a facingDegrees 左向 270 分支须仍在",
                manualCode.contains("FACING_LEFT -> 270"));
    }

    /** L3b 意图（A7 基石）：Overlay 两处 rearCameraFacing 默认值仍是 FACING_AUTO。 */
    @Test
    public void l3b_overlayTwoAutoDefaultsIntact() {
        assertEquals("L3b RearCameraOverlay 两处 rearCameraFacing 默认仍为 FACING_AUTO（:296/:783）",
                2, countOccurrences(overlayCode,
                        "rearCameraFacing: String = ManualOrientation.FACING_AUTO"));
        assertTrue("L3b Style 字段默认值仍是 FACING_AUTO（含 val 前缀）",
                overlayCode.contains("val rearCameraFacing: String = ManualOrientation.FACING_AUTO"));
        // 5.4.17 起 show() 末尾新增 cameraLens 参数，故 FACING_AUTO 后可能是 ',' 而非 ')'。
        // 契约仍是「show 参数的 rearCameraFacing 默认值保持 FACING_AUTO」。
        assertTrue("L3b show 参数默认值仍是 FACING_AUTO（其后为 ',' 或 ')' 均可）",
                countRegex(overlayCode,
                        "rearCameraFacing: String = ManualOrientation\\.FACING_AUTO\\s*[,)]") >= 1);
    }

    /** L3c 意图（A4 基石）：finalDeg 三重守卫与 run{val deg=finalDeg} 遮蔽逐字未动。 */
    @Test
    public void l3c_finalDegGuardsAndShadowingIntact() {
        assertTrue("L3c manualFacing 读取且归一仍在",
                applyCode.contains("val manualFacing = ManualOrientation.normalizeFacing(style.rearCameraFacing)"));
        assertTrue("L3c finalDeg 守卫①小窗+横条仍在",
                applyCode.contains("val finalDeg = if (style.mode == MODE_SMALLWINDOW && landscapeWindow"));
        assertTrue("L3c finalDeg 守卫②显式方向非 auto 仍在",
                applyCode.contains("&& manualFacing != ManualOrientation.FACING_AUTO"));
        assertTrue("L3c finalDeg 用手动角 else 回退 deg 仍在",
                applyCode.contains(") ManualOrientation.facingDegrees(manualFacing) else deg"));
        assertTrue("L3c run 块内同名遮蔽仍在",
                applyCode.contains("val deg = finalDeg"));
    }

    // ==================== L4 · 纯函数真值表 ====================

    /** L4a 意图：未配置时新 fallback "left" 归一仍 left，角度 270，横条小窗 finalDeg=270。 */
    @Test
    public void l4a_unconfiguredLeftFallbackYields270InLandscapeWindow() {
        assertEquals("L4a 新 fallback left 归一后仍 left", FACING_LEFT, refNormalizeFacing("left"));
        assertEquals("L4a left 角度为 270", 270, refFacingDegrees("left"));
        assertEquals("L4a 未配置链路：left → 横条小窗 finalDeg 270",
                270, refFinalDeg(true, true, "left", 90, false));
    }

    /** L4b 意图：已配置用户四方向 + auto 真值表完全不受本轮改动影响。 */
    @Test
    public void l4b_explicitFacingsUnaffected() {
        assertEquals("L4b up 归一 up", FACING_UP, refNormalizeFacing("up"));
        assertEquals("L4b up 角度 0", 0, refFacingDegrees("up"));
        assertEquals("L4b right 归一 right", FACING_RIGHT, refNormalizeFacing("right"));
        assertEquals("L4b right 角度 90", 90, refFacingDegrees("right"));
        assertEquals("L4b down 归一 down", FACING_DOWN, refNormalizeFacing("down"));
        assertEquals("L4b down 角度 180", 180, refFacingDegrees("down"));
        assertEquals("L4b left 归一 left", FACING_LEFT, refNormalizeFacing("left"));
        assertEquals("L4b left 角度 270", 270, refFacingDegrees("left"));
        assertEquals("L4b auto 归一 auto（哨兵保留）", FACING_AUTO, refNormalizeFacing("auto"));
        assertEquals("L4b auto 角度 0（无害默认）", 0, refFacingDegrees("auto"));
        assertEquals("L4b 拼错仍落 auto（哨兵语义未改）", FACING_AUTO, refNormalizeFacing("bogus"));
    }

    /** L4c 意图：竖条窗 landscapeWindow=false 时不套 270，恒返回原 deg（0）。 */
    @Test
    public void l4c_portraitWindowNeverAppliesLeft270() {
        String[] facings = { "up", "right", "down", "left", "auto" };
        for (int i = 0; i < facings.length; i++) {
            assertEquals("L4c 竖条小窗 facing=" + facings[i] + " 恒 0（不套 270）",
                    0, refFinalDeg(true, false, facings[i], 90, false));
            assertEquals("L4c 竖条全屏 facing=" + facings[i] + " 恒 0（不套 270）",
                    0, refFinalDeg(false, false, facings[i], 90, false));
            assertEquals("L4c 竖条 facing=" + facings[i] + " finalDeg 等于原 deg",
                    refDegWhen(true, false, 90, false),
                    refFinalDeg(true, false, facings[i], 90, false));
        }
        assertEquals("L4c 明确 left + 竖条仍 0", 0, refFinalDeg(true, false, "left", 90, false));
    }

    // ==================== L5 · 版本断言 ====================

    /** L5 意图：版本升到 86/5.5.1，旧号 85/5.5.0 清干净。 */
    @Test
    public void l5_versionIs81Build5416NoOldNumbers() {
        assertTrue("L5 build.gradle 须含 versionCode 135", gradleSrc.contains("versionCode 135"));
        assertTrue("L5 build.gradle 须含 versionName \"5.7.13-lx102\"",
                gradleSrc.contains("versionName \"5.7.13-lx102\""));
        assertFalse("L5 须已无 versionCode 85", gradleSrc.contains("versionCode 85"));
        assertFalse("L5 须已无 5.5.0", gradleSrc.contains("5.5.0"));
    }
}
