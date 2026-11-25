package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 【libxposed API 102 规则契约】把官方文档里「必须做到」的条目逐条钉成断言。
 *
 * 这些规则错一条，表现都不是崩溃，而是「模块装了没反应」或「热重载后静默死掉」——
 * 只能靠契约测试在构建期拦住。
 */
@RunWith(JUnit4.class)
public class LibxposedApi102ContractTest {

    private static final String APP = "src/main";
    private static final String SRC = APP + "/java/io/github/wumisc/safewalk";

    private static String read(String rel) throws Exception {
        File f = new File(rel);
        assertTrue("缺少文件: " + rel, f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 规则：模块元数据只允许官方列出的键。
     * 名称/描述来自 android:label / android:description，不在 module.prop 里。
     */
    @Test
    public void modulePropFollowsApi102Schema() throws Exception {
        Properties p = new Properties();
        try (java.io.InputStream in = Files.newInputStream(
                new File(APP + "/resources/META-INF/xposed/module.prop").toPath())) {
            p.load(in);
        }
        assertEquals("最低 API 必须 101（102 的 API 在 101 基线之上）", "101",
                p.getProperty("minApiVersion"));
        assertEquals("目标 API 必须 102", "102", p.getProperty("targetApiVersion"));
        assertEquals("作用域固定", "true", p.getProperty("staticScope"));
        assertEquals("必须允许自动热重载", "true", p.getProperty("autoHotReload"));
        assertEquals("异常模式", "protective", p.getProperty("exceptionMode"));
        Set<String> allowed = new HashSet<>(Arrays.asList(
                "minApiVersion", "targetApiVersion", "staticScope", "exceptionMode", "autoHotReload"));
        for (String k : p.stringPropertyNames()) {
            assertTrue("module.prop 出现非法/多余键: " + k, allowed.contains(k));
        }
    }

    /** 规则：热重载只支持「恰好一个 Java 入口类」 */
    @Test
    public void exactlyOneJavaEntryClass() throws Exception {
        List<String> lines = Files.readAllLines(
                new File(APP + "/resources/META-INF/xposed/java_init.list").toPath(),
                StandardCharsets.UTF_8);
        lines.removeIf(s -> s == null || s.trim().isEmpty());
        assertEquals("java_init.list 必须恰好一个入口类（多入口不支持热重载）", 1, lines.size());
        assertEquals("入口类名", "io.github.wumisc.safewalk.SafeWalkLibxModule", lines.get(0).trim());
        String module = read(SRC + "/SafeWalkLibxModule.kt");
        assertTrue("入口类必须继承 XposedModule", module.contains(": XposedModule()"));
    }

    /** 规则：作用域只声明 SystemUI */
    @Test
    public void scopeIsSystemUiOnly() throws Exception {
        List<String> lines = Files.readAllLines(
                new File(APP + "/resources/META-INF/xposed/scope.list").toPath(),
                StandardCharsets.UTF_8);
        lines.removeIf(s -> s == null || s.trim().isEmpty());
        assertEquals(1, lines.size());
        assertEquals("com.android.systemui", lines.get(0).trim());
    }

    /**
     * 规则：热重载后「package 回调不重放」，新一代必须自己在 onHotReloaded 里立起来。
     * 同时必须 unhook 旧钩子，避免旧 ClassLoader / 旧闭包被框架继续持有。
     */
    @Test
    public void hotReloadedReArmsNewGeneration() throws Exception {
        String module = read(SRC + "/SafeWalkLibxModule.kt");
        assertTrue("须读取旧钩子列表", module.contains("param.oldHookHandles"));
        assertTrue("须逐个 unhook 旧钩子", module.contains("handle.unhook()"));
        assertTrue("须让新内核主动接管", module.contains("core.afterHotReload("));
        assertTrue("释放失败时必须拒绝重载（返回 false），不能带病换代",
                module.contains("拒绝本次重载"));
        assertFalse("不能返回 true 硬扛释放异常", module.contains("仍允许重载"));
        assertFalse("不能在 onHotReloaded 里等 onPackageLoaded（它不会重放）",
                module.contains("锚点会在 onPackageLoaded 重新挂"));

        String hook = read(SRC + "/SafeWalkLSPHook.kt");
        assertTrue("内核须提供 afterHotReload", hook.contains("fun afterHotReload("));
        assertTrue("新一代须主动取 Application",
                hook.contains("triggerInit(app, \"热重载后 ActivityThread 直取\")"));
    }

    /**
     * 规则：返回 true 之前必须停掉模块自己拥有的线程 / 回调 / 静态引用。
     * 光把字段置 null 不够：在跑的线程会把旧 Context 再写回来。
     */
    @Test
    public void oldGenerationHasNoLiveOwners() throws Exception {
        String hook = read(SRC + "/SafeWalkLSPHook.kt");
        assertTrue("须有 disposed 退役标记", hook.contains("private var disposed = false"));
        assertTrue("dispose 须先立标记", hook.contains("disposed = true"));
        assertTrue("兜底线程须看标记退出", hook.contains("if (disposed || initSucceeded) return"));
        assertTrue("初始化入口须看标记", hook.contains("if (disposed) return"));
        assertTrue("须关闭配置线程", hook.contains("configExecutor.shutdownNow()"));
        assertTrue("须反注册手电回调", hook.contains("torchNotifyManager?.stopMonitor()"));
        assertTrue("须释放急救浮层", hook.contains("EmergencyOverlay.dispose()"));
    }

    /** 规则：模块名取 android:label、描述取 android:description */
    @Test
    public void manifestSuppliesModuleNameAndDescription() throws Exception {
        String manifest = read(APP + "/AndroidManifest.xml");
        assertTrue("application 须有 android:label", manifest.contains("android:label=\"安全出行\""));
        assertTrue("application 须有 android:description",
                manifest.contains("android:description=\"@string/module_description\""));
        String strings = read(APP + "/res/values/strings.xml");
        assertTrue("描述字符串须存在", strings.contains("name=\"module_description\""));
    }

    /** 规则：R8 必须保留入口并改写 java_init.list */
    @Test
    public void r8KeepsEntryAndAdaptsInitList() throws Exception {
        String pro = read("proguard-rules.pro");
        assertTrue(pro.contains("-adaptresourcefilecontents META-INF/xposed/java_init.list"));
        assertTrue(pro.contains("-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule"));
        assertTrue(pro.contains("public <init>();"));
    }
}
