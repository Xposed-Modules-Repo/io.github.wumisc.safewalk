package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.View;
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
        // 相机权限刚被授予：自动重试之前被卡住的预览动作（全屏/小窗/关闭）。
        // 实验区「夜间手电提醒」的通知按钮同样依赖 CAMERA，权限到位后链路即通。
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && pendingRearCamAction != null) {
            String retry = pendingRearCamAction;
            pendingRearCamAction = null;
            log("相机权限已授予，自动重试预览: " + retry);
            sendRearCamTestAction(retry);
        }
    }

    private void buildUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(color(R.color.bg));
        sv.setFillViewport(true);
        int pad = dp(16);
        sv.setPadding(pad, dp(40), pad, dp(40));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);

        root.addView(pageTitle("功能实验区"));
        root.addView(pageSubtitle(
                "这里的按钮只写日志、发通知、拉预览，不改动正式配置（唯一例外是「预览」会临时打开行走模式开关，离开页面自动还原）。"));

        // —— 实时状态卡片 ——
        LinearLayout status = card(root, 16);
        tvStep = statusRow(status, "实验区步数", true);
        tvWalkState = statusRow(status, "行走状态", false);
        tvXiaomiStep = statusRow(status, "硬件计步器", false);

        // 按钮表：分组 + 文案 + 动作，加一项只加一行，不再是一长串 addView
        actionGroup(root, "功能测试", new Object[][]{
                {"清空实验区步数", (Runnable) () -> {
                    labSteps = 0;
                    labWalking = false;
                    log("步数已清空");
                }},
                {"通知测试", (Runnable) () -> {
                    testNotify("测试通知", "如果你看到这条，通知正常");
                    log("通知测试");
                }},
                {"电量预警", (Runnable) () -> {
                    testNotify("电量预警", "快没电了，赶紧找个充电宝或就近充电");
                    log("电量预警");
                }},
                {"证件提醒", (Runnable) () -> {
                    testNotify("证件提醒", "身份证、护照等重要证件一定要贴身放好");
                    log("证件提醒");
                }},
                {"久坐提醒", (Runnable) () -> {
                    testNotify("久坐提醒", "已坐了2小时了，该起来运动运动了");
                    log("久坐提醒");
                }},
                {"社交礼仪", (Runnable) () -> {
                    testNotify("社交提示", "守时，不打探隐私，不强行劝酒");
                    log("社交礼仪");
                }},
                {"手电筒开关", (Runnable) this::toggleTorch},
                {"夜间手电提醒（可点开灯）", (Runnable) this::testNightTorchSuggest},
                {"手电悬浮通知测试", (Runnable) this::testTorchNotify},
                {"关闭手电悬浮通知", (Runnable) this::dismissTorchNotify},
        });

        actionGroup(root, "行走模式预览", new Object[][]{
                {"全屏预览", (Runnable) this::openRearCamFull},
                {"小窗预览", (Runnable) this::openRearCamSmall},
                {"关闭预览", (Runnable) this::closeRearCam},
                {"模拟出门触发", (Runnable) () -> {
                    testNotify("出门提醒", "检测到持续步行，证件已贴身放好？");
                    log("模拟出门触发");
                }},
        });

        actionGroup(root, "诊断", new Object[][]{
                {"当前日志目录", (Runnable) () -> {
                    String st = DiagLog.status();
                    Toast.makeText(this, st, Toast.LENGTH_LONG).show();
                    log("日志目录: " + st);
                    log("探针结果: " + DiagLog.probeReport());
                }},
                {"授予「所有文件访问」（写入 Android/Travel safely）", (Runnable) this::openAllFilesAccess},
                {"拉取系统界面日志", (Runnable) this::requestHookLog},
                {"注入链路自检（对照当前版本的初始化链）", (Runnable) this::diagnoseHookChain},
                {"预览服务状态", (Runnable) this::dumpPreviewState},
                {"步态自检（正常走 8 秒）", (Runnable) this::runGaitSelfTest},
                {"查看 diag.log 末尾", (Runnable) this::showLogTail},
        });

        // —— 调试日志 ——
        root.addView(sectionLabel("调试日志"));
        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setLineSpacing(dp(2), 1f);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setTextIsSelectable(true);
        tvLog.setBackgroundColor(0xFF1C1C1E);
        tvLog.setTextColor(0xFF32D74B);
        tvLog.setPadding(dp(12), dp(12), dp(12), dp(12));
        // 原来写死 300px（约 100dp），在 1080p 屏上只够看四五行，日志一滚就看不到上下文。
        // 改成按屏幕高度取 42%，并用 dp 保证不同密度下观感一致，最少 280dp。
        int logH = Math.max(dp(280), (int) (getResources().getDisplayMetrics().heightPixels * 0.42f));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, logH);
        llp.topMargin = dp(8);
        tvLog.setLayoutParams(llp);
        root.addView(tvLog);

        LinearLayout logBar = new LinearLayout(this);
        logBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        logBar.setLayoutParams(blp);
        logBar.addView(pill("复制日志", color(R.color.primary), color(R.color.on_accent), () -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logBuf.toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }, 1f));
        logBar.addView(pill("清空", color(R.color.card_bg), color(R.color.primary), () -> {
            logBuf.setLength(0);
            if (tvLog != null) tvLog.setText("");
        }, 1f));
        root.addView(logBar);

        setContentView(sv);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        log(accel != null ? "加速度计已注册" : "无加速度计");
        log(stepCounter != null ? "硬件计步器已注册" : "无硬件计步器");
    }

    // ========== 视图构件（统一在这里，避免各处散落魔法值）==========
    //
    // 全部走 @color token：values-night 换一套值，这个页面自动跟着变深，
    // 原来这里写的是 0xFF1D1D1D 之类的常量，深色下白底黑字、黑底黑字都出现过。

    private int color(int id) {
        return getColor(id);
    }

    private TextView pageTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setTextColor(color(R.color.text_primary));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pageSubtitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(color(R.color.text_hint));
        t.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(color(R.color.text_hint));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 白卡片：加进 root 并返回，行由调用方往里塞 */
    private LinearLayout card(LinearLayout root, int topMarginDp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(color(R.color.card_bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        c.setLayoutParams(lp);
        root.addView(c);
        return c;
    }

    private View divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(color(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        parent.addView(v);
        return v;
    }

    /** 状态行：左标签 + 右值（只读） */
    private TextView statusRow(LinearLayout parent, String label, boolean first) {
        if (!first) divider(parent);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(13), dp(16), dp(13));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(16);
        l.setTextColor(color(R.color.text_primary));
        r.addView(l, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setTextSize(16);
        v.setTextColor(color(R.color.text_hint));
        r.addView(v);

        parent.addView(r);
        return v;
    }

    /** 可点击行：标题 + 行尾箭头，和首页/关于页同一套 */
    private void actionRow(LinearLayout parent, String text, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));

        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            r.setBackgroundResource(tv.resourceId);
        }
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(v -> action.run());

        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(16);
        l.setTextColor(color(R.color.text_primary));
        r.addView(l, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(color(R.color.chevron));
        r.addView(chevron);

        parent.addView(r);
    }

    /**
     * 按分组批量挂按钮。
     *
     * 原来是一长串 `root.addView(btn("…", v -> …))`，加一项就要复制一整行；
     * 而且 btn() 用的是「宽 0 + weight 1」的参数 —— 那是横向排布的写法，
     * 挂在纵向 LinearLayout 里宽度直接变 0，整排按钮全部不可见（实测就是一片空白）。
     * 现在改成行式列表，和首页视觉一致，也不会再踩宽度的坑。
     */
    private void actionGroup(LinearLayout root, String header, Object[][] items) {
        root.addView(sectionLabel(header));
        LinearLayout c = card(root, 0);
        for (int i = 0; i < items.length; i++) {
            if (i > 0) divider(c);
            actionRow(c, (String) items[i][0], (Runnable) items[i][1]);
        }
    }

    /** 并排小按钮（日志区的「复制 / 清空」），圆角实底 */
    private Button pill(String text, int bg, int fg, Runnable action, float weight) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(fg);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.topMargin = dp(8);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void diagnoseHookChain() {
        log("—— 注入链路自检 ——");
        log("预览服务存活=" + WalkPreviewService.isAlive()
                + " 正在显示=" + WalkPreviewService.isPreviewShowing());
        requestHookLog();
        new Thread(() -> {
            try {
                Thread.sleep(1800);
            } catch (InterruptedException ignored) {
            }
            // 读整个 diag.log，必须离开主线程
            String text = DiagLog.tail(900);
            runOnUiThread(() -> checkMarkers(text));
        }, "lab-diag").start();
    }

    private void checkMarkers(String text) {
        // 每条：标记字符串 / 含义 / 缺失时说明
        mark(text, "===== 进程日志回传", "系统界面日志已回传", "没收到回传：LSPosed 作用域没勾 SystemUI，或模块未启用");
        mark(text, "[hook] 锚点已挂上", "Hook 锚点挂载", "四个锚点全部没挂上，走的是反射轮询兜底");
        mark(text, "[hook] 初始化触发源", "初始化入口命中", "初始化根本没跑起来");
        mark(text, "core init", "核心初始化完成", "Context 或日志出口初始化失败（看重试日志）");
        mark(text, "初始化步骤失败", "有步骤单独失败", "（此项出现即为问题，日志里有步骤名）");
        mark(text, "已拉起透明自启页", "开机自启链路", "没拉起过：可能不是重启后首次进入，或系统拦了后台启动 Activity");
        mark(text, "[cam] 前台检测启用第", "相机前台检测降级", "未降级（第 1 级可用）属正常；出现第 2~4 级说明 ROM 限制隐藏 API");
        mark(text, "步数探针", "计步探针已触发", "45 秒内没写过探针，可能 tick 没转");
        log("—— 自检结束，以上 ✗ 项按括号里的说明排查 ——");
    }

    private void mark(String text, String marker, String okLabel, String failHint) {
        boolean ok = text != null && text.contains(marker);
        log((ok ? "✓ " : "✗ ") + okLabel + (ok ? "" : " — " + failHint));
    }

    /** 预览服务的实时状态：判断「该显示却不显示」时先看这里 */
    private void dumpPreviewState() {
        boolean alive = WalkPreviewService.isAlive();
        boolean showing = WalkPreviewService.isPreviewShowing();
        log("预览服务: " + (alive ? "存活" : "未启动") + " / 画面: " + (showing ? "在显示" : "已收起"));
        if (!alive) {
            log("提示：点「全屏预览」会顺带把它拉起来；若仍不存活，看 diag.log 里 startForeground 相关报错");
        } else if (!showing) {
            log("提示：服务活着但不显示，通常是相机被别的应用占用，或开关已被关掉");
        }
        Toast.makeText(this, "服务=" + (alive ? "存活" : "未启动") + "，画面=" + (showing ? "显示中" : "已收起"),
                Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
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

    /** 预览指令暂存：等相机权限授予后自动重试（实验区预览/手电都依赖 CAMERA） */
    private String pendingRearCamAction = null;
    /** 实验区临时打开预览开关前的原值，关闭预览时恢复，避免污染用户配置 */
    private boolean rearCamWasEnabled = false;
    private boolean rearCamOverridden = false;

    /**
     * 实验区「临时打开行走模式」的备份，落在 SharedPreferences 里而不是内存字段。
     *
     * 原来这两个值是实例字段：实验区强制把 enableRearCamera 写成 true 之后，
     * 用户直接返回（不走「关闭预览」按钮）就没人还原，而原始值只存在内存里，
     * 进程一被回收就永久丢失 —— 结果是「在实验区点过一次预览，行走模式从此常开，
     * 过一会儿全屏摄像头画面自己糊上来」。现在持久化备份，任何入口都能还原。
     */
    private static final String SP_LAB = "safewalk_lab";
    private static final String KEY_BACKUP_ON = "rear_cam_backup_on";
    private static final String KEY_BACKUP_MODE = "rear_cam_backup_mode";
    private static final String KEY_OVERRIDDEN = "rear_cam_overridden";

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
            if (!camOk) {
                // 相机权限：实验区预览/手电都依赖。原来用独立 code 2001 请求，
                // 而 onRequestPermissionsResult 只处理 4001 —— 授权后既不记录也不自动重试，
                // 用户点「预览」弹一次权限框、授予后却没反应，看起来就是「失效」。
                // 统一走 REQ_LAB_PERMS，并记下待执行动作，授权回调里自动重试。
                pendingRearCamAction = action;
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_LAB_PERMS);
                log("预览缺少相机权限，已请求（授权后自动重试: " + action + "）");
                return;
            }
            if (!overlayOk) {
                Toast.makeText(this, "预览需要悬浮窗权限：到系统设置 → 应用 → SafeWalk → 显示在其他应用上层 开启",
                        Toast.LENGTH_LONG).show();
                log("预览缺少悬浮窗权限，请到系统设置开启");
                return;
            }
            String mode = "open_full".equals(action) ? "fullscreen"
                    : ("open_small".equals(action) ? "smallwindow" : null);
            if (mode == null) {
                WalkPreviewService.send(this, WalkPreviewService.ACTION_HIDE, null);
                restoreRearCamSwitch();
            } else {
                // 关键：实验区预览必须绕过「行走模式开关」。
                // WalkPreviewService.applyFromConfig 第一行就判 enableRearCamera，
                // 关着时直接 hide —— 实验区只发 SHOW 指令不写开关，开关默认 false，
                // 于是预览永远出不来（用户看到的「点了没反应」就是这个）。
                // SHOW 前把开关临时打开并记下原值，关闭预览时恢复。
                overrideRearCamSwitch(mode);
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

    /** 实验区临时打开预览开关，并同步配置给 Hook（离开实验区时恢复原值） */
    private void overrideRearCamSwitch(String mode) {
        SharedPreferences cfg = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        if (!rearCamOverridden) {
            rearCamWasEnabled = SafePrefs.getBool(cfg, SafeWalkPrefKey.enableRearCamera, false);
            rearCamOverridden = true;
            // 持久化备份：进程被回收后仍能还原，不会把开关永久留在「开」
            getSharedPreferences(SP_LAB, MODE_PRIVATE).edit()
                    .putBoolean(KEY_BACKUP_ON, rearCamWasEnabled)
                    .putString(KEY_BACKUP_MODE, SafePrefs.getString(cfg, SafeWalkPrefKey.rearCamMode, ""))
                    .putBoolean(KEY_OVERRIDDEN, true)
                    .commit();
        }
        cfg.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, true).commit();
        cfg.edit().putString(SafeWalkPrefKey.rearCamMode, mode).commit();
        SafeWalkConfig.exportConfig(this);
    }

    private void restoreRearCamSwitch() {
        if (!rearCamOverridden) return;
        SharedPreferences cfg = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        cfg.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, rearCamWasEnabled).commit();
        SafeWalkConfig.exportConfig(this);
        rearCamOverridden = false;
        getSharedPreferences(SP_LAB, MODE_PRIVATE).edit().clear().commit();
    }

    /**
     * 兜底还原：上一次在实验区打开预览后进程被杀 / 崩溃，备份还留在 SP 里。
     * 由 MainActivity 启动时调用，保证「进了实验区→没点关闭→返回」不会再留下常开的开关。
     */
    static void recoverStaleOverride(android.content.Context ctx) {
        SharedPreferences lab = ctx.getSharedPreferences(SP_LAB, ctx.MODE_PRIVATE);
        if (!lab.getBoolean(KEY_OVERRIDDEN, false)) return;
        boolean wasOn = lab.getBoolean(KEY_BACKUP_ON, false);
        String wasMode = lab.getString(KEY_BACKUP_MODE, null);
        SharedPreferences cfg = ctx.getSharedPreferences("safewalk_xsp", ctx.MODE_PRIVATE);
        SharedPreferences.Editor e = cfg.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, wasOn);
        if (wasMode != null && !wasMode.isEmpty()) e.putString(SafeWalkPrefKey.rearCamMode, wasMode);
        e.commit();
        lab.edit().clear().commit();
        SafeWalkConfig.exportConfig(ctx);
        DiagLog.log("[lab] 恢复上次实验区临时打开的行走模式开关 → " + (wasOn ? "开" : "关"));
    }

    @Override protected void onStop() {
        super.onStop();
        // 只在自己真的要退出时还原（旋转/分屏切走不算），避免预览被反复关掉
        if (isFinishing()) restoreRearCamSwitch();
    }

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
