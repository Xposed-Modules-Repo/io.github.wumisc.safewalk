package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P0 验收测试：多 ROM 适配层的数据表与判定逻辑。
 *
 * 对应 `docs/rom-adaptation-plan.md` 第 6 节 P0 的验收标准：
 * 「Registry 覆盖每个 RomId（当前五厂商 + 原生），每个 profile 的 cameraPkgs 非空」。
 *
 * 这组测试锁的是**表的完整性**，不是表里 component 名字对不对——
 * 名字对不对只能靠真机 `resolveActivity` 验，写死在单测里反而会因为 ROM 升级
 * 导致测试红、进而被人删掉，那才是真的失守。
 *
 * 所以这里只守四条底线：
 * 1. 六个生态（五厂商 + 原生）一个都不能漏，枚举加了新值测试必须提醒；
 * 2. 除 AOSP 外，每家至少有一条自启动候选（否则引导页第 4 项必然失败）；
 * 3. 所有 spec 格式合法（能交给 ComponentName / Intent 解析）；
 * 4. 未识别设备必须落到 AOSP，绝不能猜成某一家。
 */
public class RomRegistryTest {

    // ---------------------------------------------------------------- 表完整性

    @Test
    public void registryCoversEveryRomId() {
        List<RomId> ids = RomRegistry.ids();
        assertEquals("生态数量变了就要同步改表与文档", RomId.values().length, ids.size());
        for (RomId id : RomId.values()) {
            RomProfile p = RomRegistry.profile(id);
            assertNotNull(id + " 缺少档案", p);
            assertEquals("档案 id 与键不一致", id, p.getId());
        }
    }

    @Test
    public void everyProfileHasCameraPackages() {
        for (RomId id : RomId.values()) {
            RomProfile p = RomRegistry.profile(id);
            assertFalse(id + " 的 cameraPkgs 为空：前台是相机时必须能识别出来，否则小窗会盖住取景框",
                    p.getCameraPkgs().isEmpty());
            for (String pkg : p.getCameraPkgs()) {
                assertTrue(id + " 的相机包名不像包名：" + pkg, pkg.contains("."));
            }
        }
    }

    @Test
    public void vendorProfilesHaveAtLeastOneAutostartPath() {
        for (RomId id : RomId.values()) {
            RomProfile p = RomRegistry.profile(id);
            if (id == RomId.AOSP) {
                assertTrue("AOSP 不该硬凑一个不存在的自启动页", !p.hasAutostart());
                continue;
            }
            assertTrue(id + " 没有任何自启动候选：引导页第 4 项会必然失败",
                    p.hasAutostart());
        }
    }

    @Test
    public void everyVendorProfileHasBatteryPage() {
        for (RomId id : RomId.values()) {
            RomProfile p = RomRegistry.profile(id);
            assertFalse(id + " 缺少电池优化页：四家国产 ROM 都保留了这个标准 action",
                    p.pagesOf(TargetKind.BATTERY).isEmpty());
        }
    }

    // ---------------------------------------------------------------- 格式合法性

    @Test
    public void allTargetsAreWellFormed() {
        for (RomId id : RomId.values()) {
            for (PageTarget t : RomRegistry.profile(id).getPages()) {
                String where = id + " / " + t.getKind() + " / " + t.getSpec();
                assertNotNull(where + " 缺 label", t.getLabel());
                assertFalse(where + " 的 label 为空", t.getLabel().trim().isEmpty());

                if (t.isAction()) {
                    String a = t.action();
                    assertNotNull(a);
                    // 标准设置页都是 android.*；但一加/OPPO 的后台优化页用的是
                    // com.android.settings.action.BACKGROUND_OPTIMIZE —— 它挂在 settings 包下，
                    // 却不是 AOSP 的公开 action。真实存在，所以放行这两个前缀。
                    assertTrue(
                            where + " 的 action 前缀不合法：应以 android. 或 com.android.settings.action. 开头",
                            a.startsWith("android.") || a.startsWith("com.android.settings.action."));
                } else {
                    assertTrue(where + " 不是合法的 pkg/cls", t.looksLikeComponent());
                    String c = t.componentSpec();
                    assertNotNull(c);
                    assertTrue(where + " 的包名段应至少有一个点", c.split("/")[0].contains("."));
                }
            }
        }
    }

    @Test
    public void noDuplicateTargets() {
        for (RomId id : RomId.values()) {
            Set<String> seen = new HashSet<>();
            for (PageTarget t : RomRegistry.profile(id).getPages()) {
                String key = t.getKind() + "|" + t.getSpec();
                assertTrue(id + " 出现重复条目：" + key, seen.add(key));
            }
        }
    }

    // ---------------------------------------------------------------- 防手滑

