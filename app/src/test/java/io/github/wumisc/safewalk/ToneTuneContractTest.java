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
 * 5.4.12「路况预览黑乎乎」渲染层调色提亮的纯 JVM 契约用例。
 *
 * <p><b>测试性质声明（务必先读）：</b>
 * <ul>
 *   <li><b>A 组 · 源码文本契约（stripComments 后口径）</b>：直接读取 {@code RearCameraOverlay.kt} 与
 *       {@code PresetResolve.kt} 文本，按花括号配平 / 三引号定界截取
 *       {@code ENHANCE_AGSL} shader 块、{@code applyScreenEnhance()} 兜底块、
 *       {@code buildPreviewRequest()} 守卫段，锁住本次修复的<b>结构</b>——
 *       「gamma 提亮 {@code pow(c,1/g)} + 越黑越大回抬 {@code pow(1-s,2)} + 统一 B_CONST=0.30」
 *       「末级 clamp 与饱和度保留」「ColorMatrix 高光锚定 255f*(1-s) + coerceIn + setSaturation」
 *       「负向：不得回退到旧压暗式与旧 127.5f 线性式」「54 号曝光互斥守卫仍在、AE token 计数不增」
 *       「方向/形状/HUD/buffer 硬边界 token 计数不变」「两套模板参数未改（提亮靠算法非参数）」，
 *       防止回退到「以 0.5 为支点拉伸压死暗部」的旧算法或误碰曝光层/方向层。</li>
 *   <li><b>B 组 · 提亮真值表（独立参考实现复算，绝不假装调用私有 shader）</b>：生产算法内联在
 *       AGSL 运行时字符串 {@code ENHANCE_AGSL}（运行时由 GPU 编译，<b>JVM 无法直接调用</b>），
 *       且本任务未抽取可测纯函数。故本组用类内独立参考函数 {@code refTone} 复刻实现报告 §4
 *       的数学（g=1+k*1.2；s=c^(1/g)；w=(1-s)^2；s=s+(1-s)*w*(lift*0.30)；末级 clamp），
 *       B_CONST 用 Max 实际写入 shader 的 0.30（实现报告 §3 决策 1），
 *       <b>绝不假装直接调用了私有 shader</b>。逐档 c∈{0.05,0.1,0.2,0.3,0.5,0.8,1.0} 断言
 *       「输出≥输入（本 bug 核心：暗部不再被压暗）」「c=0.05 落入目标区间」「c=1.0 严格=1.0（不吹白）」
 *       「全程≤1.0（不引入白屏）」。参考函数是否与 shader 一致，由 A 组文本断言兜底。</li>
 *   <li><b>C 组 · 可证性声明</b>：见本 javadoc 末段「只能真机验证、本文件测不到的部分」——
 *       调色主观效果与运行时现象 JVM 一律测不到，只锁可判定的源码结构与纯函数真值。</li>
 * </ul>
 *
 * <p><b>B 组参考实现 ↔ 生产源码映射（重要，避免误读为直接调用）：</b>
 * <table>
 *   <tr><th>本文件参考物</th><th>生产对应（RearCameraOverlay.kt ENHANCE_AGSL）</th></tr>
 *   <tr><td>{@code refTone} 的 g=1+k*1.2</td><td>:163 {@code half g = half(1.0 + contrast * 1.2);}</td></tr>
 *   <tr><td>{@code refTone} 的 s=c^(1/g)</td><td>:164 {@code half3 s = pow(c, half3(half(1.0) / g));}</td></tr>
 *   <tr><td>{@code refTone} 的 w=(1-s)^2、回抬系数 lift*0.30</td>
 *       <td>:168-170 {@code const half B_CONST = half(0.30);} /
 *       {@code pow(half3(1.0)-s, half3(2.0))} /
 *       {@code s = s + (half3(1.0)-s)*w*half(lift * B_CONST);}</td></tr>
 *   <tr><td>{@code refTone} 的末级 clamp</td><td>:176 {@code clamp(s, half3(0.0), half3(1.0))}</td></tr>
 * </table>
 * ③ 饱和度步骤（:173-174）对灰度像素 l=s 不改变数值，故参考函数省略之，与实现报告 §4 口径一致。
 *
 * <p><b>token 计数口径说明（避免误报，以源码实测为准）：</b>任务书 A 组把 token 列在
 * 「stripComments 后」标题下，但源码实测与 {@code TorchWhiteoutContractTest} /
 * {@code PortraitRestoreContractTest} 类 javadoc 一致：{@code FLASH_MODE_TORCH} 含注释全文为 <b>8</b>、
 * 去注释为 <b>1</b>（唯一真实下发点）；{@code CONTROL_AE_EXPOSURE_COMPENSATION} 含注释全文为 <b>2</b>
 * （:2034 下发 + :2047 KDoc）、去注释为 <b>1</b>。故 A5 对这两个 token <b>两种口径都断言</b>：
 * 全文锁基线不增不减，去注释锁真实下发点唯一。其余结构 token 全文与去注释计数一致。
 * {@code isLandscapeWindowNow()} 去注释共 4 处（1 处定义 + 3 处调用，KDoc 不带括号不计入），
 * 断言「总数 4 − 定义 1 = 调用 3」。
 *
 * <p><b>只能真机验证、本文件测不到的部分（C 组可证性声明）：</b>
 * <ol>
 *   <li><b>调色主观效果</b>：像不像图一（白桌面 + 粉耳机盒 + 香蕉清晰明亮）、白天模板发不发灰
 *       （gamma 提亮抬中灰的固有副作用）——主观渲染效果，沙箱无 GPU/真机画面，JVM 不可证。</li>
 *   <li><b>真机白屏复现</b>：夜间真开 TORCH 场景是否复现 54 号白屏、新提亮是否引入高光白片——
 *       依赖具体 HAL/GPU/ROM，需真机抓 safewalk diag.log，JVM 不可证。</li>
 *   <li><b>AGSL 运行有效性</b>：{@code ENHANCE_AGSL} 是运行时字符串，Kotlin 编译器不校验；
 *       {@code pow(half3, half3)} 在目标 ROM（API 33+）是否编译通过并正确执行，JVM 不可证
 *       （异常时现有 catch 降级 ColorMatrix，本兜底已同步修好）。</li>
 *   <li><b>ColorMatrix 降级量纲</b>：{@code offset=255f*(1f-s)} 在 {@code ColorMatrixColorFilter}
 *       的归一化量纲/8bit 平移是否如实现报告 §2 所述，降级机型实际亮度——需真机确认，JVM 不可证。</li>
 * </ol>
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试，零外部依赖（JUnit4 为项目既有依赖），
 * 不编译（交 Vic 统一编译运行）。写法与 {@code TorchWhiteoutContractTest} /
 * {@code PortraitRestoreContractTest} 一致。
 */
