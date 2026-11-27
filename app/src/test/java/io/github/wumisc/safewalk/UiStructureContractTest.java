package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 版面结构契约（136 起）：三页导航 + 置顶条两处 + 功能页分层顺序。
 *
 * 这些都是「用户明确提过」的结构，改布局很容易顺手改掉，
 * 所以钉成测试：谁动的、动在哪，一跑就报。
 */
public class UiStructureContractTest {

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(Path.of(rel)), StandardCharsets.UTF_8);
    }

    /** 置顶条必须同时出现在首页与关于页：出问题时用户未必停在首页。 */
    @Test
    public void pinBarOnHomeAndAbout() throws Exception {
        String xml = read("src/main/res/layout/activity_main.xml");
        int count = xml.split("layout=\"@layout/view_pin_bar\"", -1).length - 1;
        assertEquals("置顶条必须被 include 两次（首页 + 关于）", 2, count);
        assertTrue("首页那份要有 id pin_home", xml.contains("R.id.pin_home") || xml.contains("@+id/pin_home"));
        assertTrue("关于页那份要有 id pin_about", xml.contains("@+id/pin_about"));
    }

    /** 首页只留「状态 + 总开关 + 公益卡」：三个二级入口都必须已经搬走，不能两边都留一份。 */
    @Test
    public void featuresMovedOutOfHome() throws Exception {
        String xml = read("src/main/res/layout/activity_main.xml");
        int home = xml.indexOf("@+id/page_settings");
        int features = xml.indexOf("@+id/page_features");
        int about = xml.indexOf("@+id/page_about");
        assertTrue("三个页面都要在", home > 0 && features > home && about > features);
        String homeBlock = xml.substring(home, features);
        assertTrue("首页要有总开关（进门第一眼就得知道开没开）", homeBlock.contains("@+id/tile_enable"));
        assertTrue("首页不该再有智能提醒入口", !homeBlock.contains("@+id/tile_smart"));
        assertTrue("首页不该再有行走模式入口", !homeBlock.contains("@+id/tile_walk"));
        assertTrue("首页不该再有急救卡入口", !homeBlock.contains("@+id/tile_emergency"));
        String featureBlock = xml.substring(features, about);
        assertTrue("功能页不该再放一份总开关", !featureBlock.contains("@+id/tile_enable"));
        assertTrue("功能页要有智能提醒", featureBlock.contains("@+id/tile_smart"));
        assertTrue("功能页要有行走模式", featureBlock.contains("@+id/tile_walk"));
        assertTrue("功能页要有急救卡", featureBlock.contains("@+id/tile_emergency"));
    }

    /** 功能页的分层顺序：智能提醒 → 行走模式 → 急救卡。 */
    @Test
    public void featuresOrder() throws Exception {
        String xml = read("src/main/res/layout/activity_main.xml");
        int smart = xml.indexOf("@+id/tile_smart\"");
        int walk = xml.indexOf("@+id/tile_walk\"");
        int emergency = xml.indexOf("@+id/tile_emergency\"");
        assertTrue("智能提醒在行走模式之前", smart < walk);
        assertTrue("行走模式在急救卡之前", walk < emergency);
    }

    /** 导航必须是三格：两格时候「功能」没地方放。 */
    @Test
    public void navHasThreeTabs() throws Exception {
        String nav = read("src/main/java/io/github/wumisc/safewalk/MainNav.kt");
        assertTrue(nav.contains("tabsCount = 3"));
        assertTrue(nav.contains("\"首页\""));
        assertTrue(nav.contains("\"功能\""));
        assertTrue(nav.contains("\"关于\""));
    }
}
