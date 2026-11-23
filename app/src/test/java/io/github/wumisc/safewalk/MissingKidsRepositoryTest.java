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
    public void refreshIntervalIs24h() {
        assertEquals(24L * 3600_000L, MissingKidsRepository.REFRESH_MS);
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
        assertTrue("换新后须触发清理", code.contains("prunePhotos(ctx, list)"));
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
}