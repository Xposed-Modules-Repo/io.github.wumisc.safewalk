package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * 【5.4.15(80) 手动方向】三项手动方向选择的纯 JVM 契约用例（本轮新增专项）。
 *
 * <p><b>覆盖范围（success_criteria 1）：</b>横屏窗口形状 {@code landscapeWindowShape} /
 * 竖屏窗口形状 {@code portraitWindowShape} / 后摄转正方向 {@code rearCameraFacing} 三键的
 * 归一落回 auto、四象限角度、三态形状、{@code finalDeg} 覆盖逻辑、
 * {@code isLandscapeWindowNow} 手动形状只在小窗生效、以及改动即时生效（清锁 + 重锁）。</p>
 *
 * <p><b>拍板语义（务必先读）：</b></p>
 * <ul>
 *   <li>形状取值 auto/landscape/portrait，默认 auto；角度取值 auto/up/right/down/left，
 *       角度 up=0 / right=90 / down=180 / left=270，默认 auto。</li>
 *   <li><b>横放/竖放 = 窗口框形状</b>（不是画面转几度）；<b>后摄方向 = 直接指定画面最终朝向角</b>
 *       （不是叠加补偿角）。两个维度相互独立。</li>
 *   <li><b>竖条窗口画面永不旋转</b>：后摄方向只在「横条小窗」生效；竖条恒 0、全屏 modal 完全不套用。</li>
 * </ul>
 *
 * <p><b>测试性质声明：</b></p>
 * <ul>
 *   <li><b>A 组 · 源码文本契约（stripComments 口径为主）</b>：读 {@code SafeWalkDataStore.kt} /
 *       {@code RearCameraOverlay.kt} / {@code ConfigProvider.kt} 原文，锁住三键定义与白名单、
 *       {@code ManualOrientation} 的归一/角度/三态四函数字面、{@code finalDeg} 三重守卫与
 *       {@code run { val deg = finalDeg }} 遮蔽、{@code isLandscapeWindowNow} 小窗手动覆盖、
 *       {@code manualOrientChanged} 清锁与 {@code reapplyManualOrientation} 重锁链。同时正向断言
 *       5.4.14 冻结的 deg when 四分支/注释/token 基线仍在（手动覆盖是「在其后加一层」，不是改写）。</li>
 *   <li><b>B 组 · 纯函数真值表（独立参考实现，绝不假装调用私有/编译期未链接的 Kotlin 符号）</b>：
 *       本模块 JVM 单测源码目录只有 .java，{@code ManualOrientation} 是 Kotlin object，不能从本
 *       测试类直接 {@code import} 调用（会编译失败）。本组用类内独立纯函数 {@code refNormalizeShape /
 *       refNormalizeFacing / refFacingDegrees / refShapeIsLandscape} 复刻取证到的四函数真值表，
 *       <b>绝不假装直接调用了生产函数</b>。参考实现是否与源码一致，由 A 组文本断言兜底。</li>
 *   <li><b>C 组 · 集成决策真值表（独立参考实现）</b>：{@code finalDeg} / {@code isLandscapeWindowNow} /
 *       {@code manualOrientChanged} 依赖 Android View/Camera 上下文，<b>JVM 无法直接调用</b>；本组用
 *       {@code refFinalDeg / refShapeOverride / refManualOrientChanged} 复刻实现报告第 3.2/3.3/3.5 节
 *       的算式与落点，逐场景锁住真值，A 组文本断言兜底「参考实现确实接上了源码」。</li>
 *   <li><b>D 组 · 可证性声明</b>：真机肉眼效果（转正到上/右/下/左的观感、deg=180/270 时竖条 buffer
 *       拉伸到横条框、反复切换瞬态、一帧形状延迟）JVM 不可证，交 Vic/真机，见类末。</li>
 * </ul>
 *
 * <p><b>参考实现 ↔ 生产源码映射：</b></p>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应</th></tr>
 *   <tr><td>{@code refNormalizeShape/Facing}</td>
 *       <td>SafeWalkDataStore.kt:435-449（when 白名单，else 落 auto）。</td></tr>
 *   <tr><td>{@code refFacingDegrees}</td><td>SafeWalkDataStore.kt:456-461（right90/down180/left270/else0）。</td></tr>
 *   <tr><td>{@code refShapeIsLandscape}</td><td>SafeWalkDataStore.kt:468-472（landscape true/portrait false/else null）。</td></tr>
 *   <tr><td>{@code refFinalDeg}</td>
 *       <td>RearCameraOverlay.kt:1266-1273（finalDeg 三重守卫 + run 遮蔽）。</td></tr>
 *   <tr><td>{@code refShapeOverride}</td>
 *       <td>RearCameraOverlay.kt:1477-1495（isLandscapeWindowNow 小窗手动覆盖 + auto 落回原判据）。</td></tr>
 *   <tr><td>{@code refManualOrientChanged}</td>
 *       <td>RearCameraOverlay.kt:862-885（三键比较 + 清锁 + reapplyManualOrientation）。</td></tr>
 * </table>
 *
 * <p>本文件只新增测试，不改任何实现代码、配置或既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不编译、不跑测试（交 Vic 统一编译运行）。写法与 {@code LandscapeNoRotateContractTest} /
 * {@code LandscapeFishHeadRightContractTest} 一致。</p>
 */
