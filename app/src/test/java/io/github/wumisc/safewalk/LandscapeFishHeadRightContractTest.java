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
 * 59 号任务（5.4.14(79)）「横条小窗画面顺时针转 90°（鱼头朝右）」的纯 JVM 专项契约用例。
 *
 * <p><b>与 58 号的关系（务必先读）：</b>58 号 {@code LandscapeNoRotateContractTest} 固化的是「横条
 * 恒不转（deg=0）」；59 号任务由用户明确授权推翻这一方向契约：跟随系统横屏的<b>小窗</b>横条展示角
 * 改为 90（顺时针，鱼头朝右）。Max 在 {@code applyFraming()} 的 deg when 新增
 * {@code style.mode == MODE_SMALLWINDOW -> 90}，竖条仍 0、全屏横条仍走原
 * {@code alignToWindow(appliedRotDeg, true)}。本文件是本轮<b>新增专项</b>，与合法同步后的 58 号文件
 * 并存：本文件锁「新语义确实落地」，58 号锁「其余契约/token 未被误伤」。</p>
 *
 * <p><b>测试性质声明：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约（stripComments 口径为主）</b>：读 {@code RearCameraOverlay.kt} /
 *       {@code build.gradle} 原文，锁住「deg when 四分支齐全（首帧0 / 竖条0 / 小窗横条90 /
 *       全屏兜底 alignToWindow）」「5.4.14 注释逐字固化且为注释非代码」
 *       「swap / boxW / boxH / layer.rotation=deg / hudView.rotation=-deg 的字面存在（角度靠 deg 随动，
 *       不写死 90）」「版本号 79/5.4.14 且无旧号」「硬边界 token 计数不破」
 *       「54 号曝光守卫 / 57 号调色 pow 式 / D1-D3 诊断 / 5.4.10 带参 applyFraming / referenceDegrees
 *       仍在」。</li>
 *   <li><b>B 组 · 纯函数真值表（独立参考实现，绝不假装调用私有方法）</b>：生产链路
 *       （{@code deg when}、{@code swap/boxW/boxH}、锁定重放、取景缩放）依赖 Android View/Camera 上下文，
 *       <b>JVM 无法直接调用</b>。本组用类内独立纯函数 {@code refFinalDeg / refSwapBox / refReplay / refZoom}
 *       复刻调查报告 Q1/Q5/Q6 与实现报告第四节的算式与落点，逐分支锁住新语义真值，
 *       <b>绝不假装直接调用了私有方法</b>。参考实现是否与源码一致，由 A 组文本断言兜底。</li>
 *   <li><b>C 组 · 可证性声明</b>：见本 javadoc 末段「只能真机验证、本文件测不到的部分」——
 *       鱼头朝右等真机主观项 JVM 一律测不到，只锁可判定的源码结构与纯函数真值。</li>
 * </ul>
 *
 * <p><b>B 组参考实现 ↔ 生产源码映射（避免误读为直接调用）：</b>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应</th></tr>
 *   <tr><td>{@code refFinalDeg(small, landscape, appliedRotDeg, firstFrame)}</td>
 *       <td>RearCameraOverlay.kt:1222-1229 deg when：首帧/竖条→0；小窗横条→90；全屏横条→
 *           {@code alignToWindow(appliedRotDeg, true)}。</td></tr>
 *   <tr><td>{@code refSwapBox(deg, w, h)}</td>
 *       <td>RearCameraOverlay.kt:1233-1235：{@code swap = deg==90||deg==270}；
 *           {@code boxW = if(swap) h else w}；{@code boxH = if(swap) w else h}。</td></tr>
 *   <tr><td>{@code refReplay(layerRot, hudRot)}</td>
 *       <td>RearCameraOverlay.kt:1272 {@code replayLockedComposition()}：只赋值恢复首帧快照，
 *           不重新推导角度 → deg=90 的锁重放后 layer=90 / hud=-90。</td></tr>
 *   <tr><td>{@code refZoom(landscape, boxW, boxH, f)}</td>
 *       <td>RearCameraOverlay.kt:1369-1389 {@code applyFramingZoom()}：默认取景（f 非正）时只用
 *           {@code !landscapeWindow && boxH > boxW} 判真竖条窗才 2.0 折中填充；deg=90 翻框的横条窗
 *           （landscape=true）仍 1:1，不被误放大；主动取景走 {@code 1/(1-f/200)}。</td></tr>
 *   <tr><td>{@code refAlign(deg, landscape)}</td>
 *       <td>PreviewOrientationPolicy.kt:137-146 {@code alignToWindow()}（本轮未改）：全屏横条兜底
 *           输出域 ⊆{0,180}。</td></tr>
 * </table>
 * 参考函数是否正确接上源码，由 A 组文本断言兜底；两组合起来才等价于「判据正确且真的接上了」。</p>
 *
 * <p><b>token 计数口径说明（防误报，以源码实测双口径断言）：</b>{@code FLASH_MODE_TORCH} 全文含注释
 * 为 <b>8</b>、去注释为 <b>1</b>；{@code CONTROL_AE_EXPOSURE_COMPENSATION} 全文为 <b>2</b>、
 * 去注释为 <b>1</b>；故两个口径都断言。{@code isLandscapeWindowNow()} 去注释共 4 处（1 定义 +
 * 3 调用），断言「总数 4 − 定义 1 = 调用 3」；59 号守卫复用已有 {@code style.mode}，
 * <b>未新增 isLandscapeWindowNow() 调用</b>。D1-D3 标记只在注释中，用全文口径，另断言去注释后
 * {@code stage("方向诊断"} 日志代码仍在。</p>
 *
 * <p><b>只能真机验证、本文件测不到的部分（C 组可证性声明）：</b>
 * <ol>
 *   <li><b>鱼头朝右肉眼确认</b>：装 5.4.14 后横条小窗内鱼头是否肉眼朝右（对照四向拼图 R270 逆格 =
 *       Android 顺时针 90°）——渲染主观效果，沙箱无真机画面，JVM 不可证。</li>
 *   <li><b>HUD 文字正立</b>：deg=90 下 {@code hudView.rotation=-90} 净角是否为 0、「路况预览」文字
 *       是否仍正立可读——依赖真机绘制，JVM 不可证。</li>
 *   <li><b>竖→横→竖切换无瞬态</b>：反复切换后回竖屏是否恢复 deg=0、无「转一下再回正」瞬态——
 *       依赖真机 configuration 回调时序，JVM 不可证。</li>
 *   <li><b>全屏不误转</b>：全屏横屏是否仍走 alignToWindow、不被 90 误转（{@code MODE_SMALLWINDOW}
 *       守卫生效）——依赖真机全屏窗口，JVM 不可证。</li>
 *   <li><b>取景落点</b>：55% 取景下「脚下路面段」落点（{@code pivotX} 靠右分支，deg=90）是否正确——
 *       依赖真机画面，JVM 不可证。</li>
 *   <li><b>重锁稳定</b>：横屏回小窗重开相机 / 系统真翻转重锁后 deg=90 是否稳定保持（锁定重放不丢
 *       90）——依赖真机回调链，JVM 不可证。</li>
 *   <li><b>旋转锁 + pose=270 边界</b>：旋转锁打开 + 顶部朝右横持（poseDeg=270，58 号遗留理论 180
 *       路径）下的新行为——本轮未实证，需真机确认。</li>
 *   <li><b>默认取景下视野不砍半（连锁副作用修复，真机可观察项）</b>：deg=90 会把横条窗 box 翻成
 *       竖条 488×845，修复后用窗口真实朝向 {@code landscapeWindow} 判定，横条窗不再被误放大 2 倍、
 *       视野不砍半——装真机肉眼可观察；JVM 侧由 A 组文本断言（签名/判据/调用点/负向）+ B 组
 *       {@code refZoom} 四场景真值表锁定判据，真机观察项本身 JVM 不可证。</li>
 * </ol>
 *
 * <p>本文件只新增测试，不改任何实现代码、配置或既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不编译、不跑测试（交 Vic 统一编译运行）。写法与 {@code ToneTuneContractTest} /
 * {@code TorchWhiteoutContractTest} / {@code LandscapeNoRotateContractTest} 一致。</p>
 */
public class LandscapeFishHeadRightContractTest {

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

    // ==================== B 组：独立参考实现（复刻 deg when / swap / 重放，非调用私有方法） ====================

    /** 复刻 alignToWindow()（Policy 未改）：哨兵原样返回；横条窗口 needSwap=false，输出域 ⊆{0,180}。 */
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

    /** 复刻 5.4.14 的 applyFraming deg when（RearCameraOverlay.kt:1222-1229）：
     *  firstFrame / appliedRotDeg<0 → 0；竖条 → 0；小窗横条 → 90；全屏横条 → refAlign 兜底。 */
    private static int refFinalDeg(boolean isSmallWindow, boolean landscapeWindow,
                                   int appliedRotDeg, boolean firstFrame) {
        if (firstFrame || appliedRotDeg < 0) return 0;
        if (!landscapeWindow) return 0;
        if (isSmallWindow) return 90;
        return refAlign(appliedRotDeg, true);
    }

    /** 复刻 swap/boxW/boxH（:1233-1235）：deg=90 → swap=true → boxW=h / boxH=w。
     *  返回 {boxW, boxH, swap?1:0}。 */
    private static int[] refSwapBox(int deg, int w, int h) {
        boolean swap = deg == 90 || deg == 270;
        return new int[] { swap ? h : w, swap ? w : h, swap ? 1 : 0 };
    }

    /** 复刻 replayLockedComposition()（:1272）：只恢复首帧快照的 layerRot/hudRot，不重新推导角度。 */
    private static int[] refReplay(int layerRot, int hudRot) {
        return new int[] { layerRot, hudRot };
    }

    /** 复刻修复后 applyFramingZoom()（:1369-1389）的缩放判据：默认取景（f 非正）只用窗口真实朝向
     *  landscape 判定，真竖条窗才 2.0 折中填充；横条窗（含 deg=90 翻框成 boxH>boxW）一律 1:1。
     *  主动取景 f>0 走 1/(1-f/200)。与 A7 文本断言共同锁住「deg=90 不误放大、视野不砍半」。 */
    private static float refZoom(boolean landscape, int boxW, int boxH, int f) {
        if (f <= 0) return (!landscape && boxH > boxW) ? 2.0f : 1.0f;
        return 1f / (1f - f / 200f);
    }

    // ==================== A 组 · 源码文本契约 ====================

    /** A1 意图（核心防回归）：deg when 四分支齐全，小窗横条直给 90，else 的 alignToWindow 仍在（计数1）。 */
    @Test
    public void a1_degWhen_hasSmallWindowNinetyBranchWithFallbackIntact() {
        assertTrue("A1 须有 val deg = when 判据",
                applyFramingCode.contains("val deg = when"));
        assertTrue("A1 首帧保护分支须在（appliedRotDeg < 0 -> 0）",
                applyFramingCode.contains("appliedRotDeg < 0 -> 0"));
        assertTrue("A1 竖条不转分支须在（!landscapeWindow -> 0）",
                applyFramingCode.contains("!landscapeWindow -> 0"));
        assertTrue("A1 核心：小窗横条直给 90（style.mode == MODE_SMALLWINDOW -> 90）",
                applyFramingCode.contains("style.mode == MODE_SMALLWINDOW -> LANDSCAPE_SMALLWINDOW_DEG"));
        assertTrue("A1 全屏横条兜底须在（else -> PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)）",
                applyFramingCode.contains(
                        "else -> PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)"));
        assertEquals("A1 alignToWindow(appliedRotDeg, true) 全文须恰好 1 处（else 保留，未新增）",
                1, countOccurrences(codeOnly, "alignToWindow(appliedRotDeg, true)"));
    }

    /** A2 意图：5.4.14 设计意图注释逐字固化在 applyFraming 内，且确为注释不是代码。 */
    @Test
    public void a2_fishHeadRightCommentsFrozenAsComments() {
        String[] intent = {
                "【5.4.14】用户以有头尾的鱼给出精确判据：横条小窗画面顺时针转 90°，鱼头朝右。",
                "5.4.8 的问题不是\"转90°\"本身错，而是 alignToWindow 把 90 折成 180 导致倒立。",
                "本轮小窗横条绕过该规整直接给 deg=90；竖条仍 0、全屏横条不受本规则控制。",
                "alignToWindow(x, true) 输出域恒为 {0,180}，此处仅作全屏横条兜底。",
                "【5.4.14】小窗横条：画面顺时针转 90°（鱼头朝右），绕过 alignToWindow 的{0,180}输出域。",
                "全屏横条仍走原 alignToWindow，保证\"最大化/分屏不受本规则控制\"。"
        };
        for (int i = 0; i < intent.length; i++) {
            assertTrue("A2 第 " + (i + 1) + " 条 5.4.14 注释须逐字在 applyFraming 内：" + intent[i],
                    applyFramingSrc.contains(intent[i]));
            assertFalse("A2 第 " + (i + 1) + " 条须是注释非代码（stripComments 后不应出现）",
                    applyFramingCode.contains(intent[i]));
        }
    }

    /** A3 意图（连锁随动但不写死角度）：swap/boxW/boxH/layer.rotation/hudView.rotation 字面在，
     *  deg=90 靠变量随动而非硬编码，避免未来有人绕过 deg when 直接写死 90/-90。 */
    @Test
    public void a3_swapBoxAndRotationFollowDegNotHardcoded() {
        assertTrue("A3 landscapeWindow 判据须在（val landscapeWindow = w > h）",
                applyFramingCode.contains("val landscapeWindow = w > h"));
        assertTrue("A3 swap 判据须在（val swap = deg == 90 || deg == 270）",
                applyFramingCode.contains("val swap = deg == 90 || deg == 270"));
        assertTrue("A3 boxW 须按 swap 取 h（val boxW = if (swap) h else w）",
                applyFramingCode.contains("val boxW = if (swap) h else w"));
        assertTrue("A3 boxH 须按 swap 取 w（val boxH = if (swap) w else h）",
                applyFramingCode.contains("val boxH = if (swap) w else h"));
        assertTrue("A3 layer 旋转须取 deg（layer.rotation = deg.toFloat()）",
                applyFramingCode.contains("layer.rotation = deg.toFloat()"));
        assertTrue("A3 HUD 反向角须取 -deg（hudColumn?.rotation = -deg.toFloat()）",
                applyFramingCode.contains("hudColumn?.rotation = -deg.toFloat()"));
        assertFalse("A3 负向：不得写死 layer 旋转 90（角度必须随 deg 变量）",
                applyFramingCode.contains("layer.rotation = 90"));
        assertFalse("A3 负向：不得写死 HUD 反向角 -90（必须随 -deg）",
                applyFramingCode.contains("hudColumn?.rotation = -90"));
    }

    /** A4 意图：版本号升到 86/5.5.1，旧号 85/5.5.0 清干净。 */
    @Test
    public void a4_versionIs5414Build79NoOldNumbers() {
        assertTrue("A4 build.gradle 须含 versionCode 139", gradleSrc.contains("versionCode 139"));
        assertTrue("A4 build.gradle 须含 versionName \"5.7.17-lx102\"",
                gradleSrc.contains("versionName \"5.7.17-lx102\""));
        assertFalse("A4 须已无 versionCode 85", gradleSrc.contains("versionCode 85"));
        assertFalse("A4 须已无 5.5.0", gradleSrc.contains("5.5.0"));
    }

    /** A5 意图：硬边界 token 计数不破（59 号只改 deg when 一分支 + 版本 + 注释）。
     *  双口径见类 javadoc；isLandscapeWindowNow() 调用恰 3（未为守卫新增调用）。 */
    @Test
    public void a5_hardBoundaryTokenCountsUnchanged() {
        assertEquals("A5 invalidateOutline 须恰好 3 次", 3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A5 setDefaultBufferSize( 须恰好 1 次", 1, countOccurrences(codeOnly, "setDefaultBufferSize("));
        assertEquals("A5 hudColumn?.rotation = -deg 须恰好 1 次",
                1, countOccurrences(codeOnly, "hudColumn?.rotation = -deg"));
        assertEquals("A5 addView(hudView 须恰好 1 次", 1, countOccurrences(codeOnly, "addView(hudView"));
        assertEquals("A5 全文 FLASH_MODE_TORCH 须恰好 8 次（基线）",
                8, countOccurrences(overlaySrc, "FLASH_MODE_TORCH"));
        assertEquals("A5 去注释 FLASH_MODE_TORCH 须恰好 1 次",
                1, countOccurrences(codeOnly, "FLASH_MODE_TORCH"));
        assertEquals("A5 全文 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 2 次（基线）",
                2, countOccurrences(overlaySrc, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        assertEquals("A5 去注释 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 1 次",
                1, countOccurrences(codeOnly, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        int isLandTotal = countOccurrences(codeOnly, "isLandscapeWindowNow()");
        int isLandDef = countOccurrences(codeOnly, "fun isLandscapeWindowNow()");
        assertEquals("A5 去注释 isLandscapeWindowNow() 含定义共 4 处（1 定义 + 3 调用）", 4, isLandTotal);
        assertEquals("A5 其中函数定义 1 处", 1, isLandDef);
        assertEquals("A5 真实调用点须恰好 3 处（59 号未新增守卫调用）", 3, isLandTotal - isLandDef);
        assertEquals("A5 LANDSCAPE_UPRIGHT_ROT_DEG 须恰好 4 次（叠加块未动）",
                4, countOccurrences(codeOnly, "LANDSCAPE_UPRIGHT_ROT_DEG"));
    }

    /** A6 意图：54 号曝光守卫 / 57 号调色 pow 式 / D1-D3 诊断 / 5.4.10 带参 applyFraming /
     *  referenceDegrees 全部在位（本轮未碰这些既有逻辑）。 */
    @Test
    public void a6_preservedItemsStillInPlace() {
        assertTrue("A6 54 号曝光互斥守卫须逐字保留",
                codeOnly.contains("!(style.torchWithPreview && flashAvailable)"));
        assertTrue("A6 57 号 gamma 提亮 pow 式须保留",
                codeOnly.contains("pow(c, half3(half(1.0) / g))"));
        assertTrue("A6 57 号越黑越亮 pow 式须保留",
                codeOnly.contains("pow(half3(1.0) - s, half3(2.0))"));
        assertTrue("A6 5.4.10 带参 applyFraming(lp.width, lp.height) 须保留",
                codeOnly.contains("applyFraming(lp.width, lp.height)"));
        assertTrue("A6 referenceDegrees() 须保留",
                codeOnly.contains("private fun referenceDegrees()"));
        assertTrue("A6 D1 诊断标记须保留（全文口径）", overlaySrc.contains("【5.4.10 诊断 D1】"));
        assertTrue("A6 D2 诊断标记须保留（全文口径）", overlaySrc.contains("【5.4.10 诊断 D2】"));
        assertTrue("A6 D3 诊断标记须保留（全文口径）", overlaySrc.contains("【5.4.10 诊断 D3】"));
        assertTrue("A6 方向诊断 stage 日志代码须仍在（去注释口径）", codeOnly.contains("stage(\"方向诊断"));
    }

    /** A7 意图（连锁副作用防回归）：Ray 发现 deg=90 经 swap 把横条窗 box 翻成竖条 488×845，
     *  旧判据只看 boxH>boxW 会在默认取景下误放大 2 倍、视野砍半。Max 给 applyFramingZoom 加
     *  landscapeWindow 参数、判据改为 !landscapeWindow && boxH > boxW；本用例锁住签名/判据/调用点
     *  四处字面，并负向排除旧的孤立判据。stripComments 口径。 */
    @Test
    public void a7_framingZoomUsesLandscapeWindowNotBoxShape() {
        assertTrue("A7 签名须含 landscapeWindow: Boolean 参数",
                codeOnly.contains("private fun applyFramingZoom(view: TextureView, boxW: Int, boxH: Int, deg: Int, landscapeWindow: Boolean)"));
        assertTrue("A7 核心防回归：f<=0 判据须为 !landscapeWindow && boxH > boxW",
                codeOnly.contains("if (!landscapeWindow && boxH > boxW)"));
        assertTrue("A7 唯一调用点须把 landscapeWindow 传给 applyFramingZoom",
                codeOnly.contains("applyFramingZoom(view, boxW, boxH, deg, landscapeWindow)"));
        assertEquals("A7 applyFramingZoom(view, boxW, boxH, deg, landscapeWindow) 调用点须恰好 1 处",
                1, countOccurrences(codeOnly, "applyFramingZoom(view, boxW, boxH, deg, landscapeWindow)"));
        assertFalse("A7 负向：不得残留旧的孤立 if (boxH > boxW) 判据（须带 !landscapeWindow）",
                codeOnly.contains("if (boxH > boxW)"));
    }

    // ==================== B 组 · 纯函数真值表（独立参考实现复算） ====================

    /** B1 意图（首帧护栏）：firstFrame/appliedRotDeg<0 一律 deg=0，即使小窗横条也不转（首帧保护不动）。 */
    @Test
    public void b1_firstFrame_alwaysZeroEvenSmallWindowLandscape() {
        assertEquals("B1 firstFrame=true 的小窗横条 deg 须 0（首帧保护优先）",
                0, refFinalDeg(true, true, 90, true));
        assertEquals("B1 appliedRotDeg=-1 首帧哨兵的小窗横条 deg 须 0",
                0, refFinalDeg(true, true, -1, false));
    }

    /** B2 意图（竖条仍 0）：4.8.0 行为保留，竖条窗 deg=0，不论是否小窗/appliedRotDeg 为何。 */
    @Test
    public void b2_portraitWindow_alwaysZero() {
        assertEquals("B2 竖条小窗 deg 须 0", 0, refFinalDeg(true, false, 90, false));
        assertEquals("B2 竖条全屏 deg 须 0", 0, refFinalDeg(false, false, 0, false));
        assertEquals("B2 竖条小窗 appliedRotDeg=270 时 deg 仍须 0",
                0, refFinalDeg(true, false, 270, false));
    }

    /** B3 意图（核心终点）：小窗横条 deg=90（顺时针、鱼头朝右），与 appliedRotDeg 取值无关。 */
    @Test
    public void b3_smallWindowLandscape_alwaysNinety() {
        assertEquals("B3 小窗横条 appliedRotDeg=0 → deg 90",
                90, refFinalDeg(true, true, 0, false));
        assertEquals("B3 小窗横条 appliedRotDeg=90 → deg 90（不再被 alignToWindow 折成 180）",
                90, refFinalDeg(true, true, 90, false));
        assertEquals("B3 小窗横条 appliedRotDeg=180 → deg 90",
                90, refFinalDeg(true, true, 180, false));
        assertEquals("B3 小窗横条 appliedRotDeg=270 → deg 90",
                90, refFinalDeg(true, true, 270, false));
    }

    /** B4 意图（守卫有效）：全屏横条走 refAlign 兜底，输出 ⊆{0,180} 且恒 ≠90——MODE_SMALLWINDOW
     *  守卫确保全屏不被误转。 */
    @Test
    public void b4_fullscreenLandscape_neverNinetyGuard() {
        for (int deg = 0; deg < 360; deg += 90) {
            int out = refFinalDeg(false, true, deg, false);
            assertTrue("B4 全屏横条 appliedRotDeg=" + deg + " 输出须 ⊆{0,180}，实际=" + out,
                    out == 0 || out == 180);
            assertFalse("B4 全屏横条 appliedRotDeg=" + deg + " 输出须恒 ≠90（全屏不被误转）",
                    out == 90);
        }
    }

    /** B5 意图（swap/box 几何自洽）：deg=90 → swap=true → boxW=h=488 / boxH=w=845，
     *  旋转层用竖条框 488×845，顺时针 90° 后投影回 845×488 横条窗。 */
    @Test
    public void b5_swapBoxAtNinety_usesPortraitFrame() {
        int frameW = 845;
        int frameH = 488;
        int[] box = refSwapBox(90, frameW, frameH);
        assertEquals("B5 deg=90 须 swap=true", 1, box[2]);
        assertEquals("B5 boxW 须取 h=488", 488, box[0]);
        assertEquals("B5 boxH 须取 w=845", 845, box[1]);
        // 旋转前竖条框 488×845，顺时针转 90° 后投影恰为横条窗 845×488。
        assertEquals("B5 boxW×boxH 转 90° 后宽投影 = 原 boxH = 845（横条窗宽）", 845, box[1]);
        assertEquals("B5 boxW×boxH 转 90° 后高投影 = 原 boxW = 488（横条窗高）", 488, box[0]);
    }

    /** B6 意图（锁定重放保 90/-90）：首帧快照 layerRot=90 / hudRot=-90，replay 后原样恢复，
     *  且 layer+hud 净角恒 0（HUD 文字正立的数学保证）。 */
    @Test
    public void b6_lockReplay_preservesNinetyAndHudMinusNinety() {
        int layerRot = 90;
        int hudRot = -90;
        int[] replayed = refReplay(layerRot, hudRot);
        assertEquals("B6 重放后 layer.rotation 须保持 90（不丢 90）", 90, replayed[0]);
        assertEquals("B6 重放后 hudView.rotation 须保持 -90", -90, replayed[1]);
        assertEquals("B6 layer+hud 净角须恒 0（HUD 文字正立）", 0, replayed[0] + replayed[1]);
    }

    /** B7 意图（连锁副作用四场景真值表）：复刻修复后默认取景判据，锁住 deg=90 翻框的横条窗
     *  （landscape=true, boxW=488, boxH=845）在 f=0 时缩放 1.0 而非误放大 2.0——本 bug 核心；
     *  同时保住 deg=0 横条 1.0、真竖条窗 2.0 折中填充、主动取景 f=55 的 1/(1-f/200) 不受影响。 */
    @Test
    public void b7_framingZoomTruthTable_noFalseZoomOnNinetyLandscape() {
        assertEquals("B7 核心：横条窗 deg=90 f=0（box 翻成 488×845）缩放须 1.0（修复前误为 2.0）",
                1.0f, refZoom(true, 488, 845, 0), 0.0001f);
        assertEquals("B7 横条窗 deg=0 f=0（845×488）缩放须 1.0（不变）",
                1.0f, refZoom(true, 845, 488, 0), 0.0001f);
        assertEquals("B7 竖条窗 f=0（488×845）须保留 2.0 折中填充",
                2.0f, refZoom(false, 488, 845, 0), 0.0001f);
        assertEquals("B7 横条窗 deg=90 f=55（用户真机场景）缩放须≈1.379，不受翻框影响",
                1f / (1f - 55f / 200f), refZoom(true, 488, 845, 55), 0.0001f);
    }
}
