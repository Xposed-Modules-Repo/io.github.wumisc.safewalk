package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Flyme（魅族 / 魅蓝）适配的回归锁。
 *
 * ## 为什么单独一个测试类
 *
 * Flyme 是第 6 家，加它的时候同时动了四处：RomId 枚举、RomDetector 关键字表、
 * RomRegistry 档案、首页芯片布局。任何一处漏改都会出现「识别成魅族，但点芯片
 * 什么都不跳」这类半生效状态——单测把这个组合锁死，比人工点一遍可靠。
 *
 * ## 为什么测 mblu
 *
 * 魅蓝（mblu）是魅族的子品牌，`Build.BRAND` 常见值是 `mblu` 而不是 `meizu`。
 * 只写 meizu 会让魅蓝用户落到「未识别」，自启动跳不过去。
 */
public class FlymeAdaptationTest {

    // ---------------------------------------------------------------- 识别

    @Test
    public void detectsMeizuByManufacturer() {
        assertEquals(RomId.FLYME, RomDetector.detect("Meizu", "meizu", "m1872"));
    }

    @Test
    public void detectsFlymeByProduct() {
        // 刷过 Flyme 的非魅族硬件，manufacturer 可能不变，只能靠 product 里的 flyme
        assertEquals(RomId.FLYME, RomDetector.detect(null, null, "flyme"));
    }

    @Test
    public void detectsMbluBrand() {
        // 魅蓝的品牌字段是 mblu，只写 meizu 会漏
        assertEquals(RomId.FLYME, RomDetector.detect("Meizu", "mblu", "M1852"));
    }

    @Test
    public void meizuIsNotUnrecognized() {
        assertFalse(RomDetector.isUnrecognized("Meizu", "meizu", "m1872"));
        assertFalse(RomDetector.isUnrecognized(null, "mblu", null));
    }

    // ---------------------------------------------------------------- 档案

    @Test
    public void flymeHasVendorPages() {
        // AOSP 没有自启动概念；Flyme 必须有，否则用户开不了自启动
        assertTrue(RomId.FLYME.getHasVendorPages());
    }

    @Test
    public void flymeAutostartPointsToMeizuSafe() {
        RomProfile p = RomRegistry.profile(RomId.FLYME);
        assertNotNull(p);
        PageTarget first = p.pagesOf(TargetKind.AUTOSTART).get(0);
        assertNotNull("Flyme 自启动首选必须是组件形式，不能直接给兜底 action", first.componentSpec());
        assertTrue("Flyme 自启动首选应落在 com.meizu.safe：" + first.componentSpec(),
                first.componentSpec().startsWith("com.meizu.safe/"));
    }

    @Test
    public void flymeAutostartAlwaysHasFallback() {
        // 厂商页路径会随版本漂移，最后一条必须是标准应用详情页，保证怎么都能点到
        java.util.List<PageTarget> list = RomRegistry.profile(RomId.FLYME).pagesOf(TargetKind.AUTOSTART);
        boolean hasDetails = false;
        for (PageTarget t : list) {
            String a = t.action();
            if (a != null && a.equals("android.settings.APPLICATION_DETAILS_SETTINGS")) hasDetails = true;
        }
        assertTrue("Flyme 自启动缺少兜底详情页", hasDetails);
    }

    @Test
    public void flymeCameraPackageRegistered() {
        assertTrue(RomRegistry.cameraPkgs(RomId.FLYME).contains("com.meizu.camera"));
    }

    @Test
    public void flymeChipShortNameIsTwoChars() {
        // 首页芯片两行四列，每格约 75dp；「魅族」两个字正好，四个字会溢出
        assertEquals("魅族", RomId.FLYME.getShortName());
    }

    // ---------------------------------------------------------------- 不回归

    @Test
    public void addingFlymeDidNotStealOtherVendors() {
        // 加关键字最容易出的事故是「越界命中」，把别家抢过来
        assertEquals(RomId.HYPEROS, RomDetector.detect("Xiaomi", "Redmi", "mondrian"));
        assertEquals(RomId.MAGICOS, RomDetector.detect("HONOR", "HONOR", "ANY"));
        assertEquals(RomId.COLOROS, RomDetector.detect("OPPO", "OPPO", "PDEM30"));
        assertEquals(RomId.ORIGINOS, RomDetector.detect("vivo", "vivo", "V2049A"));
        assertEquals(RomId.HARMONYOS, RomDetector.detect("HUAWEI", "HUAWEI", "ALN-AL00"));
    }

    @Test
    public void everyRomIdHasProfile() {
        // 新增枚举值后 PROFILES 漏一项，RomRegistry.profile() 会抛 error 直接崩
        for (RomId id : RomId.values()) {
            assertNotNull(id + " 缺档案", RomRegistry.profile(id));
        }
        // 5.2.0 加了三星期一家，7 → 8
        assertEquals(8, RomId.values().length);
    }
}
