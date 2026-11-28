package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 【5.7.18】GitHub Release JSON 解析（需要真 org.json 实现，故走 Robolectric）。
 *
 * 只锁一件事：真接口回的那种 JSON 能解析出版本号；坏 JSON / 缺 tag 一律 null。
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateCheckerJsonTest {

    @Test
    public void parsesLatestReleaseJson() {
        String json = "{\"tag_name\":\"141-5.7.19-lx102\",\"name\":\"141\",\"draft\":false}";
        UpdateChecker.Release r = UpdateChecker.parseResponse(json);
        assertNotNull(r);
        assertEquals(141, r.getVersionCode());
        assertEquals("5.7.19-lx102", r.getVersionName());
    }

    @Test
    public void badJsonGivesNull() {
        assertNull(UpdateChecker.parseResponse("not json"));
        assertNull(UpdateChecker.parseResponse("{}"));
        assertNull(UpdateChecker.parseResponse("{\"tag_name\":\"5.6.15\"}"));
    }
}