public class ToneTuneContractTest {

    private static final String OVERLAY_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String PRESET_REL =
            "src/main/java/io/github/wumisc/safewalk/PresetResolve.kt";

    private static final String ENHANCE_SIG = "private const val ENHANCE_AGSL = \"\"\"";
    private static final String ENHANCE_SIG_END = "\"\"\"";
    private static final String ENHANCE_FN_SIG = "private fun applyScreenEnhance()";
    private static final String BUILD_SIG = "private fun buildPreviewRequest(";

    // B 组参考函数常量：Max 实际写入 shader 的 B_CONST（实现报告 §3 决策 1 / §4 数学表口径）
    private static final double B_CONST = 0.30;
    private static final double EPS = 1e-4;

    private static String overlaySrc;      // 原始 .kt 源码（保留注释，用于全文 token 基线计数）
    private static String codeOnly;        // 去注释全文（用于去注释口径计数与全局关键串）
    private static String shaderText;      // ENHANCE_AGSL 三引号内文本（stripComments 后，含 AGSL 内 // 注释原样）
    private static String enhanceBody;     // applyScreenEnhance() 方法体（去注释）
    private static String buildBody;       // buildPreviewRequest() 方法体（去注释）
    private static String presetSrc;       // PresetResolve.kt 去注释全文（参数未改断言）

