package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 【libxposed API 102 迁移】模块元数据契约测试。
 *
 * <p>角标为什么从 legacy 变 API 102，全看这三件事：模块元数据文件、入口清单、作用域。
 * 任何一项写错，LSPosed 都会把模块当 legacy（或干脆不加载），而在真机上表现就是
 * 「模块列表里有，但 SystemUI 里没生效」。这些是纯文件断言，JVM 就能判定。
 */
public class LibxposedMetadataContractTest {

    private static final String ROOT = "src/main/resources/META-INF/xposed";
    private static final String MAIN_SRC = "src/main/java/io/github/wumisc/safewalk";

    private static String read(String rel) throws Exception {
        Path[] c = {Paths.get("app/" + rel), Paths.get(rel), Paths.get("../app/" + rel)};
        for (Path p : c) {
            if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("文件不存在: " + rel);
    }

    private static boolean exists(String rel) {
        Path[] c = {Paths.get("app/" + rel), Paths.get(rel), Paths.get("../app/" + rel)};
        for (Path p : c) if (Files.exists(p)) return true;
        return false;
    }

    /** 1. module.prop 必须声明 101/102，且 open 的 target 是 102（才支持热重载）。 */
    @Test
    public void modulePropDeclaresModernApi() throws Exception {
        String prop = read(ROOT + "/module.prop");
        assertTrue("须声明 minApiVersion", prop.contains("minApiVersion=101"));
        assertTrue("须声明 targetApiVersion=102", prop.contains("targetApiVersion=102"));
        assertTrue("须启用自动热重载", prop.contains("autoHotReload=true"));
    }

    /** 2. java_init.list 指向真实存在的入口类，且该类继承 XposedModule。 */
    @Test
    public void entryIsLibxposedModule() throws Exception {
        String list = read(ROOT + "/java_init.list").trim();
        assertTrue("java_init.list 须指向 SafeWalkLibxModule",
                list.equals("io.github.wumisc.safewalk.SafeWalkLibxModule"));
        String src = read(MAIN_SRC + "/SafeWalkLibxModule.kt");
        assertTrue("入口须继承 XposedModule", src.contains("XposedModule()"));
        assertTrue("须实现 onPackageLoaded", src.contains("onPackageLoaded"));
        assertTrue("须用现代 hook 接口", src.contains(".intercept"));
    }

    /** 3. 作用域只有 SystemUI；且 legacy 声明必须清干净，两套并存会让框架二选一。 */
    @Test
    public void scopeIsSystemUiOnlyAndLegacyRemoved() throws Exception {
        String scope = read(ROOT + "/scope.list").trim();
        assertTrue("作用域须只有 com.android.systemui", scope.equals("com.android.systemui"));
        assertFalse("不得保留 legacy 的 assets/xposed_init", exists("src/main/assets/xposed_init"));
        String manifest = read("src/main/AndroidManifest.xml");
        assertFalse("不得保留 xposedminversion（会被判成 legacy）", manifest.contains("xposedminversion"));
        assertFalse("不得保留 xposedmodule meta-data", manifest.contains("\"xposedmodule\""));
    }

    /** 4. 业务内核不得再引用老 API：102 模块调用 de.robv 会直接失败。 */
    @Test
    public void kernelHasNoLegacyApi() throws Exception {
        String hook = read(MAIN_SRC + "/SafeWalkLSPHook.kt");
        assertFalse("内核不得 import de.robv", hook.contains("de.robv.android.xposed"));
        assertFalse("内核不得实现 IXposedHookLoadPackage", hook.contains("IXposedHookLoadPackage"));
        assertTrue("内核须保留供入口调用的初始化入口", hook.contains("internal fun triggerInit"));
        assertTrue("内核须保留 Context 兜底通道", hook.contains("internal fun startContextFallback"));
    }

    /**
     * 5. 热重载必须连资源一起换：只换钩子会把上一代的传感器监听、广播接收器、
     * tick 链留在 SystemUI 里，和下一代同时跑（双倍耗电 / 重复计步 / 旧闭包操作空 Context）。
     */
    @Test
    public void hotReloadReleasesOldGenerationResources() throws Exception {
        String entry = read(MAIN_SRC + "/SafeWalkLibxModule.kt");
        assertTrue("入口须实现 onHotReloading", entry.contains("override fun onHotReloading"));
        assertTrue("重载前须调用内核释放", entry.contains("disposeForReload"));
        String hook = read(MAIN_SRC + "/SafeWalkLSPHook.kt");
        assertTrue("内核须提供 disposeForReload", hook.contains("fun disposeForReload"));
        assertTrue("须反注册动态接收器", hook.contains("unregisterReceiver"));
        assertTrue("须停止加速度计兜底监听", hook.contains("stopAccFallback()"));
        assertTrue("旧一代 tick 须能自杀", hook.contains("if (!tickStarted) return"));
        // 静态单例是「换钩子不换资源」最隐蔽的残留点：它们不在类的实例字段里，
        // 新一代 init 也不会重置，必须逐个显式释放。
        assertTrue("须反注册手电回调（否则旧 CameraManager 回调活着）",
                hook.contains("torchNotifyManager?.stopMonitor()"));
        assertTrue("须收起并释放急救浮层", hook.contains("EmergencyOverlay.dispose()"));
        assertTrue("须释放显示取件客户端（接收器+取件线程）", hook.contains("DisplayClient.dispose()"));
        assertTrue("须反注册电量变化接收器", hook.contains("batteryReceiver?.let"));
        assertTrue("须摘掉步态复核采样监听", hook.contains("stopGaitSampling()"));
        assertTrue("配置线程释放后须可重建，不能在已关闭的池上提交",
                hook.contains("ensureConfigExecutor()"));
        assertTrue("须关闭旧一代配置线程", hook.contains("configExecutor?.shutdownNow()"));
        String overlay = read(MAIN_SRC + "/EmergencyOverlay.kt");
        assertTrue("急救浮层须提供 dispose", overlay.contains("fun dispose()"));
        assertTrue("dispose 须清 WindowManager 引用", overlay.contains("wm = null"));
        String display = read(MAIN_SRC + "/DisplayClient.kt");
        assertTrue("显示客户端须提供 dispose", display.contains("fun dispose()"));
        assertTrue("dispose 须反注册接收器", display.contains("unregisterReceiver"));
        assertTrue("dispose 须关闭取件线程", display.contains("shutdownNow()"));
        assertTrue("退场接收器（VISIBLE/HIDE）必须注册", display.contains("register(c, exitReceiver"));
    }
}
