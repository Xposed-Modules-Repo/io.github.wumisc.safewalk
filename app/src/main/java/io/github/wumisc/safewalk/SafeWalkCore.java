package io.github.wumisc.safewalk;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/* loaded from: classes3.dex */
public final class SafeWalkCore {
    private static final long AVOID_COOL_MS = 90000;
    private static Context app;
    private static Handler handler;
    private static SensorManager sensorManager;
    private static boolean screenOn = true;
    private static long confLastMod = -1;
    private static boolean foregroundAvoid = false;
    private static long avoidUntil = 0;
    private static final Set<String> AVOID_APPS = new HashSet(Arrays.asList("com.android.camera", "com.huawei.camera", "com.miui.camera", "com.xiaomi.camera", "com.oplus.camera", "com.vivo.camera", "com.samsung.android.camera", "com.oneplus.camera", "com.tencent.mm", "com.eg.android.AlipayGphone", "com.tencent.mobileqq", "com.ss.android.ugc.aweme", "com.unionpay"));
    private static int initRetry = 0;
    private static final Handler INIT_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable tickRunnable = new Runnable() { // from class: io.github.wumisc.safewalk.SafeWalkCore.1
        @Override // java.lang.Runnable
        public void run() {
            if (!SafeWalkCore.screenOn) {
                return;
            }
            long m = new File(Config.CONF_PATH).lastModified();
            if (m != SafeWalkCore.confLastMod) {
                SafeWalkCore.confLastMod = m;
                Config.reload();
            }
            boolean z = true;
            boolean needDetector = Config.enable && (Config.autoWalk || Config.lookdown || Config.nightFlash);
            WalkDetector.setAutoEnabled(needDetector);
            LookdownReminder.setEnabled(Config.enable && Config.lookdown);
            NightFlashlight.setEnabled(Config.enable && Config.nightFlash);
            if (!Config.enable || !Config.safety) {
                z = false;
            }
            SafetyFeatures.setEnabled(z);
            SafeWalkCore.applyForegroundAvoid();
            SafeWalkCore.updateOverlay();
            SafeWalkCore.handler.postDelayed(this, Config.enable ? 2000L : 5000L);
        }
    };
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /* loaded from: classes3.dex */
    public interface OnConfigWritten {
        void onResult(boolean z);
    }

    private SafeWalkCore() {
    }

    public static void init() {
        if (app != null) {
            return;
        }
        if (initRetry > 60) {
            SafeWalkInit.diag("init: give up after retries");
            return;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread", new Object[0]);
            Object appObj = thread == null ? null : XposedHelpers.getObjectField(thread, "mInitialApplication");
            if (appObj instanceof Context) {
                SafeWalkInit.diag("init: app ready -> " + appObj.getClass().getName());
                initContext((Context) appObj);
                return;
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("init: " + t);
        }
        initRetry++;
        SafeWalkInit.diag("init: app not ready (retry " + initRetry + ")");
        INIT_HANDLER.postDelayed(new Runnable() { // from class: io.github.wumisc.safewalk.SafeWalkCore.2
            @Override // java.lang.Runnable
            public final void run() {
                SafeWalkCore.init();
            }
        }, 200L);
    }

    public static void init(Context context) {
        initContext(context.getApplicationContext());
    }

    public static void initWithApplication(Context appContext) {
        if (app != null) {
            return;
        }
        if (appContext == null) {
            SafeWalkInit.diag("initWithApplication: null");
        } else {
            SafeWalkInit.diag("initWithApplication: " + appContext.getClass().getName());
            initContext(appContext);
        }
    }

    private static void initContext(Context c) {
        if (app != null) {
            return;
        }
        app = c;
        initRetry = 0;
        try {
            sensorManager = (SensorManager) app.getSystemService("sensor");
            SafeWalkInit.diag("init: sensorManager=" + (sensorManager == null ? "null" : "ok"));
            WalkDetector.init(sensorManager);
            LookdownReminder.init(app, sensorManager);
            NightFlashlight.init(app);
            SafetyFeatures.init(app);
        } catch (Throwable t) {
            SafeWalkInit.diag("init: sensors err " + t);
        }
        handler = new Handler(Looper.getMainLooper());
        registerScreenReceiver();
        handler.post(tickRunnable);
        SafeWalkInit.diag("core init");
    }

    private static String getForegroundPackage() {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object atm = XposedHelpers.callStaticMethod(atmCls, "getInstance", new Object[0]);
            Object info = XposedHelpers.callMethod(atm, "getFocusedRootTaskInfo", new Object[0]);
            if (info == null) {
                return null;
            }
            Object top = XposedHelpers.getObjectField(info, "topActivity");
            if (!(top instanceof ComponentName)) {
                return null;
            }
            return ((ComponentName) top).getPackageName();
        } catch (Throwable th) {
            return null;
        }
    }

