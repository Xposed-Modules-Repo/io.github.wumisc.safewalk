package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 三星 One UI 适配的回归锁（5.2.0 新增）。
 *
 * ## 为什么是第 7 家
 *
 * 前面六家（小米、荣耀、OPPO、vivo、华为、魅族）都是国产 ROM，结构类似：都有
 * 「自启动管理」这个独立开关。三星不一样。
 *
 * 它**没有自启动开关**，用「设备维护 → 电池 → 后台使用限制」里的
 * 「休眠应用 / 深度休眠应用」名单控制冻结。语义上等价，但入口、文案、能做什么
 * 全都不一样 —— 拿国产 ROM 的表去套，用户会点进一个不存在的页面。
 *
 * ## 以前是什么状态
 *
 * 三星落到 [RomId.AOSP]，首页那行注明直接写「本机 UI：原生」。
 * S24 Ultra 用户打开设置页看到自己手机上明明写着 One UI 8，却被告知是原生 ——
 * 一句明确错误的话，比不写更糟。这是 [RomRegistry] 里「三处不能只写一处」的代价。
 */
public class OneUiAdaptationTest {

    // ---------------------------------------------------------------- 识别

    @Test
    public void detectsSamsungByManufacturer() {
        // S24 Ultra 国行：SM-S9280；S23 Ultra：SM-S9180
        assertEquals(RomId.ONEUI, RomDetector.detect("samsung", "samsung", "e3q"));
        assertEquals(RomId.ONEUI, RomDetector.detect("samsung", "samsung", "SM-S9280"));
    }

    @Test
    public void detectsOneUiByProduct() {
        // 刷过 One UI 的非三星硬件 / 工程机，manufacturer 可能为空
        assertEquals(RomId.ONEUI, RomDetector.detect(null, null, "oneui"));
        assertEquals(RomId.ONEUI, RomDetector.detect("SAMSUNG", "samsung", "b0q"));
    }

    @Test
    public void samsungIsNotUnrecognized() {
        assertFalse(RomDetector.isUnrecognized("samsung", "samsung", "SM-S9280"));
        assertFalse(RomDetector.isUnrecognized(null, "samsung", null));
    }

    /** 加三星不能把别家抢过来：`samsung`/`oneui` 两个关键字都不该命中其他厂商 */
    @Test
    public void addingOneUiDidNotStealOtherVendors() {
        assertEquals(RomId.HYPEROS, RomDetector.detect("Xiaomi", "Redmi", "mondrian"));
        assertEquals(RomId.MAGICOS, RomDetector.detect("HONOR", "HONOR", "ANY"));
        assertEquals(RomId.COLOROS, RomDetector.detect("OPPO", "OPPO", "PDEM30"));
        assertEquals(RomId.ORIGINOS, RomDetector.detect("vivo", "vivo", "V2049A"));
        assertEquals(RomId.FLYME, RomDetector.detect("Meizu", "meizu", "m1872"));
        assertEquals(RomId.HARMONYOS, RomDetector.detect("HUAWEI", "HUAWEI", "ALN-AL00"));
        // 摩托罗拉 / 索尼仍然「未识别」，不能被顺手吞掉
        assertTrue(RomDetector.isUnrecognized("motorola", "moto", "eagle"));
        assertTrue(RomDetector.isUnrecognized("Sony", "sony", "XQ-DQ72"));
    }

    // ---------------------------------------------------------------- 档案

    @Test
    public void oneUiHasVendorPages() {
        assertTrue("三星必须有厂商页，否则用户找不到「后台使用限制」",
                RomId.ONEUI.getHasVendorPages());
    }

    /**
     * 三星自启动首选必须落在 `com.samsung.android.lool`。
     *
     * 这一条锁的是「别把别家的包名抄过来」—— 之前 COLOROS 段就误收过
     * `com.oplus.safecenter` 之外的旧路径，跳转直接失败。
     */
    @Test
    public void oneUiAutostartPointsToDeviceCare() {
        RomProfile p = RomRegistry.profile(RomId.ONEUI);
        assertNotNull(p);
        PageTarget first = p.pagesOf(TargetKind.AUTOSTART).get(0);
        assertNotNull("三星自启动首选必须是组件形式", first.componentSpec());
        assertTrue("三星自启动首选应落在 com.samsung.android.lool：" + first.componentSpec(),
                first.componentSpec().startsWith("com.samsung.android.lool/"));
        assertTrue("组件必须是 BatteryActivity（后台使用限制就在这里）",
                first.componentSpec().contains("BatteryActivity"));
    }

    /** 三星没有自启动开关，两个语义指向同一页面是**设计**，不是漏写 */
    @Test
    public void oneUiAutostartAndBatteryShareTheSameEntry() {
        List<PageTarget> auto = RomRegistry.profile(RomId.ONEUI).pagesOf(TargetKind.AUTOSTART);
        List<PageTarget> battery = RomRegistry.profile(RomId.ONEUI).pagesOf(TargetKind.BATTERY);
        String autoFirst = auto.get(0).componentSpec();
        String batteryFirst = battery.get(0).componentSpec();
        assertEquals("三星的「自启动」与「电池」确实是同一个入口",
                autoFirst, batteryFirst);
    }

    @Test
    public void oneUiAutostartAlwaysHasFallback() {
        // 三星改过两次包内路径 + 一次包名，候选会失效；最后一条必须是标准应用详情页
        List<PageTarget> list = RomRegistry.profile(RomId.ONEUI).pagesOf(TargetKind.AUTOSTART);
        boolean hasDetails = false;
        for (PageTarget t : list) {
            String a = t.action();
            if (a != null && a.equals("android.settings.APPLICATION_DETAILS_SETTINGS")) {
                hasDetails = true;
            }
        }
        assertTrue("三星自启动缺少兜底详情页", hasDetails);
    }

    @Test
    public void oneUiHasNoFakeBackgroundPopupPage() {
        // 三星没有「后台弹出界面」这个独立开关。这里只能是应用详情页兜底，
        // 不能硬凑一个 component —— 那会把用户送到一个打不开的页面
        List<PageTarget> list = RomRegistry.profile(RomId.ONEUI)
                .pagesOf(TargetKind.BACKGROUND_POPUP);
        assertFalse("三星不该有「后台弹出界面」的私有组件", list.isEmpty());
        for (PageTarget t : list) {
            assertTrue("三星后台弹窗只能走 action 兜底，实际：" + t.getSpec(), t.isAction());
        }
    }

    @Test
    public void oneUiCameraPackageRegistered() {
        assertTrue(RomRegistry.cameraPkgs(RomId.ONEUI).contains("com.sec.android.app.camera"));
    }

    @Test
    public void oneUiChipShortNameIsTwoChars() {
        // 首页三行三列，每格约 100dp；「三星」两个字整齐
        assertEquals("三星", RomId.ONEUI.getShortName());
        assertEquals("One UI", RomId.ONEUI.getDisplayName());
    }

    // ---------------------------------------------------------------- 不回归

    @Test
    public void everyRomIdStillHasProfile() {
        for (RomId id : RomId.values()) {
            assertNotNull(id + " 缺档案", RomRegistry.profile(id));
        }
        assertEquals("5.2.0 起共 8 个生态", 8, RomId.values().length);
    }

    /** 手动指定必须能解析到 ONEUI（用户在自动识别失灵时的逃生口） */
    @Test
    public void overrideAcceptsOneUi() {
        assertEquals(RomId.ONEUI, RomSettings.overrideId("ONEUI"));
        // 大小写 / 空格不敏感
        assertEquals(RomId.ONEUI, RomSettings.overrideId("  oneui "));
    }
}
