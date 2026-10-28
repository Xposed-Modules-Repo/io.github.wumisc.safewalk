package io.github.wumisc.safewalk;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XposedHelpers;

/**
 * 安全出行核心管理器：协调总开关、行走模式（手动/自动）、摄像头路况、看路提醒。
 * 省电设计：
 *  - 灭屏时全部暂停（停轮询、注销传感器、关摄像头），亮屏自动恢复；
 *  - 配置仅按文件 mtime 变化才重读，静态时轮询几乎零开销；
 *  - 轮询间隔：开启 2s / 关闭 5s；诊断日志默认关闭（Config.debug）。
 * 冲突避让：前台进入相机类应用（相机/微信/支付宝等扫码拍景）时自动收起路况摄像头，
 * 避免抢占相机导致对方打不开；切回其他应用自动恢复。
 */
public final class SafeWalkCore {

    private static Context app;
    private static Handler handler;
    private static SensorManager sensorManager;
    private static boolean screenOn = true;
    private static long confLastMod = -1;
    private static boolean foregroundAvoid = false;
    /** 避让冷却：切出相机类应用后这段时间内，自动模式不立即重开摄像头 */
    private static long avoidUntil = 0;
    private static final long AVOID_COOL_MS = 90_000; // 90 秒（约一两分钟）