    @Test
    public void hyperOsKeepsTheTwoKnownAutostartPaths() {
        // 这两条是 3.x 起就在用、线上验证过的路径，重构时不能被误删
        List<String> cs = new ArrayList<>();
        for (PageTarget t : RomRegistry.profile(RomId.HYPEROS).pagesOf(TargetKind.AUTOSTART)) {
            if (!t.isAction()) {
                cs.add(t.componentSpec());
            }
        }
        boolean permcenter = false;
        boolean permission = false;
        for (String c : cs) {
            if (c.contains("com.miui.permcenter.autostart")) permcenter = true;
            if (c.contains("com.miui.securitycenter.permission.autostart")) permission = true;
        }
        assertTrue("小米 permcenter 自启动页丢失", permcenter);
        assertTrue("小米 securitycenter.permission 自启动页丢失", permission);
    }

    @Test
    public void everyVendorProfileExplainsItsPitfall() {
        for (RomId id : RomId.values()) {
            String note = RomRegistry.profile(id).getNote();
            assertNotNull(id + " 缺少 note", note);
            assertTrue(id + " 的 note 太短，应写清这一家最容易踩的坑", note.length() > 10);
        }
    }

    // ---------------------------------------------------------------- 判定

    @Test
    public void detectorMapsMainstreamVendors() {
        assertEquals(RomId.HYPEROS, RomDetector.detect("Xiaomi", "Redmi", "mondrian"));
        assertEquals(RomId.MAGICOS, RomDetector.detect("HONOR", "HONOR", "BVL-N49"));
        assertEquals(RomId.COLOROS, RomDetector.detect("OPPO", "OPPO", "CPH2581"));
        assertEquals(RomId.ORIGINOS, RomDetector.detect("vivo", "vivo", "V2309A"));
        assertEquals(RomId.AOSP, RomDetector.detect("Google", "google", "husky"));
    }

    @Test
    public void detectorHandlesSubBrands() {
        assertEquals("POCO 属 HyperOS", RomId.HYPEROS,
                RomDetector.detect("Xiaomi", "POCO", "vermeer"));
        assertEquals("一加属 ColorOS", RomId.COLOROS,
                RomDetector.detect("OnePlus", "OnePlus", "CPH2449"));
        assertEquals("realme 属 ColorOS", RomId.COLOROS,
                RomDetector.detect("realme", "realme", "RMX3741"));
        assertEquals("iQOO 属 OriginOS", RomId.ORIGINOS,
                RomDetector.detect("vivo", "iQOO", "PD2301"));
    }

    @Test
    public void detectorIsCaseInsensitiveAndTolerant() {
        assertEquals(RomId.HYPEROS, RomDetector.detect("XIAOMI", null, null));
        assertEquals(RomId.HYPEROS, RomDetector.detect(null, "pOcO", null));
        assertEquals("只有 product 也要能认", RomId.COLOROS,
                RomDetector.detect(null, null, "OPLUS_CPH2581"));
    }

    @Test
    public void detectorNeverGuessesOnUnknownVendor() {
        assertEquals("三星不该被猜成国产四家", RomId.AOSP,
                RomDetector.detect("samsung", "samsung", "SM-S918B"));
        assertEquals("摩托罗拉同理", RomId.AOSP,
                RomDetector.detect("motorola", "motorola", "lynx"));
        assertEquals("全空也要安全", RomId.AOSP, RomDetector.detect(null, null, null));
        assertEquals(RomId.AOSP, RomDetector.detect("", "", ""));
    }

    // ---------------------------------------------------------------- 合并与文案

    @Test
    public void cameraPackagesAreUnionNotReplacement() {
        Set<String> merged = RomRegistry.cameraPkgs(RomId.HYPEROS);
        // 通用表里的三星必须还在：并集而不是替换
        assertTrue("通用表被厂商表覆盖了", merged.contains("com.samsung.android.camera"));
        // 厂商专属的也要在
        assertTrue(merged.contains("com.miui.camera"));

        // 四家的相机包名并集后应覆盖四家主力包
        Set<String> all = new HashSet<>();
        for (RomId id : RomId.values()) {
            all.addAll(RomRegistry.cameraPkgs(id));
        }
        for (String must : new String[]{
                "com.miui.camera", "com.hihonor.camera",
                "com.oplus.camera", "com.vivo.camera", "com.android.camera2"}) {
            assertTrue("并集缺少 " + must, all.contains(must));
        }
    }

    @Test
    public void describeStaysHonest() {
        for (RomId id : RomId.values()) {
            String d = RomRegistry.describe(id);
            assertTrue("摘要要写清是哪一家：" + d,
                    d.contains(RomRegistry.profile(id).getId().getDisplayName()));
            assertFalse("不许承诺「完美」：" + d, d.contains("完美"));
            assertFalse("不许承诺「100%」：" + d, d.contains("100%"));
        }
    }
}
