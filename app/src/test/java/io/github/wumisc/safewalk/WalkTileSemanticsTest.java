package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 【5.5.0 磁贴语义还原】验收测试（QA 独立编写）。
 *
 * <p>用户原话：「磁贴撤销『点击直接进入行走模式』，还原成『常显 ↔ 自动跟随行走』的切换
 * （通知栏和 LSPosed 都能进 App，磁贴不该当恢复入口）」。
 *
 * <p>这条需求有三个独立的失败模式，必须分别钉死：
 * <ol>
 *   <li><b>恢复分支残留</b>：{@code onClick()} 里只要还剩「{@code hideLauncherIcon=true} → 跳
 *       MainActivity」一段，隐藏图标的用户点磁贴就永远进不了切换语义 —— 这正是用户要撤销的行为。
 *       行为无法在 JVM 里跑（TileService 依赖 QS 框架），故用<b>源码文本契约</b>锁死。</li>
 *   <li><b>切换语义被改坏</b>：关档必须只关 {@code rearCamAuto}（画面转常显、服务不停），
 *       开档必须同时打开 {@code enableRearCamera + rearCamAuto}。少写一个开关，
 *       用户看到的就是「点了没画面」或「画面再也收不起来」。</li>
 *   <li><b>恢复文案还在指引点磁贴</b>：{@code recoveryMessage} 第 ③ 条若仍是「控制中心点磁贴」，
 *       用户点下去只会切档位，反而更困惑。</li>
 * </ol>
 *
 * <p>另含 {@code WalkTileStatePolicy} 的全状态矩阵（2×2×2 = 8 种组合）：
 * 磁贴点亮/置灰算错任一格，都会让用户以为「点了没反应」或「永远关不掉」。
 */
public class WalkTileSemanticsTest {

    private static final String SERVICE_SRC =
            "src/main/java/io/github/wumisc/safewalk/WalkTileService.kt";

    private static String serviceSrc;
    /** 去掉注释后的源码：避免「注释里提到 hideLauncherIcon」被误判成还有恢复分支 */
    private static String serviceCode;

    @BeforeClass
    public static void loadSource() throws IOException {
        serviceSrc = new String(Files.readAllBytes(locate(SERVICE_SRC)), StandardCharsets.UTF_8);
        serviceCode = stripComments(serviceSrc);
    }

    // ==================== ① 恢复分支必须整段消失 ====================

    @Test
    public void onClick_hasNoLauncherIconRestoreBranch() {
        String onIcon = normalize(extractMethod(serviceCode, "fun onClick()"));

        assertFalse("onClick() 不得再读写 hideLauncherIcon —— 磁贴不再是隐藏图标的恢复入口",
                onIcon.contains("hideLauncherIcon"));
        assertFalse("onClick() 不得直接跳 MainActivity（恢复入口走通知/LSPosed）",
                onIcon.contains("MainActivity"));
        // 点击必须只落到纯策略分派
        assertTrue("onClick() 必须经 WalkTileStatePolicy.resolve() 分派",
                onIcon.contains("WalkTileStatePolicy.resolve("));
        assertTrue("ACTIVE 必须落到 turnOff()", onIcon.contains("ACTIVE -> turnOff()"));
        assertTrue("其余状态必须落到 turnOn(walk)", onIcon.contains("turnOn(walk)"));
        assertTrue("UNAVAILABLE 必须给出明确原因并引导去 App 打开总开关",
                onIcon.contains("UNAVAILABLE") && onIcon.contains("mainIntent()"));
    }

    @Test
    public void serviceFile_neverReferencesLauncherIconState() {
        // 整份文件都不应出现：副标题/行为都不能因「图标已隐藏」而变
        assertFalse("WalkTileService 全文不得引用 hideLauncherIcon",
                serviceCode.contains("hideLauncherIcon"));
    }

    @Test
    public void refreshTile_subtitleComesOnlyFromStatePolicy() {
        String refresh = normalize(extractMethod(serviceCode, "fun refreshTile()"));
        assertTrue("副标题必须由纯策略给出", refresh.contains("WalkTileStatePolicy.subtitle("));
        assertTrue("状态必须由纯策略给出", refresh.contains("WalkTileStatePolicy.resolve("));
        assertFalse("副标题不得再读 hideLauncherIcon", refresh.contains("hideLauncherIcon"));
        assertFalse("副标题不得出现「进入 App 恢复」这类旧文案", refresh.contains("恢复"));
    }

