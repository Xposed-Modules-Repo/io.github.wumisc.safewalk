package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 【5.6.12】通用提醒焦点 JSON 合法性与结构契约（Robolectric 真实 org.json）。
 *
 * 重点锁：无按钮 / 带按钮两种形态都是合法 JSON（旧 Bundle 写法系统直接忽略）；
 * 岛短标签 ≤4 字；带按钮时 islandProperty=2 且 actions/hintInfo 齐全；
 * 正文里的引号/换行被清洗，不破坏 JSON。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FocusNotifyParamTest {

    private static JSONObject p(String title, String content, String tag) throws Exception {
        return new JSONObject(FocusNotifyParam.INSTANCE.json(title, content, tag))
                .getJSONObject("param_v2");
    }

    @Test
    public void noActionMode_validJson_infoIsland() throws Exception {
        JSONObject v = p("SafeWalk 电量预警", "快没电了，赶紧找个充电宝。", "电量");
        assertEquals("safewalk_remind", v.getString("business"));
        assertFalse(v.getBoolean("updatable"));
        assertEquals(1, v.getJSONObject("param_island").getInt("islandProperty"));
        assertEquals("SafeWalk 电量预警", v.getJSONObject("baseInfo").getString("title"));
        assertFalse("无按钮不写 actions", v.has("actions"));
        assertFalse("无按钮不写 hintInfo", v.has("hintInfo"));
    }

    @Test
    public void actionMode_operationIslandWithButtons() throws Exception {
        String s = FocusNotifyParam.INSTANCE.json(
                "SafeWalk 夜间照明", "天黑了，点按钮开手电筒照亮前路", "照明",
                java.util.Collections.singletonList("miui.focus.action_torch"),
                "miui.focus.action_torch");
        JSONObject v = new JSONObject(s).getJSONObject("param_v2");
        assertEquals(2, v.getJSONObject("param_island").getInt("islandProperty"));
        assertEquals(1, v.getJSONArray("actions").length());
        assertEquals("miui.focus.action_torch",
                v.getJSONArray("actions").getJSONObject(0).getString("action"));
        assertEquals("miui.focus.action_torch",
                v.getJSONObject("hintInfo").getJSONObject("actionInfo").getString("action"));
    }

    @Test
    public void islandShortTextWithinLimit() throws Exception {
        JSONObject text = p("SafeWalk 夜间独行", "夜间已经走了 10 分钟。", "独行")
                .getJSONObject("param_island").getJSONObject("bigIslandArea")
                .getJSONObject("imageTextInfoLeft").getJSONObject("textInfo");
        assertTrue(text.getString("title").length() <= FocusNotifyParam.ISLAND_TITLE_MAX);
        assertTrue(text.getString("frontTitle").length() <= FocusNotifyParam.ISLAND_TITLE_MAX);
        assertTrue(text.getString("content").length() <= 8);
    }

    @Test
    public void longTagTruncatedToFour() throws Exception {
        JSONObject v = p("标题", "正文", "超级长的标签");
        assertEquals(4, v.getJSONObject("param_island").getJSONObject("bigIslandArea")
                .getJSONObject("imageTextInfoLeft").getJSONObject("textInfo")
                .getString("title").length());
    }

    @Test
    public void quotesAndNewlinesSanitized_jsonStillValid() throws Exception {
        JSONObject v = p("标题\"带引号", "第一行\n第二行\\反斜杠", "故障");
        assertEquals("标题带引号", v.getJSONObject("baseInfo").getString("title"));
        assertFalse(v.getJSONObject("baseInfo").getString("content").contains("\n"));
    }
}