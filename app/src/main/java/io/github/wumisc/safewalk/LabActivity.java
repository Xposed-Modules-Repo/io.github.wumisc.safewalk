package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    /** 实验区运行时权限申请码（通知 + 相机） */
    private static final int REQ_LAB_PERMS = 4001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DiagLog.init(getApplicationContext());
        try {
            buildUI();
            startTorchMonitor();
            ensureRuntimePermissions();
            log("实验区启动");
            log("日志目录: " + DiagLog.status());
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

    /**
     * 实验区的功能大多依赖运行时权限，缺一个就是「点了没反应」：
     *  - POST_NOTIFICATIONS（Android 13+）：通知测试 / 夜间手电提醒 / 悬浮通知全走 notify()，
     *    没授权时系统静默丢弃，看起来就是「无效」；
     *  - CAMERA：手电 setTorchMode 和预览都必需。
     * 进页面时一次性把缺的都要过来，避免用户点哪个按钮哪个没反应。
     */
    private void ensureRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_LAB_PERMS);
        } else {
            log("运行时权限齐备：通知 + 相机");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_LAB_PERMS) return;
        StringBuilder sb = new StringBuilder("权限结果: ");
        for (int i = 0; i < perms.length; i++) {
            sb.append(perms[i]).append("=")
                    .append(results.length > i && results[i] == PackageManager.PERMISSION_GRANTED
                            ? "已授权" : "拒绝")
                    .append(" ");
        }
        log(sb.toString());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予通知权限，通知类测试不会弹出；可在系统设置里开启",
                    Toast.LENGTH_LONG).show();
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
        root.addView(btn("夜间手电提醒（可点开灯）", v -> testNightTorchSuggest()));
        root.addView(btn("🔦 手电悬浮通知测试", v -> testTorchNotify()));
        root.addView(btn("🔦 关闭手电悬浮通知", v -> dismissTorchNotify()));
        root.addView(label("—— 行走模式预览 ——"));
        root.addView(btn("📷 全屏预览", v -> openRearCamFull()));
        root.addView(btn("📷 小窗预览", v -> openRearCamSmall()));
        root.addView(btn("📷 关闭预览", v -> closeRearCam()));
        root.addView(btn("模拟出门触发", v -> { testNotify("出门提醒", "检测到持续步行，证件已贴身放好？"); log("模拟出门触发"); }));

        root.addView(label("—— 诊断日志 ——"));
        root.addView(btn("📂 当前日志目录", v -> {
            String st = DiagLog.status();
            Toast.makeText(this, st, Toast.LENGTH_LONG).show();
            log("日志目录: " + st);
            log("探针结果: " + DiagLog.probeReport());
        }));
        root.addView(btn("🔓 授予「所有文件访问」（写入 Android/Travel safely）",
                v -> openAllFilesAccess()));
        root.addView(btn("📥 拉取系统界面日志", v -> requestHookLog()));
        root.addView(btn("🧭 步态自检（正常走 8 秒）", v -> runGaitSelfTest()));
        root.addView(btn("📄 查看 diag.log 末尾", v -> showLogTail()));

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
        // 实验区可能一开就是几小时，日志无上限增长最终会把一个 TextView 撑爆。
        // 只保留最近的一段，够看就行。
        if (logBuf.length() > 8000) {
            logBuf.delete(0, logBuf.length() - 6000);
        }
        if (tvLog != null) tvLog.setText(logBuf.toString());
    }

    private boolean torchOn = false;

    private static final String SYSTEMUI_PKG = "com.android.systemui";

    // ========== 诊断日志 ==========

    /** 跳到「所有文件访问」授权页：不给这个权限，Android 11+ 写不进 Android/ 下的目录 */
    private void openAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            log("系统版本低于 Android 11，不需要该权限");
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            log("已跳转「所有文件访问」授权页");
        } catch (Throwable t) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                Toast.makeText(this, "跳转失败，请到系统设置手动开启", Toast.LENGTH_LONG).show();
                log("跳转失败: " + t.getMessage());
            }
        }
    }

    /** 让 SystemUI 里的 Hook 把内存日志回传一份，落到本应用的 diag.log */
    private void requestHookLog() {
        try {
            android.content.Intent i = new android.content.Intent(DiagLog.ACTION_REQUEST_LOG);
            i.setPackage(SYSTEMUI_PKG);
            i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            sendBroadcast(i);
            log("已请求系统界面日志；等 1~2 秒后点「当前日志目录」查看落盘位置");
        } catch (Throwable t) {
            log("请求系统界面日志失败: " + t.getMessage());
        }
    }

    // ========== 步态自检 ==========

    /**
     * 用与 Hook 完全相同的算法，在本进程采 8 秒加速度计。
     *
     * 目的只有一个：把「传感器/算法有问题」和「系统界面进程里的 Hook 没跑起来」分开。
     * 以前这两种情况在用户看来一模一样 —— 走半天，什么都不发生。
     * 自检识别到步数就说明前半段是好的，问题在 Hook 侧；识别不到就是算法在这台机器上不适用。
     */
    private void runGaitSelfTest() {
        if (sm == null || accel == null) {
            log("没有加速度计，无法自检");
            return;
        }
        final GaitDetector gait = new GaitDetector();
        final int[] steps = {0};
        final long dur = 8000L;

        log("开始步态自检：请拿着手机正常走 8 秒…");

        final SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                float[] v = e.values;
                // 直接复用 Hook 用的同一套算法，自检结果才有可比性
                if (gait.feedAcc(v[0], v[1], v[2])) steps[0]++;
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };

        try {
            sm.registerListener(l, accel, SensorManager.SENSOR_DELAY_GAME);
        } catch (Throwable t) {
            log("注册加速度计失败: " + t.getMessage());
            return;
        }

        main.postDelayed(() -> {
            try { sm.unregisterListener(l); } catch (Throwable ignored) {}
            boolean ok = gait.hasStableGait();
            log("自检结果：识别 " + steps[0] + " 步 / 8 秒；" + gait.debugInfo() + "；稳定步态=" + ok);
            log(ok
                    ? "→ 传感器与算法正常，问题在系统界面进程（Hook 未生效 / 作用域没勾系统界面）"
                    : "→ 算法在这台设备上判不出步行，请把上面这行发我");
        }, dur);
    }

    private void showLogTail() {
        String text = DiagLog.tail(80);
        logBuf.setLength(0);
        log("===== diag.log 末尾 80 行 =====");
        log(text);
    }

    // ========== 手电真实状态 ==========
    private android.hardware.camera2.CameraManager.TorchCallback torchCallback;

    /**
     * 监听闪光灯的真实开关状态。
     *
     * 之前 `torchOn` 只是个本地布尔：发完广播就自己翻转。用户用下拉控制中心
     * 手动开过灯，或者手电被系统关掉，这里的记录就全错，
     * 点「手电筒开关」会发出与预期相反的指令。
     * TorchCallback 是 Camera2 的全局广播，不需要相机权限，读到的就是真实状态。
     */
    private void startTorchMonitor() {
        if (torchCallback != null) return;
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            torchCallback = new android.hardware.camera2.CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    torchOn = enabled;
                    log("闪光灯实际状态: " + (enabled ? "开" : "关"));
                }
            };
            cm.registerTorchCallback(torchCallback, null);
        } catch (Throwable t) {
            log("闪光灯状态监听失败: " + t.getMessage());
        }
    }

    private void stopTorchMonitor() {
        if (torchCallback == null) return;
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cm.unregisterTorchCallback(torchCallback);
        } catch (Throwable ignored) {
        }
        torchCallback = null;
    }

    /**
     * 实验室里开关手电。
     *
     * 这里**直接调应用进程的 TorchController**，不再发广播绕一圈。
     * 实验室的用途就是验证「这颗灯到底能不能亮」：如果这里能亮，
     * 说明相机、权限、闪光灯都正常，问题只在通知按钮的投递方式上；
     * 如果这里也亮不了，日志里的失败原因就是最终答案，不用再猜。
     */
    private void setTorch(boolean on) {
        TorchController.set(this, on, r -> {
            runOnUiThread(() -> {
                if (r.getOk()) {
                    log("手电已" + (on ? "开启" : "关闭") + "（应用内直连）");
                    Toast.makeText(this, "手电已" + (on ? "开启" : "关闭"),
                            Toast.LENGTH_SHORT).show();
                } else {
                    log("手电" + (on ? "开启" : "关闭") + "失败: " + r.getReason() + " " + r.getMessage());
                    Toast.makeText(this, "手电打不开: " + r.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    private void toggleTorch() {
        setTorch(!torchOn);
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

    /**
     * 夜间手电提醒（真实链路）。
     *
     * 与 Hook 端 SafeWalkNotificationManager.flashlightSuggest() 发的同一条：
     * 「手电筒」按钮发 OPEN_TORCH 给系统界面里的 Hook，由它调 CameraManager.setTorchMode。
     *
     * 原来试验区的「夜间手电通知」走的是 testNotify（纯文字、没有按钮），
     * 点了也验证不了「选项能不能开灯」——这里补上真正可点的版本。
     * 刻意不预先开灯：灯是灭的，点按钮后亮了才算链路通。
     */
    private void testNightTorchSuggest() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String ch = "safewalk_night_torch";
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    ch, "SafeWalk夜间照明", NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 200, 100, 200});
            channel.setSound(android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_NOTIFICATION), null);
            nm.createNotificationChannel(channel);

            android.content.Intent openIntent = new android.content.Intent(
                    TorchActionReceiver.ACTION_TORCH_ON);
            // 与正式通知完全一致：拉起透明 Activity。
            // 广播要靠「进程恰好还活着」，Activity 则由系统强制拉起，这是唯一有保证的路径。
            openIntent.setClassName(getPackageName(), getPackageName() + ".TorchActionActivity");
            openIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            openIntent.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(
                    this, 1, openIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.content.Intent appIntent = new android.content.Intent(this, MainActivity.class);
            android.app.PendingIntent appPi = android.app.PendingIntent.getActivity(
                    this, 2, appIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(this, ch)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("SafeWalk 夜间照明")
                    .setContentText("天黑了，点按钮开手电筒照亮前路")
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_media_play, "手电筒", openPi).build())
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_menu_view, "打开SafeWalk", appPi).build())
                    .setTimeoutAfter(120_000)
                    .build();
            nm.notify(1005, n);
            log("夜间手电提醒已弹出，点「手电筒」验证开灯");
        } catch (Throwable t) {
            log("夜间手电提醒失败: " + t.getMessage());
        }
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

            // 打开手电（走应用内 TorchActionReceiver，与正式通知同一条链路）
            setTorch(true);

            // 构建带按钮的通知：关闭按钮同样拉起透明 Activity
            android.content.Intent closeIntent = new android.content.Intent(
                    TorchActionReceiver.ACTION_TORCH_OFF);
            closeIntent.setClassName(getPackageName(), getPackageName() + ".TorchActionActivity");
            closeIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            closeIntent.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            android.app.PendingIntent closePi = android.app.PendingIntent.getActivity(
                this, 200, closeIntent,
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

    // ========== 行走模式测试（直接指挥 App 进程的预览服务，不再经过 SystemUI） ==========
    private void sendRearCamTestAction(String action) {
        try {
            boolean camOk = checkSelfPermission(android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean overlayOk = android.provider.Settings.canDrawOverlays(this);
            if (!camOk || !overlayOk) {
                if (!camOk) requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 2001);
                else Toast.makeText(this, "先到系统设置开启「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
                log("预览缺少权限（相机/悬浮窗），已请求");
                return;
            }
            String mode = "open_full".equals(action) ? "fullscreen"
                    : ("open_small".equals(action) ? "smallwindow" : null);
            if (mode == null) {
                WalkPreviewService.send(this, WalkPreviewService.ACTION_HIDE, null);
            } else {
                WalkPreviewService.start(this);
                WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, mode);
            }
            log("已发送行走模式指令: " + action);
        } catch (Throwable t) {
            log("发送指令失败: " + t.getMessage());
        }
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

    @Override
    protected void onDestroy() {
        stopTorchMonitor();
        super.onDestroy();
    }

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
