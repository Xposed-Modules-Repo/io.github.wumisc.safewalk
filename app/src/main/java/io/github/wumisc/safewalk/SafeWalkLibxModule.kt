package io.github.wumisc.safewalk

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 【libxposed API 102 入口】SafeWalk 现代 API 实验版。
 *
 * 与原 legacy 版（`IXposedHookLoadPackage` + `XposedHelpers.findAndHookMethod`）的区别：
 *  · 模块只加载进作用域进程（SystemUI），不再注入 zygote；
 *  · 钩子走 `hook(Executable).intercept(Hooker)`，类型安全，且 102 支持热重载/原子替换；
 *  · 模块元数据不再用 AndroidManifest 的 xposed* meta-data，而是
 *    `META-INF/xposed/module.prop` + `java_init.list` + `scope.list`（见 app/src/main/resources）。
 *
 * 业务内核完全复用 [SafeWalkLSPHook]：这里只负责「什么时候拿到 SystemUI 的 Context」，
 * 一旦拿到就交给 [SafeWalkLSPHook.triggerInit] 跑原来的状态机。三条锚点与原版一一对应，
 * 并存关系保持「谁先到谁初始化，其余幂等忽略」。
 */
class SafeWalkLibxModule : XposedModule() {

    companion object {
        private const val TAG = "SafeWalk"
        private const val SYSTEMUI_PKG = "com.android.systemui"
    }