    // ==================== ② 常显 ↔ 自动跟随 的切换语义 ====================

    @Test
    public void turnOff_disablesOnlyAutoFollow_keepsPreviewRunning() {
        String body = normalize(extractMethod(serviceCode, "fun turnOff()"));

        assertTrue("关档必须把 rearCamAuto 写成 false（画面转常显）",
                body.contains("SafeWalkPrefKey.rearCamAuto, false"));
        assertFalse("关档绝不能动总开关 enableRearCamera —— 否则画面直接消失、服务白跑",
                body.contains("enableRearCamera"));
        assertTrue("关档必须通知服务重算（画面转常显），而不是停服务",
                body.contains("WalkPreviewService.ACTION_SHOW"));
        assertFalse("关档不得停服务 / 不得 stopService", body.contains("stopService"));
    }

    @Test
    public void turnOn_enablesPreviewAndAutoFollow() {
        String body = normalize(extractMethod(serviceCode, "fun turnOn(walkOn: Boolean)"));

        assertTrue("开档必须打开总开关 enableRearCamera = true（没有总开关就没有画面）",
                body.contains("SafeWalkPrefKey.enableRearCamera, true"));
        assertTrue("开档必须打开自动跟随 rearCamAuto = true",
                body.contains("SafeWalkPrefKey.rearCamAuto, true"));
        // 两条路径都必须能拉起预览：服务在跑 → ACTION_SHOW；服务没跑 → 透明中转页
        assertTrue("服务已在跑时必须发 ACTION_SHOW 让画面回来",
                body.contains("WalkPreviewService.ACTION_SHOW"));
        assertTrue("服务没跑时必须经 WalkToggleActivity 拉起前台服务（Android 14 限制）",
                body.contains("WalkToggleActivity::class.java"));
        assertTrue("服务在跑时应先判 isAlive() 走轻量路径",
                body.contains("WalkPreviewService.isAlive()"));
    }

    // ==================== ③ 恢复文案：通知而非磁贴 ====================

    @Test
    public void recoveryMessage_pointsToNotificationNotTile() {
        String msg = LauncherIconPolicy.recoveryMessage("io.github.wumisc.safewalk");

        assertFalse("恢复文案不得再提磁贴", msg.contains("磁贴"));
        assertFalse("恢复文案不得再提控制中心", msg.contains("控制中心"));
        assertTrue("第 ③ 条必须指向通知栏常驻通知", msg.contains("通知"));
        assertTrue("必须写清通知的名字，否则用户不知道点哪个",
                msg.contains("行走模式运行中"));
        // 四条路径齐全且顺序不变：① LSPosed（首推）→ ② 设置 → ③ 通知 → ④ adb
        int i1 = msg.indexOf("①");
        int i2 = msg.indexOf("②");
        int i3 = msg.indexOf("③");
        int i4 = msg.indexOf("④");
        assertTrue("必须保留 ①～④ 四条路径",
                i1 >= 0 && i2 > i1 && i3 > i2 && i4 > i3);
        assertTrue("LSPosed 必须仍是首推路径（用户群体都装了模块）",
                msg.indexOf("LSPosed") > i1 && msg.indexOf("LSPosed") < i2);
        assertTrue("adb 兜底路径必须保留",
                msg.contains("adb shell am start -n io.github.wumisc.safewalk/.MainActivity"));
    }

    // ==================== ④ 全状态矩阵（8 种组合） ====================

    /** 矩阵一行：master / walk / auto → 期望状态 + 期望副标题关键字（任一命中即可） */
    private static final class Case {
        final boolean master;
        final boolean walk;
        final boolean auto;
        final int state;
        final String[] subtitleHints;

        Case(boolean master, boolean walk, boolean auto, int state, String... subtitleHints) {
            this.master = master;
            this.walk = walk;
            this.auto = auto;
            this.state = state;
            this.subtitleHints = subtitleHints;
        }

