package io.github.wumisc.safewalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/* loaded from: classes3.dex */
public final class SafetyFeatures {
    private static final String CH_ID = "safewalk_safety";
    private static final int MAX_ID_REMINDERS = 2;
    private static final int NOTI_ID = 7400;
    private static final long SEDENTARY_MS = 7200000;
    private static final float STEP_METERS = 0.7f;
    private static SafetyFeatures inst;
    private boolean batteryWarned;
    private final Context ctx;
    private boolean enabled;
    private int idRemindersToday;
    private long lastActiveTime;
    private long lastIdReminderDay;
    private final PowerManager pm;
    private boolean receiverRegistered;
    private int totalSteps;
    private long walkStartStep;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { // from class: io.github.wumisc.safewalk.SafetyFeatures.1
        @Override // java.lang.Runnable
        public void run() {
            if (SafetyFeatures.this.enabled) {
                SafetyFeatures.this.checkSedentary();
                SafetyFeatures.this.checkIdReminder();
                SafetyFeatures.this.main.postDelayed(this, 60000L);
            }
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() { // from class: io.github.wumisc.safewalk.SafetyFeatures.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra("level", -1);
            int scale = i.getIntExtra("scale", 100);
            int pct = scale > 0 ? (level * 100) / scale : 100;
            if (pct <= 15 && !SafetyFeatures.this.batteryWarned && WalkDetector.isWalking()) {
                SafetyFeatures.this.batteryWarned = true;
                SafetyFeatures.this.showNotification("电量预警", "电量已到 " + pct + "%，建议提前缓存离线地图，关闭后台高耗电应用。");
            }
            if (pct > 20) {
                SafetyFeatures.this.batteryWarned = false;
            }
        }
    };

    private SafetyFeatures(Context c) {
        this.ctx = c.getApplicationContext();
        this.pm = (PowerManager) this.ctx.getSystemService("power");
    }

    public static void init(Context c) {
        if (inst == null) {
            inst = new SafetyFeatures(c);
        }
    }

    public static void setEnabled(boolean on) {
        if (inst == null || on == inst.enabled) {
            return;
        }
        inst.enabled = on;
        SafetyFeatures safetyFeatures = inst;
        if (on) {
            safetyFeatures.start();
        } else {
            safetyFeatures.stop();
        }
    }

    private void start() {
        if (!this.receiverRegistered) {
            IntentFilter f = new IntentFilter("android.intent.action.BATTERY_CHANGED");
            this.ctx.registerReceiver(this.batteryReceiver, f);
            this.receiverRegistered = true;
        }
        this.lastActiveTime = System.currentTimeMillis();
        this.main.postDelayed(this.tick, 60000L);
    }

    private void stop() {
        this.main.removeCallbacks(this.tick);
        if (this.receiverRegistered) {
            try {
                this.ctx.unregisterReceiver(this.batteryReceiver);
            } catch (Throwable th) {
            }
            this.receiverRegistered = false;
        }
    }

    public static void onStep() {
        if (inst == null || !inst.enabled) {
            return;
        }
        inst.totalSteps++;
        inst.lastActiveTime = System.currentTimeMillis();
        if (inst.walkStartStep == 0) {
            inst.walkStartStep = inst.totalSteps;
        }
    }

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
            showNotification("出门提醒", "已移动约 " + ((int) meters) + " 米，请确认身份证原件贴身妥善存放。");
            this.idRemindersToday++;
            this.walkStartStep = this.totalSteps;
        }
    }

    public void checkSedentary() {
        boolean screenOn = this.pm != null && this.pm.isInteractive();
        if (!screenOn) {
            this.lastActiveTime = System.currentTimeMillis();
            return;
        }
        long idle = System.currentTimeMillis() - this.lastActiveTime;
        if (idle >= SEDENTARY_MS) {
            long hours = idle / 3600000;
            showNotification("久坐提醒", "已静止 " + hours + " 小时，起身活动，舒展肢体，缓解神经、肌肉压迫不适。");
            this.lastActiveTime = System.currentTimeMillis();
        }
    }

    public static void showSocialCard() {
        if (inst == null) {
            return;
        }
        inst.showNotification("社交礼仪提示", "守时赴约，不打探隐私，不强行劝酒，尊重对方边界。");
    }

    public void showNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) this.ctx.getSystemService("notification");
            NotificationChannel ch = new NotificationChannel(CH_ID, "安全提醒", 4);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(this.ctx, CH_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setAutoCancel(true).setPriority(1).build();
            nm.notify(((int) (System.currentTimeMillis() % 1000)) + NOTI_ID, n);
        } catch (Throwable t) {
            SafeWalkInit.diag("safety notify: " + t);
        }
    }
}
