package io.github.wumisc.safewalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import java.util.Calendar;

/* loaded from: classes3.dex */
public final class SafetyHub {
    public static final String ACTION_TORCH_ON = "io.github.wumisc.safewalk.TORCH_ON";
    private static final String CH_NIGHT = "safewalk_night";
    private static final String CH_SAFETY = "safewalk_safety";
    private static final int MAX_ID_REMINDERS = 2;
    private static final int NOTI_BASE = 7400;
    private static final int NOTI_TORCH = 7301;
    private static final long SEDENTARY_MS = 7200000;
    private static final float STEP_METERS = 0.7f;
    private static final long TORCH_COOL_MS = 600000;
    private static final long TORCH_WALK_REQUIRE_MS = 30000;
    private static SafetyHub inst;
    private boolean batteryReceiverRegistered;
    private boolean batteryWarned;
    private String cameraId;
    private final CameraManager cm;
    private final Context ctx;
    private int idRemindersToday;
    private long lastActiveTime;
    private long lastIdReminderDay;
    private boolean nightEnabled;
    private final PowerManager pm;
    private boolean safetyEnabled;
    private int totalSteps;
    private long walkStartStep;
    private long torchWalkSince = -1;
    private long lastTorchNotifyAt = 0;
    private final Runnable nightTick = new Runnable() { // from class: io.github.wumisc.safewalk.SafetyHub.1
        @Override // java.lang.Runnable
        public void run() {
            if (SafetyHub.this.nightEnabled) {
                try {
                    SafetyHub.this.checkNightTorch();
                } catch (Throwable t) {
                    DiagLogger.e("SafetyHub", "nightTick: " + t);
                }
                SafetyHub.this.main.postDelayed(this, 5000L);
            }
        }
    };
    private final Runnable safetyTick = new Runnable() { // from class: io.github.wumisc.safewalk.SafetyHub.2
        @Override // java.lang.Runnable
        public void run() {
            if (SafetyHub.this.safetyEnabled) {
                try {
                    SafetyHub.this.checkSedentary();
                    SafetyHub.this.checkIdReminder();
                } catch (Throwable t) {
                    DiagLogger.e("SafetyHub", "safetyTick: " + t);
                }
                SafetyHub.this.main.postDelayed(this, 60000L);
            }
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() { // from class: io.github.wumisc.safewalk.SafetyHub.3
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context c, Intent i) {
            try {
                int level = i.getIntExtra("level", -1);
                int scale = i.getIntExtra("scale", 100);
                int pct = scale > 0 ? (level * 100) / scale : 100;
                if (pct <= 15 && !SafetyHub.this.batteryWarned && WalkDetector.isWalking()) {
                    SafetyHub.this.batteryWarned = true;
                    SafetyHub.this.showNotification(SafetyHub.CH_SAFETY, "安全提醒", "电量预警", "电量已到 " + pct + "%，建议提前缓存离线地图，关闭后台高耗电应用。");
                }
                if (pct > 20) {
                    SafetyHub.this.batteryWarned = false;
                }
            } catch (Throwable t) {
                DiagLogger.e("SafetyHub", "batteryReceiver: " + t);
            }
        }
    };
    private final Handler main = new Handler(Looper.getMainLooper());

    private SafetyHub(Context c) {
        this.ctx = c.getApplicationContext();
        this.pm = (PowerManager) this.ctx.getSystemService("power");
        this.cm = (CameraManager) this.ctx.getSystemService("camera");
        discoverTorchCamera();
    }

    public static void init(Context c) {
        if (inst == null) {
            inst = new SafetyHub(c);
            DiagLogger.i("SafetyHub", "init ok, torchCamera=" + (inst.cameraId == null ? "none" : inst.cameraId));
        }
    }

    public static void setNightEnabled(boolean on) {
        if (inst == null || on == inst.nightEnabled) {
            return;
        }
        inst.nightEnabled = on;
        inst.torchWalkSince = -1L;
        if (on) {
            inst.main.post(inst.nightTick);
        } else {
            inst.main.removeCallbacks(inst.nightTick);
        }
    }

    public static void setSafetyEnabled(boolean on) {
        if (inst == null || on == inst.safetyEnabled) {
            return;
        }
        inst.safetyEnabled = on;
        if (on) {
            inst.startSafety();
        } else {
            inst.stopSafety();
        }
    }

    public static void onStep() {
        if (inst == null || !inst.safetyEnabled) {
            return;
        }
        inst.totalSteps++;
        inst.lastActiveTime = System.currentTimeMillis();
        if (inst.walkStartStep == 0) {
            inst.walkStartStep = inst.totalSteps;
        }
    }

    public static void showSocialCard() {
        if (inst == null) {
            return;
        }
        inst.showNotification(CH_SAFETY, "安全提醒", "社交礼仪提示", "守时赴约，不打探隐私，不强行劝酒，尊重对方边界。");
    }

    private void startSafety() {
        if (!this.batteryReceiverRegistered) {
            try {
                IntentFilter f = new IntentFilter("android.intent.action.BATTERY_CHANGED");
                this.ctx.registerReceiver(this.batteryReceiver, f);
                this.batteryReceiverRegistered = true;
            } catch (Throwable t) {
                DiagLogger.e("SafetyHub", "register battery rx: " + t);
            }
        }
        this.lastActiveTime = System.currentTimeMillis();
        this.main.postDelayed(this.safetyTick, 60000L);
    }

    private void stopSafety() {
        this.main.removeCallbacks(this.safetyTick);
        if (this.batteryReceiverRegistered) {
            try {
                this.ctx.unregisterReceiver(this.batteryReceiver);
            } catch (Throwable th) {
            }
            this.batteryReceiverRegistered = false;
        }
    }

    private void discoverTorchCamera() {
        try {
            for (String id : this.cm.getCameraIdList()) {
                CameraCharacteristics cc = this.cm.getCameraCharacteristics(id);
                Integer facing = (Integer) cc.get(CameraCharacteristics.LENS_FACING);
                Boolean hasFlash = (Boolean) cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null && facing.intValue() == 1 && hasFlash != null && hasFlash.booleanValue()) {
                    this.cameraId = id;
                    return;
                }
            }
        } catch (Throwable t) {
            DiagLogger.e("SafetyHub", "torch discover: " + t);
        }
    }

