package io.github.wumisc.safewalk;

import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes3.dex */
public final class SafeWalkInit implements IXposedHookLoadPackage {
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }
        DiagLogger.i("Init", "injected into SystemUI, process=" + lpparam.processName);
        DiagLogger.i("Init", "diag log file -> " + DiagLogger.resolveLogFile().getAbsolutePath());
        try {
            Class<?> appCls = XposedHelpers.findClassIfExists("com.android.systemui.SystemUIApplication", lpparam.classLoader);
            if (appCls != null) {
                XposedHelpers.findAndHookMethod(appCls, "onCreate", new Object[]{new XC_MethodHook() { // from class: io.github.wumisc.safewalk.SafeWalkInit.1
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        Object self = param.thisObject;
                        DiagLogger.i("Init", "SystemUIApplication.onCreate fired -> " + (self == null ? "null" : self.getClass().getName()));
                        if (self instanceof Context) {
                            SafeWalkCore.initWithApplication((Context) self);
                        }
                    }
                }});
                DiagLogger.i("Init", "hooked SystemUIApplication.onCreate");
            } else {
                DiagLogger.w("Init", "SystemUIApplication class not found");
            }
            SafeWalkCore.init();
        } catch (Throwable t) {
            DiagLogger.e("Init", "handleLoadPackage err: " + t);
        }
    }

    public static void diag(String msg) {
        DiagLogger.i("Diag", msg);
    }
}
