package io.github.wumisc.safewalk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * P2 验收：按开源项目（judemanutd/AutoStarter）核对过的 OEM 路径不能又被删掉。
 *
 * 这几条断言看起来「只是在数条目」，实际守的是**真实机型的覆盖率**：
 * · ColorOS 的 `permission.startup` 是 ColorOS 11 及更早版本的唯一入口，
 *   新路径 `startupapp` 在那些机器上不存在；
 * · iQOO 的自启动管理在自己独立的 `com.iqoo.secure` 包里，
 *   `com.vivo.permissionmanager` 在部分 iQOO 机型上没有对应 Activity；
 * · 老荣耀（MagicUI）仍用华为的 `com.huawei.systemmanager`。
 *
 * 任何一次「表太长，精简一下」的改动，都会先撞到这里。
 */
public class RomOemCandidatesTest {

    private static boolean has(RomId id, TargetKind kind, String pkgAndCls) {
        List<PageTarget> list = RomRegistry.profile(id).pagesOf(kind);
        for (PageTarget t : list) {
            String spec = t.componentSpec();
            if (pkgAndCls.equals(spec)) return true;
        }
        return false;
    }

    private static boolean hasAction(RomId id, TargetKind kind, String action) {
        for (PageTarget t : RomRegistry.profile(id).pagesOf(kind)) {
            if (action.equals(t.action())) return true;
        }
        return false;
    }

    @Test
    public void coloros_keepsBothOldAndNewAutostartPages() {
        assertTrue("ColorOS 11 及更早的 permission.startup 入口丢了",
                has(RomId.COLOROS, TargetKind.AUTOSTART,
                        "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity"));
        assertTrue("新版 oplus startupapp 入口丢了",
                has(RomId.COLOROS, TargetKind.AUTOSTART,
                        "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity"));
        assertTrue("ColorOS 7 及更早的 com.oppo.safe 入口丢了",
                has(RomId.COLOROS, TargetKind.AUTOSTART,
                        "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity"));
    }

    @Test
    public void coloros_coversOnePlus() {
        assertTrue("一加（氧 OS）的链式启动页丢了",
                has(RomId.COLOROS, TargetKind.AUTOSTART,
                        "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
        assertTrue("一加的后台优化 action 丢了",
                hasAction(RomId.COLOROS, TargetKind.AUTOSTART,
                        "com.android.settings.action.BACKGROUND_OPTIMIZE"));
    }

    @Test
    public void originos_coversIqooOwnSecurityCenter() {
        assertTrue("iQOO 的白名单页丢了",
                has(RomId.ORIGINOS, TargetKind.AUTOSTART,
                        "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
        assertTrue("iQOO 旧版后台启动页丢了",
                has(RomId.ORIGINOS, TargetKind.AUTOSTART,
                        "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"));
        assertTrue("vivo 原生入口丢了",
                has(RomId.ORIGINOS, TargetKind.AUTOSTART,
                        "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
    }

    @Test
    public void magicos_coversMagicUiEraHonor() {
        assertTrue("MagicOS 7+ 的 hihonor 入口丢了",
                has(RomId.MAGICOS, TargetKind.AUTOSTART,
                        "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
        assertTrue("MagicUI 时代的华为管家入口丢了",
                has(RomId.MAGICOS, TargetKind.AUTOSTART,
                        "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
    }

    @Test
    public void everyVendorAutostartCandidateIsInItsOwnEcosystem() {
        // 候选表里跨生态串门的包名会让「按品牌路线」失效：例如 ColorOS 表里混入
        // 小米安全中心，这台机器上必然解析失败，白白多一次尝试。
        String[][] forbidden = {
                {"com.miui.securitycenter", "COLOROS"},
                {"com.miui.securitycenter", "MAGICOS"},
                {"com.miui.securitycenter", "ORIGINOS"},
                {"com.oplus.safecenter", "HYPEROS"},
                {"com.iqoo.secure", "COLOROS"},
        };
        for (String[] pair : forbidden) {
            String pkg = pair[0];
            RomId id = RomId.valueOf(pair[1]);
            for (PageTarget t : RomRegistry.profile(id).pagesOf(TargetKind.AUTOSTART)) {
                String spec = t.componentSpec();
                if (spec == null) continue;
                assertTrue(id + " 的自启动候选串到了别的生态：" + spec, !spec.startsWith(pkg + "/"));
            }
        }
    }

    @Test
    public void cameraUnionCoversEveryEcosystem() {
        // 并集要覆盖六家各自的相机包；漏一家就是「那家用户开相机时小窗不收起」
        String[][] expected = {
                {"HYPEROS", "com.miui.camera"},
                {"MAGICOS", "com.hihonor.camera"},
                {"COLOROS", "com.oplus.camera"},
                {"ORIGINOS", "com.vivo.camera"},
                {"AOSP", "com.android.camera2"},
        };
        for (String[] pair : expected) {
            RomId id = RomId.valueOf(pair[0]);
            assertTrue(id + " 的相机包名缺失：" + pair[1],
                    RomRegistry.cameraPkgs(id).contains(pair[1]));
        }
        // 通用表里补的跨生态包名要对每一家都可见（并集语义）
        for (RomId id : RomRegistry.ids()) {
            if (!RomRegistry.cameraPkgs(id).contains("com.sec.android.app.camera")) {
                throw new AssertionError(id + " 没拿到三星相机包名，并集没生效");
            }
        }
    }
}