    @BeforeClass
    public static void loadSource() throws IOException {
        overlaySrc = new String(Files.readAllBytes(locate(OVERLAY_REL)), StandardCharsets.UTF_8);
        codeOnly = stripComments(overlaySrc);
        shaderText = extractShader(codeOnly);
        enhanceBody = stripComments(extractMethod(overlaySrc, ENHANCE_FN_SIG));
        buildBody = stripComments(extractMethod(overlaySrc, BUILD_SIG));
        presetSrc = stripComments(new String(Files.readAllBytes(locate(PRESET_REL)), StandardCharsets.UTF_8));
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

    /** 截取 ENHANCE_AGSL 三引号字符串内部（不含三引号本身），供 shader 文本断言使用。 */
    private static String extractShader(String code) {
        int start = code.indexOf(ENHANCE_SIG);
        assertTrue("源码中找不到 ENHANCE_AGSL 签名", start >= 0);
        int bodyStart = start + ENHANCE_SIG.length();
        int end = code.indexOf(ENHANCE_SIG_END, bodyStart);
        assertTrue("ENHANCE_AGSL 找不到结束三引号", end >= 0);
        return code.substring(bodyStart, end);
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

    // ==================== B 组：独立参考实现（复刻提亮数学，非调用私有 shader） ====================

    /**
     * 复刻 ENHANCE_AGSL ①②+末级 clamp 的净效果（灰度像素，③ 饱和度不改变数值）。
     * 参数：c=输入亮度 0..1、k=contrast、lift=暗部回抬强度。
     * g=1+k*1.2（沿用原增益）；s=c^(1/g) 保端点；w=(1-s)^2 越黑越大；
     * s=s+(1-s)*w*(lift*B_CONST)；末级 clamp 防白屏。
     */
    private static double refTone(double c, double k, double lift) {
        double g = 1.0 + k * 1.2;
        double s = Math.pow(c, 1.0 / g);
        double w = Math.pow(1.0 - s, 2.0);
        s = s + (1.0 - s) * w * (lift * B_CONST);
        return Math.min(1.0, Math.max(0.0, s));
    }

    // ==================== A 组 · 源码文本契约 ====================

    /** A1 意图（核心 · 防回退）：shader ① 用 gamma 提亮 pow(c,1/g)、② 用越黑越大回抬、统一 B_CONST=0.30；
     *  负向断言不含旧「以 0.5 为支点拉伸」压暗式。 */
    @Test
    public void a1_shaderUsesGammaLiftAndDarkBoostNotOldCompression() {
        assertTrue("A1 须含 gamma 增益 g=1+contrast*1.2（沿用原 1.2 增益）",
                shaderText.contains("half(1.0 + contrast * 1.2)"));
        assertTrue("A1 须含 gamma 提亮 pow(c, half3(half(1.0) / g))（保端点、不吹高光）",
                shaderText.contains("pow(c, half3(half(1.0) / g))"));
        assertTrue("A1 须含统一常量 B_CONST=0.30（Max 口径，白天系数 0.12 / 夜间 0.21）",
                shaderText.contains("const half B_CONST = half(0.30)"));
        assertTrue("A1 须含越黑越大回抬权重 pow(half3(1.0) - s, half3(2.0))",
                shaderText.contains("pow(half3(1.0) - s, half3(2.0))"));
        assertTrue("A1 回抬系数须由 lift 驱动 lift * B_CONST（不新增 uniform）",
                shaderText.contains("lift * B_CONST"));
        assertFalse("A1 负向：不得回退到旧压暗式 half3(0.5) + (c - half3(0.5))（会把暗部压死）",
                shaderText.contains("half3(0.5) + (c - half3(0.5))"));
        assertFalse("A1 负向：shader 不得再出现 0.5 支点 half3(0.5)",
                shaderText.contains("half3(0.5)"));
    }

    /** A2 意图：③ 饱和度与末级 clamp 原样保留（clamp 是防白屏第二道保险，不得删）。 */
    @Test
    public void a2_shaderKeepsSaturationAndFinalClamp() {
        assertTrue("A2 末级 clamp 须保留 clamp(s, half3(0.0), half3(1.0))（防白屏封顶）",
                shaderText.contains("clamp(s, half3(0.0), half3(1.0))"));
        assertTrue("A2 饱和度灰度投影须保留 dot(s, half3(0.299, 0.587, 0.114))",
                shaderText.contains("dot(s, half3(0.299, 0.587, 0.114))"));
        assertTrue("A2 饱和度混合须保留 mix(half3(l), s, half(1.0 + sat))",
                shaderText.contains("mix(half3(l), s, half(1.0 + sat))"));
    }

    /** A3 意图：ColorMatrix 兜底同步为高光锚定线性提亮 255f*(1-s) + coerceIn + setSaturation；
     *  负向断言不含旧 127.5f 线性式（同 shader① 压暗病）。 */
    @Test
    public void a3_colorMatrixFallbackUsesHighlightAnchoredLift() {
        assertTrue("A3 兜底须含高光锚定 offset=255f * (1f - s)（in=255→255 不吹白）",
                enhanceBody.contains("255f * (1f - s)"));
        assertTrue("A3 兜底 s 须 coerceIn(0.5f, 1f)（线性提亮封顶）",
                enhanceBody.contains("coerceIn(0.5f, 1f)"));
        assertTrue("A3 兜底须保留 cm.setSaturation(1f + sat)",
                enhanceBody.contains("cm.setSaturation(1f + sat)"));
        assertTrue("A3 兜底系数须为 s=1-0.10k-0.23lift（SC6 联立标定）",
                enhanceBody.contains("1f - 0.10f * k - 0.23f * lift"));
        assertFalse("A3 负向：去注释兜底块不得再出现旧 127.5f 支点（压死暗部）",
                enhanceBody.contains("127.5f"));
        assertFalse("A3 负向：去注释兜底块不得再出现旧 lift * 22 系数",
                enhanceBody.contains("lift * 22"));
    }

    /** A4 意图：54 号曝光互斥守卫原样保留（提亮只在渲染层，不碰曝光层）；AE 字段计数不增不减。 */
    @Test
    public void a4_exposureGuardIntactAndAeTokenCountsUnchanged() {
        assertTrue("A4 曝光补偿 if 须含 54 号互斥守卫 !(style.torchWithPreview && flashAvailable)",
                codeOnly.contains("!(style.torchWithPreview && flashAvailable)"));
        assertTrue("A4 守卫整句须逐字保留（lowLight + aeCompTarget>0 + 互斥）",
                buildBody.contains("if (style.lowLight && aeCompTarget > 0 && !(style.torchWithPreview && flashAvailable))"));
        assertEquals("A4 全文 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 2 次（:2034 下发 + :2047 KDoc 基线）",
                2, countOccurrences(overlaySrc, "CONTROL_AE_EXPOSURE_COMPENSATION"));
        assertEquals("A4 去注释 CONTROL_AE_EXPOSURE_COMPENSATION 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "CONTROL_AE_EXPOSURE_COMPENSATION"));
    }

    /** A5 意图：方向/形状/HUD/buffer/闪光灯硬边界 token 计数与基线一致（本轮只改 shader 字符串与兜底块）；
     *  isLandscapeWindowNow() 调用点恒为 3（定义行不算）。 */
    @Test
    public void a5_hardBoundaryTokenCountsUnchanged() {
        assertEquals("A5 invalidateOutline 须恰好 3 次", 3, countOccurrences(codeOnly, "invalidateOutline"));
        assertEquals("A5 setDefaultBufferSize( 须恰好 1 次", 1, countOccurrences(codeOnly, "setDefaultBufferSize("));
        assertEquals("A5 hudColumn?.rotation = -deg 须恰好 1 次", 1, countOccurrences(codeOnly, "hudColumn?.rotation = -deg"));
        assertEquals("A5 addView(hudView 须恰好 1 次", 1, countOccurrences(codeOnly, "addView(hudView"));
        assertEquals("A5 全文 FLASH_MODE_TORCH 须恰好 8 次（基线，未新增 token）",
                8, countOccurrences(overlaySrc, "FLASH_MODE_TORCH"));
        assertEquals("A5 去注释 FLASH_MODE_TORCH 须恰好 1 次（唯一真实下发点）",
                1, countOccurrences(codeOnly, "FLASH_MODE_TORCH"));
        int isLandTotal = countOccurrences(codeOnly, "isLandscapeWindowNow()");
        int isLandDef = countOccurrences(codeOnly, "fun isLandscapeWindowNow()");
        assertEquals("A5 去注释 isLandscapeWindowNow() 含定义共 4 处（1 定义 + 3 调用）",
                4, isLandTotal);
        assertEquals("A5 其中函数定义 1 处（private fun ...）",
                1, isLandDef);
        assertEquals("A5 真实调用点须恰好 3 处（定义行不算，与基线一致）",
                3, isLandTotal - isLandDef);
    }

    /** A6 意图：PresetResolve.kt 两套模板参数未改—— DAY contrast=45、NIGHT contrast=55；
     *  确保提亮靠算法修复而非调参（防止有人偷偷改参数绕过算法契约）。 */
    @Test
    public void a6_presetParamsUnchanged() {
        assertTrue("A6 PresetResolve 须保留 DAY_PARAMS 定义",
                presetSrc.contains("private val DAY_PARAMS = Params("));
        assertTrue("A6 PresetResolve 须保留 NIGHT_PARAMS 定义",
                presetSrc.contains("private val NIGHT_PARAMS = Params("));
        int dayStart = presetSrc.indexOf("private val DAY_PARAMS = Params(");
        int nightStart = presetSrc.indexOf("private val NIGHT_PARAMS = Params(");
        int plainStart = presetSrc.indexOf("private val PLAIN_PARAMS = Params(");
        assertTrue("A6 DAY_PARAMS 须在 NIGHT_PARAMS 之前", dayStart >= 0 && nightStart > dayStart);
        assertTrue("A6 NIGHT_PARAMS 须在 PLAIN_PARAMS 之前", plainStart > nightStart);
        String dayBlock = presetSrc.substring(dayStart, nightStart);
        String nightBlock = presetSrc.substring(nightStart, plainStart);
        assertTrue("A6 DAY 仍须 contrast = 45（参数未改）", dayBlock.contains("screenContrast = 45"));
        assertTrue("A6 NIGHT 仍须 contrast = 55（参数未改）", nightBlock.contains("screenContrast = 55"));
        assertTrue("A6 DAY 仍须 shadowLift = 40 / saturation = 50",
                dayBlock.contains("screenShadowLift = 40") && dayBlock.contains("screenSaturation = 50"));
        assertTrue("A6 NIGHT 仍须 shadowLift = 70 / saturation = 35",
                nightBlock.contains("screenShadowLift = 70") && nightBlock.contains("screenSaturation = 35"));
    }

    // ==================== B 组 · 提亮真值表（独立参考实现复算） ====================

    private static final double[] CS = { 0.05, 0.1, 0.2, 0.3, 0.5, 0.8, 1.0 };

    // 实现报告 §4 数学表最终值（B_CONST=0.30 口径），用于逐档锁住算法防漂移
    private static final double[] DAY_EXPECT = { 0.218492, 0.280237, 0.384363, 0.476732, 0.643280, 0.865405, 1.0 };
    private static final double[] NIGHT_EXPECT = { 0.286995, 0.338464, 0.429486, 0.513007, 0.667005, 0.874637, 1.0 };

    /** B1 意图（本 bug 核心）：逐档断言输出 ≥ 输入——暗部不再被压暗。白天 k=0.45/lift=0.40，夜间 k=0.55/lift=0.70。 */
    @Test
    public void b1_allLevels_outputNotBelowInput() {
        for (int i = 0; i < CS.length; i++) {
            double c = CS[i];
            assertTrue("B1 白天 c=" + c + " 输出须 ≥ 输入（暗部不再压暗）",
                    refTone(c, 0.45, 0.40) >= c - EPS);
            assertTrue("B1 夜间 c=" + c + " 输出须 ≥ 输入（暗部不再压暗）",
                    refTone(c, 0.55, 0.70) >= c - EPS);
        }
    }

    /** B2 意图（与实现报告数学表一致）：c=0.05 白天落 [0.15,0.35]、夜间落 [0.20,0.40]。 */
    @Test
    public void b2_darkestLevel_inTargetRange() {
        double day = refTone(0.05, 0.45, 0.40);
        double night = refTone(0.05, 0.55, 0.70);
        assertTrue("B2 白天 c=0.05 须落 [0.15,0.35]，实际=" + day,
                day >= 0.15 - EPS && day <= 0.35 + EPS);
        assertTrue("B2 夜间 c=0.05 须落 [0.20,0.40]，实际=" + night,
                night >= 0.20 - EPS && night <= 0.40 + EPS);
    }

    /** B3 意图（防白屏）：c=1.0 严格映射 1.0——幂律保端点、不吹白、不引入白片。 */
    @Test
    public void b3_whiteEnd_staysExactlyOne() {
        assertEquals("B3 白天 c=1.0 须严格 = 1.0", 1.0, refTone(1.0, 0.45, 0.40), EPS);
        assertEquals("B3 夜间 c=1.0 须严格 = 1.0", 1.0, refTone(1.0, 0.55, 0.70), EPS);
    }

    /** B4 意图（防白屏）：全程输出 ≤ 1.0（含高光 c=0.8/1.0），末级 clamp 双保险。 */
    @Test
    public void b4_allLevels_neverExceedOne() {
        for (int i = 0; i < CS.length; i++) {
            double c = CS[i];
            assertTrue("B4 白天 c=" + c + " 输出须 ≤ 1.0", refTone(c, 0.45, 0.40) <= 1.0 + EPS);
            assertTrue("B4 夜间 c=" + c + " 输出须 ≤ 1.0", refTone(c, 0.55, 0.70) <= 1.0 + EPS);
        }
    }

    /** B5 意图：逐档复算与实现报告 §4 数学表数值一致（1e-4 容差），锁住算法不被悄悄改动。 */
    @Test
    public void b5_matchesImplementationMathTable() {
        for (int i = 0; i < CS.length; i++) {
            assertEquals("B5 白天 c=" + CS[i] + " 须等于实现报告数学表",
                    DAY_EXPECT[i], refTone(CS[i], 0.45, 0.40), EPS);
            assertEquals("B5 夜间 c=" + CS[i] + " 须等于实现报告数学表",
                    NIGHT_EXPECT[i], refTone(CS[i], 0.55, 0.70), EPS);
        }
    }
}
