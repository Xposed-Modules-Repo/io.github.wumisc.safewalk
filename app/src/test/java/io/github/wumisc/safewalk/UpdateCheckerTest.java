package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * 【5.7.18 更新提示】版本解析与「两条下载通道」契约。
 *
 * 锁三件事：
 * ① tag `140-5.7.18-lx102` 必须解析成 140，老式 tag（`5.6.15`）一律解析失败 ——
 *    宁可不提示，也不提示一个错的版本；
 * ② 只有远端严格大于本机才提示；
 * ③ 关于页有「检查更新」这一行，两条下载通道（123 云盘 / GitHub）都写在代码里。
 */
public class UpdateCheckerTest {

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
            if (p != null) {
                c.add(p.resolve("app").resolve(rel));
                c.add(p.resolve(rel));
            }
        }
        for (Path p : c) {
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 " + rel);
    }

    @Test
    public void releaseTagCarriesVersionCodeAndName() {
        UpdateChecker.Release r = UpdateChecker.parseRelease("140-5.7.18-lx102");
        assertNotNull(r);
        assertEquals(140, r.getVersionCode());
        assertEquals("5.7.18-lx102", r.getVersionName());
    }

    @Test
    public void oldStyleTagGivesNoVersion() {
        // 老 tag 里没有版本号，不能猜、不能当成 0 去提示
        assertNull(UpdateChecker.parseRelease("5.6.15"));
        assertNull(UpdateChecker.parseRelease("v5.5.0"));
        assertNull(UpdateChecker.parseRelease(""));
        assertNull(UpdateChecker.parseRelease("release-notes"));
    }

    // JSON 解析要真 org.json 实现（纯 JVM 单元测试里只有抛异常的桩），
    // 所以那条放在 UpdateCheckerJsonTest（Robolectric）里跑。

    @Test
    public void onlyStrictlyNewerPrompts() {
        assertTrue(UpdateChecker.isNewer(139, 140));
        assertFalse("同版本不提示", UpdateChecker.isNewer(140, 140));
        assertFalse("降级安装不提示", UpdateChecker.isNewer(140, 139));
    }

    @Test
    public void pan123PageScanPicksHighestCode() {
        // 123 分享页扫文件名：取 code 最大的那个；扫不到就是 null
        String html = "<a>SafeWalk_5.7.18-lx102_140.apk</a><a>SafeWalk_5.7.17-lx102_139.apk</a>";
        UpdateChecker.Release r = UpdateChecker.parsePan123(html);
        assertNotNull(r);
        assertEquals(140, r.getVersionCode());
        assertEquals("5.7.18-lx102", r.getVersionName());
        assertNull("页面里没有包名就当没查到", UpdateChecker.parsePan123("<div>空</div>"));
    }

    @Test
    public void atomFeedScanPicksLatestTag() {
        String xml = "<entry><link href=\"https://github.com/X/X/releases/tag/140-5.7.18-lx102\"/></entry>"
                + "<entry><link href=\"https://github.com/X/X/releases/tag/v5.5.0\"/></entry>";
        UpdateChecker.Release r = UpdateChecker.parseAtom(xml);
        assertNotNull("Atom 里必须能扫出版本", r);
        assertEquals(140, r.getVersionCode());
        assertEquals("5.7.18-lx102", r.getVersionName());
        assertNull(UpdateChecker.parseAtom("<html>无链接</html>"));
    }

    @Test
    public void checkOrderIsGithubThenPan123() throws IOException {
        String checker = read("src/main/java/io/github/wumisc/safewalk/UpdateChecker.kt");
        int gh = checker.indexOf("private fun fetchGithub()");
        int pan = checker.indexOf("private fun fetchPan123()");
        int chain = checker.indexOf("fetchGithub()?.let { return it }");
        assertTrue("必须有 GitHub 查版函数", gh > 0);
        assertTrue("必须有 123 云盘备选查版函数", pan > 0);
        assertTrue("检查链必须先 GitHub", chain > 0 && chain < pan);
        assertTrue("api 失败先换 Atom（不限流）",
                checker.contains("fetchAtom()?.let { return it }") && checker.contains("releases.atom"));
        assertTrue("两条 GitHub 路都不通才落到 123", checker.contains("return fetchPan123()"));
        assertTrue("弹窗两个渠道都列出",
                checker.contains("setPositiveButton(\"GitHub\"") && checker.contains("setNeutralButton(\"123 云盘\""));
    }

    @Test
    public void aboutPageHasUpdateRow() throws IOException {
        String s = read("src/main/res/layout/activity_main.xml");
        int about = s.indexOf("==================== 关于");
        assertTrue("必须有关于页标记", about > 0);
        String aboutPage = s.substring(about);
        assertTrue("关于页必须有「检查更新」入口", aboutPage.contains("btn_check_update"));
        assertTrue("右侧结果文字必须存在", aboutPage.contains("tv_update_state"));
    }

    @Test
    public void twoDownloadChannelsAreBothWired() throws IOException {
        String checker = read("src/main/java/io/github/wumisc/safewalk/UpdateChecker.kt");
        assertTrue("必须有 123 云盘分享地址", checker.contains("share.123pan.cn"));
        assertTrue("必须带提取码参数 pwd", checker.contains("pwd=9266"));
        assertTrue("必须有 GitHub 最新版接口",
                checker.contains("api.github.com/repos/Xposed-Modules-Repo/io.github.wumisc.safewalk/releases/latest"));
        assertTrue("两个按钮都必须接上打开动作",
                checker.contains("openPan123") && checker.contains("openGithub"));
    }

    @Test
    public void autoCheckIsCooledDownToOncePerDay() throws IOException {
        String checker = read("src/main/java/io/github/wumisc/safewalk/UpdateChecker.kt");
        assertTrue("自动检查必须有 24 小时冷却", checker.contains("24L * 3600_000L"));
        assertTrue("手动检查必须能绕过冷却（force 分支）", checker.contains("if (!force && now"));
    }

    @Test
    public void charityDataHasMirrorFallback() throws IOException {
        String repo = read("src/main/java/io/github/wumisc/safewalk/MissingKidsRepository.kt");
        assertTrue("公益数据必须优先走 Pages 镜像", repo.contains("safewalk-diag.pages.dev/api/kids"));
        assertTrue("镜像必须是抓取链第一个源", repo.contains("listOf(MIRROR, RAW, CDN)"));
        assertTrue("照片必须有镜像兜底", repo.contains("api/kidimg?u="));
    }
}
