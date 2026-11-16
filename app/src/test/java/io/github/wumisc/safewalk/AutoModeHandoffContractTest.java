package io.github.wumisc.safewalk;

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
 * 「自动跟随步行」false→true 跃变时显示权交接的回归用例（5.3.9）。
 *
 * <p><b>测试性质声明：</b>A 组是对跃变判据布尔逻辑的<b>等价测试</b>（参考实现复刻
 * {@code lastAppliedAuto != null && !lastAppliedAuto && newAuto}），并非对 Android
 * Service 实例的运行期集成测试——WalkPreviewService 依赖 Context/SensorManager/overlay，
 * 当前 JVM 纯单测体系跑不了实例，真实交接行为（窗口真的消失、步态真的拿到显示权）
 * 留真机验证。B 组是<b>源码契约测试</b>：读取 WalkPreviewService.java 文本锁住
 * handoffToAutoMode() 的必要动作与禁止动作，防止未来漏项/误加导致同一 bug 复发。
 *
 * <p>本文件只新增测试，不改任何实现代码与既有测试。
 */
public class AutoModeHandoffContractTest {

    /** 相对模块根 app/ 的源码路径；另做多路径 fallback 适配 repo 根作为工作目录的情况。 */
    private static final String SOURCE_REL = "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
    private static final String METHOD_SIGNATURE = "private void handoffToAutoMode()";

    private static String codeOnly; // 去掉 // 与 /* */ 注释后的源码；避免注释文字造成误判

    @BeforeClass
    public static void loadServiceSource() throws IOException {
        Path source = locateServiceSource();
        String raw = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        codeOnly = stripComments(raw);
    }

