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

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 步数相关 UI 的「功耗契约」回归（5.6.7）。
 *
 * <p>步数每秒都在变，最怕的是「每一步都做一次重活」：重建 drawable、重设 level 触发
 * invalidate、new Calendar、跨进程调 View。这些开销单看都很小，叠在一天几千步上就是
 * 实打实的耗电与发热，而且不会崩溃、不会报错 —— 只能靠契约测试把写法钉住。
 *
 * <p>锁六条：① level 同值早退；② 描边 drawable 按 key 复用；③ 档位不变不重建描边；
 * ④ dayKey 有 30s 缓存；⑤ 每步路径不碰 overlay；⑥ 徽章显隐同值早退。
 */
public class StepRewardPowerContractTest {

    private static final String OVERLAY =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String SERVICE =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";

    private static String overlay;
    private static String service;

    @BeforeClass
    public static void load() throws IOException {
        overlay = stripLineComments(read(OVERLAY));
        service = stripLineComments(read(SERVICE));
    }

    @Test
    public void hudLevelSetter_earlyReturnsOnSameValue() {
        int i = overlay.indexOf("var level: Int");
        assertTrue("找不到 HudTextView.level 定义", i >= 0);
        String seg = overlay.substring(Math.max(0, i - 40), Math.min(overlay.length(), i + 320));
        assertTrue("level setter 必须同值早退（否则每步都 invalidate）",
                seg.contains("if (field == v) return"));
    }

    @Test
    public void borderDrawable_isCachedByKey() {
        assertTrue("描边 drawable 必须有缓存字段", overlay.contains("private var borderDrawable"));
        assertTrue("描边必须有缓存键", overlay.contains("private var borderKey"));
        assertTrue("命中缓存时必须复用而不是新建",
                overlay.contains("if (d == null || key != borderKey)"));
        // 缓存命中但窗口重建过：只重新挂一次，不再新建
        assertTrue("窗口重建后复用已有 drawable", overlay.contains("root.foreground !== d"));
    }

    @Test
    public void stepsChange_doesNotRebuildBorderWhenTierUnchanged() {
        int i = overlay.indexOf("fun setTodaySteps(");
        assertTrue("找不到 setTodaySteps", i >= 0);
        String seg = overlay.substring(i, Math.min(overlay.length(), i + 700));
        assertTrue("档位没变时不得调用 applyBorder",
                seg.contains("if (newTier != oldTier)"));
        int tierIf = seg.indexOf("if (newTier != oldTier)");
        int apply = seg.indexOf("applyBorder()", tierIf);
        assertTrue("applyBorder 必须落在档位变化的 if 之内", apply > tierIf);
    }

    @Test
    public void dayKey_hasCacheWindow() {
        assertTrue("dayKey 必须有缓存时长常量", service.contains("DAY_KEY_CACHE_MS"));
        assertTrue("dayKey 必须有缓存字段", service.contains("stepDayKeyCache"));
        int i = service.indexOf("String stepDayKeyOf(");
        assertTrue("找不到 stepDayKeyOf", i >= 0);
        String seg = service.substring(i, Math.min(service.length(), i + 900));
        assertTrue("缓存命中时必须直接返回，不建 Calendar",
                seg.contains("return stepDayKeyCache"));
        // 每步都 new Calendar 是旧写法，必须已消失
        assertFalse("每步不得再 new Calendar（缓存命中时短路）",
                seg.contains("Calendar.getInstance();") && !seg.contains("DAY_KEY_CACHE_MS"));
    }

    @Test
    public void perStepPath_neverTouchesOverlay() {
        int i = service.indexOf("private void onStepObserved(");
        assertTrue("找不到 onStepObserved", i >= 0);
        // 截到下一个方法签名前，避免把别的方法算进来
        int end = service.indexOf("\n    /**", i + 10);
        if (end < 0) end = Math.min(service.length(), i + 1_200);
        String seg = service.substring(i, end);
        assertFalse("每步路径里不得直接操作悬浮窗（View 调用集中在 gaitTick）",
                seg.contains("overlay."));
        assertTrue("每步只允许轻量累加 + 节流落盘",
                seg.contains("recomputeTodaySteps()") || seg.contains("todayStepsCache"));
    }

    @Test
    public void badgeVisibility_earlyReturnsOnSameState() {
        assertTrue("徽章 GONE 分支须同值早退",
                overlay.contains("if (badge.visibility != View.GONE)"));
        assertTrue("徽章 VISIBLE 分支须同值早退",
                overlay.contains("if (badge.visibility != View.VISIBLE)"));
    }

    // ==================== 工具 ====================

    private static String read(String rel) throws IOException {
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
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 " + rel + "，已尝试路径：" + tried);
    }

    /** 去掉行注释（字符串内的 // 不截断），避免注释文字把断言引偏。 */
    private static String stripLineComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            int cut = -1;
            boolean inStr = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) inStr = !inStr;
                if (!inStr && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    cut = i;
                    break;
                }
            }
            out.append(cut >= 0 ? line.substring(0, cut) : line).append('\n');
        }
        return out.toString();
    }
}
