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
 * 5.4.9「夜间闪光灯联动导致路况预览白屏」最小修复的纯 JVM 契约用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约</b>：直接读取 {@code RearCameraOverlay.kt} 文本，按花括号配平截取
 *       {@code buildPreviewRequest / refreshRequestInSession / onConfigured} 方法体，锁住
 *       「首帧 TORCH 链路仍在」「曝光补偿条件含 TORCH 互斥守卫（M1 治本点）」「L1 日志判据与 M1 同源」
 *       「首帧与会话内重下发共用同一 request 构造」「L4/L5 诊断日志文本」「预览路径不得用 setTorchMode」
 *       「硬边界 token 计数不增不减」等结构，防止漏改或误删。</li>
 *   <li><b>B 组 · 真值表（独立参考实现复算）</b>：生产「是否下发」逻辑内联在
 *       {@code buildPreviewRequest()}（私有方法且依赖 Android CameraDevice/CaptureRequest），
 *       <b>JVM 无法直接调用</b>；且本任务授权仅为守卫 + 日志行，未抽 {@code TorchPolicy} 纯函数
 *       （实现报告 §3 决策 4）。故本组用类内独立纯函数参考模型（{@code refTorchWithPreview /
 *       refShouldSetTorch / refShouldSetAeComp / refIsNightTime / refIsNightHour}）复刻调查报告
 *       SC7-B 的六行真值表与夜间判定的跨零点边界，<b>绝不假装直接调用了私有方法</b>。
 *       映射关系见下表。</li>
 *   <li><b>C 组 · 可证性声明</b>：见本 javadoc 末段「只能真机验证、本文件测不到的部分」——
 *       白屏根因的四类运行时现象 JVM 一律测不到，只锁可判定的源码结构与纯函数真值。</li>
 * </ul>
 *
 * <p><b>B 组参考实现 ↔ 生产源码映射（重要，避免误读为直接调用）：</b>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应（WalkPreviewService.java / PresetResolve.kt / RearCameraOverlay.kt）</th></tr>
 *   <tr><td>{@code refTorchWithPreview(link, night)}</td><td>WalkPreviewService.java:822 {@code torchWithPreview = linkEnabled && night}</td></tr>
 *   <tr><td>{@code refShouldSetTorch(twp, flash)}</td><td>RearCameraOverlay.kt:2000 守卫 {@code style.torchWithPreview && flashAvailable}</td></tr>
 *   <tr><td>{@code refShouldSetAeComp(low, target, torchOn)}</td><td>RearCameraOverlay.kt:2007 M1 条件（{@code !torchOn} 等价于互斥项）</td></tr>
 *   <tr><td>{@code refIsNightTime(h, s, e)}</td><td>WalkPreviewService.java:972 {@code hour >= start || hour < end}</td></tr>
 *   <tr><td>{@code refIsNightHour(h, s, e)}</td><td>PresetResolve.kt:106-112（归一化 + 同值禁用 + 跨零点）</td></tr>
 * </table>
 * 参考模型只复刻<b>可由 JVM 判定的布尔/时段语义</b>；守卫是否真接上、token 是否真未增，由 A 组文本断言兜底。
 * 两组合起来才等价于「逻辑正确且真的接上了」。
 *
 * <p><b>token 计数口径说明（避免误报，以源码实测为准）：</b>调查报告/实现报告的硬边界基线
 * {@code FLASH_MODE_TORCH=8}、{@code CONTROL_AE_EXPOSURE_COMPENSATION=2} 是<b>全文含注释</b>口径；
 * 本文件按任务书要求用 {@code stripComments()} 去注释后，前者为 1（唯一真实下发点在 buildPreviewRequest），
 * 后者为 1（唯一真实下发点）。故 A8 对这两个 token <b>两种口径都断言</b>：全文口径锁基线不增不减，
 * 去注释口径锁真实下发点唯一。其余四个 token 全文与去注释计数一致。
 *
 * <p><b>只能真机验证、本文件测不到的部分（C 组可证性声明）：</b>
 * <ol>
 *   <li><b>候选 A</b>：HAL 在 {@code setRepeatingRequest} 切 {@code FLASH_MODE} 时是否 reconfig 预览流并产出白/黑帧——HAL 运行时行为，JVM 不可证。</li>
 *   <li><b>候选 B（核心待证）</b>：TORCH + 正向曝光补偿叠加瞬间，AE 是否真的未收敛、帧 Y 是否饱和到 255——需真机读回 {@code CaptureResult}（AE_STATE / 曝光补偿 / 曝光时间）并夜间复现，JVM 不可证。</li>
 *   <li><b>候选 C</b>：AGSL S 曲线在近饱和高亮输入下的实际 {@code clamp(s,0,1)} 输出——依赖 GPU/ROM，JVM 不可证。</li>
 *   <li><b>机制②判别</b>：白屏时 {@code onSurfaceTextureUpdated} 帧时间戳是否停止——需真机帧级日志（L3 未落地），JVM 不可证。</li>
 * </ol>
 * 上述均依赖具体 HAL/GPU/ROM（本例 HyperOS4 / SDK37 / M332BF）；最终验收仍需夜间复现抓 safewalk diag.log。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。写法与 {@code DirectionLockContractTest} /
 * {@code FixedCameraBuffer544Test} / {@code HudRotationContractTest} 一致。
 */
