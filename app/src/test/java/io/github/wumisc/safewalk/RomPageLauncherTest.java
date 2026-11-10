package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * P1 验收：指路层的「选哪条 / 跳过哪条 / 文案跟谁走」。
 *
 * 这三件事都不需要 Android 运行时，所以能在 JVM 单测里锁死。真机上才有意义的
 * 部分（component 到底存不存在）留给 docs/rom-support-matrix.md 的 🟡 项。
 */
public class RomPageLauncherTest {

    private static PageTarget t(TargetKind kind, String spec, String label) {
        return new PageTarget(kind, spec, label);
    }

    // ---------- 1. 顺序即优先级 ----------

    @Test
    public void firstResolvable_takesFirstInDeclarationOrder() {
        // 表里两条小米路径的先后是有意排的：新版在前、旧版在后。
        // 若被手滑调换，真机上会先跳旧版页（可能空白），这条测试就是为了拦住它。
        List<PageTarget> cands = Arrays.asList(
                t(TargetKind.AUTOSTART, "component:a.b/com.a.b.New", "新版"),
                t(TargetKind.AUTOSTART, "component:a.b/com.a.b.Old", "旧版")
        );
        PageTarget hit = RomPageLauncher.firstResolvable(cands, c -> true);
        assertNotNull(hit);
        assertEquals("新版", hit.getLabel());
    }

    @Test
    public void firstResolvable_skipsUnresolvableAndKeepsOrder() {
        List<PageTarget> cands = Arrays.asList(
                t(TargetKind.AUTOSTART, "component:a.b/com.a.b.New", "新版"),
                t(TargetKind.AUTOSTART, "component:a.b/com.a.b.Old", "旧版")
        );
        // 第一条装不了，第二条能装 → 必须选第二条，而不是直接回退
        PageTarget hit = RomPageLauncher.firstResolvable(cands, c -> c.getLabel().equals("旧版"));
        assertNotNull(hit);
        assertEquals("旧版", hit.getLabel());
    }

    @Test
    public void firstResolvable_returnsNullWhenAllFail() {
        List<PageTarget> cands = Arrays.asList(
                t(TargetKind.AUTOSTART, "component:a.b/com.a.b.New", "新版"),
                t(TargetKind.AUTOSTART, "action:android.settings.APPLICATION_DETAILS_SETTINGS", "兜底")
        );
        assertNull(RomPageLauncher.firstResolvable(cands, c -> false));
    }

    @Test
    public void firstResolvable_emptyListIsNullNotCrash() {
        assertNull(RomPageLauncher.firstResolvable(new ArrayList<>(), c -> true));
    }

    // ---------- 2. 哪些 action 必须带 package ----------

    @Test
    public void needsPackageData_onlyForPerAppPages() {
        // 这三条不带 data 会打开「应用列表」而不是「本应用」，用户还得自己找一遍
        assertTrue(RomPageLauncher.needsPackageData(
                "android.settings.APPLICATION_DETAILS_SETTINGS"));
        assertTrue(RomPageLauncher.needsPackageData(
                "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"));
        assertTrue(RomPageLauncher.needsPackageData(
                "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
        // 电池优化总列表是全局页，带 package 反而可能解析失败
        assertFalse(RomPageLauncher.needsPackageData(
                "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"));
        assertFalse(RomPageLauncher.needsPackageData("android.settings.SETTINGS"));
    }

    // ---------- 3. 引导步骤文案跟着判定结果走 ----------

    @Test
    public void labels_haveFixedSixStepsForEveryRom() {
        for (RomId id : RomRegistry.ids()) {
            List<String> labels = OnboardingSteps.labelsFor(id);
            assertEquals(id + " 步骤数应为 6", 6, labels.size());
            // 游标按位置跳转，位置错了会「第 4 步跳到悬浮窗」
            assertTrue(id + " 第 4 步必须是自启动",
                    labels.get(OnboardingSteps.INDEX_AUTOSTART).contains("自启动"));
        }
    }

    @Test
    public void autostartLabel_namesTheDetectedSystemOnly() {
        for (RomId id : RomRegistry.ids()) {
            String label = OnboardingSteps.autostartLabel(id);
            if (id.getHasVendorPages()) {
                // 有私有页的必须写出本系统名：MagicOS 用户不该看到「HyperOS 自启动」
                assertTrue(id + " 文案缺系统名：" + label, label.contains(id.getDisplayName()));
                for (RomId other : RomRegistry.ids()) {
                    if (other != id && other.getHasVendorPages()) {
                        assertFalse(
                                id + " 的文案串到了 " + other + "：" + label,
                                label.contains(other.getDisplayName()));
                    }
                }
            } else {
                // AOSP 明确告诉用户可跳过，否则用户会一直找这个不存在的开关
                assertTrue("AOSP 文案应提示可跳过：" + label, label.contains("可跳过"));
            }
        }
    }

    @Test
    public void autostartDesc_matchesLabelPolicy() {
        for (RomId id : RomRegistry.ids()) {
            String desc = OnboardingSteps.autostartDesc(id);
            if (id.getHasVendorPages()) {
                assertTrue(id + " 说明应含系统名：" + desc, desc.contains(id.getDisplayName()));
            } else {
                assertTrue("AOSP 说明应写可跳过：" + desc, desc.contains("可跳过"));
            }
        }
    }

    // ---------- 4. 表与提示的一致性 ----------

    @Test
    public void routeHint_neverPointsNowhere() {
        for (RomId id : RomRegistry.ids()) {
            for (TargetKind kind : TargetKind.values()) {
                String hint = RomRegistry.routeHint(id, kind);
                assertNotNull(hint);
                assertFalse(id + "/" + kind + " 提示为空", hint.trim().isEmpty());
                if (!id.getHasVendorPages()) {
                    // AOSP 没有的类别必须说清「无需设置」，不能给个空串让 UI 出现空白行
                    assertFalse(
                            id + "/" + kind + " 空类别提示没说明白：" + hint,
                            hint.contains("null"));
                }
            }
        }
        // 有厂商页的四家，自启动提示必须是真实去处而非「未收录」
        for (RomId id : RomRegistry.ids()) {
            if (id.getHasVendorPages()) {
                assertFalse(id + " 自启动应有去处",
                        RomRegistry.routeHint(id, TargetKind.AUTOSTART).contains("未收录"));
            }
        }
    }

    @Test
    public void autostartCandidates_areAllComponentOrActionWithDetailFallback() {
        // 兜底那条（action 形式的应用详情页）必须在每家的自启动候选里：
        // 少了它，RomPageLauncher 全部失败时就没有「至少带用户到应用详情」的落点。
        for (RomId id : RomRegistry.ids()) {
            if (!id.getHasVendorPages()) continue;
            List<PageTarget> cands = RomRegistry.profile(id).pagesOf(TargetKind.AUTOSTART);
            boolean hasFallback = false;
            for (PageTarget c : cands) {
                if ("android.settings.APPLICATION_DETAILS_SETTINGS".equals(c.action())) {
                    hasFallback = true;
                    break;
                }
            }
            assertTrue(id + " 自启动候选缺应用详情页兜底", hasFallback);
        }
    }
}