public class ManualOrientationContractTest {

    private static final String STORE_REL =
            "src/main/java/io/github/wumisc/safewalk/SafeWalkDataStore.kt";
    private static final String OVERLAY_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String CONFIG_REL =
            "src/main/java/io/github/wumisc/safewalk/ConfigProvider.kt";

    private static final String MANUAL_OBJ_SIG = "object ManualOrientation {";
    private static final String APPLY_FRAMING_SIG =
            "private fun applyFraming(windowW: Int = 0, windowH: Int = 0)";
    private static final String IS_LAND_SIG =
            "private fun isLandscapeWindowNow(): Boolean";
    private static final String REAPPLY_SIG =
            "private fun reapplyManualOrientation()";

    private static String storeSrc;       // SafeWalkDataStore.kt 原文（全文口径）
    private static String storeCode;      // SafeWalkDataStore.kt 去注释
    private static String overlaySrc;     // RearCameraOverlay.kt 原文
    private static String overlayCode;    // RearCameraOverlay.kt 去注释
    private static String configCode;     // ConfigProvider.kt 去注释
    private static String manualObjCode;  // ManualOrientation object 块（去注释）
    private static String applyFramingCode; // applyFraming() 方法体（去注释）
    private static String isLandCode;     // isLandscapeWindowNow() 方法体（去注释）
    private static String reapplyCode;    // reapplyManualOrientation() 方法体（去注释）