    public static void applyForegroundAvoid() {
        try {
            String fg = getForegroundPackage();
            boolean avoid = fg != null && AVOID_APPS.contains(fg);
            if (avoid && !foregroundAvoid) {
                foregroundAvoid = true;
                SafeWalkInit.diag("avoid on: " + fg);
            } else if (!avoid && foregroundAvoid) {
                foregroundAvoid = false;
                avoidUntil = System.currentTimeMillis() + AVOID_COOL_MS;
                SafeWalkInit.diag("avoid off, cooling 90s");
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("avoid: " + t);
        }
    }

    private static void registerScreenReceiver() {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction("android.intent.action.SCREEN_OFF");
            f.addAction("android.intent.action.SCREEN_ON");
            app.registerReceiver(new BroadcastReceiver() { // from class: io.github.wumisc.safewalk.SafeWalkCore.3
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context c, Intent i) {
                    try {
                        if ("android.intent.action.SCREEN_OFF".equals(i.getAction())) {
                            SafeWalkCore.screenOn = false;
                            SafeWalkCore.handler.removeCallbacks(SafeWalkCore.tickRunnable);
                            WalkDetector.setAutoEnabled(false);
                            LookdownReminder.setEnabled(false);
                            NightFlashlight.setEnabled(false);
                            SafetyFeatures.setEnabled(false);
                            CameraOverlay.hide();
                            SafeWalkInit.diag("screen off -> all paused");
                        } else if ("android.intent.action.SCREEN_ON".equals(i.getAction())) {
                            SafeWalkCore.screenOn = true;
                            SafeWalkCore.confLastMod = -1L;
                            SafeWalkCore.handler.removeCallbacks(SafeWalkCore.tickRunnable);
                            SafeWalkCore.handler.post(SafeWalkCore.tickRunnable);
                            SafeWalkInit.diag("screen on -> resume");
                        }
                    } catch (Throwable t) {
                        SafeWalkInit.diag("screen rx: " + t);
                    }
                }
            }, f);
        } catch (Throwable t) {
            SafeWalkInit.diag("register screen rx: " + t);
        }
    }

    public static Context getAppContext() {
        return app;
    }

    public static void updateOverlay() {
        boolean want;
        if (!Config.enable) {
            want = false;
        } else {
            boolean manual = Config.walkMode;
            boolean z = true;
            boolean auto = Config.autoWalk && WalkDetector.isWalking();
            boolean inCoolDown = System.currentTimeMillis() < avoidUntil;
            if (foregroundAvoid || (!manual && (!auto || inCoolDown))) {
                z = false;
            }
            want = z;
        }
        if (want) {
            CameraOverlay.show(app, Config.overlayForm, Config.alpha, Config.corner);
        } else {
            CameraOverlay.hide();
        }
    }

    public static boolean toggleWalkMode() {
        Config.reload();
        Config.walkMode = !Config.walkMode;
        writeConfigAsync(null);
        return Config.walkMode;
    }

    public static void writeConfigAsync(final OnConfigWritten cb) {
        final String conf = buildConf();
        new Thread(new Runnable() { // from class: io.github.wumisc.safewalk.SafeWalkCore.4
            @Override // java.lang.Runnable
            public final void run() {
                SafeWalkCore.lambda$writeConfigAsync$2(conf, cb);
            }
        }).start();
    }

    public static void lambda$writeConfigAsync$2(String conf, final OnConfigWritten cb) {
        boolean ok = false;
        try {
            String b64 = Base64.encodeToString(conf.getBytes(), 2);
            boolean z = false;
            Process p = new ProcessBuilder("su", "-c", "echo " + b64 + " | base64 -d > " + Config.CONF_PATH + " && chmod 644 " + Config.CONF_PATH).start();
            if (p.waitFor(8L, TimeUnit.SECONDS) && p.exitValue() == 0) {
                z = true;
            }
            ok = z;
            if (!ok) {
                SafeWalkInit.diag("su write fail exit=" + (p.isAlive() ? "timeout" : Integer.valueOf(p.exitValue())));
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("write config: " + t);
        }
        final boolean result = ok;
        if (cb != null) {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public final void run() {
                    cb.onResult(result);
                }
            });
        }
    }

    private static String buildConf() {
        return "# 安全出行配置\nenable=" + Config.enable + "\nwalk_mode=" + Config.walkMode + "\nauto_walk=" + Config.autoWalk + "\noverlay_form=" + Config.overlayForm + "\nalpha=" + Config.alpha + "\ncorner=" + Config.corner + "\nlookdown=" + Config.lookdown + "\nlookdown_sec=" + Config.lookdownSec + "\nnight_flash=" + Config.nightFlash + "\nsafety=" + Config.safety + "\ntile_state=" + Config.tileState + "\ndebug=" + Config.debug + "\n";
    }
}
