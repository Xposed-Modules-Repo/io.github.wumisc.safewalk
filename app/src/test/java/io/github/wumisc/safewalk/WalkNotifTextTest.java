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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 「行走模式运行中」常驻通知三态回归（5.6.8）。
 *
 * <p>用户反馈：开的是自动跟随（走起来才显示），通知却一直写「身后路况持续显示中」，
 * 而且小窗收起一小时后文案还是旧的。修复分两层，各锁一组：
 * ① 纯逻辑 [WalkNotifText] 的三态映射；② 服务侧真的在「显示/收起」「自动/手动切换」
 * 两个时机刷新通知，且同文案不重发。
 */
@RunWith(JUnit4.class)
public class WalkNotifTextTest {

    // ==================== 纯逻辑三态 ====================

    @Test
    public void showing_alwaysSaysShowing() {
        assertEquals(WalkNotifText.SHOWING, WalkNotifText.subtitle(true, true));
        assertEquals(WalkNotifText.SHOWING, WalkNotifText.subtitle(true, false));
    }

    @Test
    public void autoNotShowing_saysGaitDetecting() {
        assertEquals("自动跟随 + 未显示 → 步态检测运行中",
                WalkNotifText.AUTO_WAITING, WalkNotifText.subtitle(false, true));
        assertTrue(WalkNotifText.AUTO_WAITING.contains("步态检测"));
        assertFalse("等待态不得再宣称持续显示",
                WalkNotifText.AUTO_WAITING.contains("持续显示"));
    }

    @Test
    public void manualNotShowing_saysAlwaysOn() {
        assertEquals(WalkNotifText.MANUAL_ALWAYS, WalkNotifText.subtitle(false, false));
        assertTrue(WalkNotifText.MANUAL_ALWAYS.contains("持续显示"));
    }

    @Test
    public void threeStatesAreDistinct() {
        assertFalse(WalkNotifText.SHOWING.equals(WalkNotifText.AUTO_WAITING));
        assertFalse(WalkNotifText.AUTO_WAITING.equals(WalkNotifText.MANUAL_ALWAYS));
        assertFalse(WalkNotifText.SHOWING.equals(WalkNotifText.MANUAL_ALWAYS));
    }

    // ==================== 服务侧接线契约 ====================

    @Test
    public void serviceWiresRefreshAtBothMoments() throws IOException {
        String src = readService();
        assertTrue("常驻通知构建必须用三态文案，不得再写死",
                src.contains(".setContentText(walkNotifSubtitle())"));
        assertFalse("写死的旧文案必须消失（goForeground 里）",
                src.contains(".setContentText(\"身后路况持续显示中 · 点击查看\")"));
        assertTrue("显示状态变化要刷新通知",
                src.contains("notifyPreviewState(showing);")
                        && src.contains("refreshWalkNotification();"));
        assertTrue("同文案不得重发（缓存字段）", src.contains("lastWalkNotifText"));
        assertTrue("自动/手动切换也要刷新",
                src.contains("lastAppliedAuto = newAuto;")
                        && src.indexOf("refreshWalkNotification();",
                                src.indexOf("lastAppliedAuto = newAuto;")) > 0);
        assertTrue("刷新必须以前台已提起为前提",
                src.contains("if (!foregroundEstablished) return;"));
    }

    private static String readService() throws IOException {
        String rel = "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
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
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 WalkPreviewService.java");
    }
}