    private static boolean isNight() {
        int h = Calendar.getInstance().get(11);
        return h >= 22 || h < 7;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkNightTorch() {
        boolean screenOn = this.pm != null && this.pm.isInteractive();
        boolean walking = WalkDetector.isWalking();
        if (!screenOn || !walking || !isNight()) {
            this.torchWalkSince = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        if (this.torchWalkSince < 0) {
            this.torchWalkSince = now;
        }
        if (now - this.torchWalkSince >= TORCH_WALK_REQUIRE_MS && now - this.lastTorchNotifyAt >= TORCH_COOL_MS && !CameraOverlay.isShown()) {
            showTorchNotification();
            this.lastTorchNotifyAt = now;
            this.torchWalkSince = -1L;
        }
    }

    private void showTorchNotification() {
        try {
            Intent intent = new Intent("io.github.wumisc.safewalk.TORCH_ON").setPackage(this.ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this.ctx, 0, intent, 201326592);
            Notification n = new Notification.Builder(this.ctx, CH_NIGHT).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("夜间出行提醒").setContentText("你正在夜间行走，要打开手电筒照亮路面吗？").setContentIntent(pi).setAutoCancel(true).setPriority(1).setCategory(NotificationCompat.CATEGORY_REMINDER).addAction(0, "打开手电筒", pi).build();
            NotificationManager nm = (NotificationManager) this.ctx.getSystemService("notification");
            ensureChannel(nm, CH_NIGHT, "夜间手电筒", "夜间行走时提醒打开手电筒");
            nm.notify(NOTI_TORCH, n);
            DiagLogger.i("SafetyHub", "torch reminder shown");
        } catch (Throwable t) {
            DiagLogger.e("SafetyHub", "torch notify: " + t);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkIdReminder() {
        long today = System.currentTimeMillis() / 86400000;
        if (today != this.lastIdReminderDay) {
            this.idRemindersToday = 0;
            this.lastIdReminderDay = today;
        }
        if (this.idRemindersToday >= 2) {
            return;
        }
        int walked = this.totalSteps - ((int) this.walkStartStep);
        float meters = walked * STEP_METERS;
        if (meters >= 200.0f && this.walkStartStep > 0) {
            showNotification(CH_SAFETY, "安全提醒", "出门提醒", "已移动约 " + ((int) meters) + " 米，请确认身份证原件贴身妥善存放。");
            this.idRemindersToday++;
            this.walkStartStep = this.totalSteps;
            DiagLogger.i("SafetyHub", "id reminder, meters=" + ((int) meters));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkSedentary() {
        boolean screenOn = this.pm != null && this.pm.isInteractive();
        if (!screenOn) {
            this.lastActiveTime = System.currentTimeMillis();
            return;
        }
        long idle = System.currentTimeMillis() - this.lastActiveTime;
        if (idle >= SEDENTARY_MS) {
            long hours = idle / 3600000;
            showNotification(CH_SAFETY, "安全提醒", "久坐提醒", "已静止 " + hours + " 小时，起身活动，舒展肢体，缓解神经、肌肉压迫不适。");
            this.lastActiveTime = System.currentTimeMillis();
            DiagLogger.i("SafetyHub", "sedentary reminder, hours=" + hours);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showNotification(String channelId, String channelName, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) this.ctx.getSystemService("notification");
            ensureChannel(nm, channelId, channelName, channelName + "提醒");
            Notification n = new Notification.Builder(this.ctx, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setAutoCancel(true).setPriority(1).build();
            nm.notify(((int) (System.currentTimeMillis() % 1000)) + NOTI_BASE, n);
        } catch (Throwable t) {
            DiagLogger.e("SafetyHub", "notify: " + t);
        }
    }

    private static void ensureChannel(NotificationManager nm, String id, String name, String desc) {
        try {
            NotificationChannel ch = new NotificationChannel(id, name, 4);
            ch.setDescription(desc);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            DiagLogger.e("SafetyHub", "ensureChannel: " + t);
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
                    if (i >= length) {
                        break;
                    }
                    String id = cameraIdList[i];
                    Boolean flash = (Boolean) cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash.booleanValue()) {
                        camId = id;
                        break;
                    }
                    i++;
                }
                if (camId != null) {
                    cm.setTorchMode(camId, true);
                }
                NotificationManager nm = (NotificationManager) c.getSystemService("notification");
                nm.cancel(SafetyHub.NOTI_TORCH);
            } catch (Throwable th) {
            }
        }
    }
}