    /**
     * 每个进程一份内核实例（与原来 legacy 版的单实例语义一致）。
     *
     * 【API 102 规则】`onModuleLoaded` 之前不做任何初始化：改用 lazy，
     * 第一次真正用到内核（onPackageLoaded / onHotReloading）时才构造。
     */
    private val core by lazy { SafeWalkLSPHook() }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "libxposed 模块已加载 | framework=" + frameworkName
                + " " + frameworkVersion + " api=" + apiVersion)
        // 用日志一并确认「现代 API 生效」这件事：legacy 版这里永远是 82/93
        log(Log.INFO, TAG, "SafeWalk 现代 API 入口生效（API_102=" + XposedInterface.API_102 + "）")
        // 给下一次升级留证据：这一代是否声明了可热重载。若日志缺失，LSPosed 会按
        // onHotReloading 默认 false 处理，升级时就会看到「热重载失败」。
        log(Log.INFO, TAG, "热重载能力=已声明（onHotReloading=true），升级后可原地换代码")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != SYSTEMUI_PKG) return
        log(Log.INFO, TAG, "成功挂载至 SystemUI【系统界面】进程（libxposed）")
        SafeWalkLSPHook.diag("===== libxposed 加载到 SystemUI 进程 =====")
        SafeWalkLSPHook.diag("成功挂载至 SystemUI【系统界面】进程（libxposed API " + apiVersion + "）")

        val cl = param.defaultClassLoader

        // ---- 锚点 1：SystemUIApplication.onCreate（主入口） ----
        core.hookAnchor("SystemUIApplication.onCreate") {
            hookAfter(cl, "com.android.systemui.SystemUIApplication", "onCreate",
                emptyArray(), "SystemUIApplication.onCreate") { chain ->
                chain.thisObject as? Context
            }
        }

        // ---- 锚点 2：Instrumentation.callApplicationOnCreate（兜住 OEM 改类名） ----
        core.hookAnchor("Instrumentation.callApplicationOnCreate") {
            hookAfter(cl, "android.app.Instrumentation", "callApplicationOnCreate",
                arrayOf(android.app.Application::class.java), "Instrumentation.callApplicationOnCreate") { chain ->
                chain.getArg(0) as? Context
            }
        }

        // ---- 锚点 3：SystemUIService.onCreate（旁路，启动极早） ----
        core.hookAnchor("SystemUIService.onCreate") {
            hookAfter(cl, "com.android.systemui.SystemUIService", "onCreate",
                emptyArray(), "SystemUIService.onCreate") { chain ->
                chain.thisObject as? Context
            }
        }

        // ---- 第四条路：锚点全没命中时，自己从 ActivityThread 摸 Context ----
        core.startContextFallback()
    }

    /**
     * 【5.7.3 热重载】framework 换钩子之前，先把上一代在 SystemUI 里留下的资源全部释放。
     *
     * 只换钩子是不够的：本内核还持有传感器监听、动态广播接收器、tick 链、看门狗
     * 与配置快照。这些对象挂在旧模块实例上，不主动释放就会和下一代同时运行
     * （双倍耗电、重复计步、日志翻倍，甚至对已作废的 Context 发广播）。
     *
     * 【为什么 115 之前升级会收到「1 个进程热重载失败」】
     * API 102 里 `onHotReloading` 的**默认实现返回 false**（libxposed 源码原文
     * `default boolean onHotReloading(...) { return false; }`）。110/112 那几代没有
     * 覆写它，框架按 `autoHotReload=true` 发起重载 → 旧代码回答「我还没准备好」→
     * 框架记为 FAILED，通知栏就是「已更新，1 个进程热重载失败」。这不是框架 bug，
     * 是旧一代的模块没有声明可以退役。从本代起覆写并返回 true 才会真正热重载。
     *
     * 返回语义：**只有资源确实释放完才允许返回 true**。释放过程抛异常时返回 false，
     * 让框架放弃这次重载（旧一代继续正常服务），由 App 首页的「重启系统界面」兜底 ——
     * 带着未释放的传感器/广播去换代码，比不换更糟。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        return try {
            core.disposeForReload("libxposed 热重载")
            log(Log.INFO, TAG, "热重载：旧一代资源已释放，允许重载")
            true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "热重载前释放资源异常，拒绝本次重载（等进程重启）: ${t.message}")
            false
        }
    }

    /**
     * 【API 102 规则】热重载完成：新一代必须自己重新立起来。
     *
     * 官方文档两条硬规则：
     *  1. 热重载后 **package 生命周期回调不会自动重放**（`onPackageLoaded` 不会再来）；
     *  2. `onHotReloaded` 的默认实现只负责 unhook 所有旧钩子，不会帮你初始化。
     *
     * 所以这里做两件事：
     *  · 把 `param.oldHookHandles` 里上一代挂的锚点逐个 `unhook()` —— 不 unhook 的话，
     *    旧一代的 hooker 闭包（连同旧 ClassLoader / 旧内核）会被框架继续持有，
     *    每来一次热重载就多留一代，所谓「换钩子不换资源」；
     *  · 调 [SafeWalkLSPHook.afterHotReload] 让新内核用 `ActivityThread` 反射取当前
     *    Application 立即初始化（锚点已经错过，不能再等）。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        log(Log.INFO, TAG, "热重载完成：process=${param.processName}，接管旧钩子 "
                + param.oldHookHandles.size + " 个")
        SafeWalkLSPHook.diag("===== libxposed 热重载完成（process=${param.processName}） =====")
        for (handle in param.oldHookHandles) {
            try {
                handle.unhook()
            } catch (t: Throwable) {
                Log.w(TAG, "旧钩子 unhook 失败（继续接管）: ${t.message}")
            }
        }
        core.afterHotReload(param.processName)
    }

    /**
     * 钩一个方法并在它**执行完**之后回调（等价于 legacy 的 afterHookedMethod）。
     *
     * 为什么先 `proceed()` 再跑业务：原版三个锚点都在「Application / Service 已建好」之后
     * 才去拿 Context；提前跑会拿到半初始化的 Context，传感器、通知渠道都会失败。
     * 任何异常都吞在钩子里并原样返回结果，绝不把 SystemUI 带崩。
     */
    private fun hookAfter(
        cl: ClassLoader,
        className: String,
        method: String,
        params: Array<Class<*>>,
        label: String,
        ctxOf: (XposedInterface.Chain) -> Context?
    ): Boolean {
        val cls = try {
            Class.forName(className, false, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "锚点类不存在: $className → ${t.javaClass.simpleName}")
            return false
        }
        val m = try {
            cls.getDeclaredMethod(method, *params)
        } catch (t: Throwable) {
            Log.w(TAG, "锚点方法不存在: $className#$method → ${t.javaClass.simpleName}")
            return false
        }
        try {
            hook(m).intercept { chain ->
                val result = chain.proceed()
                try {
                    ctxOf(chain)?.let { core.triggerInit(it, label) }
                } catch (t: Throwable) {
                    Log.e(TAG, "锚点回调异常（已忽略，不影响 SystemUI）: $label", t)
                }
                result
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "钩点注册失败: $className#$method", t)
            return false
        }
    }
}
