package io.github.wumisc.safewalk;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 「步数奖励开关」源码契约回归（5.6.5）。
 *
 * 这是纯 JVM 源码契约测试：开关涉及四个层面，任何一层漏改都会表现为
 * 「设置页能点、但小窗不变色」或者「关不掉」，跑真机才知道太慢。
 * 因此这里一次锁死四条线：
 * <ol>
 *   <li>小窗自定义布局里确有 `sw_step_reward`，且整行可点（`card_step_reward`）；</li>
 *   <li>设置页读 key、写 key 两处都在（只读不写 = 重启就复位）；</li>
 *   <li>配置白名单带上 key（否则导出 / 换机会丢）；</li>
 *   <li>渲染层与服务端都把开关传到底（`borderColor(..., stepRewardEnabled)` /
 *       `showFlag(..., stepRewardEnabled)` / `setStepRewardEnabled(...)`）。</li>
 * </ol>
 */
public class StepRewardSwitchContractTest {

    private static final String OVERLAY =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";
    private static final String SERVICE =
            "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
    private static final String ACTIVITY =
            "src/main/java/io/github/wumisc/safewalk/PreviewStyleActivity.java";
    private static final String PROVIDER =
            "src/main/java/io/github/wumisc/safewalk/ConfigProvider.kt";
    private static final String LAYOUT =
            "src/main/res/layout/activity_preview_style.xml";

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

    @Test
    public void layoutHasSwitchAndTappableRow() throws IOException {
        String xml = read(LAYOUT);
        assertTrue("布局须有 switch：@+id/sw_step_reward",
                xml.contains("@+id/sw_step_reward"));
        assertTrue("布局须有可点整行：@+id/card_step_reward",
                xml.contains("@+id/card_step_reward"));
        assertTrue("布局须出现「步数奖励」标题", xml.contains("android:text=\"步数奖励\""));
    }

    @Test
    public void activityReadsAndWritesPrefKey() throws IOException {
        String src = read(ACTIVITY);
        assertTrue("设置页须读 key",
                src.contains("SafePrefs.getBool(sp, SafeWalkPrefKey.stepRewardEnabled"));
        assertTrue("设置页须写 key（否则重启复位）",
                src.contains("putBoolean(SafeWalkPrefKey.stepRewardEnabled"));
        assertTrue("开关变化须即时预览", src.contains("swStepReward.setOnCheckedChangeListener"));
        // 全屏没有描边，开关必须随小窗档位置灰，不能静默无效
        assertTrue("全屏档位须置灰开关", src.contains("setEnabledState(swStepReward, small)"));
    }

    @Test
    public void configWhitelistContainsKey() throws IOException {
        assertTrue("导出配置须带 stepRewardEnabled",
                read(PROVIDER).contains("SafeWalkPrefKey.stepRewardEnabled"));
    }

    @Test
    public void overlayPassesSwitchToBorderAndFlag() throws IOException {
        String src = read(OVERLAY);
        assertTrue("渲染层须有 setStepRewardEnabled", src.contains("fun setStepRewardEnabled("));
        assertTrue("描边取色须带开关",
                src.contains("StepBorderPolicy.borderColor(todaySteps, stepRewardEnabled)"));
        assertTrue("小红旗须带开关",
                src.contains("StepBorderPolicy.showFlag(todaySteps, stepRewardEnabled)"));
    }

    @Test
    public void serviceDeliversSwitchBeforeShow() throws IOException {
        String src = read(SERVICE);
        int idx = src.indexOf("overlay.setStepRewardEnabled(");
        assertTrue("服务端须下发开关", idx >= 0);
        assertTrue("下发须读同一个 key",
                src.contains("SafePrefs.getBool(sp, SafeWalkPrefKey.stepRewardEnabled, true)"));
        int show = src.indexOf("overlay.show(mode, opacity", idx);
        assertTrue("下发必须在 overlay.show 之前（窗口首帧就按开关渲染）", show > idx);
    }
}
