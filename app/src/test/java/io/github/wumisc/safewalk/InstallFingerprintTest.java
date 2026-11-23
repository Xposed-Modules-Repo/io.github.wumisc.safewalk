package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 「覆盖安装也算新安装事件」的指纹单测。
 *
 * 守的是用户反复提的那条：**覆盖安装也必须提示重启系统界面**。
 * 只比时间戳会漏 —— 部分 ROM（含个别 HyperOS 版本）覆盖安装后 lastUpdateTime 不刷新，
 * 于是同版本重装一声不吭，用户以为改的东西没生效。APK 字节数是确定的：换包必变。
 */
public class InstallFingerprintTest {

    @Test
    public void detectsReplacedApkBySize() {
        // 换包（同版本、内容不同）：大小变了 → 必须判为新安装事件
        assertTrue("APK 大小变了 → 覆盖安装，必须提示重启",
                InstallFingerprint.changed(3811018L, 3810882L));
        // 同一份包，只是又打开一次 App：大小不变 → 不重复打扰
        assertFalse("同一份包 → 不再提示",
                InstallFingerprint.changed(3811018L, 3811018L));
        // 从没记过（旧版本升级上来）：不据此判定，交给 versionCode / 时间戳
        assertFalse("没有历史记录 → 不据此判定",
                InstallFingerprint.changed(3811018L, 0L));
        // 取不到当前大小：同样不判定，避免误弹
        assertFalse("取不到大小 → 不判定",
                InstallFingerprint.changed(0L, 3811018L));
    }

    @Test
    public void homePageWiresAllFourEvidence() throws IOException {
        // 契约：首页判定必须同时接上四条证据（版本号 / 时间戳 / APK 大小 / 覆盖安装广播），
        // 少接一条就会出现「装了新包却不提示」的沉默路径。
        String s = read("src/main/java/io/github/wumisc/safewalk/MainActivity.java");
        assertTrue("首页必须用 InstallFingerprint.stamp",
                s.contains("InstallFingerprint.stamp(this)"));
        assertTrue("首页必须用 InstallFingerprint.apkSize",
                s.contains("InstallFingerprint.apkSize(this)"));
        assertTrue("首页必须比对 APK 大小",
                s.contains("InstallFingerprint.changed(apkSize"));
        assertTrue("版本号判定必须在",
                s.contains("RestartReminderPolicy.shouldPrompt(installed, acked)"));
        assertTrue("覆盖安装广播标记必须在",
                s.contains("replacedPending"));
        // 重启成功后要同时落「已处理」的时间戳与大小，否则下一轮会重复弹
        assertTrue("确认重启后必须记下 APK 大小",
                s.contains("KEY_INSTALL_SIZE, apkSize"));
        // 引导页重启成功也要落同一组标记（否则回首页会二次弹窗）
        String onboarding = read(
                "src/main/java/io/github/wumisc/safewalk/PermissionOnboardingActivity.kt");
        assertTrue("引导页确认后也要记 APK 大小",
                onboarding.contains("RestartReminderPolicy.KEY_INSTALL_SIZE"));
    }

    @Test
    public void policyDeclaresSizeKeys() throws IOException {
        String s = read("src/main/java/io/github/wumisc/safewalk/RestartReminderPolicy.kt");
        assertTrue("KEY_INSTALL_SIZE", s.contains("restartSuiInstallSize"));
        assertTrue("KEY_PROMPTED_SIZE", s.contains("restartSuiPromptedSize"));
    }

    private static String read(String relative) throws IOException {
        Path p = Paths.get(relative);
        if (!Files.exists(p)) {
            p = Paths.get("app", relative);
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