public class TorchWhiteoutContractTest {

    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static final String BUILD_SIG = "private fun buildPreviewRequest(";
    private static final String REFRESH_SIG = "private fun refreshRequestInSession()";
    private static final String ON_CONFIGURED_SIG = "override fun onConfigured(";

    private static String overlaySrc;      // 原始 .kt 源码（保留注释，用于全文 token 基线计数）
    private static String codeOnly;        // 去注释全文（用于真实下发点计数与全局关键串）
    private static String buildBody;       // buildPreviewRequest() 方法体（去注释）
    private static String refreshBody;     // refreshRequestInSession() 方法体（去注释）
    private static String onConfiguredBody;// onConfigured() 方法体（去注释）

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locate(SOURCE_REL)), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
        buildBody = stripComments(extractMethod(overlaySrc, BUILD_SIG));
        refreshBody = stripComments(extractMethod(overlaySrc, REFRESH_SIG));
        onConfiguredBody = stripComments(extractMethod(overlaySrc, ON_CONFIGURED_SIG));
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

    /** 在 text 内截取 startToken → endToken 整段（含 endToken）。 */
    private static String segment(String text, String startToken, String endToken, String tag) {
        int start = text.indexOf(startToken);
        assertTrue(tag + " 找不到起点 " + startToken, start >= 0);
        int end = text.indexOf(endToken, start);
        assertTrue(tag + " 找不到终点 " + endToken, end >= 0);
        return text.substring(start, end + endToken.length());
    }

    /** 正则跨行匹配：源码里 onConfigured 的 setRepeatingRequest 与 buildPreviewRequest 分处两行。 */
    private static boolean matchesMultiLine(String text, String regex) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(text).find();
    }

    // ==================== B 组：独立参考实现（复刻纯布尔/时段语义，非调用私有方法） ====================

    /** 派生最终值：WalkPreviewService.java:822 {@code torchWithPreview = linkEnabled && night}。 */
    private static boolean refTorchWithPreview(boolean linkEnabled, boolean night) {
        return linkEnabled && night;
    }

    /** 是否下发 TORCH：RearCameraOverlay.kt:2000 守卫 {@code style.torchWithPreview && flashAvailable}。 */
    private static boolean refShouldSetTorch(boolean torchWithPreview, boolean flashAvailable) {
        return torchWithPreview && flashAvailable;
    }

    /**
     * 修复后是否下发正曝光补偿：复刻 M1 条件（:2007）与 L1 判据（:2011）。
     * 当 {@code torchOn = torchWithPreview && flashAvailable} 时，{@code !torchOn} 等价于
     * 源码里的互斥项 {@code !(style.torchWithPreview && flashAvailable)}。
     */
    private static boolean refShouldSetAeComp(boolean lowLight, int aeCompTarget, boolean torchOn) {
        return lowLight && aeCompTarget > 0 && !torchOn;
    }

    /** 复刻 WalkPreviewService.java:972 {@code hour >= start || hour < end}（无归一化、无同值禁用）。 */
    private static boolean refIsNightTime(int hour, int start, int end) {
        return hour >= start || hour < end;
    }

    /** 复刻 PresetResolve.kt:106-112：归一化到 0-23；start==end 禁用；跨零点用或，非跨零点用区间。 */
    private static boolean refIsNightHour(int hour, int start, int end) {
        int h = ((hour % 24) + 24) % 24;
        int s = ((start % 24) + 24) % 24;
        int e = ((end % 24) + 24) % 24;
        if (s == e) return false;
        return s < e ? (h >= s && h < e) : (h >= s || h < e);
    }

    // ==================== A 组 · 源码文本契约 ====================

    /** A1 意图：buildPreviewRequest 内首帧 TORCH 链路三要素仍在（AE_MODE_ON + FLASH_MODE_TORCH + 守卫）。 */
    @Test
    public void a1_buildPreviewRequest_torchChainIntact() {
        assertTrue("A1 须保留 CaptureRequest.CONTROL_AE_MODE_ON（配合 TORCH，避免 HAL 拒整条 request）",
                buildBody.contains("CaptureRequest.CONTROL_AE_MODE_ON"));
        assertTrue("A1 须保留 CaptureRequest.FLASH_MODE_TORCH（会话内点灯，非 setTorchMode）",
                buildBody.contains("CaptureRequest.FLASH_MODE_TORCH"));
        assertTrue("A1 须保留 TORCH 守卫 if (style.torchWithPreview && flashAvailable)",
                buildBody.contains("if (style.torchWithPreview && flashAvailable)"));
    }

    /**
     * A2 意图（核心 · 防回归 M1）：曝光补偿那条 if 必须含互斥项
     * {@code !(style.torchWithPreview && flashAvailable)}——有人删掉守卫则本测试失败。
     * 输入=buildPreviewRequest 方法体；预期=含 CONTROL_AE_EXPOSURE_COMPENSATION 的 if 同时含三要素。
     */
    @Test
    public void a2_buildPreviewRequest_aeCompExcludesTorch() {
        String aeIf = segment(buildBody,
                "if (style.lowLight && aeCompTarget > 0",
                "CONTROL_AE_EXPOSURE_COMPENSATION",
                "A2 曝光补偿条件段");
        assertTrue("A2 曝光补偿条件须含 style.lowLight", aeIf.contains("style.lowLight"));
        assertTrue("A2 曝光补偿条件须含 aeCompTarget > 0", aeIf.contains("aeCompTarget > 0"));
        assertTrue("A2 核心：曝光补偿 if 须含 TORCH 互斥项 !(style.torchWithPreview && flashAvailable)",
                aeIf.contains("!(style.torchWithPreview && flashAvailable)"));
        assertTrue("A2 曝光补偿仍写入 CONTROL_AE_EXPOSURE_COMPENSATION 字段",
                aeIf.contains("CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION"));
        assertEquals("A2 去注释后曝光补偿字段须恰好 1 处下发（唯一路径未被复制）",
                1, countOccurrences(buildBody, "CONTROL_AE_EXPOSURE_COMPENSATION"));
    }

    /** A3 意图：L1 诊断日志实际下发四字段，且判据 aeCompApplied 与 M1 同源（含 !torchOn）。 */
    @Test
    public void a3_buildPreviewRequest_l1LogJudgeConsistentWithM1() {
        assertTrue("A3 须含 L1 日志文本 [cam] preview req",
                buildBody.contains("[cam] preview req"));
        assertTrue("A3 L1 判据须逐字为 lowLight && aeCompTarget > 0 && !torchOn（与 M1 同源）",
                buildBody.contains("val aeCompApplied = style.lowLight && aeCompTarget > 0 && !torchOn"));
        assertTrue("A3 L1 须定义 torchOn = torchWithPreview && flashAvailable",
                buildBody.contains("val torchOn = style.torchWithPreview && flashAvailable"));
        assertTrue("A3 L1 日志须用 aeCompApplied 决定 aeComp 实际值",
                buildBody.contains("aeComp=${if (aeCompApplied)"));
        assertEquals("A3 去注释后 !torchOn 判据须恰好 1 处",
                1, countOccurrences(buildBody, "!torchOn"));
    }

    /** A4 意图：会话内重下发仍复用 buildPreviewRequest，且保留「预览请求已重下发」日志。 */
    @Test
    public void a4_refreshRequestInSession_reusesBuildPreviewRequest() {
        assertTrue("A4 refreshRequestInSession 须含 cs.setRepeatingRequest(buildPreviewRequest(d, surface), null, mainHandler)",
                refreshBody.contains("cs.setRepeatingRequest(buildPreviewRequest(d, surface), null, mainHandler)"));
        assertTrue("A4 须保留「预览请求已重下发」日志文本",
                refreshBody.contains("预览请求已重下发"));
    }

    /** A5 意图：首帧 onConfigured 也用 buildPreviewRequest 构造——首帧与重下发共用同一 request，torch 语义一致。 */
    @Test
    public void a5_onConfigured_firstFrameReusesBuildPreviewRequest() {
        assertTrue("A5 首帧 onConfigured 须调用 setRepeatingRequest(buildPreviewRequest(d, surface)...（跨行）",
                matchesMultiLine(onConfiguredBody,
                        "setRepeatingRequest\\s*\\(\\s*buildPreviewRequest\\(d, surface\\)"));
        assertEquals("A5 全文 buildPreviewRequest(d, surface) 调用须恰好 2 处（首帧 + 重下发）",
                2, countOccurrences(codeOnly, "buildPreviewRequest(d, surface)"));
    }

    /** A6 意图：L4 能力探测、L5 变化检测、重下发判据三处文本仍在（诊断链可追溯）。 */
    @Test
    public void a6_diagnosticLogs_l4ProbeL5ChangeDetectIntact() {
        assertTrue("A6 须含 L4 能力探测日志 [cam] 能力探测",
                codeOnly.contains("[cam] 能力探测"));
        assertTrue("A6 L4 日志须记录 flashAvailable 与 aeCompTarget",
                codeOnly.contains("flashAvailable=$flashAvailable aeCompTarget=$aeCompTarget"));
        assertTrue("A6 须含 L5 变化检测日志 torchChanged=$torchChanged",
                codeOnly.contains("torchChanged=$torchChanged"));
        assertTrue("A6 须保留 if (torchChanged || lowLightChanged) refreshRequestInSession() 判据",
                codeOnly.contains("if (torchChanged || lowLightChanged) refreshRequestInSession()"));
    }

    /** A7 意图（负向）：overlay 预览路径不得出现 setTorchMode（会与预览抢后置相机抛 CAMERA_IN_USE）。 */
    @Test
    public void a7_noSetTorchModeInPreviewPath() {
        assertFalse("A7 去注释源码不得出现 CameraManager.setTorchMode（设计约束：会话内 FLASH_MODE_TORCH）",
                codeOnly.contains("setTorchMode"));
        assertTrue("A7 同时确认 buildPreviewRequest 用的是 CaptureRequest.FLASH_MODE（非 setTorchMode）",
                buildBody.contains("builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)"));
    }

    /**
     * A8 意图：硬边界 token 计数不增不减。文件内四个结构 token 全文=去注释计数；
     * 两个闪光灯/曝光 token 全文锁基线（8/2）、去注释锁真实下发点唯一（1/1）——口径见类 javadoc。
     */
    @Test
    public void a8_hardBoundaryTokenCountsUnchanged() {
        // 四个结构 token：全文与去注释计数一致
        assertEquals("A8 全文 invalidateOutline 须恰好 3 次", 3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A8 全文 setDefaultBufferSize( 须恰好 1 次", 1, countOccurrences(codeOnly, "setDefaultBufferSize("));
        assertEquals("A8 全文 hudView?.rotation = -deg 须恰好 1 次", 1, countOccurrences(codeOnly, "hudView?.rotation = -deg"));
        assertEquals("A8 全文 addView(hudView 须恰好 1 次", 1, countOccurrences(codeOnly, "addView(hudView"));
        // FLASH_MODE_TORCH：全文口径锁基线 8（含注释），去注释口径锁唯一真实下发点 1
        assertEquals("A8 全文 FLASH_MODE_TORCH 须恰好 8 次（与实现报告 SC4 基线一致，未新增 token）",
                8, countOccurrences(overlaySrc, "FLASH_MODE_TORCH"));
        assertEquals("A8 去注释 FLASH_MODE_TORCH 须恰好 1 次（唯一真实下发点在 buildPreviewRequest）",
                1, countOccurrences(codeOnly, "FLASH_MODE_TORCH"));
        // CONTROL_AE_EXPOSURE_COMPENSATION：全文口径锁基线 2，去注释口径锁唯一真实下发点 1
        assertEquals("A8 全文 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 2 次（:2008 下发 + :2021 注释）",
                2, countOccurrences(overlaySrc, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        assertEquals("A8 去注释 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "CONTROL_AE_EXPOSURE_COMPENSATION"));
    }

    // ==================== B 组 · 真值表（独立参考实现复算） ====================

    /**
     * B1 意图：穷举调查报告 SC7-B 六行（link × night × flashAvailable），确认
     * <b>只有 (link=T, night=T, flash=T) 时 torchOn=true</b>，其余一律 false。
     */
    @Test
    public void b1_truthTable_onlyAllTrueYieldsTorch() {
        boolean[][] cases = {
                { false, false, false }, // 行1
                { false, false, true  }, // 行2
                { false, true,  true  }, // 行3
                { true,  false, true  }, // 行4
                { true,  true,  false }, // 行5（flash 降级）
                { true,  true,  true  }, // 行6（唯一真）
        };
        boolean[] expectTorch = { false, false, false, false, false, true };
        for (int i = 0; i < cases.length; i++) {
            boolean twp = refTorchWithPreview(cases[i][0], cases[i][1]);
            boolean torchOn = refShouldSetTorch(twp, cases[i][2]);
            assertEquals("B1 第 " + (i + 1) + " 行 torchOn 须符合 SC7-B", expectTorch[i], torchOn);
        }
    }

    /**
     * B2 意图（关键治本行）：torchOn=true 时曝光补偿不下发——夜间 TORCH 与正补偿互斥，
     * 这正是 M1 修复点。逐字复刻 M1 条件 lowLight && aeCompTarget>0 && !torchOn。
     */
    @Test
    public void b2_keyRow_aeCompSuppressedWhenTorchOn() {
        // 场景：夜间 + 联动开 + 有闪光灯 + lowLight 生效 + aeCompTarget=6（约 +2EV 档）
        boolean twp = refTorchWithPreview(true, true);
        boolean torchOn = refShouldSetTorch(twp, true);
        assertTrue("B2 该行 torchOn 须为 true", torchOn);
        assertFalse("B2 核心：torchOn=true 时正曝光补偿必须不下发（M1 治本点）",
                refShouldSetAeComp(true, 6, torchOn));
        // 对照：torch 未亮时 lowLight 仍独立生效（无灯靠 +2EV 看轮廓）
        assertTrue("B2 对照：torch 未亮时 lowLight 正补偿仍下发",
                refShouldSetAeComp(true, 6, false));
    }

    /** B3 意图：flashAvailable=false 时即使开关 + 夜间都为 true 也不下发 TORCH（能力降级）。 */
    @Test
    public void b3_flashUnavailable_degradesNoTorch() {
        boolean twp = refTorchWithPreview(true, true);
        assertTrue("B3 派生值 torchWithPreview 须为 true（开关与夜间都 true）", twp);
        assertFalse("B3 无闪光灯时不得下发 TORCH（HAL 会拒整条 preview request → 黑屏）",
                refShouldSetTorch(twp, false));
        // 由于 torch 实际未亮，无需互斥，曝光补偿仍可独立下发
        assertTrue("B3 torch 降级未亮时曝光补偿不受互斥影响，仍按 lowLight 下发",
                refShouldSetAeComp(true, 6, refShouldSetTorch(twp, false)));
    }

    /** B4 意图：aeCompTarget<=0 或无 lowLight 时不下发曝光补偿（能力降级 + 白天分支）。 */
    @Test
    public void b4_aeCompRequiresLowLightAndPositiveTarget() {
        assertFalse("B4 aeCompTarget=0 不下发（无曝光补偿能力）", refShouldSetAeComp(true, 0, false));
        assertFalse("B4 aeCompTarget<0 不下发", refShouldSetAeComp(true, -1, false));
        assertFalse("B4 lowLight=false 不下发（白天分支）", refShouldSetAeComp(false, 6, false));
        assertTrue("B4 lowLight=true 且 aeCompTarget>0 且 torch 未亮才下发",
                refShouldSetAeComp(true, 6, false));
    }

    /**
     * B5 意图：夜间判定跨零点边界——默认 22/6 下两条实现（isNightTime / isNightHour）
     * 对 0..23 每个 hour 结果一致，且边界值符合「含起点 22、不含终点 6」。
     */
    @Test
    public void b5_nightBoundary_crossMidnightConsistent() {
        for (int hour = 0; hour < 24; hour++) {
            assertEquals("B5 默认 22/6 hour=" + hour + " 两条夜间判定须一致",
                    refIsNightTime(hour, 22, 6), refIsNightHour(hour, 22, 6));
        }
        // 跨零点边界：22 含、23 含、0 含、5 含、6 不含、21 不含、白天 13 不含
        assertTrue("B5 hour=22 命中（起点含）", refIsNightHour(22, 22, 6));
        assertTrue("B5 hour=23 命中", refIsNightHour(23, 22, 6));
        assertTrue("B5 hour=0 命中（跨零点）", refIsNightHour(0, 22, 6));
        assertTrue("B5 hour=5 命中", refIsNightHour(5, 22, 6));
        assertFalse("B5 hour=6 不命中（终点不含）", refIsNightHour(6, 22, 6));
        assertFalse("B5 hour=21 不命中", refIsNightHour(21, 22, 6));
        assertFalse("B5 hour=13 不命中（白天）", refIsNightHour(13, 22, 6));
    }

    /** B6 意图：夜间判定的两个特例——start==end 禁用（PresetResolve 语义）、非跨零点用区间。 */
    @Test
    public void b6_nightSpecialCases_sameHourDisabledAndNonCrossing() {
        for (int hour = 0; hour < 24; hour++) {
            assertFalse("B6 start==end=22 时 hour=" + hour + " 一律不命中（同值禁用）",
                    refIsNightHour(hour, 22, 22));
        }
        // 非跨零点 6/22：命中区间 [6,22)
        assertFalse("B6 hour=5 在 6/22 下不命中", refIsNightHour(5, 6, 22));
        assertTrue("B6 hour=6 在 6/22 下命中", refIsNightHour(6, 6, 22));
        assertTrue("B6 hour=21 在 6/22 下命中", refIsNightHour(21, 6, 22));
        assertFalse("B6 hour=22 在 6/22 下不命中", refIsNightHour(22, 6, 22));
    }

    /** B7 意图：判据同源——L1 的 !torchOn 与 M1 的 !(torchWithPreview && flashAvailable) 全枚举等价，防日后两处漂移。 */
    @Test
    public void b7_judgeIdentity_notTorchOnEqualsMutexGuard() {
        for (int twp = 0; twp <= 1; twp++) {
            for (int flash = 0; flash <= 1; flash++) {
                boolean torchOn = refShouldSetTorch(twp == 1, flash == 1);
                assertEquals("B7 !torchOn 须等价于 !(torchWithPreview && flashAvailable)",
                        !(twp == 1 && flash == 1), !torchOn);
                // 进一步：曝光补偿在此等价判据下，torch 亮则必不成立
                if (torchOn) {
                    assertFalse("B7 torch 亮时 M1 条件必 false",
                            refShouldSetAeComp(true, 6, torchOn));
                }
            }
        }
    }
}