        String label() {
            return "master=" + master + ", walk=" + walk + ", auto=" + auto;
        }

        boolean subtitleMatches(String sub) {
            for (String h : subtitleHints) {
                if (sub.contains(h)) return true;
            }
            return false;
        }
    }

    private static List<Case> matrix() {
        List<Case> list = new ArrayList<Case>();
        // 总开关关：无论其余开关残留什么，都必须置灰（点了不生效）
        list.add(new Case(false, false, false, WalkTileStatePolicy.UNAVAILABLE, "总开关"));
        list.add(new Case(false, true, true, WalkTileStatePolicy.UNAVAILABLE, "总开关"));
        list.add(new Case(false, true, false, WalkTileStatePolicy.UNAVAILABLE, "总开关"));
        list.add(new Case(false, false, true, WalkTileStatePolicy.UNAVAILABLE, "总开关"));
        // 总开关开，但自动跟随没开（含「只开常显」）：灰着，点一下开启自动跟随
        list.add(new Case(true, false, false, WalkTileStatePolicy.INACTIVE, "点击开启"));
        list.add(new Case(true, true, false, WalkTileStatePolicy.INACTIVE, "点击开启"));
        list.add(new Case(true, false, true, WalkTileStatePolicy.INACTIVE, "点击开启"));
        // 两个都开：点亮。服务活着说「走起来才显示」，服务没活说「预览未运行」
        list.add(new Case(true, true, true, WalkTileStatePolicy.ACTIVE,
                "走起来", "预览未运行"));
        return list;
    }

    @Test
    public void stateMatrix_allEightCombinations() {
        for (Case c : matrix()) {
            assertEquals(c.label() + " 状态判定错误",
                    c.state, WalkTileStatePolicy.resolve(c.master, c.walk, c.auto));
        }
    }

    @Test
    public void subtitleMatrix_allEightCombinations() {
        for (Case c : matrix()) {
            // 两种服务存活态都要覆盖：副标题不能因 isAlive 改变「点一下会发生什么」
            for (boolean alive : new boolean[]{true, false}) {
                String sub = WalkTileStatePolicy.subtitle(c.master, c.walk, c.auto, alive);
                assertTrue(c.label() + " alive=" + alive + " 副标题应含 描述之一，实际=" + sub,
                        c.subtitleMatches(sub));
                assertFalse("副标题不得把磁贴说成恢复入口：" + sub,
                        sub.contains("磁贴") || sub.contains("控制中心") || sub.contains("图标"));
                assertTrue("副标题不能为空", sub.trim().length() > 0);
            }
        }
    }

    @Test
    public void subtitle_whenAutoOnButServiceDead_saysPreviewNotRunning() {
        // 自动跟随开着、服务没活：必须提示「预览未运行」，否则用户对着黑屏无从下手
        String sub = WalkTileStatePolicy.subtitle(true, true, true, false);
        assertTrue("自动跟随开着但服务没活时必须提示预览未运行，实际=" + sub,
                sub.contains("预览未运行"));
        // 而且必须仍然是「点一下关闭」的切换语义，不能写成「点一下重试」
        assertFalse("不得承诺「重试」—— 点下去其实是关闭", sub.contains("重试"));
    }

    // ==================== 源码文本工具 ====================

    /** 多候选路径定位源码：AGP 单测工作目录通常是模块根 app/，也可能是 repo 根。 */
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
        throw new IOException("找不到源码 " + rel + "，已尝试：" + tried);
    }

    /** 从签名起按花括号配平截取单个方法文本（跳过字符串/字符字面量中的花括号）。 */
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
                    continue;
                }
                if (inString && c == '"') inString = false;
                if (inChar && c == '\'') inChar = false;
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
                if (depth == 0) return src.substring(brace, i + 1);
            }
        }
        throw new AssertionError("方法未闭合：" + signature);
    }

    /** 去掉行注释与块注释（保留字符串内容），避免注释里的旧描述造成误判。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(next);
                    i++;
                    continue;
                }
                if (inString && c == '"') inString = false;
                if (inChar && c == '\'') inChar = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '\'') {
                inChar = true;
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 归一化空白，让「ACTIVE -> turnOff()」这类断言不受换行/缩进影响。 */
    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ");
    }
}
