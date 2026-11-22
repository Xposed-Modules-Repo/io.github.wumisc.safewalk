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
 * 59 号任务（5.4.14(79)）「横条小窗画面顺时针转 90°（鱼头朝右）」的纯 JVM 契约用例。
 *
 * <p><b>性质声明（务必先读）：</b>本文件原为 58 号（5.4.13(78)）「横条窗画面不转」契约。
 * 59 号任务由用户明确授权推翻该方向契约：跟随系统横屏的小窗横条展示角由 0 改为 90（顺时针，
 * 鱼头朝右）。Max 已在 {@code applyFraming()} 的 deg when 新增
 * {@code style.mode == MODE_SMALLWINDOW -> 90}，竖条仍 0、全屏横条仍走原
 * {@code alignToWindow(appliedRotDeg, true)}。本文件据此把 58 号中已过期的断言<b>合法同步</b>到
 * 新语义（用户授权的方向契约变更，非改测试迁就 bug），版本 78→79 / 5.4.13→5.4.14；
 * 59 号新增专项契约见 {@code LandscapeFishHeadRightContractTest}。</p>
 *
 * <p><b>测试性质声明：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约（stripComments 口径为主）</b>：读 {@code build.gradle}、
 *       {@code RearCameraOverlay.kt}、{@code PreviewOrientationPolicy.kt} 文本，锁住
 *       「版本号已升且旧号已清」「5.4.14 设计意图注释逐字固化在 applyFraming 内、且确为注释
 *       不是代码」「{@code val deg = when} + 小窗横条 {@code style.mode == MODE_SMALLWINDOW -> 90}
 *       新分支 + 竖条 {@code !landscapeWindow -> 0} + 首帧 {@code appliedRotDeg < 0 -> 0}
 *       + else 的 {@code alignToWindow(appliedRotDeg, true)}（全屏兜底，全文恰 1 处）」
 *       「硬边界 token 计数不变」「referenceDegrees / D1-D3 诊断 / 54 号曝光互斥守卫 /
 *       5.4.10 带参 applyFraming / 57 号调色 pow 提亮式均在」，防止版本回退或误删。</li>
 *   <li><b>B 组 · 端到端真值表（独立参考实现复算，绝不假装调用私有方法）</b>：生产链路
 *       （{@code referenceDegrees() → rotationDegrees() → applyMode() → alignToWindow()}，
 *       以及 5.4.14 新增 deg when）依赖 Android 上下文且跨多个私有方法，<b>JVM 无法直接调用</b>。
 *       本组用类内独立纯函数 {@code refRefDeg / refRotation / refAlign / refFinalDeg} 复刻
 *       调查报告 Q1/Q5/Q6 与实现报告第四节算式，锁住本任务特有语义：正常横屏 sensor=90 +
 *       系统回调横屏 → 参考角 90 → 原始旋转 0 → <b>小窗横条最终展示角 90（鱼头朝右，由 deg when
 *       覆盖）</b>；<b>竖条窗最终展示角仍 0</b>（deg when 的 {@code !landscapeWindow -> 0}）；
 *       {@code refAlign} 复刻的 {@code Policy.alignToWindow}（<b>Policy 本轮未改</b>）横条输出域
 *       仍 ⊆{0,180}，这是<b>全屏横条兜底</b>的数学性质，<b>不代表小窗横条展示角</b>（后者由 deg
 *       when 覆盖为 90，二者不矛盾）；buffer 取窗口短边×长边=488×845，与 deg=90 时 swap 后的
 *       竖条框 488×845 1:1 自洽。<b>既有 {@code PreviewOrientationPolicyTest} 的 26 用例已覆盖
 *       alignToWindow 逐值精确断言，本文件刻意不重复这些逐值断言</b>，只断言「输出域/不变量/
 *       端到端链路/窗口形状对照」。</li>
 *   <li><b>C 组 · 可证性声明</b>：见本 javadoc 末段「只能真机验证、本文件测不到的部分」——
 *       真机肉眼效果 JVM 一律测不到，只锁可判定的源码结构与纯函数真值。</li>
 * </ul>
 *
 * <p><b>B 组参考实现 ↔ 生产源码映射（避免误读为直接调用）：</b>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应</th></tr>
 *   <tr><td>{@code refRefDeg(screenLandscape, pose)}</td>
 *       <td>RearCameraOverlay.kt:1450-1457 {@code referenceDegrees()}：<br>
 *           auto 下系统回调横屏 → 90；回调竖屏 → {@code normalize(pose)}；未回调 → pose。</td></tr>
 *   <tr><td>{@code refRotation(sensor, ref, front)}</td>
 *       <td>PreviewOrientationPolicy.kt:75-85：后摄 {@code (s-d+360)%360}、前摄
 *           {@code (s+d)%360}，再 {@code ((raw+45)/90%4)*90} 四舍五入到 90 的倍数。</td></tr>
 *   <tr><td>{@code refAlign(deg, landscape)}</td>
 *       <td>PreviewOrientationPolicy.kt:137-146 {@code alignToWindow()}（本轮未改）：哨兵 -1
 *           原样返回；{@code swapped = normalized∈{90,270}}、{@code needSwap = !landscapeWindow}。
 *           仅作全屏横条兜底路径。</td></tr>
 *   <tr><td>{@code refFinalDeg(small, landscape, appliedRotDeg, firstFrame)}</td>
 *       <td>RearCameraOverlay.kt:1222-1229 deg when：首帧/竖条 → 0；小窗横条 → 90（新分支）；
 *           全屏横条 → {@code alignToWindow(appliedRotDeg, true)}。展示角最终出口。</td></tr>
 *   <tr><td>b4 的 min/max</td><td>RearCameraOverlay.kt:2143-2155 {@code openCamera()}：
 *           横条 845×488 窗口取 {@code actualBufferW=min=488 / H=max=845}。</td></tr>
 * </table>
 * 参考函数是否正确接上源码，由 A3 的 deg when 文本断言与策略算式断言兜底；两组联合才等价于
 * 「判据没被改且语义仍成立」。</p>
 *
 * <p><b>token 计数口径说明（防误报，按源码实测双口径断言）：</b>任务书 A4 把全部 token 列在
 * 「stripComments 后」标题下，但源码实测与 {@code ToneTuneContractTest} /
 * {@code TorchWhiteoutContractTest} 类 javadoc 一致：{@code FLASH_MODE_TORCH} 全文含注释为
 * <b>8</b>、去注释为 <b>1</b>；{@code CONTROL_AE_EXPOSURE_COMPENSATION} 全文为 <b>2</b>、
 * 去注释为 <b>1</b>。故 A4 对这两个 token <b>两种口径都断言</b>：全文锁基线不增不减，
 * 去注释锁真实下发点唯一。其余六个 token 全文与去注释计数一致。
 * {@code isLandscapeWindowNow()} 去注释共 4 处（1 定义 + 3 调用，KDoc 不带括号不计入），
 * 断言「总数 4 − 定义 1 = 调用 3」。D1-D3 诊断标记只存在于注释中，故 A5 对这三项用全文口径，
 * 并额外断言去注释后 {@code stage("方向诊断"} 日志代码仍在。</p>
 *
 * <p><b>只能真机验证、本文件测不到的部分（C 组可证性声明）：</b>
 * <ol>
 *   <li><b>鱼头朝右肉眼确认</b>：装 5.4.14 后，横条小窗内鱼头是否肉眼朝右（对照四向拼图
 *       R270 逆格，即 Android 顺时针 90°）——渲染主观效果，沙箱无真机画面，JVM 不可证。</li>
 *   <li><b>HUD 文字正立</b>：deg=90 下 {@code hudView.rotation=-90} 净角是否为 0、
 *       「路况预览」文字是否仍正立可读——依赖真机绘制，JVM 不可证。</li>
 *   <li><b>竖→横→竖切换</b>：反复切换后回竖屏是否恢复 deg=0、无「转一下再回正」瞬态——
 *       依赖真机 configuration 回调时序，JVM 不可证。</li>
 *   <li><b>全屏横屏不误转</b>：全屏路径是否仍走 alignToWindow、不被 90 误转
 *       （{@code MODE_SMALLWINDOW} 守卫生效）——依赖真机全屏窗口，JVM 不可证。</li>
 *   <li><b>取景落点</b>：55% 取景下「脚下路面段」落点（{@code pivotX} 靠右分支，deg=90）
 *       是否正确——依赖真机画面，JVM 不可证。</li>
 *   <li><b>重锁稳定</b>：横屏回小窗重开相机/真翻转重锁后 deg=90 是否稳定保持（锁定重放不丢
 *       90）——依赖真机回调链，JVM 不可证。</li>
 *   <li><b>旋转锁 + pose=270 理论 180 边界</b>（调查报告 Q1 路径 2）：旋转锁打开 + 顶部朝右
 *       横持 + 系统未回调横屏时，横条窗展示角如何——本轮未实证，修它需改
 *       {@code referenceDegrees}/{@code isLandscapeWindowNow} 硬边界，超出授权，需真机确认。</li>
 * </ol>
 *
 * <p>本文件只把 58 号过期断言合法同步到 59 号新语义，不改任何实现代码、配置或其他既有测试，
 * 零外部依赖（JUnit4 为项目既有依赖），不编译、不跑测试（交 Vic 统一编译运行）。写法与
 * {@code ToneTuneContractTest} / {@code TorchWhiteoutContractTest} 一致。</p>
 */
public class LandscapeNoRotateContractTest {

    private static final String OVERLAY_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String POLICY_REL =
            "src/main/java/io/github/wumisc/safewalk/PreviewOrientationPolicy.kt";
    private static final String GRADLE_REL = "build.gradle";

    private static final String APPLY_FRAMING_SIG =
            "private fun applyFraming(windowW: Int = 0, windowH: Int = 0)";

    private static String overlaySrc;       // RearCameraOverlay.kt 原文（含注释，全文口径）
    private static String codeOnly;         // RearCameraOverlay.kt 去注释全文
    private static String applyFramingSrc;  // applyFraming() 方法体（含注释）
    private static String applyFramingCode; // applyFraming() 方法体（去注释）
    private static String policyCode;       // PreviewOrientationPolicy.kt 去注释全文
    private static String gradleSrc;        // build.gradle 原文

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locate(OVERLAY_REL)), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
        applyFramingSrc = extractMethod(overlaySrc, APPLY_FRAMING_SIG);
        applyFramingCode = stripComments(applyFramingSrc);
        policyCode = stripComments(new String(
                Files.readAllBytes(locate(POLICY_REL)), StandardCharsets.UTF_8));
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

    // ==================== B 组：独立参考实现（复刻调查 Q1 链路，非调用私有方法） ====================

    /** 复刻 referenceDegrees()：横屏回调钉 90；竖屏回调取归一 pose；未回调取当前 pose。 */
    private static int refRefDeg(Boolean screenLandscape, int pose) {
        if (screenLandscape != null && screenLandscape) return 90;
        if (screenLandscape != null) return normalize(pose);
        return pose;
    }

    /** 复刻 rotationDegrees()：后摄相减、前摄相加，四舍五入到最近的 90°（整数除法同 Kotlin）。 */
    private static int refRotation(int sensorOrientation, int displayRotationDeg, boolean frontFacing) {
        int s = normalize(sensorOrientation);
        int d = normalize(displayRotationDeg);
        int raw = frontFacing ? (s + d) % 360 : (s - d + 360) % 360;
        return ((raw + 45) / 90 % 4) * 90;
    }

    /** 复刻 alignToWindow()：哨兵原样返回；横条窗口 needSwap=false，输出域恒 ⊆{0,180}。 */
    private static int refAlign(int deg, boolean landscapeWindow) {
        if (deg == -1) return -1;
        int normalized = normalize(deg);
        boolean swapped = normalized == 90 || normalized == 270;
        boolean needSwap = !landscapeWindow;
        return swapped == needSwap ? normalized : (normalized + 90) % 360;
    }

    private static int normalize(int deg) {
        return ((deg % 360) + 360) % 360;
    }

    /** 复刻 5.4.14 的 applyFraming deg when（展示角最终出口，RearCameraOverlay.kt:1222-1229）：
     *  首帧→0；竖条→0；小窗横条→90（新分支，鱼头朝右）；全屏横条→refAlign 兜底 ∈{0,180}。
     *  与 refAlign 只复刻 Policy（未改）不同，本函数复刻的是含新分支的最终展示角。 */
    private static int refFinalDeg(boolean isSmallWindow, boolean landscapeWindow,
                                   int appliedRotDeg, boolean firstFrame) {
        if (firstFrame || appliedRotDeg < 0) return 0;
        if (!landscapeWindow) return 0;
        if (isSmallWindow) return 90;
        return refAlign(appliedRotDeg, true);
    }

    // ==================== A 组 · 源码文本契约 ====================

    /** A1 意图：版本号已升 5.6.2(89)，旧号 5.5.0/85 彻底清除。 */
    @Test
    public void a1_versionBumpedTo5414Build79() {
        assertTrue("A1 build.gradle 须含 versionCode 91", gradleSrc.contains("versionCode 91"));
        assertTrue("A1 build.gradle 须含 versionName \"5.6.4\"",
                gradleSrc.contains("versionName \"5.6.4\""));
        assertFalse("A1 须已无 versionCode 85", gradleSrc.contains("versionCode 85"));
        assertFalse("A1 须已无 5.5.0", gradleSrc.contains("5.5.0"));
    }

    /** A2 意图（本任务核心固化）：5.4.14 设计意图注释逐字保留在 applyFraming 内，且确为注释不是代码。 */
    @Test
    public void a2_designIntentCommentsFrozenInApplyFraming() {
        String[] intent = {
                "【5.4.14】用户以有头尾的鱼给出精确判据：横条小窗画面顺时针转 90°，鱼头朝右。",
                "5.4.8 的问题不是\"转90°\"本身错，而是 alignToWindow 把 90 折成 180 导致倒立。",
                "本轮小窗横条绕过该规整直接给 deg=90；竖条仍 0、全屏横条不受本规则控制。",
                "alignToWindow(x, true) 输出域恒为 {0,180}，此处仅作全屏横条兜底。",
                "【5.4.14】小窗横条：画面顺时针转 90°（鱼头朝右），绕过 alignToWindow 的{0,180}输出域。",
                "全屏横条仍走原 alignToWindow，保证\"最大化/分屏不受本规则控制\"。"
        };
        for (int i = 0; i < intent.length; i++) {
            assertTrue("A2 设计意图注释第 " + (i + 1) + " 行须逐字保留在 applyFraming 内：" + intent[i],
                    applyFramingSrc.contains(intent[i]));
            assertFalse("A2 第 " + (i + 1) + " 行须是注释而非可执行代码（stripComments 后不应出现）",
                    applyFramingCode.contains(intent[i]));
        }
    }

    /** A3 意图：deg when 判据（首帧0/竖条0/小窗横条90/全屏兜底 alignToWindow）与策略算式均未改。
     *  注意 alignToWindow(appliedRotDeg, true) 字面在 else 分支保留，全文计数仍恰 1（勿改反）。 */
    @Test
    public void a3_landscapeFishHeadRightDecisionStillInPlace() {
        assertTrue("A3 applyFraming 须保留 val deg = when 判据",
                applyFramingCode.contains("val deg = when"));
        assertTrue("A3 首帧保护分支须在（appliedRotDeg < 0 -> 0）",
                applyFramingCode.contains("appliedRotDeg < 0 -> 0"));
        assertTrue("A3 竖条一律不转分支须在（!landscapeWindow -> 0）",
                applyFramingCode.contains("!landscapeWindow -> 0"));
        assertTrue("A3 核心新分支：小窗横条直给 90（style.mode == MODE_SMALLWINDOW -> 90）",
                applyFramingCode.contains("style.mode == MODE_SMALLWINDOW -> LANDSCAPE_SMALLWINDOW_DEG"));
        assertTrue("A3 全屏横条兜底仍走 PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)",
                applyFramingCode.contains(
                        "else -> PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)"));
        assertEquals("A3 alignToWindow(appliedRotDeg, true) 调用须恰好 1 处（else 分支保留，计数未变）",
                1, countOccurrences(codeOnly, "alignToWindow(appliedRotDeg, true)"));
        // B 组参考实现的策略侧源码兜底：算式一字未改，参考实现映射才成立。
        assertTrue("A3 哨兵 -1 须原样返回", policyCode.contains("if (deg == -1) return deg"));
        assertTrue("A3 swapped 判据须保留",
                policyCode.contains("val swapped = normalized == 90 || normalized == 270"));
        assertTrue("A3 needSwap = !landscapeWindow 须保留（横条不交换宽高）",
                policyCode.contains("val needSwap = !landscapeWindow"));
    }

    /** A4 意图：硬边界 token 计数与基线一致（59 号只改 deg when 分支持平 + 版本号 + 注释）；双口径见类 javadoc。 */
    @Test
    public void a4_hardBoundaryTokenCountsUnchanged() {
        assertEquals("A4 invalidateOutline 须恰好 3 次",
                3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A4 setDefaultBufferSize( 须恰好 1 次",
                1, countOccurrences(codeOnly, "setDefaultBufferSize("));
        assertEquals("A4 hudColumn?.rotation = -deg 须恰好 1 次",
                1, countOccurrences(codeOnly, "hudColumn?.rotation = -deg"));
        assertEquals("A4 addView(hudView 须恰好 1 次",
                1, countOccurrences(codeOnly, "addView(hudView"));
        assertEquals("A4 全文 FLASH_MODE_TORCH 须恰好 8 次（基线，未新增 token）",
                8, countOccurrences(overlaySrc, "FLASH_MODE_TORCH"));
        assertEquals("A4 去注释 FLASH_MODE_TORCH 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "FLASH_MODE_TORCH"));
        assertEquals("A4 全文 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 2 次（基线）",
                2, countOccurrences(overlaySrc, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        assertEquals("A4 去注释 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        int isLandTotal = countOccurrences(codeOnly, "isLandscapeWindowNow()");
        int isLandDef = countOccurrences(codeOnly, "fun isLandscapeWindowNow()");
        assertEquals("A4 去注释 isLandscapeWindowNow() 含定义共 4 处（1 定义 + 3 调用）",
                4, isLandTotal);
        assertEquals("A4 其中函数定义 1 处",
                1, isLandDef);
        assertEquals("A4 真实调用点须恰好 3 处（定义行不算，与基线一致）",
                3, isLandTotal - isLandDef);
        assertEquals("A4 LANDSCAPE_UPRIGHT_ROT_DEG 须恰好 4 次（保持 0、不移除）",
                4, countOccurrences(codeOnly, "LANDSCAPE_UPRIGHT_ROT_DEG"));
    }

    /** A5 意图：referenceDegrees / D1-D3 诊断 / 54 号曝光守卫 / 5.4.10 带参 applyFraming /
     *  57 号调色 pow 提亮式 全部在位（本轮未碰这些既有逻辑）。 */
    @Test
    public void a5_preservedItemsStillInPlace() {
        assertTrue("A5 referenceDegrees() 须保留",
                codeOnly.contains("private fun referenceDegrees()"));
        assertTrue("A5 54 号曝光互斥守卫须逐字保留",
                codeOnly.contains("!(style.torchWithPreview && flashAvailable)"));
        assertTrue("A5 5.4.10 带参 applyFraming(lp.width, lp.height) 须保留",
                codeOnly.contains("applyFraming(lp.width, lp.height)"));
        assertTrue("A5 57 号调色 gamma 提亮式 pow(c, half3(half(1.0) / g)) 须保留",
                codeOnly.contains("pow(c, half3(half(1.0) / g))"));
        assertTrue("A5 57 号调色越黑越亮式 pow(half3(1.0) - s, half3(2.0)) 须保留",
                codeOnly.contains("pow(half3(1.0) - s, half3(2.0))"));
        // D1-D3 标记活在注释里；另断言诊断日志代码（去注释后）仍在，避免只有注释没有日志。
        assertTrue("A5 D1 诊断标记须保留（全文口径）",
                overlaySrc.contains("【5.4.10 诊断 D1】"));
        assertTrue("A5 D2 诊断标记须保留（全文口径）",
                overlaySrc.contains("【5.4.10 诊断 D2】"));
        assertTrue("A5 D3 诊断标记须保留（全文口径）",
                overlaySrc.contains("【5.4.10 诊断 D3】"));
        assertTrue("A5 方向诊断 stage 日志代码须仍在（去注释口径）",
                codeOnly.contains("stage(\"方向诊断"));
    }

    // ==================== B 组 · 端到端真值表（独立参考实现复算） ====================

    /** B1 意图（用户诉求终点）：正常横屏 sensor=90 + 回调横屏 → 参考角 90 → 原始旋转 0 →
     *  小窗横条最终展示角 90（5.4.14 deg when 覆盖，鱼头朝右）。 */
    @Test
    public void b1_normalLandscape_smallWindowChainEndsAtNinety() {
        int ref = refRefDeg(Boolean.TRUE, 90);
        assertEquals("B1 系统回调横屏时参考朝向须钉 90（normalizeMode=auto）", 90, ref);
        int rotation = refRotation(90, ref, false);
        assertEquals("B1 后摄 sensor=90 + 参考=90 → 原始旋转角 0", 0, rotation);
        // 5.4.14：小窗横条展示角由 deg when 的 MODE_SMALLWINDOW 分支覆盖为 90（鱼头朝右），
        // 不再由 refAlign（Policy 全屏兜底）给出 0。
        assertEquals("B1 小窗横条最终展示角 90：顺时针转 90°、鱼头朝右",
                90, refFinalDeg(true, true, rotation, false));
    }

    /** B2 意图（全屏横条兜底路径的数学保证）：refAlign 复刻的是 Policy.alignToWindow（Policy 本轮未改），
     *  其输出域恒 ⊆{0,180}、绝不出现 90/270——这条数学断言仍成立，锁的是<b>全屏横条兜底</b>路径。
     *  注意：这<b>不代表小窗横条展示角</b>；小窗横条展示角由 applyFraming 的 deg when 新分支直接覆盖为
     *  90（见 A3/B1），绕过了这条规整。二者分属不同路径，不矛盾。不逐值重复既有 26 用例。 */
    @Test
    public void b2_policyAlign_outputDomainNeverQuarterTurn() {
        int[] degs = { 0, 90, 180, 270 };
        for (int deg : degs) {
            int out = refAlign(deg, true);
            assertTrue("B2 Policy.alignToWindow 横条兜底 deg=" + deg + " 输出须 ∈{0,180}，实际=" + out,
                    out == 0 || out == 180);
            assertFalse("B2 Policy 横条兜底 deg=" + deg + " 输出须恒 ≠90（小窗 90 由 deg when 覆盖，非此路径）",
                    out == 90);
            assertFalse("B2 Policy 横条兜底 deg=" + deg + " 输出须恒 ≠270", out == 270);
        }
    }

    /** B3 意图（形状决定论 + 竖条仍 0）：Policy.alignToWindow 竖条对照 align(0,false)=90，与横条
     *  兜底 align(0,true)=0 形成对比，证明「形状决定输出域」这条 Policy 数学仍成立（Policy 未改）。
     *  同时锁定：竖条窗最终展示角仍为 0——5.4.14 只改小窗横条分支，竖条分支 !landscapeWindow->0 一字未动。 */
    @Test
    public void b3_portraitWindow_contrastProvesShapeDecides() {
        assertEquals("B3 Policy 竖条对照：align(0,false)=90（Policy 层竖条才交换宽高）", 90, refAlign(0, false));
        // 竖条窗最终展示角仍 0（4.8.0 行为不变）：deg when 的 !landscapeWindow->0 抢在 90 分支之前。
        assertEquals("B3 竖条窗最终展示角仍 0（5.4.14 未动竖条分支）",
                0, refFinalDeg(true, false, 90, false));
        int[] degs = { 0, 90, 180, 270 };
        for (int deg : degs) {
            int land = refAlign(deg, true);
            int port = refAlign(deg, false);
            assertTrue("B3 同输入 Policy 横条兜底输出须 ⊆{0,180}", land == 0 || land == 180);
            assertTrue("B3 同输入 Policy 竖条输出须 ⊆{90,270}", port == 90 || port == 270);
            assertFalse("B3 同一 deg Policy 横条与竖条结果必不同：窗口形状决定输出域", land == port);
        }
    }

    /** B4 意图（buffer 口径自洽）：横条 845×488 窗口的预览缓冲取短边×长边=488×845（竖条框）。
     *  5.4.14 下 deg=90 → swap=true → 旋转层也用竖条框 488×845，buffer 与 layer 框 1:1 等比铺满，
     *  旋转后投影恰为 845×488 横条窗——比 deg=0 硬拉伸更自洽。数值断言与 58 号基线一致，仍成立。 */
    @Test
    public void b4_bufferCaliber_shortSideBufferStretchedIntoLandscapeFrame() {
        int frameW = 845;
        int frameH = 488;
        int bufferW = Math.min(frameW, frameH);
        int bufferH = Math.max(frameW, frameH);
        assertEquals("B4 缓冲宽须取窗口短边 488（与日志 缓冲=488x845 一致）", 488, bufferW);
        assertEquals("B4 缓冲高须取窗口长边 845", 845, bufferH);
        assertTrue("B4 buffer 488x845 为竖条框、窗口 845x488 为横条：deg=90 时旋转层同样用 488x845 竖条框，1:1 等比",
                bufferW < bufferH && frameW > frameH);
    }
}
