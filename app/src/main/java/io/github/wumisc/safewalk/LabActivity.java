package io.github.wumisc.safewalk;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 功能实验区：调试用，独立注册传感器。
 */
public class LabActivity extends Activity implements SensorEventListener {

    private TextView tvStep, tvWalkState, tvXiaomiStep, tvLog;
    private Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private Sensor accel, stepCounter;

    private int labSteps = 0;
    private long lastStepTime = 0;
    private float lastMag = 0;
    private boolean labWalking = false;
    private long walkSince = 0;

    private long xiaomiBase = -1;
    private long xiaomiSteps = 0;
    private StringBuilder logBuf = new StringBuilder();

    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            buildUI();
            log("实验区启动");
        } catch (Throwable t) {
            LinearLayout err = new LinearLayout(this);
            err.setOrientation(LinearLayout.VERTICAL);
            err.setPadding(40, 80, 40, 40);
            TextView et = new TextView(this);
            et.setText("崩溃:\n" + android.util.Log.getStackTraceString(t));
            et.setTextSize(12);
            err.addView(et);
            setContentView(err);
        }
    }

    private void buildUI() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 60);
        root.setBackgroundColor(0xFFF5F0E8);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("🧪 功能实验区");
        title.setTextSize(24);
        title.setTextColor(0xFF1B3A5C);
        root.addView(title);

        LinearLayout card = card();
        tvStep = row(card, "本实验区步数");
        tvWalkState = row(card, "行走状态");
        tvXiaomiStep = row(card, "小米计步器");
        root.addView(card);

        root.addView(btn("清空实验区步数", v -> {
            labSteps = 0; labWalking = false;
            log("步数已清空");
        }));

        root.addView(label("—— 功能测试 ——"));
        root.addView(btn("通知测试", v -> { testNotify("测试通知", "如果你看到这条，通知正常"); log("通知测试"); }));
        root.addView(btn("电量预警", v -> { testNotify("电量预警", "快没电了，赶紧找个充电宝或就近充电"); log("电量预警"); }));
        root.addView(btn("证件提醒", v -> { testNotify("证件提醒", "身份证、护照等重要证件一定要贴身放好"); log("证件提醒"); }));
        root.addView(btn("久坐提醒", v -> { testNotify("久坐提醒", "已坐了2小时了，该起来运动运动了"); log("久坐提醒"); }));
        root.addView(btn("社交礼仪", v -> { testNotify("社交提示", "守时，不打探隐私，不强行劝酒"); log("社交礼仪"); }));
        root.addView(btn("手电筒开关", v -> toggleTorch()));
        root.addView(btn("夜间手电通知", v -> { testNotify("夜间照明", "天黑了，走路小心，建议开启手电筒照亮前路"); log("夜间手电通知"); }));
        root.addView(btn("🔦 手电悬浮通知测试", v -> testTorchNotify()));
        root.addView(btn("🔦 关闭手电悬浮通知", v -> dismissTorchNotify()));
        root.addView(label("—— 行走模式测试 ——"));
        root.addView(btn("🚶 打开行走模式（全屏）", v -> openRearCamFull()));
        root.addView(btn("🚶 打开行走模式（小窗）", v -> openRearCamSmall()));
        root.addView(btn("🚶 关闭行走模式", v -> closeRearCam()));
        root.addView(btn("模拟出门触发", v -> { testNotify("出门提醒", "检测到持续步行，证件已贴身放好？"); log("模拟出门触发"); }));

        root.addView(label("—— 调试日志 ——"));
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setLineSpacing(2, 1f);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setBackgroundColor(0xFF1A1A1A);
        tvLog.setTextColor(0xFF00FF00);
        tvLog.setPadding(16, 16, 16, 16);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 300);
        llp.topMargin = 10;
        tvLog.setLayoutParams(llp);
        root.addView(tvLog);

        root.addView(btn("📋 复制日志", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logBuf.toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }));

        sv.addView(root);
        setContentView(sv);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        log(accel != null ? "加速度计已注册" : "无加速度计");
        log(stepCounter != null ? "硬件计步器已注册" : "无硬件计步器");
    }

    private TextView row(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = new TextView(this);
        l.setText(label + ": ");
        l.setTextSize(14); l.setTextColor(0xFF8B7D6B);
        TextView v = new TextView(this);
        v.setTextSize(14); v.setTextColor(0xFF1A1A1A);
        r.addView(l); r.addView(v);
        r.setPadding(0, 10, 0, 10);
        parent.addView(r);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFFFFFCF5);
        c.setPadding(30, 20, 30, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 30;
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(13); t.setTextColor(0xFF8B7D6B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = 30;
        t.setLayoutParams(lp);
        return t;
    }

    private Button btn(String text, Runnable l) { return btn(text, v -> l.run()); }
    private Button btn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false);
        b.setBackgroundColor(0xFF1B3A5C); b.setTextColor(0xFFFFFFFF);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 15;
        b.setLayoutParams(lp);
        return b;
    }

    private void log(String msg) {
        logBuf.append(SDF.format(new Date())).append(" ").append(msg).append("\n");
        if (tvLog != null) tvLog.setText(logBuf.toString());
    }

    private boolean torchOn = false;
    private void toggleTorch() {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            String flashId = null;
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics props = cm.getCameraCharacteristics(id);
                Boolean hasFlash = props.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) { flashId = id; break; }
            }
            if (flashId == null) { log("无闪光灯"); return; }
            torchOn = !torchOn;
            cm.setTorchMode(flashId, torchOn);
            log("手电筒: " + (torchOn ? "开" : "关"));
        } catch (Throwable t) { log("手电筒失败: " + t.getMessage()); }
    }

    private void testNotify(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String ch = "safewalk_test";
            nm.createNotificationChannel(new android.app.NotificationChannel(ch, "测试", NotificationManager.IMPORTANCE_HIGH));
            Notification n = new Notification.Builder(this, ch)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setTimeoutAfter(60_000).build();
            nm.notify((int) System.currentTimeMillis() % 10000, n);
            log("通知: " + title);
        } catch (Throwable t) { log("失败: " + t.getMessage()); }
    }

    // ========== 手电悬浮通知测试 ==========
    private void testTorchNotify() {
        try {
            // 先创建通道（高优先级+震动+声音，确保是悬浮通知）
            String ch = "safewalk_torch";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel = new android.app.NotificationChannel(ch, "SafeWalk夜间手电", NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 200, 100, 200});
            channel.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), null);
            nm.createNotificationChannel(channel);

            // 打开手电
            toggleTorch();

            // 构建带按钮的通知
            android.app.PendingIntent closePi = android.app.PendingIntent.getBroadcast(
                this, 200,
                new Intent("io.github.wumisc.safewalk.CLOSE_TORCH").setPackage(getPackageName()),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );

            Notification n = new Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("手电筒已开启")
                .setContentText("已长时间静置，记得及时关闭手电节约电量")
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setDefaults(Notification.DEFAULT_ALL)
                .setTimeoutAfter(60_000)
                .addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "关闭手电", closePi
                ).build())
                .build();
            nm.notify(2003, n);
            log("手电悬浮通知已弹出");
        } catch (Throwable t) { log("手电通知失败: " + t.getMessage()); }
    }

    private void dismissTorchNotify() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(2003);
            log("手电通知已关闭");
        } catch (Throwable t) { log("失败: " + t.getMessage()); }
    }

    // ========== 行走模式测试（写入safewalk.conf，对齐2.0最好版） ==========
    private void sendRearCamTestAction(String action) {
        try {
            // 先读取现有配置，再修改对应字段
            StringBuilder conf = new StringBuilder();
            conf.append("enable=true\n");
            conf.append("walk_mode=").append(action.equals("close") ? "false" : "true").append("\n");
            conf.append("auto_walk=false\n");
            conf.append("overlay_form=").append(action.equals("open_small") ? "0" : "1").append("\n");
            conf.append("alpha=55\n");
            conf.append("corner=2\n");

            // Base64编码后通过su写入
            String b64 = android.util.Base64.encodeToString(conf.toString().getBytes(), android.util.Base64.NO_WRAP);
            Process p = new ProcessBuilder("su", "-c",
                "echo " + b64 + " | base64 -d > /data/local/tmp/safewalk.conf && chmod 644 /data/local/tmp/safewalk.conf").start();
            boolean ok = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) {
                log("已写入safewalk.conf: " + action);
            } else {
                log("写入失败，请检查Root权限");
            }
        } catch (Throwable t) { log("发送指令失败: " + t.getMessage()); }
    }

    private void openRearCamFull() { sendRearCamTestAction("open_full"); }
    private void openRearCamSmall() { sendRearCamTestAction("open_small"); }
    private void closeRearCam() { sendRearCamTestAction("close"); }

    @Override protected void onResume() {
        super.onResume();
        try {
            if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            if (stepCounter != null) sm.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_UI);
            main.postDelayed(refresh, 500);
        } catch (Throwable t) { log("onResume err: " + t.getMessage()); }
    }

    @Override protected void onPause() {
        super.onPause();
        try { sm.unregisterListener(this); } catch (Throwable ignored) {}
        main.removeCallbacks(refresh);
    }

    private Runnable refresh = new Runnable() {
        @Override public void run() {
            try {
                tvStep.setText(labSteps + " 步");
                tvWalkState.setText(labWalking ? "🚶 行走中" : "🚫 静止");
                tvXiaomiStep.setText(xiaomiSteps + " 步");
            } catch (Throwable t) { log("refresh err: " + t.getMessage()); }
            main.postDelayed(this, 500);
        }
    };

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float mag = (float) Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]);
            long now = System.currentTimeMillis();
            if (Math.abs(mag - lastMag) > 0.8 && now - lastStepTime > 250) {
                labSteps++; lastStepTime = now; labWalking = true; walkSince = now;
                if (labSteps % 10 == 0) log("第 " + labSteps + " 步");
            }
            if (now - walkSince > 5000) labWalking = false;
            lastMag = mag;
        } else if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            long total = (long) e.values[0];
            if (xiaomiBase < 0) xiaomiBase = total;
            xiaomiSteps = total - xiaomiBase;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
