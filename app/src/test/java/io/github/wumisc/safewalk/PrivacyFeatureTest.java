package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 【5.5.0 隐私】桌面图标隐藏 / 最近任务隐藏 的回归测试。
 *
 * 重点不是「开关能点」，而是三个最容易写错、且写错后代价极大的点：
 *  ① 两个偏好键能可靠持久化（且绝不混入 ConfigProvider 白名单被导出）；
 *  ② 双 alias 的启用状态计算必须「一开一关」，且幂等；
 *  ③ 隐藏态下恢复入口仍可达 —— 绝不能出现「隐藏后再也打不开」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PrivacyFeatureTest {

    // ==================== 偏好键持久化 ====================

    @Test
    public void preferenceKeys_roundTripInSharedPreferences() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE);
        sp.edit()
                .remove(SafeWalkPrefKey.hideLauncherIcon)
                .remove(SafeWalkPrefKey.hideFromRecentTasks)
                .commit();

        // 默认必须是「不隐藏」：老用户升级上来不能一装就丢图标
        assertFalse(SafePrefs.getBool(sp, SafeWalkPrefKey.hideLauncherIcon, false));
        assertFalse(SafePrefs.getBool(sp, SafeWalkPrefKey.hideFromRecentTasks, false));

        sp.edit()
                .putBoolean(SafeWalkPrefKey.hideLauncherIcon, true)
                .putBoolean(SafeWalkPrefKey.hideFromRecentTasks, true)
                .commit();
        assertTrue(SafePrefs.getBool(sp, SafeWalkPrefKey.hideLauncherIcon, false));
        assertTrue(SafePrefs.getBool(sp, SafeWalkPrefKey.hideFromRecentTasks, false));

        // 恢复路径：写回 false 必须能被读回，否则用户关不掉隐藏
        sp.edit()
                .putBoolean(SafeWalkPrefKey.hideLauncherIcon, false)
                .putBoolean(SafeWalkPrefKey.hideFromRecentTasks, false)
                .commit();
        assertFalse(SafePrefs.getBool(sp, SafeWalkPrefKey.hideLauncherIcon, true));
        assertFalse(SafePrefs.getBool(sp, SafeWalkPrefKey.hideFromRecentTasks, true));
    }

    @Test
    public void privacyKeys_neverEnterConfigWhitelist() {
        // 两项都是纯 App 本地设置，进白名单就会被导出到 safewalk_config.json，
        // 平白让 SystemUI 进程多一份「隐私偏好」副本，还多一处两进程不一致的状态。
        assertFalse(ConfigProvider.Companion.getWHITELIST()
                .contains(SafeWalkPrefKey.hideLauncherIcon));
        assertFalse(ConfigProvider.Companion.getWHITELIST()
                .contains(SafeWalkPrefKey.hideFromRecentTasks));
    }

    // ==================== alias 状态计算（纯函数） ====================

    @Test
    public void aliasStates_areAlwaysOneOnOneOff() {
        // 正常态（hide=false）：带 LAUNCHER 的开启、无 LAUNCHER 的关闭
        assertTrue(LauncherIconPolicy.normalAliasEnabled(false));
        assertFalse(LauncherIconPolicy.hiddenAliasEnabled(false));
        // 隐藏态（hide=true）：反过来，且无 LAUNCHER 的那个开着也不会长出图标
        assertFalse(LauncherIconPolicy.normalAliasEnabled(true));
        assertTrue(LauncherIconPolicy.hiddenAliasEnabled(true));
    }

    @Test
    public void componentState_matchesPackageManagerConstants() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                LauncherIconPolicy.componentState(true));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                LauncherIconPolicy.componentState(false));
    }

    @Test
    public void needsChange_isIdempotent() {
        int ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

        // 已是目标态 → 不再调用系统 API
        assertFalse(LauncherIconPolicy.needsChange(ENABLED, true));
        assertFalse(LauncherIconPolicy.needsChange(DISABLED, false));
        // 不同态 / 未初始化 → 需要落一次显式状态
        assertTrue(LauncherIconPolicy.needsChange(DISABLED, true));
        assertTrue(LauncherIconPolicy.needsChange(ENABLED, false));
        assertTrue(LauncherIconPolicy.needsChange(DEFAULT, true));
        assertTrue(LauncherIconPolicy.needsChange(DEFAULT, false));
    }

    // ==================== 恢复入口可达性 ====================

    @Test
    public void recoveryMessage_listsAllFourPaths() {
        String pkg = "io.github.wumisc.safewalk";
        String msg = LauncherIconPolicy.recoveryMessage(pkg);
        // ① LSPosed 是首推路径：模块经 MainActivity 的 MAIN+INFO 入口启动，不依赖桌面图标
        assertTrue("必须把 LSPosed 作为首推恢复路径", msg.contains("LSPosed"));
        assertTrue("必须写明从 LSPosed 模块页进入首页", msg.contains("模块"));
        assertTrue("必须写明 INFO 入口不依赖桌面图标", msg.contains("INFO"));
        // ② 系统设置；③ 通知栏常驻通知；④ adb —— 其余三条保留
        assertTrue("必须写明系统设置恢复路径", msg.contains("设置"));
        assertTrue("必须写明通知栏常驻通知恢复路径", msg.contains("通知"));
        assertTrue("必须写明 adb 显式启动路径",
                msg.contains("adb shell am start -n " + pkg + "/.MainActivity"));
        // 【语义还原】磁贴已回归「常显 ↔ 自动跟随」纯切换，不再承担恢复入口，
        // 因此恢复文案里不能再引导用户去点磁贴 / 控制中心（点了只是切档位，反而更困惑）。
        assertFalse("磁贴不再是恢复入口，文案不得再提磁贴", msg.contains("磁贴"));
        assertFalse("磁贴不再是恢复入口，文案不得再提控制中心", msg.contains("控制中心"));
        // 仍然必须是四条路径：①～④ 各一条
        assertTrue("必须保留四条恢复路径", msg.contains("①") && msg.contains("②")
                && msg.contains("③") && msg.contains("④"));
    }

    @Test
    public void hiddenIcons_stillLeavesMainActivityReachable()
            throws PackageManager.NameNotFoundException {
        Context ctx = ApplicationProvider.getApplicationContext();
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        ComponentName normal = new ComponentName(pkg, pkg + "." + LauncherIconPolicy.ALIAS_NORMAL);
        ComponentName hidden = new ComponentName(pkg, pkg + "." + LauncherIconPolicy.ALIAS_HIDDEN);
        // 注意：ComponentName(pkg, cls) 的 cls 必须是完整类名，不能传 getSimpleName()，
        // 否则 getActivityInfo / resolveActivity 都按全限名匹配而找不到组件。
        ComponentName main = new ComponentName(pkg, MainActivity.class.getName());

        // 模拟「隐藏」：带 LAUNCHER 的关掉，无 LAUNCHER 的打开
        try {
            pm.setComponentEnabledSetting(normal,
                    LauncherIconPolicy.componentState(LauncherIconPolicy.normalAliasEnabled(true)),
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(hidden,
                    LauncherIconPolicy.componentState(LauncherIconPolicy.hiddenAliasEnabled(true)),
                    PackageManager.DONT_KILL_APP);
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    pm.getComponentEnabledSetting(normal));
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    pm.getComponentEnabledSetting(hidden));

            // 关键断言：桌面图标没了，但显式启动 MainActivity 必须仍然可达（adb 走的就是这条；
            // LSPosed 的 INFO 入口与常驻通知的 contentIntent 也最终落到同一个 MainActivity）。
            // 【为何不用 resolveActivity】Robolectric 的 pm.resolveActivity() 即便 Intent 带显式
            // component，仍要求 intent-filter 的 category 匹配；MainActivity 现为 MAIN + INFO
            // （无 LAUNCHER / DEFAULT），会被误判不可达。真机上显式 component 启动不经过 filter 匹配，
            // 只要组件在 Manifest 声明、exported=true、且未被 disable 就一定能启动。
            // 因此用「组件信息可取 + 组件未被禁用」验证显式可达性，这才是意图的本义。
            android.content.pm.ActivityInfo info = pm.getActivityInfo(main, 0);
            assertNotNull("隐藏图标后 MainActivity 仍须在 Manifest 中声明（显式启动前提）", info);
            assertEquals(MainActivity.class.getName(), info.name);
            assertFalse("隐藏图标只切 alias，绝不能禁用 MainActivity 本体（否则 adb/通知/LSPosed 都无法恢复）",
                    pm.getComponentEnabledSetting(main)
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        } finally {
            // 无论断言是否通过都恢复 alias 正常态，避免污染其它测试类（状态写在全局 PM 里）
            pm.setComponentEnabledSetting(normal,
                    LauncherIconPolicy.componentState(LauncherIconPolicy.normalAliasEnabled(false)),
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(hidden,
                    LauncherIconPolicy.componentState(LauncherIconPolicy.hiddenAliasEnabled(false)),
                    PackageManager.DONT_KILL_APP);
        }

        // 模拟「恢复」：一开一关回到正常态
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                pm.getComponentEnabledSetting(normal));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                pm.getComponentEnabledSetting(hidden));
    }

    // ==================== 最近任务隐藏 ====================

    @Test
    public void recentTaskPolicy_launchFlagsAndLabel() {
        assertEquals(0, RecentTaskPolicy.launchFlags(false));
        assertEquals(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                RecentTaskPolicy.launchFlags(true));
        assertEquals("安全出行",
                RecentTaskPolicy.taskDescriptionLabel("安全出行", false));
        assertTrue(RecentTaskPolicy.taskDescriptionLabel("安全出行", true).contains("隐私"));
    }
}