    /** 多候选路径定位源码：AGP 单测工作目录通常是模块根 app/，但也可能被改成 repo 根。 */
    private static Path locateServiceSource() throws IOException {
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
        throw new IOException("找不到 WalkPreviewService.java，已尝试路径：" + tried);
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

    /** 从方法签名开始做花括号配对，取出真正的方法体（排除签名前的 Javadoc 注释）。 */
    private static String methodBody(String source, String signature) {
        int sig = source.indexOf(signature);
        if (sig < 0) {
            throw new AssertionError("源码中找不到方法签名：" + signature);
        }
        int open = source.indexOf('{', sig);
        if (open < 0) {
            throw new AssertionError("方法签名后找不到左花括号：" + signature);
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("方法花括号未闭合：" + signature);
    }

    private static String handoffBody() {
        return methodBody(codeOnly, METHOD_SIGNATURE);
    }

    private static boolean hasCall(String body, String method) {
        return Pattern.compile(Pattern.quote(method) + "\\s*\\(\\s*\\)").matcher(body).find();
    }

    private static boolean resetsToZero(String body, String field) {
        // 兼容 Java long 字面量 0L 与整数字面量 0，但不接受任何非零值
        return Pattern.compile(Pattern.quote(field) + "\\s*=\\s*0[Ll]?\\s*;").matcher(body).find();
    }

    /** 等价布尔函数：仅当「有上一态、上一态是关、新态是开」时交接一次。 */
    private static boolean shouldHandoff(Boolean prev, boolean next) {
        return prev != null && !prev && next;
    }

    // ==================== A 组 · 跃变判据真值表（等价测试） ====================

    /** A1 意图：冷启动首帧且新态为开也不交接（没有旧常显窗可收）。输入 prev=null,next=true；预期 false。 */
    @Test
    public void a1_coldStartFirstFrameTrue_noHandoff() {
        assertFalse("A1 冷启动首帧不应交接", shouldHandoff(null, true));
    }

    /** A2 意图：冷启动首帧且新态为关不交接。输入 prev=null,next=false；预期 false。 */
    @Test
    public void a2_coldStartFirstFrameFalse_noHandoff() {
        assertFalse("A2 冷启动首帧不应交接", shouldHandoff(null, false));
    }

    /** A3 意图：本次 bug 核心——从关切到开必须交接一次，收起悬挂的常显窗。输入 prev=false,next=true；预期 true。 */
    @Test
    public void a3_offToOn_shouldHandoff() {
        assertTrue("A3 false→true 必须交接一次", shouldHandoff(false, true));
    }

    /** A4 意图：高频重复 ACTION_SHOW（已处于开）不重复交接，防闪窗。输入 prev=true,next=true；预期 false。 */
    @Test
    public void a4_onToOn_noRepeatHandoff() {
        assertFalse("A4 重复开不应交接", shouldHandoff(true, true));
    }

    /** A5 意图：从开切到关不在这里交接，关走 stopGait 正常路径。输入 prev=true,next=false；预期 false。 */
    @Test
    public void a5_onToOff_noHandoff() {
        assertFalse("A5 true→false 不交接", shouldHandoff(true, false));
    }

    /** A6 意图：持续处于关，无跃变不交接。输入 prev=false,next=false；预期 false。 */
    @Test
    public void a6_offToOff_noHandoff() {
        assertFalse("A6 false→false 不交接", shouldHandoff(false, false));
    }

    // ==================== B 组 · handoffToAutoMode() 源码契约 ====================

    /** B1 意图：交接必须调用 overlay.hide() 夺回显示权（真机 bug 核心）；预期方法体含该调用。 */
    @Test
    public void b1_handoffBody_hidesOverlay() {
        String body = handoffBody();
        assertTrue("B1 handoffToAutoMode() 必须调用 overlay.hide()", hasCall(body, "overlay.hide"));
    }

    /** B2 意图：四项状态复位缺一不可（显示基线/合格步时钟/连续步行起点/收起冷却）；预期四项均归零。 */
    @Test
    public void b2_handoffBody_resetsAllFourTimestamps() {
        String body = handoffBody();
        assertTrue("B2 缺 gaitShowTs 归零", resetsToZero(body, "gaitShowTs"));
        assertTrue("B2 缺 lastStepTs 归零", resetsToZero(body, "lastStepTs"));
        assertTrue("B2 缺 walkingStreakStartTs 归零", resetsToZero(body, "walkingStreakStartTs"));
        assertTrue("B2 缺 lastStopTs 归零", resetsToZero(body, "lastStopTs"));
    }

    /** B3 意图：交接必须留 DiagLog.log 供真机 grep 自证；预期方法体含该调用。 */
    @Test
    public void b3_handoffBody_logsForDiagnosis() {
        String body = handoffBody();
        assertTrue("B3 handoffToAutoMode() 必须含 DiagLog.log",
                Pattern.compile("DiagLog\\.log\\s*\\(").matcher(body).find());
    }

    /** B4 意图：交接只做清场，不得重复触发显示；预期方法体不含 startGait/evaluateShow/doShow 调用。 */
    @Test
    public void b4_handoffBody_doesNotRestartShowing() {
        String body = handoffBody();
        assertFalse("B4 交接里不得调用 startGait()", hasCall(body, "startGait"));
        assertFalse("B4 交接里不得调用 evaluateShow()", hasCall(body, "evaluateShow"));
        assertFalse("B4 交接里不得调用 doShow()", hasCall(body, "doShow"));
    }

    // ==================== 额外加固 · 把 A 组参考实现与生产判据绑定 ====================

    /** B5 额外意图：生产代码里的跃变判据须与 A 组真值表一致；预期 applyFromConfig 含相同布尔条件。 */
    @Test
    public void b5_applyFromConfig_conditionMatchesTruthTable() {
        String body = methodBody(codeOnly, "private void applyFromConfig(");
        assertTrue("B5 生产判据须为 lastAppliedAuto != null && !lastAppliedAuto && newAuto",
                Pattern.compile("lastAppliedAuto\\s*!=\\s*null\\s*&&\\s*!lastAppliedAuto\\s*&&\\s*newAuto")
                        .matcher(body).find());
        assertTrue("B5 跃变成立时必须调用 handoffToAutoMode()", hasCall(body, "handoffToAutoMode"));
    }

    /** B6 额外意图：判据后须把新态记入 lastAppliedAuto，否则 A4 的防重复交接会失效。 */
    @Test
    public void b6_applyFromConfig_updatesLastAppliedAuto() {
        String body = methodBody(codeOnly, "private void applyFromConfig(");
        assertTrue("B6 必须执行 lastAppliedAuto = newAuto",
                Pattern.compile("lastAppliedAuto\\s*=\\s*newAuto\\s*;").matcher(body).find());
    }

    /** B7 额外意图：lastAppliedAuto 必须是可空三态字段（null=冷启动未知），否则 A1/A2 无从谈起。 */
    @Test
    public void b7_lastAppliedAuto_isNullableBooleanInitializedNull() {
        assertTrue("B7 字段须声明为 private Boolean lastAppliedAuto = null",
                Pattern.compile("private\\s+Boolean\\s+lastAppliedAuto\\s*=\\s*null\\s*;")
                        .matcher(codeOnly).find());
    }
}
