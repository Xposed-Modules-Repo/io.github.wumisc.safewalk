package io.github.wumisc.safewalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Calendar;

/**
 * 夜间出行手电筒提醒。
 *
 * 触发条件（全部满足才弹通知）：
 *  - 当前时间在夜间窗口（22:00 ~ 次日 07:00）
 *  - 持续行走超过 30 秒（避免误触）
 *  - 距离上次提醒超过冷却时间（10 分钟）
 *  - 摄像头路况未占用相机
 *
 * 通知按钮"打开手电筒"点击后即开手电并取消通知。
 * 关闭手电走系统快捷设置磁贴即可，避免跨进程状态同步问题。
 */
public final class NightFlashlight {

    public static final String ACTION_TORCH_ON = "io.github.wumisc.safewalk.TORCH_ON";
    private static final String CHANNEL_ID = "safewalk_night";
    private static final int NOTI_ID = 7301;
    private static final long COOL_MS = 10 * 60_000L;
    private static final long WALK_REQUIRE_MS = 30_000L;

    private static NightFlashlight inst;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context ctx;
    private final PowerManager pm;
    private final CameraManager cm;

    private boolean enabled;
    private long walkSince = -1;
    private long lastNotifyAt = 0;
    private String cameraId;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!enabled) return;
            try { check(); } catch (Throwable t) { SafeWalkInit.diag("night-flash: " + t); }
            main.postDelayed(this, 2000);
        }
    };

    private NightFlashlight(Context context) {
        this.ctx = context.getApplicationContext();
        this.pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        this.cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        discoverCamera();
    }

    public static void init(Context context) {
        if (inst == null) {
            inst = new NightFlashlight(context);
        }
    }

    public static void setEnabled(boolean on) {
        if (inst == null) return;
        if (on == inst.enabled) return;
        inst.enabled = on;
        inst.walkSince = -1;
        if (on) {
            inst.main.post(inst.tick);
        } else {
            inst.main.removeCallbacks(inst.tick);
        }
    }

    private void discoverCamera() {
        try {
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                Boolean hasFlash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                        && hasFlash != null && hasFlash) {
                    cameraId = id;
                    return;
                }
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("night-flash discover: " + t);
        }
    }

    private static boolean isNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 7;
    }

    private void check() {
        boolean screenOn = pm != null && pm.isInteractive();
        boolean walking = WalkDetector.isWalking();

        if (!screenOn || !walking || !isNight()) {
            walkSince = -1;
            return;
        }

        long now = System.currentTimeMillis();
        if (walkSince < 0) {
            walkSince = now;
        }
        if (now - walkSince < WALK_REQUIRE_MS) return;
        if (now - lastNotifyAt < COOL_MS) return;
        if (CameraOverlay.isShown()) return;

        showNotification();
        lastNotifyAt = now;
        walkSince = -1;
    }

    private void showNotification() {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "夜间手电筒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("夜间行走时提醒打开手电筒");
            nm.createNotificationChannel(ch);

            Intent intent = new Intent(ACTION_TORCH_ON).setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentTitle("夜间出行提醒")
                    .setContentText("你正在夜间行走，要打开手电筒照亮路面吗？")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .addAction(0, "打开手电筒", pi)
                    .build();
            nm.notify(NOTI_ID, n);
        } catch (Throwable t) {
            SafeWalkInit.diag("night-flash notify: " + t);
        }
    }

    /** 广播接收器：在 App 进程运行，点击后开手电并取消通知 */
    public static final class TorchReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            try {
                CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
                String camId = null;
                for (String id : cm.getCameraIdList()) {
                    Boolean flash = cm.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) { camId = id; break; }
                }                if (camId != null) {
                    cm.setTorchMode(camId, true);
                }
                NotificationManager nm = (NotificationManager)
                        c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(NOTI_ID);
            } catch (Throwable t) {
                // ignore
            }
        }
    }
}
