package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [PlaceArrivalMonitor.adminPrefix] 日志地址粗粒度裁剪的纯函数契约。
 *
 * 反编码地址会随诊断日志离开设备，落盘前必须去掉区、街道、路名、门牌等可定位到
 * 具体住址的细址；只保留省/市级前缀，识别不出行政区划时不落细址。
 */
public class PlaceArrivalMonitorAdminPrefixTest {

    @Test
    public void prefix_keepsProvinceAndCity() {
        String addr = "黑龙江省齐齐哈尔市富拉尔基区群众街富强小区";
        assertEquals("黑龙江省齐齐哈尔市", PlaceArrivalMonitor.adminPrefix(addr));
    }

    @Test
    public void prefix_stripsDistrictsInMunicipality() {
        assertEquals("北京市", PlaceArrivalMonitor.adminPrefix("北京市朝阳区建国路88号"));
    }

    @Test
    public void prefix_keepsAutonomousRegionAndCity() {
        assertEquals(
            "内蒙古自治区呼和浩特市",
            PlaceArrivalMonitor.adminPrefix("内蒙古自治区呼和浩特市新城区成吉思汗大街10号")
        );
    }

    @Test
    public void prefix_nullOrBlank_isDash() {
        assertEquals("-", PlaceArrivalMonitor.adminPrefix(null));
        assertEquals("-", PlaceArrivalMonitor.adminPrefix("   "));
    }

    @Test
    public void prefix_unrecognizedArea_isDash() {
        // 没有可识别的行政区划词时，不落细址，避免把地名整体带出
        assertEquals("-", PlaceArrivalMonitor.adminPrefix("某某大学图书馆北侧"));
    }

    @Test
    public void gridTag_isStableShortHexWithoutCoordinates() {
        String tag = PlaceArrivalMonitor.gridTag("47.12,123.45");
        assertEquals("同一网格哈希必须稳定", tag, PlaceArrivalMonitor.gridTag("47.12,123.45"));
        assertTrue("哈希应为 8 位十六进制", tag.matches("[0-9a-f]{8}"));
        assertFalse("哈希不得保留原始坐标片段", tag.contains("47.12"));
        assertFalse("哈希不得保留原始坐标片段", tag.contains("123.45"));
    }

    @Test
    public void gridTag_differsForDifferentGrid() {
        assertNotEquals(
            PlaceArrivalMonitor.gridTag("47.12,123.45"),
            PlaceArrivalMonitor.gridTag("47.13,123.46")
        );
    }
}
