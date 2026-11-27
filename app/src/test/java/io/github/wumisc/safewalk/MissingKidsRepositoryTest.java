package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 【5.6.13 公益】kids.json 解析与展示契约（Robolectric 真实 org.json）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MissingKidsRepositoryTest {

    private static final String JSON = "{\"items\":["
            + "{\"name\":\"牛乾坤\",\"gender\":\"男\","
            + "\"lost_time\":\"1995-08-16\",\"lost_place\":\"河北省,邯郸市,丛台区\","
            + "\"feature\":\"额头圆形\",\"url\":\"https://baobeihuijia.com/x.html\","
            + "\"photo\":\"https://baobeihuijia.com/p.png\"},"
            + "{\"name\":\"李茜\",\"gender\":\"女\","
            + "\"lost_time\":\"2001-11-06\",\"lost_place\":\"湖南省,长沙市,市辖区\","
            + "\"feature\":\"短发\",\"url\":\"https://baobeihuijia.com/y.html\","
            + "\"photo\":\"https://baobeihuijia.com/q.png\"}]}";

    @Test
    public void parseLatestItem() {
        MissingKidsRepository.Kid k = MissingKidsRepository.parseKid(JSON);
        assertNotNull(k);
        assertEquals("牛乾坤", k.getName());
        assertEquals("https://baobeihuijia.com/x.html", k.getUrl());
    }

    /** 列表轮换：四条互不重复；两天整批前进 4 位；条目不足时只取全部且不重复。 */
    @Test
    public void listIndicesRotatesBySlots() {
        long day = 100L;
        long anchorMs = day * 24L * 3600_000L;
        java.util.List<Integer> first = MissingKidsRepository.listIndices(anchorMs, day, 7, 4);
        assertEquals(4, first.size());
        assertEquals(java.util.List.of(0, 1, 2, 3), first);

        // 两天后整批前进 4 位
        java.util.List<Integer> next = MissingKidsRepository.listIndices(
                anchorMs + 48L * 3600_000L, day, 7, 4);
        assertEquals(java.util.List.of(4, 5, 6, 0), next);

        // 同一槽内不变（第二天还没到换批的时刻）
        assertEquals(next, MissingKidsRepository.listIndices(
                anchorMs + 70L * 3600_000L, day, 7, 4));

        // 条目只有 3 条：取 3 条、不重复
        java.util.List<Integer> three = MissingKidsRepository.listIndices(anchorMs, day, 3, 4);
        assertEquals(3, three.size());
        assertEquals(3, new java.util.HashSet<>(three).size());
    }

    @Test
    public void parseListReturnsTwoItems() {
        java.util.List<MissingKidsRepository.Kid> list = MissingKidsRepository.parseList(JSON);
        assertEquals(2, list.size());
        assertEquals("牛乾坤", list.get(0).getName());
        assertEquals("李茜", list.get(1).getName());
    }

    @Test
    public void subtitleCompressesPlace() {
        MissingKidsRepository.Kid k = MissingKidsRepository.parseKid(JSON);
        String s = MissingKidsRepository.subtitle(k);
        assertTrue(s.contains("男"));
        assertTrue(s.contains("1995-08-16"));
        assertTrue("地点逗号应压缩为空格", s.contains("河北省 邯郸市"));
    }

    @Test
    public void emptyOrBrokenReturnsNull() {
        assertNull(MissingKidsRepository.parseKid("{\"items\":[]}"));
        assertNull(MissingKidsRepository.parseKid("not json"));
        assertNull(MissingKidsRepository.parseKid("{}"));
    }

    @Test
    public void refreshIntervalIs48h() {
        assertEquals(48L * 3600_000L, MissingKidsRepository.REFRESH_MS);
    }

    /** 【省流量】取不到数据后必须退避 6 小时，否则每次进首页都会重新打两轮请求。 */
    @Test
    public void failedFetchBacksOffSixHours() {
        assertEquals(6L * 3600_000L, MissingKidsRepository.RETRY_BACKOFF_MS);
    }

    /** 【省流量】照片缓存必须有上限，换新时淘汰最久未用，不能无限堆积。 */
    @Test
    public void photoCacheCappedAtEight() throws Exception {
        java.nio.file.Path src = java.nio.file.Paths.get(
                "src/main/java/io/github/wumisc/safewalk/MissingKidsRepository.kt");
        if (!java.nio.file.Files.isRegularFile(src)) {
            src = java.nio.file.Paths.get("app", src.toString());
        }
        String code = new String(java.nio.file.Files.readAllBytes(src),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("照片缓存上限必须为 8", code.contains("MAX_PHOTOS = 8"));
        assertTrue("命中缓存须刷新 LRU 时间戳", code.contains("cache.setLastModified("));
        assertTrue("换新后须触发清理", code.contains("prunePhotos(ctx, snap.kids)"));
    }

    @Test
    public void sampleSizeDownsamplesLargeImage() {
        // 512×512 目标 128：两次折半到 128，inSampleSize=4
        assertEquals(4, MissingKidsRepository.sampleSize(512, 512, 128));
        // 小图不放大
        assertEquals(1, MissingKidsRepository.sampleSize(96, 96, 128));
        // 非法尺寸兜底 1
        assertEquals(1, MissingKidsRepository.sampleSize(0, 0, 128));
    }

    @Test
    public void photoFileNameIsSafeAndStable() {
        String f = MissingKidsRepository.photoFileName("https://baobeihuijia.com/p/a%20b.png?x=1");
        assertTrue(f.startsWith("kid_"));
        assertTrue(f.endsWith(".jpg"));
        assertFalse(f.contains("%"));
        assertFalse(f.contains("?"));
        // 同一 URL 文件名稳定
        assertEquals(f, MissingKidsRepository.photoFileName("https://baobeihuijia.com/p/a%20b.png?x=1"));
    }

    /** 【5.6.19】只有「首次」或「真拉到网络新数据」才重置锚点，覆盖安装不跳回第一对。 */
    @Test
    public void anchorResetsOnlyOnFreshNetworkData() {
        // 首次：没有锚点 → 重置到今天
        assertEquals(100L, MissingKidsRepository.anchorDayOf(-1L, "", 100L, "v1", false));
        // 包内快照版本变了但没联网（fresh=false）→ 位置保持，不跳回第一对
        assertEquals(90L, MissingKidsRepository.anchorDayOf(90L, "v1", 100L, "v2", false));
        // 真拉到网络新数据（fresh=true）且版本变了 → 重置
        assertEquals(100L, MissingKidsRepository.anchorDayOf(90L, "v1", 100L, "v2", true));
        // 网络数据版本没变（例行刷新）→ 不重置
        assertEquals(90L, MissingKidsRepository.anchorDayOf(90L, "v1", 100L, "v1", true));
    }

    @Test
    public void bundledSnapshotHasFullRotation() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/assets/kids.json");
        if (!java.nio.file.Files.isRegularFile(p)) {
            p = java.nio.file.Paths.get("app", "src/main/assets/kids.json");
        }
        assertTrue("必须内置 assets/kids.json", java.nio.file.Files.isRegularFile(p));
        String json = new String(java.nio.file.Files.readAllBytes(p),
                java.nio.charset.StandardCharsets.UTF_8);
        MissingKidsRepository.Snapshot snap = MissingKidsRepository.parseSnapshot(json);
        assertNotNull(snap);
        assertTrue("内置快照条数应 ≥ 一整轮", snap.getKids().size() >= 3);
        assertFalse("内置快照必须带数据版本 updated", snap.getUpdated().isEmpty());
        for (MissingKidsRepository.Kid k : snap.getKids()) {
            assertFalse("每条都要有名字", k.getName().isEmpty());
            assertTrue("每条都要有照片地址", k.getPhoto().startsWith("https://"));
            assertTrue("每条都要有登记页", k.getUrl().startsWith("https://"));
        }
    }
}