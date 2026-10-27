package io.github.wumisc.safewalk;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 安全出行 Xposed 入口。
 * 注入 SystemUI 进程。核心初始化必须等到 SystemUI 的 Application 完整创建之后，
 * 否则在 handleLoadPackage 阶段拿到的都是"裸上下文"，getSystemService 不可用。
 * 双保险：
 *  - 优先 hook SystemUIApplication.onCreate（onCreate 里拿完整 Application）
 *  - 若当时 Application 已创建，则直接读 mInitialApplication 初始化
 */
public final class SafeWalkInit implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }
        diag("injected into SystemUI process=" + lpparam.processName);
        try {
            // 方式 A：hook SystemUIApplication.onCreate，等 Application 完整创建后再初始化
            Class<?> appCls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.SystemUIApplication", lpparam.classLoader);
            if (appCls != null) {
                XposedHelpers.findAndHookMethod(appCls, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        diag("onCreate fired -> " + (self == null ? "null" : self.getClass().getName()));
                        if (self instanceof android.content.Context) {
                            SafeWalkCore.initWithApplication((android.content.Context) self);
                        }
                    }
                });
                diag("hooked SystemUIApplication.onCreate");
            } else {
                diag("SystemUIApplication class not found");
            }
            // 方式 B：若 Application 已经创建，直接初始化
            SafeWalkCore.init();
        } catch (Throwable t) {
            diag("handleLoadPackage err: " + t);
        }
    }

    /** 诊断日志：受 Config.debug 总闸控制（默认关），关闭时零 IO、零写入 */
    public static void diag(String msg) {
        if (!Config.debug) {
            return;
        }
        String line = System.currentTimeMillis() + " [safe] " + msg + "\n";
        // 只写 /data/local/tmp（SystemUI 进程有权限），避免重复写两个文件
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream("/data/local/tmp/safewalk_diag.log", true)) {
            fos.write(line.getBytes());
        } catch (Throwable ignored) {
        }
    }
}