    @BeforeClass
    public static void loadSource() throws IOException {
        storeSrc = new String(Files.readAllBytes(locate(STORE_REL)), StandardCharsets.UTF_8);
        storeCode = stripComments(storeSrc);
        overlaySrc = new String(Files.readAllBytes(locate(OVERLAY_REL)), StandardCharsets.UTF_8);
        overlayCode = stripComments(overlaySrc);
        configCode = stripComments(new String(
                Files.readAllBytes(locate(CONFIG_REL)), StandardCharsets.UTF_8));
        manualObjCode = stripComments(extractBlock(storeSrc, MANUAL_OBJ_SIG));
        applyFramingCode = stripComments(extractBlock(overlaySrc, APPLY_FRAMING_SIG));
        isLandCode = stripComments(extractBlock(overlaySrc, IS_LAND_SIG));
        reapplyCode = stripComments(extractBlock(overlaySrc, REAPPLY_SIG));
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

    // ==================== B 组：独立参考实现（复刻四纯函数真值表） ====================

    private static final String SHAPE_AUTO = "auto";
    private static final String SHAPE_LANDSCAPE = "landscape";
    private static final String SHAPE_PORTRAIT = "portrait";
    private static final String FACING_AUTO = "auto";
    private static final String FACING_UP = "up";
    private static final String FACING_RIGHT = "right";
    private static final String FACING_DOWN = "down";
    private static final String FACING_LEFT = "left";

    /** 复刻 normalizeShape()：白名单只认 landscape/portrait，其余（含 null/拼错/旧值）落 auto。 */
    private static String refNormalizeShape(String v) {
        if (SHAPE_LANDSCAPE.equals(v)) return SHAPE_LANDSCAPE;
        if (SHAPE_PORTRAIT.equals(v)) return SHAPE_PORTRAIT;
        return SHAPE_AUTO;
    }

    /** 复刻 normalizeFacing()：白名单只认 up/right/down/left，其余落 auto。 */
    private static String refNormalizeFacing(String v) {
        if (FACING_UP.equals(v)) return FACING_UP;
        if (FACING_RIGHT.equals(v)) return FACING_RIGHT;
        if (FACING_DOWN.equals(v)) return FACING_DOWN;
        if (FACING_LEFT.equals(v)) return FACING_LEFT;
        return FACING_AUTO;
    }

    /** 复刻 facingDegrees()：right=90/down=180/left=270；up 与 auto/拼错都走 else=0（auto 哨兵）。 */
    private static int refFacingDegrees(String v) {
        String n = refNormalizeFacing(v);
        if (FACING_RIGHT.equals(n)) return 90;
        if (FACING_DOWN.equals(n)) return 180;
        if (FACING_LEFT.equals(n)) return 270;
        return 0;
    }

    /** 复刻 shapeIsLandscape()：landscape=true / portrait=false / auto(含拼错)=null（不覆盖）。 */
    private static Boolean refShapeIsLandscape(String v) {
        String n = refNormalizeShape(v);
        if (SHAPE_LANDSCAPE.equals(n)) return Boolean.TRUE;
        if (SHAPE_PORTRAIT.equals(n)) return Boolean.FALSE;
        return null;
    }

    // ==================== C 组：独立参考实现（复刻集成决策链路） ====================

    /** 复刻 5.4.14 的 applyFraming deg when（未改）：首帧/未算→0；竖条→0；小窗横条→90；全屏兜底 align。 */
    private static int refDegWhen(boolean isSmall, boolean landscapeWindow,
                                  int appliedRotDeg, boolean firstFrame) {
        if (firstFrame || appliedRotDeg < 0) return 0;
        if (!landscapeWindow) return 0;
        if (isSmall) return 90;
        return refAlign(appliedRotDeg, true);
    }

    /** 复刻 PreviewOrientationPolicy.alignToWindow()（本轮未改）：全屏横条兜底输出域 ⊆{0,180}。 */
    private static int refAlign(int deg, boolean landscapeWindow) {
        if (deg == -1) return -1;
        int normalized = ((deg % 360) + 360) % 360;
        boolean swapped = normalized == 90 || normalized == 270;
        boolean needSwap = !landscapeWindow;
        return swapped == needSwap ? normalized : (normalized + 90) % 360;
    }

    /** 复刻 finalDeg（RearCameraOverlay.kt:1266-1269）：小窗 + 横条 + 显式方向 → 手动角，否则用 deg。 */
    private static int refFinalDeg(boolean isSmall, boolean landscapeWindow,
                                   String facing, int appliedRotDeg, boolean firstFrame) {
        int deg = refDegWhen(isSmall, landscapeWindow, appliedRotDeg, firstFrame);
        String manualFacing = refNormalizeFacing(facing);
        boolean manualActive = isSmall && landscapeWindow && !FACING_AUTO.equals(manualFacing);
        return manualActive ? refFacingDegrees(manualFacing) : deg;
    }

    /** 复刻 isLandscapeWindowNow 的手动形状覆盖：仅小窗读形状；auto→null（落回原判据）。
     *  screenLandscape 决定读横屏组还是竖屏组。返回 null 表示「无覆盖，沿用系统/姿态判据」。 */
    private static Boolean refShapeOverride(boolean isSmall, boolean screenLandscape,
                                            String landscapeShape, String portraitShape) {
        if (!isSmall) return null; // 全屏 modal 完全不套手动覆盖
        String cfg = screenLandscape ? landscapeShape : portraitShape;
        return refShapeIsLandscape(cfg);
    }

    /** 复刻 manualOrientChanged（:862-864）：三键任一变化即为 true。 */
    private static boolean refManualOrientChanged(String oldL, String oldP, String oldF,
                                                  String newL, String newP, String newF) {
        return !oldL.equals(newL) || !oldP.equals(newP) || !oldF.equals(newF);
    }

    // ==================== A 组 · 源码文本契约 ====================

    /** A1 意图：三键定义 + 白名单 + ManualOrientation object 全部落地（缺一键则导出/读取断链）。 */
    @Test
    public void a1_threeKeysWhitelistAndManualObjectExist() {
        assertTrue("A1 landscapeWindowShape 键定义须在",
                storeCode.contains("const val landscapeWindowShape = \"landscapeWindowShape\""));
        assertTrue("A1 portraitWindowShape 键定义须在",
                storeCode.contains("const val portraitWindowShape = \"portraitWindowShape\""));
        assertTrue("A1 rearCameraFacing 键定义须在",
                storeCode.contains("const val rearCameraFacing = \"rearCameraFacing\""));
        assertTrue("A1 ManualOrientation object 须存在", storeCode.contains(MANUAL_OBJ_SIG));
        assertTrue("A1 白名单须含 landscapeWindowShape",
                configCode.contains("SafeWalkPrefKey.landscapeWindowShape"));
        assertTrue("A1 白名单须含 portraitWindowShape",
                configCode.contains("SafeWalkPrefKey.portraitWindowShape"));
        assertTrue("A1 白名单须含 rearCameraFacing",
                configCode.contains("SafeWalkPrefKey.rearCameraFacing"));
    }

    /** A2 意图（默认零变化基石）：归一函数对拼错/旧值/空值落回 auto，哨兵常量字面正确。 */
    @Test
    public void a2_normalizeFallsBackToAutoInSource() {
        assertTrue("A2 SHAPE_AUTO=\"auto\"", manualObjCode.contains("const val SHAPE_AUTO = \"auto\""));
        assertTrue("A2 SHAPE_LANDSCAPE=\"landscape\"",
                manualObjCode.contains("const val SHAPE_LANDSCAPE = \"landscape\""));
        assertTrue("A2 SHAPE_PORTRAIT=\"portrait\"",
                manualObjCode.contains("const val SHAPE_PORTRAIT = \"portrait\""));
        assertTrue("A2 FACING_AUTO=\"auto\"", manualObjCode.contains("const val FACING_AUTO = \"auto\""));
        assertTrue("A2 FACING_UP=\"up\"", manualObjCode.contains("const val FACING_UP = \"up\""));
        assertTrue("A2 FACING_RIGHT=\"right\"", manualObjCode.contains("const val FACING_RIGHT = \"right\""));
        assertTrue("A2 FACING_DOWN=\"down\"", manualObjCode.contains("const val FACING_DOWN = \"down\""));
        assertTrue("A2 FACING_LEFT=\"left\"", manualObjCode.contains("const val FACING_LEFT = \"left\""));
        assertTrue("A2 normalizeShape 函数须在",
                manualObjCode.contains("fun normalizeShape(v: String?): String = when (v)"));
        assertTrue("A2 normalizeShape 的 else 须落 SHAPE_AUTO", manualObjCode.contains("else -> SHAPE_AUTO"));
        assertTrue("A2 normalizeFacing 函数须在",
                manualObjCode.contains("fun normalizeFacing(v: String?): String = when (v)"));
        assertTrue("A2 normalizeFacing 的 else 须落 FACING_AUTO", manualObjCode.contains("else -> FACING_AUTO"));
    }

    /** A3 意图：角度四象限、三态形状的 when 分支字面正确；参考实现映射才成立。 */
    @Test
    public void a3_angleAndShapeTruthTableInSource() {
        assertTrue("A3 facingDegrees 签名",
                manualObjCode.contains("fun facingDegrees(v: String?): Int = when (normalizeFacing(v))"));
        assertTrue("A3 right→90", manualObjCode.contains("FACING_RIGHT -> 90"));
        assertTrue("A3 down→180", manualObjCode.contains("FACING_DOWN -> 180"));
        assertTrue("A3 left→270", manualObjCode.contains("FACING_LEFT -> 270"));
        assertTrue("A3 up/auto→0 走 else", manualObjCode.contains("else -> 0"));
        assertTrue("A3 shapeIsLandscape 签名（Boolean? 三态）",
                manualObjCode.contains("fun shapeIsLandscape(v: String?): Boolean? = when (normalizeShape(v))"));
        assertTrue("A3 landscape→true", manualObjCode.contains("SHAPE_LANDSCAPE -> true"));
        assertTrue("A3 portrait→false", manualObjCode.contains("SHAPE_PORTRAIT -> false"));
        assertTrue("A3 auto→null", manualObjCode.contains("else -> null"));
    }

    /** A4 意图（核心）：finalDeg 三重守卫 + run{val deg=finalDeg} 遮蔽落地；且 5.4.14 冻结点未被改写。 */
    @Test
    public void a4_finalDegOverrideAndFrozenPointsIntact() {
        assertTrue("A4 manualFacing 读且归一",
                applyFramingCode.contains("val manualFacing = ManualOrientation.normalizeFacing(style.rearCameraFacing)"));
        assertTrue("A4 finalDeg 守卫①小窗+横条",
                applyFramingCode.contains("val finalDeg = if (style.mode == MODE_SMALLWINDOW && landscapeWindow"));
        assertTrue("A4 finalDeg 守卫②显式方向非 auto",
                applyFramingCode.contains("&& manualFacing != ManualOrientation.FACING_AUTO"));
        assertTrue("A4 finalDeg 用手动角 else 回退 deg",
                applyFramingCode.contains(") ManualOrientation.facingDegrees(manualFacing) else deg"));
        assertTrue("A4 run 块内同名遮蔽（下游 deg 吃到手动角）",
                applyFramingCode.contains("val deg = finalDeg"));
        // 5.4.14 冻结：手动覆盖是「在其后加一层」，deg when 四分支/注释/随动字面一行未改。
        assertTrue("A4 deg when 判据仍在", applyFramingCode.contains("val deg = when"));
        assertTrue("A4 首帧 0 分支仍在", applyFramingCode.contains("appliedRotDeg < 0 -> 0"));
        assertTrue("A4 竖条 0 分支仍在", applyFramingCode.contains("!landscapeWindow -> 0"));
        assertTrue("A4 小窗横条 90 分支仍在", applyFramingCode.contains("style.mode == MODE_SMALLWINDOW -> LANDSCAPE_SMALLWINDOW_DEG"));
        assertTrue("A4 全屏兜底 alignToWindow 分支仍在",
                applyFramingCode.contains("else -> PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)"));
        assertEquals("A4 alignToWindow(appliedRotDeg, true) 全文仍恰 1 处（未新增兜底）",
                1, countOccurrences(overlayCode, "alignToWindow(appliedRotDeg, true)"));
        assertTrue("A4 layer.rotation 仍随 deg 变量（未写死手动角）",
                applyFramingCode.contains("layer.rotation = deg.toFloat()"));
        assertTrue("A4 HUD 反向角仍随 -deg（未写死手动角）",
                applyFramingCode.contains("hudColumn?.rotation = -deg.toFloat()"));
    }

    /** A5 意图：手动窗口形状覆盖只在 MODE_SMALLWINDOW 内，按系统朝向选组，auto 落回原判据。 */
    @Test
    public void a5_manualShapeOnlyInSmallWindowInSource() {
        assertTrue("A5 isLandscapeWindowNow 须有小窗守卫",
                isLandCode.contains("if (style.mode == MODE_SMALLWINDOW)"));
        assertTrue("A5 按实时横竖判定选组（5.6.1 起由姿态+系统方向并集给出）",
                isLandCode.contains("val shapeCfg = if (liveWindowLandscape()) {"));
        assertTrue("A5 横屏组读 landscapeWindowShape", isLandCode.contains("style.landscapeWindowShape"));
        assertTrue("A5 竖屏组读 portraitWindowShape", isLandCode.contains("style.portraitWindowShape"));
        assertTrue("A5 形状归一为三态",
                isLandCode.contains("val manual = ManualOrientation.shapeIsLandscape(shapeCfg)"));
        assertTrue("A5 仅非 null 才覆盖返回", isLandCode.contains("if (manual != null) return manual"));
        assertTrue("A5 auto 落回 MODE_FIXED 原判据",
                isLandCode.contains("PreviewOrientationPolicy.MODE_FIXED"));
        assertTrue("A5 auto 落回实时横竖判定（与窗口形状、画面角度同源）",
                isLandCode.contains("else liveWindowLandscape()"));
        assertTrue("A5 判定链仍覆盖系统方向项",
                overlayCode.contains("lastKnownScreenLandscape"));
    }

    /** A6 意图（立即生效）：三键任一变化清锁 + 快路径重算，reapply 内完整清锁/重算/重锁链。 */
    @Test
    public void a6_manualOrientChangedClearsLockAndReapplies() {
        assertTrue("A6 manualOrientChanged 比较 landscapeWindowShape",
                overlayCode.contains("val manualOrientChanged = target.landscapeWindowShape != style.landscapeWindowShape"));
        assertTrue("A6 比较 portraitWindowShape",
                overlayCode.contains("|| target.portraitWindowShape != style.portraitWindowShape"));
        assertTrue("A6 比较 rearCameraFacing",
                overlayCode.contains("|| target.rearCameraFacing != style.rearCameraFacing"));
        assertTrue("A6 变化即清锁（含预览未开的重建路径）",
                overlayCode.contains("if (manualOrientChanged) smallLock = null"));
        assertTrue("A6 纳入快路径触发条件",
                overlayCode.contains("|| lowLightChanged || enhanceChanged || rotationChanged || manualOrientChanged")
                        && (overlayCode.contains("|| manualOrientChanged)")
                                || overlayCode.contains("|| manualOrientChanged\n")
                                || overlayCode.contains("|| manualOrientChanged\t")));
        assertTrue("A6 快路径内重算调用",
                overlayCode.contains("if (manualOrientChanged) reapplyManualOrientation()"));
        assertTrue("A6 reapply 方法存在", overlayCode.contains(REAPPLY_SIG));
        assertTrue("A6 reapply 内先清锁", reapplyCode.contains("smallLock = null"));
        assertTrue("A6 reapply 内重算角度", reapplyCode.contains("refreshRotation()"));
        assertTrue("A6 reapply 内重建窗口参数", reapplyCode.contains("val lp = buildParams()"));
        assertTrue("A6 reapply 内更新布局",
                reapplyCode.contains("wm.updateViewLayout(root, lp)"));
        assertTrue("A6 reapply 内按新窗口 applyFraming",
                reapplyCode.contains("applyFraming(lp.width, lp.height)"));
        assertTrue("A6 reapply 内重锁首帧", reapplyCode.contains("lockFirstFrame()"));
        assertTrue("A6 reapply 全屏分支不套手动覆盖",
                reapplyCode.contains("if (style.mode != MODE_SMALLWINDOW)"));
    }

    /** A7 意图：Style 字段/show 参数默认 auto + @JvmOverloads，保证旧调用点与默认行为零变化。 */
    @Test
    public void a7_defaultsAndJvmOverloadsCompatKeepBaseline() {
        assertTrue("A7 Style 字段默认 SHAPE_AUTO（landscape）",
                overlayCode.contains("val landscapeWindowShape: String = ManualOrientation.SHAPE_AUTO"));
        assertTrue("A7 Style 字段默认 SHAPE_AUTO（portrait）",
                overlayCode.contains("val portraitWindowShape: String = ManualOrientation.SHAPE_AUTO"));
        assertTrue("A7 Style 字段默认 FACING_AUTO",
                overlayCode.contains("val rearCameraFacing: String = ManualOrientation.FACING_AUTO"));
        assertTrue("A7 show 参数默认 SHAPE_AUTO（landscape）",
                overlayCode.contains("landscapeWindowShape: String = ManualOrientation.SHAPE_AUTO,"));
        assertTrue("A7 show 参数默认 FACING_AUTO",
                overlayCode.contains("rearCameraFacing: String = ManualOrientation.FACING_AUTO"));
        assertTrue("A7 show 保留 @JvmOverloads", overlaySrc.contains("@JvmOverloads"));
        assertTrue("A7 show 内三键归一后入 Style（landscape）",
                overlayCode.contains("ManualOrientation.normalizeShape(landscapeWindowShape)"));
        assertTrue("A7 show 内三键归一后入 Style（facing）",
                overlayCode.contains("ManualOrientation.normalizeFacing(rearCameraFacing)"));
    }

    // ==================== B 组 · 纯函数真值表 ====================

    /** B1 意图（默认零变化）：normalizeShape 对拼错/旧值/空值一律落 auto。 */
    @Test
    public void b1_normalizeShapeTyposOldAndNullFallBackToAuto() {
        assertEquals("B1 landscape 原样", SHAPE_LANDSCAPE, refNormalizeShape("landscape"));
        assertEquals("B1 portrait 原样", SHAPE_PORTRAIT, refNormalizeShape("portrait"));
        assertEquals("B1 auto 原样", SHAPE_AUTO, refNormalizeShape("auto"));
        String[] bad = { null, "", "LANDSCAPE", "horizontal", "vertical", "landscapeX", "1" };
        for (int i = 0; i < bad.length; i++) {
            assertEquals("B1 拼错/旧值/空值须落 auto: " + bad[i], SHAPE_AUTO, refNormalizeShape(bad[i]));
        }
    }

    /** B2 意图（默认零变化）：normalizeFacing 对拼错/旧值/空值一律落 auto。 */
    @Test
    public void b2_normalizeFacingTyposOldAndNullFallBackToAuto() {
        assertEquals("B2 up 原样", FACING_UP, refNormalizeFacing("up"));
        assertEquals("B2 right 原样", FACING_RIGHT, refNormalizeFacing("right"));
        assertEquals("B2 down 原样", FACING_DOWN, refNormalizeFacing("down"));
        assertEquals("B2 left 原样", FACING_LEFT, refNormalizeFacing("left"));
        assertEquals("B2 auto 原样", FACING_AUTO, refNormalizeFacing("auto"));
        String[] bad = { null, "", "UP", "upward", "top", "0", "90" };
        for (int i = 0; i < bad.length; i++) {
            assertEquals("B2 拼错/旧值/空值须落 auto: " + bad[i], FACING_AUTO, refNormalizeFacing(bad[i]));
        }
    }

    /** B3 意图（拍板口径）：四象限角度 up=0/right=90/down=180/left=270；auto 哨兵无害默认 0。 */
    @Test
    public void b3_facingDegreesFourQuadrantsAndAutoSentinel() {
        assertEquals("B3 up=0", 0, refFacingDegrees("up"));
        assertEquals("B3 right=90", 90, refFacingDegrees("right"));
        assertEquals("B3 down=180", 180, refFacingDegrees("down"));
        assertEquals("B3 left=270", 270, refFacingDegrees("left"));
        assertEquals("B3 auto 哨兵=0（调用方按 facing!=auto 决定是否用）", 0, refFacingDegrees("auto"));
        assertEquals("B3 null 归一 auto → 0", 0, refFacingDegrees(null));
        assertEquals("B3 拼错归一 auto → 0", 0, refFacingDegrees("bogus"));
    }

    /** B4 意图（三态可区分）：shapeIsLandscape 中 auto=null（不覆盖），与显式 portrait=false 可分。 */
    @Test
    public void b4_shapeIsLandscapeTriState() {
        assertEquals("B4 landscape=true", Boolean.TRUE, refShapeIsLandscape("landscape"));
        assertEquals("B4 portrait=false", Boolean.FALSE, refShapeIsLandscape("portrait"));
        assertNull("B4 auto=null（不覆盖）", refShapeIsLandscape("auto"));
        assertNull("B4 null=null", refShapeIsLandscape(null));
        assertNull("B4 拼错=null", refShapeIsLandscape("bogus"));
    }

    // ==================== C 组 · 集成决策真值表 ====================

    /** C1 意图（需求落地）：横条小窗 + 显式方向 → finalDeg 用手动角，与 appliedRotDeg 无关。 */
    @Test
    public void c1_smallLandscapeExplicitFacingUsesManualAngle() {
        assertEquals("C1 横条小窗 up=0 → finalDeg 0（覆盖 5.4.14 的 90）",
                0, refFinalDeg(true, true, "up", 90, false));
        assertEquals("C1 横条小窗 right=90 → finalDeg 90",
                90, refFinalDeg(true, true, "right", 90, false));
        assertEquals("C1 横条小窗 down=180 → finalDeg 180",
                180, refFinalDeg(true, true, "down", 90, false));
        assertEquals("C1 横条小窗 left=270 → finalDeg 270",
                270, refFinalDeg(true, true, "left", 90, false));
        // 与 appliedRotDeg 无关
        assertEquals("C1 appliedRotDeg=0 仍取手动角 180",
                180, refFinalDeg(true, true, "down", 0, false));
        assertEquals("C1 appliedRotDeg=270 仍取手动角 180",
                180, refFinalDeg(true, true, "down", 270, false));
    }

    /** C2 意图（写死契约）：竖条窗口画面永不旋转——即使显式选了方向，finalDeg 仍恒 0。 */
    @Test
    public void c2_portraitWindowFinalDegAlwaysZero() {
        String[] facings = { "up", "right", "down", "left" };
        for (int i = 0; i < facings.length; i++) {
            assertEquals("C2 竖条小窗 facing=" + facings[i] + " 恒 0",
                    0, refFinalDeg(true, false, facings[i], 90, false));
            assertEquals("C2 竖条全屏 facing=" + facings[i] + " 恒 0",
                    0, refFinalDeg(false, false, facings[i], 90, false));
        }
    }

    /** C3 意图（全屏不受控）：全屏横条不套用手动方向，仍走 5.4.14 兜底 deg。 */
    @Test
    public void c3_fullscreenLandscapeIgnoresManualFacing() {
        int[] applied = { 0, 90, 180, 270 };
        String[] facings = { "up", "right", "down", "left" };
        for (int i = 0; i < applied.length; i++) {
            int baseline = refDegWhen(false, true, applied[i], false);
            assertTrue("C3 全屏横条兜底输出须 ⊆{0,180}，实际=" + baseline,
                    baseline == 0 || baseline == 180);
            for (int j = 0; j < facings.length; j++) {
                assertEquals("C3 全屏横条 applied=" + applied[i] + " facing=" + facings[j]
                                + " 须等于 5.4.14 基线 deg",
                        baseline, refFinalDeg(false, true, facings[j], applied[i], false));
            }
        }
    }

    /** C4 意图（默认零变化）：facing 为 auto/null/拼错时 finalDeg==deg，横竖条、小窗全屏都不套用。 */
    @Test
    public void c4_autoFacingFinalDegEqualsDegEverywhere() {
        boolean[][] modes = { {true, true}, {true, false}, {false, true}, {false, false} };
        int[] applied = { 0, 90, 180, 270 };
        for (int m = 0; m < modes.length; m++) {
            boolean small = modes[m][0];
            boolean land = modes[m][1];
            for (int i = 0; i < applied.length; i++) {
                int deg = refDegWhen(small, land, applied[i], false);
                String tag = " small=" + small + " land=" + land + " applied=" + applied[i];
                assertEquals("C4 auto → finalDeg==deg" + tag,
                        deg, refFinalDeg(small, land, "auto", applied[i], false));
                assertEquals("C4 null 归一 auto → finalDeg==deg" + tag,
                        deg, refFinalDeg(small, land, null, applied[i], false));
                assertEquals("C4 拼错归一 auto → finalDeg==deg" + tag,
                        deg, refFinalDeg(small, land, "bogus", applied[i], false));
            }
        }
        assertEquals("C4 首帧 + auto 仍 0（首帧保护优先）",
                0, refFinalDeg(true, true, "auto", 90, true));
    }

    /** C5 意图：手动形状只改变小窗窗口形状；auto 落回原判据；全屏完全不读形状配置。 */
    @Test
    public void c5_manualShapeOnlyAffectsSmallWindow() {
        assertEquals("C5 小窗+系统横屏+landscape → true（横条窗）",
                Boolean.TRUE, refShapeOverride(true, true, "landscape", "auto"));
        assertEquals("C5 小窗+系统横屏+portrait → false（竖条窗）",
                Boolean.FALSE, refShapeOverride(true, true, "portrait", "auto"));
        assertEquals("C5 小窗+系统竖屏+portraitShape=landscape → true（竖屏也能指定横条窗）",
                Boolean.TRUE, refShapeOverride(true, false, "auto", "landscape"));
        assertEquals("C5 小窗+系统竖屏+portraitShape=portrait → false",
                Boolean.FALSE, refShapeOverride(true, false, "auto", "portrait"));
        assertNull("C5 小窗但形状 auto → 不覆盖（落回原判据）",
                refShapeOverride(true, true, "auto", "auto"));
        assertNull("C5 小窗但形状拼错 → 不覆盖",
                refShapeOverride(true, false, "bogus", "bogus"));
        assertNull("C5 全屏+显式 landscape → 仍不覆盖（全屏不套手动形状）",
                refShapeOverride(false, true, "landscape", "auto"));
        assertNull("C5 全屏+显式 portrait → 仍不覆盖",
                refShapeOverride(false, false, "auto", "portrait"));
    }

    /** C6 意图（立即生效判据）：三键任一变化即触发清锁重算；三键全同则不触发（默认空转）。 */
    @Test
    public void c6_manualOrientChangedWhenAnyKeyDiffers() {
        assertFalse("C6 三键全 auto 未变 → 不触发（默认零变化）",
                refManualOrientChanged("auto", "auto", "auto", "auto", "auto", "auto"));
        assertTrue("C6 只改 landscapeWindowShape → 触发",
                refManualOrientChanged("auto", "auto", "auto", "landscape", "auto", "auto"));
        assertTrue("C6 只改 portraitWindowShape → 触发",
                refManualOrientChanged("auto", "auto", "auto", "auto", "portrait", "auto"));
        assertTrue("C6 只改 rearCameraFacing → 触发",
                refManualOrientChanged("auto", "auto", "auto", "auto", "auto", "right"));
        assertTrue("C6 三键全改 → 触发",
                refManualOrientChanged("auto", "auto", "auto", "landscape", "portrait", "left"));
        assertFalse("C6 显式值未变（不因重新打开而误触发）",
                refManualOrientChanged("landscape", "portrait", "right",
                        "landscape", "portrait", "right"));
    }
}
