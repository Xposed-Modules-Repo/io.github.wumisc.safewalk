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
 * 步数链路「异常也必须有兜底」源码契约（5.6.8）。
 *
 * <p>这一组不测算术（那在 {@link StepRewardAbnormalTest}），只测**运行时兜底**：
 * SharedPreferences 损坏、传感器给 null、悬浮窗已销毁、时间被往回拨、屏幕状态读不到。
 * 这些路径真机上偶发一次，表现就是「服务崩了 / 步数卡死 / 通知乱弹」，
 * 没有契约测试就只能等用户反馈。
 */
public class StepRewardAbnormalContractTest {

    private static final String SERVICE =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
    private static final String OVERLAY =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static String service;
    private static String overlay;

    @BeforeClass
    public static void load() throws IOException {
        service = read(SERVICE);
        overlay = read(OVERLAY);
    }

    @Test
    public void loadStepState_survivesBrokenPrefs() {
        int i = service.indexOf("private void loadStepState()");
        assertTrue("找不到 loadStepState", i >= 0);
        String seg = service.substring(i, Math.min(service.length(), i + 1_000));
        assertTrue("SP 读坏时必须 try/catch 兜底", seg.contains("catch (Throwable"));
        assertTrue("坏数据要退化成空 dayKey + 无基线",
                seg.contains("stepDayKey = \"\"") && seg.contains("stepBaseTotal = TodayStepPolicy.NO_BASE"));
    }

    @Test
    public void flushStepState_neverThrowsIntoService() {
        int i = service.indexOf("private void flushStepState()");
        assertTrue("找不到 flushStepState", i >= 0);
        String seg = service.substring(i, Math.min(service.length(), i + 900));
        assertTrue("落盘失败不得抛给调用方", seg.contains("catch (Throwable"));
        assertTrue("失败走节流日志，不刷屏", seg.contains("logThrottled"));
        assertTrue("落盘用 apply（异步），不阻塞主线程", seg.contains(".apply()"));
    }

    @Test
    public void updateStepBadge_guardsNullAndHiddenWindow() {
        int i = service.indexOf("private void updateStepBadge(");
        assertTrue("找不到 updateStepBadge", i >= 0);
        String seg = service.substring(i, Math.min(service.length(), i + 900));
        assertTrue("overlay 为 null 时必须直接返回",
                seg.contains("overlay == null") && seg.contains("return"));
        assertTrue("窗口没显示时不得下发 View 调用", seg.contains("!overlay.getShowing()"));
    }

    @Test
    public void dayKeyCache_handlesClockGoingBackwards() {
        int i = service.indexOf("private String stepDayKeyOf(");
        assertTrue("找不到 stepDayKeyOf", i >= 0);
        String seg = service.substring(i, Math.min(service.length(), i + 900));
        // 缓存必须只在「时间往前走了不到 30 秒」时命中；时钟被回拨（dt<0）要重算
        assertTrue("缓存命中条件必须含 dt >= 0", seg.contains("dt >= 0L"));
        assertTrue("缓存命中后直接返回", seg.contains("return stepDayKeyCache"));
    }

    @Test
    public void screenState_isCachedAndBroadcastDriven() {
        assertTrue("亮屏状态必须有缓存字段", service.contains("private boolean screenInteractive"));
        assertTrue("SCREEN_OFF 要更新缓存", service.contains("screenInteractive = false"));
        assertTrue("SCREEN_ON 要更新缓存", service.contains("screenInteractive = true"));
        assertTrue("isScreenUsable 必须读缓存而不是 IPC",
                service.contains("if (!screenInteractive) return false"));
        assertTrue("服务启动时必须取一次初值", service.contains("screenInteractive = pm0.isInteractive()"));
    }

    @Test
    public void headDownBlock_skippedWhenFeatureOff() {
        int i = service.indexOf("enableHeadDownRemind");
        assertTrue("找不到低头提醒配置读取", i >= 0);
        String seg = service.substring(Math.max(0, i - 200), Math.min(service.length(), i + 600));
        assertTrue("开关关闭时必须整段跳过（不查 PowerManager / 不算 tilt）",
                seg.contains("if (headDownOn)"));
    }

    @Test
    public void overlayNullSafeWhenDestroyed() {
        // 小窗被系统回收 / 服务销毁后，任何一次步数更新都不能碰空引用
        int i = overlay.indexOf("fun setTodaySteps(");
        assertTrue("找不到 setTodaySteps", i >= 0);
        String seg = overlay.substring(i, Math.min(overlay.length(), i + 500));
        assertFalse("setTodaySteps 不得假设 rootView 非空（applyBorder 内部已判空）",
                seg.contains("rootView!!"));
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
}