    /** 相机类/扫码类应用：前台为这些包时主动避让，释放摄像头 */
    private static final java.util.Set<String> AVOID_APPS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "com.android.camera", "com.huawei.camera", "com.miui.camera",
                    "com.xiaomi.camera", "com.oplus.camera", "com.vivo.camera",
                    "com.samsung.android.camera", "com.oneplus.camera",
                    "com.tencent.mm", "com.eg.android.AlipayGphone",
                    "com.tencent.mobileqq", "com.ss.android.ugc.aweme",
                    "com.unionpay"));

    private SafeWalkCore() {
    }

    /**
     * Xposed 入口调用。SystemUI 进程里 handleLoadPackage 时机的 mInitialApplication 往往尚未就绪（null），
     * 因此用延迟轮询：每 200ms 检查一次，直到拿到完整 attach 的进程 Application（SystemUIApplication）
     * 再用它初始化。这样 getSystemService 全部可靠，不会像 getSystemContext() 的裸上下文那样内部 NPE。
     */
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
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            Object appObj = (thread == null) ? null
                    : XposedHelpers.getObjectField(thread, "mInitialApplication");
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
        INIT_HANDLER.postDelayed(() -> init(), 200);
    }

    private static int initRetry = 0;
    private static final Handler INIT_HANDLER = new Handler(Looper.getMainLooper());

    /** 供配置界面等直接传入上下文初始化 */
    public static void init(Context context) {
        initContext(context.getApplicationContext());
    }

    /**
     * 供 SystemUIApplication.onCreate hook 调用：直接传入完整 attach 的 Application 初始化。
     */
    public static void initWithApplication(Context appContext) {
        if (app != null) {
            return;
        }
        if (appContext == null) {
            SafeWalkInit.diag("initWithApplication: null");
            return;
        }
        SafeWalkInit.diag("initWithApplication: " + appContext.getClass().getName());
        initContext(appContext);
    }

    private static void initContext(Context c) {
        if (app != null) {
            return;
        }
        app = c;
        initRetry = 0; // 初始化成功后重置重试计数
        try {
            sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            SafeWalkInit.diag("init: sensorManager=" + (sensorManager == null ? "null" : "ok"));
            WalkDetector.init(sensorManager);
            LookdownReminder.init(app, sensorManager);
            NightFlashlight.init(app);
        } catch (Throwable t) {
            SafeWalkInit.diag("init: sensors err " + t);
        }
        handler = new Handler(Looper.getMainLooper());
        registerScreenReceiver();
        handler.post(tickRunnable);
        SafeWalkInit.diag("core init");
    }

    /** 运行时反射拿前台应用包名（ActivityTaskManager 为隐藏 API，编译期不可用） */
    private static String getForegroundPackage() {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object atm = XposedHelpers.callStaticMethod(atmCls, "getInstance");
            Object info = XposedHelpers.callMethod(atm, "getFocusedRootTaskInfo");
            if (info != null) {
                Object top = XposedHelpers.getObjectField(info, "topActivity");
                if (top instanceof ComponentName) {
                    return ((ComponentName) top).getPackageName();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 避让状态机：进入相机类应用立即收起摄像头，切出后进入冷却，冷却结束若仍在行走再自动恢复 */
    private static void applyForegroundAvoid() {
        try {
            String fg = getForegroundPackage();
            boolean avoid = fg != null && AVOID_APPS.contains(fg);
            if (avoid && !foregroundAvoid) {
                foregroundAvoid = true;
                SafeWalkInit.diag("avoid on: " + fg);
            } else if (!avoid && foregroundAvoid) {
                foregroundAvoid = false;
                avoidUntil = System.currentTimeMillis() + AVOID_COOL_MS;
                SafeWalkInit.diag("avoid off, cooling " + (AVOID_COOL_MS / 1000) + "s");
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("avoid: " + t);
        }
    }

    /** 灭屏 = 模块彻底待机：停轮询、注销传感器、关摄像头，功耗趋近于零 */
    private static void registerScreenReceiver() {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                            screenOn = false;
                            handler.removeCallbacks(tickRunnable);
                            WalkDetector.setAutoEnabled(false);
                            LookdownReminder.setEnabled(false);
                            NightFlashlight.setEnabled(false);
                            CameraOverlay.hide();
                            SafeWalkInit.diag("screen off -> all paused");
                        } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                            screenOn = true;
                            confLastMod = -1; // 亮屏强制重读一次配置
                            handler.removeCallbacks(tickRunnable);
                            handler.post(tickRunnable);
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

    private static final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!screenOn) {
                    return; // 灭屏时已由 receiver 暂停，这里不重启
                }
                // 配置仅按文件 mtime 变化才重读；静态时只是 stat，无 IO
                long m = new java.io.File(Config.CONF_PATH).lastModified();
                if (m != confLastMod) {
                    confLastMod = m;
                    Config.reload();
                }
                // 行走检测被"自动开摄像头"与"看路提醒"共用
                boolean needDetector = Config.enable && (Config.autoWalk || Config.lookdown || Config.nightFlash);
                WalkDetector.setAutoEnabled(needDetector);
                LookdownReminder.setEnabled(Config.enable && Config.lookdown);
                NightFlashlight.setEnabled(Config.enable && Config.nightFlash);
                applyForegroundAvoid();
                updateOverlay();
            } catch (Throwable t) {
                SafeWalkInit.diag("tick: " + t);
            }
            // 开启时 2s 轮询，关闭时 5s（只为了随时发现用户重新打开总开关）
            handler.postDelayed(this, Config.enable ? 2000 : 5000);
        }
    };

    public static Context getAppContext() {
        return app;
    }

    /** 根据开关与行走状态决定摄像头路况是否开启 */
    private static void updateOverlay() {
        boolean want;
        if (!Config.enable) {
            want = false;
        } else {
            // 行走路上就一直开着摄像头路况；坐下/停下（检测不到步行节奏）自动收起。
            // 低头不影响摄像头——低头弹 Toast 时摄像头保持显示。
            // 前台是相机/扫码类应用时避让，释放摄像头给对方用。
            boolean manual = Config.walkMode;
            boolean auto = Config.autoWalk && WalkDetector.isWalking();
            boolean inCoolDown = System.currentTimeMillis() < avoidUntil;
            // 避让中一律不开；自动模式还要等冷却（扫码/拍完一两分钟后确认仍在行走再恢复）
            want = !foregroundAvoid && (manual || (auto && !inCoolDown));
        }
        if (want) {
            CameraOverlay.show(app, Config.overlayForm, Config.alpha, Config.corner);
        } else {
            CameraOverlay.hide();
        }
    }

    /** 供磁贴调用：切换行走模式，返回切换后的状态 */
    public static boolean toggleWalkMode() {
        Config.reload();
        Config.walkMode = !Config.walkMode;
        writeConfigAsync(null);
        return Config.walkMode;
    }

    /** 配置写入结果回调（主线程） */
    public interface OnConfigWritten {
        void onResult(boolean ok);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 异步写回配置（子线程执行 su，避免主线程阻塞 ANR 闪退）。
     * 成功还会 chmod 644，保证 SystemUI 进程可读。
     */
    public static void writeConfigAsync(final OnConfigWritten cb) {
        final String conf = buildConf();
        new Thread(() -> {
            boolean ok = false;
            try {
                String b64 = android.util.Base64.encodeToString(conf.getBytes(),
                        android.util.Base64.NO_WRAP);
                Process p = new ProcessBuilder("su", "-c",
                        "echo " + b64 + " | base64 -d > " + Config.CONF_PATH
                                + " && chmod 644 " + Config.CONF_PATH).start();
                ok = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                        && p.exitValue() == 0;
                if (!ok) {
                    SafeWalkInit.diag("su write fail exit=" + (p.isAlive() ? "timeout" : p.exitValue()));
                }
            } catch (Throwable t) {
                SafeWalkInit.diag("write config: " + t);
            }
            final boolean result = ok;
            if (cb != null) {
                MAIN_HANDLER.post(() -> cb.onResult(result));
            }
        }).start();
    }

    private static String buildConf() {
        return "# 安全出行配置\n"
                + "enable=" + Config.enable + "\n"
                + "walk_mode=" + Config.walkMode + "\n"
                + "auto_walk=" + Config.autoWalk + "\n"
                + "overlay_form=" + Config.overlayForm + "\n"
                + "alpha=" + Config.alpha + "\n"
                + "corner=" + Config.corner + "\n"
                + "lookdown=" + Config.lookdown + "\n"
                + "lookdown_sec=" + Config.lookdownSec + "\n"
                + "night_flash=" + Config.nightFlash + "\n"
                + "tile_state=" + Config.tileState + "\n"
                + "debug=" + Config.debug + "\n";
    }
}
