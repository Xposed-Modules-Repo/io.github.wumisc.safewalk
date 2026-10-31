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
import androidx.core.app.NotificationCompat;
import java.util.Calendar;

/* loaded from: classes3.dex */
public final class NightFlashlight {
    public static final String ACTION_TORCH_ON = "io.github.wumisc.safewalk.TORCH_ON";
    private static final String CHANNEL_ID = "safewalk_night";
    private static final long COOL_MS = 600000;
    private static final int NOTI_ID = 7301;
    private static final long WALK_REQUIRE_MS = 30000;
    private static NightFlashlight inst;
    private String cameraId;
    private final CameraManager cm;
    private final Context ctx;
    private boolean enabled;
    private final PowerManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long walkSince = -1;
    private long lastNotifyAt = 0;
    private final Runnable tick = new Runnable() { // from class: io.github.wumisc.safewalk.NightFlashlight.1
        @Override // java.lang.Runnable
        public void run() {
            if (NightFlashlight.this.enabled) {
                try {
                    NightFlashlight.this.check();
                } catch (Throwable t) {
                    SafeWalkInit.diag("night-flash: " + t);
                }
                NightFlashlight.this.main.postDelayed(this, 5000L);
            }
        }
    };

    private NightFlashlight(Context context) {
        this.ctx = context.getApplicationContext();
        this.pm = (PowerManager) this.ctx.getSystemService("power");
        this.cm = (CameraManager) this.ctx.getSystemService("camera");
        discoverCamera();
    }

    public static void init(Context context) {
        if (inst == null) {
            inst = new NightFlashlight(context);
        }
    }

    public static void setEnabled(boolean on) {
        if (inst == null || on == inst.enabled) {
            return;
        }
        inst.enabled = on;
        inst.walkSince = -1L;
        if (on) {
            inst.main.post(inst.tick);
        } else {
            inst.main.removeCallbacks(inst.tick);
        }
    }

    private void discoverCamera() {
        try {
            for (String id : this.cm.getCameraIdList()) {
                Integer facing = (Integer) this.cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                Boolean hasFlash = (Boolean) this.cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null && facing.intValue() == 1 && hasFlash != null && hasFlash.booleanValue()) {
                    this.cameraId = id;
                    return;
                }
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("night-flash discover: " + t);
        }
    }

    private static boolean isNight() {
        int h = Calendar.getInstance().get(11);
        return h >= 22 || h < 7;
    }

    public void check() {
        boolean screenOn = this.pm != null && this.pm.isInteractive();
        boolean walking = WalkDetector.isWalking();
        if (!screenOn || !walking || !isNight()) {
            this.walkSince = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        if (this.walkSince < 0) {
            this.walkSince = now;
        }
        if (now - this.walkSince >= WALK_REQUIRE_MS && now - this.lastNotifyAt >= COOL_MS && !CameraOverlay.isShown()) {
            showNotification();
            this.lastNotifyAt = now;
            this.walkSince = -1L;
        }
    }

    private void showNotification() {
        try {
            NotificationManager nm = (NotificationManager) this.ctx.getSystemService("notification");
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "夜间手电筒", 4);
            ch.setDescription("夜间行走时提醒打开手电筒");
            nm.createNotificationChannel(ch);
            Intent intent = new Intent("io.github.wumisc.safewalk.TORCH_ON").setPackage(this.ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this.ctx, 0, intent, 201326592);
            Notification n = new Notification.Builder(this.ctx, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("夜间出行提醒").setContentText("你正在夜间行走，要打开手电筒照亮路面吗？").setContentIntent(pi).setAutoCancel(true).setPriority(1).setCategory(NotificationCompat.CATEGORY_REMINDER).addAction(0, "打开手电筒", pi).build();
            nm.notify(NOTI_ID, n);
        } catch (Throwable t) {
            SafeWalkInit.diag("night-flash notify: " + t);
        }
    }

    /* loaded from: classes3.dex */
    public static final class TorchReceiver extends BroadcastReceiver {
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context c, Intent intent) {
            try {
                CameraManager cm = (CameraManager) c.getSystemService("camera");
                String camId = null;
                String[] cameraIdList = cm.getCameraIdList();
                int length = cameraIdList.length;
                int i = 0;
                while (true) {
                    if (i < length) {
                        String id = cameraIdList[i];
                        Boolean flash = (Boolean) cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        if (flash != null && flash.booleanValue()) {
                            camId = id;
                            break;
                        }
                        i++;
                    } else {
                        break;
                    }
                }
                if (camId != null) {
                    cm.setTorchMode(camId, true);
                }
                NotificationManager nm = (NotificationManager) c.getSystemService("notification");
                nm.cancel(NightFlashlight.NOTI_ID);
            } catch (Throwable th) {
            }
        }
    }
}
