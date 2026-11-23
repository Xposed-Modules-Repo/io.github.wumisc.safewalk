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

import org.junit.Test;

/**
 * 【5.6.13 重构】布局与引导分级契约（纯 JVM 读源码/布局）。
 *
 * 锁：① 首页移除本机 UI 芯片、底部有公益卡；② 隐私两开关移入关于页；
 * ③ 首装引导必需/可选分级 + 完成闸门。
 */
public class HomeRestructureContractTest {

    private static String read(String rel) throws IOException {
        List<Path> c = new ArrayList<Path>();
        c.add(Paths.get(rel));
        c.add(Paths.get("app", rel));
        String ud = System.getProperty("user.dir");
        if (ud != null && !ud.isEmpty()) {
            Path cwd = Paths.get(ud);
            c.add(cwd.resolve(rel));
            c.add(cwd.resolve("app").resolve(rel));
            Path p = cwd.getParent();
            if (p != null) { c.add(p.resolve("app").resolve(rel)); c.add(p.resolve(rel)); }
        }
        for (Path p : c) {
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 " + rel);
    }

    @Test
    public void homeDropsRomChipsAndAddsCharity() throws IOException {
        String s = read("src/main/res/layout/activity_main.xml");
        int about = s.indexOf("==================== 关于");
        String home = s.substring(0, about);
        assertFalse("首页不再展示本机 UI 芯片", home.contains("chip_rom_auto"));
        assertTrue("首页底部必须有公益卡", home.contains("card_missing_kids"));
        assertTrue("公益卡必须展示两条孩子",
                home.contains("iv_kid_photo_1") && home.contains("iv_kid_photo_2")
                        && home.contains("tv_kid_name_1") && home.contains("tv_kid_name_2"));
    }

    @Test
    public void privacyMovedToAbout() throws IOException {
        String s = read("src/main/res/layout/activity_main.xml");
        int about = s.indexOf("==================== 关于");
        String home = s.substring(0, about);
        String aboutPage = s.substring(about);
        assertFalse("隐私开关不再在首页", home.contains("btn_hide_launcher"));
        assertTrue("隐私开关必须在关于页", aboutPage.contains("btn_hide_launcher")
                && aboutPage.contains("btn_hide_recent"));
    }

    @Test
    public void onboardingRequiredOptionalGate() throws IOException {
        String s = read("src/main/java/io/github/wumisc/safewalk/PermissionOnboardingActivity.kt");
        assertTrue("必须有必需/可选分级字段", s.contains("val required: Boolean"));
        assertTrue("必须有完成闸门", s.contains("private fun requiredMissing()"));
        assertTrue("必需项未授予不放行", s.contains("必需权限未授予"));
        assertTrue("急救卡电话/短信为可选", s.contains("电话 / 短信（急救卡·可选）"));
        assertTrue("行走模式悬浮窗为必需", s.contains("必需 · 需手动开启"));
    }

    @Test
    public void labCompletelyRemoved() throws IOException {
        String layout = read("src/main/res/layout/activity_main.xml");
        assertFalse("布局不再展示功能实验区入口行", layout.contains("btn_lab"));
        assertFalse("布局不再出现功能实验区标题", layout.contains("功能实验区"));

        String main = read("src/main/java/io/github/wumisc/safewalk/MainActivity.java");
        assertFalse("MainActivity 不再引用实验区 Activity", main.contains("LabActivity"));

        String manifest = read("src/main/AndroidManifest.xml");
        assertFalse("Manifest 不再注册实验区 Activity", manifest.contains(".LabActivity"));
    }

    @Test
    public void internetPermissionDeclaredForCharity() throws IOException {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue("公益数据源联网必须声明 INTERNET 权限",
                manifest.contains("android.permission.INTERNET"));
    }

    @Test
    public void privacyNoteInAbout() throws IOException {
        String s = read("src/main/res/layout/activity_main.xml");
        int about = s.indexOf("==================== 关于");
        String aboutPage = s.substring(about);
        assertTrue("隐私分组须有本地处理说明",
                aboutPage.contains("仅在本机处理，不上传服务器"));
    }
}