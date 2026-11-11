package io.github.wumisc.safewalk;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/**
 * 行走模式预览服务（**应用进程**）。
 *
 * 相机和悬浮窗都在本进程，吃应用自己申请的 CAMERA 权限。
 * Hook（SystemUI 进程）只负责「让路」：总开关关闭、相机应用在前台、
 * 手电占用摄像头时发收起指令，其余情况由本服务按开关状态常显。
 *
 * ## 自动跟随步行（2.10.2 修复）
 *
 * 自动模式（走起来自动开、停下自动收）的步态判定**就在这个服务里跑**，
 * 而不是交给 SystemUI 进程里的 Hook。
 *
 * 原因（也是 v2.9.6 亲自踩过的坑）：SystemUI 进程里的计步在多数 ROM 上
 * 会「注册成功但永不回调」（拿不到 `ACTIVITY_RECOGNITION`、或被后台策略掐断
 * 传感器回调），于是 `walking` 永远为 false，自动模式永远不显示——
 * 表现就是「只有手动模式好用」。本服务是 camera 类型的前台服务，
 * 传感器回调不会被后台策略掐断，正好用来做可靠的步态检测。
 *
 * 现在：
 *   - **自动关**（2.10.0 起的手工常显）：开关开 = 路况常显，关 = 收起。
 *   - **自动开**：本服务监听加速度计→GaitDetector 判走/停→直接控制预览显隐。
 *
 * Hook 仍负责「让路」：总开关关闭、相机应用在前台、手电占摄像头时发
 * 收起指令（ACTION_HOOK_HIDE），让路解除时发恢复信号（ACTION_HOOK_SHOW）。
 * 具体显不显示由本服务依据自身 walking 决定，不再依赖 SystemUI 的步数。
 */
public final class WalkPreviewService extends Service {

    /** 显示预览：mode/opacity/corner 缺省时读当前配置 */
    public static final String ACTION_SHOW = "io.github.wumisc.safewalk.REAR_CAM_SHOW";
    /** 收起预览 */
    public static final String ACTION_HIDE = "io.github.wumisc.safewalk.REAR_CAM_HIDE";
    /** 行走模式已关闭：停掉整个服务 */
    public static final String ACTION_STOP = "io.github.wumisc.safewalk.REAR_CAM_STOP";

    private static final String CHANNEL_WALK = "safewalk_walk";
    private static final int NOTIF_ID = 3001;
    /** 本进程内「通知渠道被关闭」只提示一次，避免每次 goForeground 都弹 */
    private boolean channelDisabledNotified = false;

    private RearCameraOverlay overlay;
    private SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ========== 自动跟随步行：步态检测放在应用进程（前台相机服务） ==========
    // SystemUI 进程里的计步「注册成功但永不回调」，导致自动模式永远不显示；
    // 本服务是 camera 前台服务，传感器回调不会被后台策略掐断，判定可靠。
    private SensorManager sm;
    private Sensor accSensor;
    private final GaitDetector gait = new GaitDetector();
    private SensorEventListener gaitListener;
    /** 本服务自己判定的「正在行走」：自动模式据此显隐预览 */
    private boolean gaitWalking = false;
    /** 上一帧有效步的时间戳，用于静置超时判停 */
    private long lastStepTs = 0L;
    /** 【沙发误触修复】连续行走步流的起始时间戳，计步器不可用时用它卡「连续走够久」 */
    private long walkingStreakStartTs = 0L;
    /** 判定停下（静置超时收起）的时刻，用于「收起后冷却」防秒开死循环 */
    private long lastStopTs = 0L;
    /** 收起后冷却时长：冷却内需更强的持续判走才放行，打断「显示→收起→秒开」 */
    private static final long STOP_COOLDOWN_MS = 5000L;

    // ========== 防误触：硬件计步器确认 ==========
    // 加速度计判「走」只是波形像走。拿在手里乱动、刷屏、放桌上颠，波形都像走，
    // 只有一样东西不会骗人：系统硬件计步器（TYPE_STEP_COUNTER）——真迈步才计数。
    // 所以加速度判走通过后，还要看「最近 10 秒硬件计步 ≥ 3 步」才显示预览。
    // 设备无计步器 / 传感器从不回调时自动降级不拦，保证功能不被改崩。
    private Sensor stepCounterSensor;
    private SensorEventListener stepCounterListener;
    private final ArrayDeque<Long> stepEventQueue = new ArrayDeque<>();
    /** 是否收到过硬件计步事件（没收到 = 无计步器或传感器失效，不拦） */
    private boolean stepCounterAlive = false;
    /** 硬件计步确认窗口（毫秒）与最少步数 */
    private static final long STEP_CONFIRM_WINDOW_MS = 10_000L;
    private static final int STEP_CONFIRM_MIN = 2;
    /**
     * 【沙发误触修复 v3 · 真机日志 2026-11-10 13:48~14:02】
     *
     * v2 把纯加速度门槛定成「累计 ≥ max(确认步数×2, 15) 步」，但采集层
     * [GaitDetector] 的 `peakIntervals` 队列**上限只有 10**（超过就 removeAt(0)），
     * 所以 `confirmedStepCount()` 永远 ≤ 10，`≥15` 是**永不成立的死条件**。
     * 真机日志里 `累计=7/15`、`累计=10/7` 刷了 968 条，自动跟随一次都没激活。
     *
     * 现在门槛交给 [WalkConfirmPolicy]：不看「累计多少步」，改看**步流的时间形态**
     * （连续 8~10 步、最大步间隔 ≤1.6s、平均步频 0.7~2.8Hz、整段 ≥4s，
     * 且相邻间隔离散度受控），既在窗口上限内可达，又能把间歇抖动挡在外面。
     */
    private WalkConfirmPolicy accelPolicy;
    /** 纯加速度判定用的合格步时间戳队列（每个真实合格步入队一次，断步清空） */
    private final java.util.ArrayDeque<Long> accelStepTs = new java.util.ArrayDeque<>();
    /** 上一帧看到的采集层合格步数，用于识别「本帧新增了一步」 */
    private int lastAccelCountSeen = 0;
    /** 纯加速度诊断日志节流：上次打印时间 / 上次原因 */
    private long lastAccelDiagTs = 0L;
    private String lastAccelDiagReason = "";
    /** 本段监听是否已说明过「为什么走纯加速度」（每段只说一次） */
    private boolean accelFallbackLogged = false;
    /**
     * 有硬件计步器、但 10 秒内只出 0~1 步时的兜底要求：连续步流最短时长。
     * 真机日志 13:08:07→13:08:15 只隔 8 秒就放行过一次，说明 8s 太短。
     */
    private static final long ACCEL_MIN_STREAK_MS = 15_000L;
    /** 连续步流允许的最大间隙：超过它即视为「走断了」，连续计时清零 */
    private static final long STREAK_GAP_MS = 3_000L;
    /** 启动以来计步器累计产生的步数（自本次注册起）。从未出数 = 该设备计步器不可信，停用确认 */
    private long everSteppedTotal = 0L;
    /** TYPE_STEP_COUNTER 是累计传感器，values[0]=总步数；记录上次回调的总步数以算增量 */
    private long lastTotalSteps = -1L;

    /** 静置超过该时长判定为停下（与 Hook 默认 20s 一致） */
    private static final long GAIT_STATIC_MS = 10_000L;
    private final Handler gaitHandler = new Handler(Looper.getMainLooper());
    /**
     * 静置巡检：自动模式下超过 [GAIT_STATIC_MS] 无新步 → 判停 → 收起预览。
     * 步行时 GaitDetector 会持续产出步、刷新 lastStepTs，巡检不会误收。
     */
    private final Runnable gaitTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long stopMs = Math.max(5, Math.min(30,
                    SafePrefs.getInt(sp, SafeWalkPrefKey.gaitStopSec, 10))) * 1000L;
            if (gaitListener != null
                    && SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)
                    && lastStepTs != 0L && now - lastStepTs > stopMs) {
                gaitWalking = false;
                // 清零 lastStepTs：否则「超过 10s 无步」这个条件会一直成立，
                // 每秒 tick 都重复打一条「判定停下」日志刷屏。下次真走会重新置位。
                lastStepTs = 0L;
                lastStopTs = now;
                DiagLog.log("[walk] 静置超时，判定停下 → 收起预览");
                evaluateShow();
            }
            gaitHandler.postDelayed(this, 1000L);
        }
    };
    /**
     * 被 Hook 让路（手电/相机应用/总开关关闭）强制收起时置 true，
     * 期间 evaluateShow 不再主动显示，等 Hook 发 ACTION_HOOK_SHOW 恢复。
     */
    private boolean hookHidden = false;

    /** 悬浮窗权限被回收的累计次数：只在日志里做「是不是反复发生」的判断依据。 */
    private int overlayLostCount = 0;

    /** 「前台服务已降级」是否已提示过：同一进程只提示一次，避免每次走起来都弹通知。 */
    private boolean fgsDegradeNotified = false;

    /**
     * 无障碍通道报告「相机类应用在前台」。
     *
     * 与 [hookHidden] 并列而不是合并：Hook 让路是跨进程指挥（手电、相机应用、总开关），
     * 由 Hook 自己发 SHOW 恢复；这一条来自本进程的无障碍服务，离开相机应用就自动恢复，
     * 两条通道各管各的，谁也不会把对方的「收起」状态顶掉。
     */
    private boolean a11yCameraForeground = false;

    /**
     * 上一次回执给 Hook 的预览真实状态，只在「真正跳变」时才发回执。
     *
     * 没有这个去重：自动模式下站着不动时，Hook 每 2s 发一次放行信号，App 每次都
     * evaluateShow→hide→回一条 false 回执，Hook 又把它当成「需要重发」的依据，形成
     * 每 2s 一次的跨进程乒乓广播 + 日志刷屏。只在状态真正变化时回执即可根治。
     */
    private boolean lastReportedShowing = false;

    /** Hook 广播接收器：让路（收起）或恢复（显示）由 SystemUI 进程指挥 */
    private BroadcastReceiver hookCmdReceiver;

    /** 【锁屏误报修复】亮屏/解锁/熄屏状态接收器，用于收起与恢复后视预览 */
    private BroadcastReceiver screenReceiver;

    /** Hook 端指挥广播（SystemUI → App，跨进程必须 EXPORTED + 令牌） */
    public static final String ACTION_HOOK_SHOW = "io.github.wumisc.safewalk.REAR_CAM_HOOK_SHOW";
    public static final String ACTION_HOOK_HIDE = "io.github.wumisc.safewalk.REAR_CAM_HOOK_HIDE";

    /**
     * 预览实际状态回执（App → SystemUI）。
     *
     * Hook 端发显示/收起指令时做了「只发变化」去重，它必须知道预览的**真实**
     * 状态。这里每次真正显示/收起后都回一条回执，校准 Hook 的去重记录。
     */
    public static final String ACTION_PREVIEW_STATE = "io.github.wumisc.safewalk.PREVIEW_STATE";
    public static final String EXTRA_SHOWING = "showing";

    /**
     * 当前存活的服务实例（弱引用，不阻止回收）。
     *
     * 手电要用它判断「摄像头是不是正被自己的预览占着」——这是应用内唯一
     * 能提前知道的 CAMERA_IN_USE 来源。用静态字段而不是 IPC，因为
     * TorchController 和服务在同一个进程。
     */
    private static java.lang.ref.WeakReference<WalkPreviewService> INSTANCE;

    /** 后视预览是否正开着（窗口在屏幕上）。服务没起来时恒为 false。 */
    public static boolean isPreviewShowing() {
        WalkPreviewService s = INSTANCE == null ? null : INSTANCE.get();
        return s != null && s.overlay != null && s.overlay.getShowing();
    }

    /**
     * 服务实例是否还活着。
     *
     * 和 isPreviewShowing 分开：手电重试时需要「再收一次预览」，但服务都没起来
     * 时不能为了这条指令去 startService——那会拉起一个 camera 类型的前台服务
     * 常驻，用户只是关个手电，却多出一条「行走模式运行中」的通知。
     */
    public static boolean isAlive() {
        return INSTANCE != null && INSTANCE.get() != null;
    }

    /**
     * 前台应用变化（来自 [SafeWalkA11yService]，不依赖 Xposed 的备用通道）。
     *
     * 服务没起来时是空操作：判断当前有没有在显示预览本来就没意义，也不能为了这个
     * 去 startService —— 那会拉起一个 camera 类型前台服务常驻。
     */
    public static void onForegroundAppChanged(String pkg) {
        WalkPreviewService s = INSTANCE == null ? null : INSTANCE.get();
        if (s != null) s.handleForegroundApp(pkg);
    }

    /**
     * 相机 / 录像 / 视频通话类应用包名前缀。
     *
     * 只处理「会独占摄像头或需要干净取景」的应用：小窗叠在取景框上会挡画面，
     * 同时我们自己的预览会话也会让对方拿不到相机（CAMERA_IN_USE）。
     * 不做「任意应用都收起」——那等于把常显小窗废掉。
     * 具体名单与匹配规则见 [CameraAppPolicy]（纯逻辑，有单测）。
     */
    private boolean isCameraLikePackage(String pkg) {
        return CameraAppPolicy.isCameraLike(pkg);
    }

    private void handleForegroundApp(String pkg) {
        boolean camera = isCameraLikePackage(pkg);
        if (camera == a11yCameraForeground) return;   // 只在状态跳变时动，避免每次窗口切换都重评估
        a11yCameraForeground = camera;
        DiagLog.log("[a11y] 前台应用=" + (pkg == null || pkg.isEmpty() ? "-" : pkg)
                + (camera ? " → 相机类应用在前台，收起小窗让路"
                          : " → 已离开相机类应用，恢复按步态判定"));
        evaluateShow();
    }

    public static void start(Context ctx) {
        start(ctx, false);
    }

    /**
     * @param fromRevive 是否由保活机制在系统回收进程后自动拉起。
     *                   仅用于发一条一次性「已自动恢复」回执，不改变启动方式。
     */
    public static void start(Context ctx, boolean fromRevive) {
        try {
            Intent i = new Intent(ctx, WalkPreviewService.class);
            if (fromRevive) i.putExtra(KeepAlivePolicy.EXTRA_FROM_REVIVE, true);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Toast.makeText(ctx, "预览服务启动失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void send(Context ctx, String action, String mode) {
        try {
            Intent i = new Intent(ctx, WalkPreviewService.class);
            i.setAction(action);
            if (mode != null) i.putExtra("mode", mode);
            // 服务已在运行时 startService 只是把指令排队送进来，不涉及后台启动限制
            ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = new java.lang.ref.WeakReference<>(this);
        // 预览服务可能在主界面之前被拉起（Hook 广播唤醒），独立初始化日志出口
        DiagLog.init(getApplicationContext());
        // 【配置防丢】服务可能先于主界面被拉起：先尝试从外部备份恢复配置，
        // 否则开机自启时会按空 SP 的默认值（行走模式=关）判断，导致「开机后是关闭的」。
        ConfigBackup.restoreIfNeeded(getApplicationContext());
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        // 【到达提醒】证件 / 到场提示不再由「确认步行」触发，改由 App 侧低频定位
        // 判定「到达某类场所」后再发（见 PlaceArrivalMonitor）。
        // 服务是常驻的，挂在这里最稳；start 内部幂等，多入口重复调用无害。
        PlaceArrivalMonitor.start(getApplicationContext());
        // 心跳先于一切：让 SystemUI 里的看门狗知道「本进程活着」，
        // 否则它会在 90 秒后误判失联并再拉一次服务。
        startHeartbeat();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        // 静置巡检常驻（每秒一次，仅自动模式且有步时才有动作）
        gaitHandler.postDelayed(gaitTick, 1000L);
        overlay = new RearCameraOverlay(this,
                reason -> {
                    // 锁屏/灭屏期间的失败一律静默：多半是系统暂时禁用相机，
                    // 弹通知只会变成锁屏上的骚扰（用户反复反馈的「还是显示这个问题」）。
                    if (isScreenUsable()) {
                        new SafeWalkNotificationManager(WalkPreviewService.this).previewFailed(reason);
                    } else {
                        DiagLog.log("[cam] 锁屏/灭屏期间预览失败，静默处理不弹通知: " + reason);
                    }
                    // 看门狗判定失败后会自己 hide()，服务这边必须同步回执，
                    // 否则 Hook 以为画面还在，下一次「该显示」的指令会被去重吞掉
                    reportPreviewState();
                    return kotlin.Unit.INSTANCE;
                },
                step -> {
                    DiagLog.log("[cam] " + step);
                    // 画面恢复后立刻撤掉上一条「后视画面打不开」，
                    // 否则旧通知还挂在锁屏上误导用户（截图里的现象）。
                    if (step != null && step.startsWith("✅ 已收到画面")) {
                        new SafeWalkNotificationManager(WalkPreviewService.this)
                                .cancelPreviewFailed();
                    }
                    // 分步诊断推回行走模式页（同进程广播，也走令牌校验保持一致）
                    try {
                        Intent i = new Intent("io.github.wumisc.safewalk.PREVIEW_STAGE");
                        i.setPackage(getPackageName());
                        i.putExtra("step", step);
                        i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
                        sendBroadcast(i);
                    } catch (Throwable ignored) {
                    }
                    return kotlin.Unit.INSTANCE;
                },
                null);

        // Hook 的指挥广播。为了跨进程（SystemUI → App）必须 EXPORTED，
        // 令牌校验挡掉随手发的假指令
        hookCmdReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 这条通道能直接开关用户的摄像头，只靠硬编码令牌不够：
                // 反编译拿到字符串就能静默开相机（隐私事故）或强制关掉预览。
                // API 34+ 先按发送方 uid 判断，低版本退回令牌。
                if (i == null || !ReceiverCompat.isTrustedCommand(this,
                        i.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN))) {
                    DiagLog.log("[walk] 预览指令鉴权失败，丢弃: "
                            + (i == null ? "null intent" : i.getAction()));
                    return;
                }
                String action = i.getAction();
                if (ACTION_HOOK_SHOW.equals(action)) {
                    // 让路解除：恢复信号。具体显不显示由本服务按自身 walking 决定
                    hookHidden = false;
                    evaluateShow();
                } else if (ACTION_HOOK_HIDE.equals(action)) {
                    // 让路：手电/相机应用/总开关关闭 → 强制收起
                    hookHidden = true;
                    overlay.hide();
                    reportPreviewState();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WalkPreviewService.ACTION_HOOK_SHOW);
        f.addAction(WalkPreviewService.ACTION_HOOK_HIDE);
        ReceiverCompat.registerExported(this, hookCmdReceiver, f);

        // 【BUG 修复 · 锁屏误报】亮屏/解锁状态变化要重新评估预览：
        // 熄屏立刻收起，解锁后自动恢复。系统广播，无需 exported/令牌。
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = (i == null) ? null : i.getAction();
                DiagLog.log("[screen] " + a + " → 重新评估后视预览");
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    if (overlay != null) overlay.hide();
                    reportPreviewState();
                } else {
                    // ACTION_SCREEN_ON 时可能仍锁屏，isScreenUsable() 会挡住；
                    // ACTION_USER_PRESENT（真正解锁）才放行。
                    evaluateShow();
                }
            }
        };
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_USER_PRESENT);
        try { registerReceiver(screenReceiver, sf); } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        goForeground();
        // 服务起来了：磁贴副标题里的「服务未运行」提示要立刻消失
        WalkTileService.refresh(this);

        // 系统回收后自动恢复：给一条一次性回执，解释「通知为什么消失又回来了」
        if (intent != null && intent.getBooleanExtra(KeepAlivePolicy.EXTRA_FROM_REVIVE, false)) {
            notifyRevivedIfNeeded();
        }

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // 用户主动关闭：停心跳、撤掉待执行的自检。否则看门狗会看到
            // 「配置没变但心跳停了」把服务又拉回来 —— 变成关不掉的功能。
            stopHeartbeat();
            cancelReviveCheck();
            overlay.hide();
            reportPreviewState();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            // 预览服务停掉 = 磁贴该变回「关」；面板没展开时也要及时对齐
            WalkTileService.refresh(this);
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE.equals(action)) {
            overlay.hide();
            reportPreviewState();
            return START_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            // 用户主动点击「预览」/重试（区别于 Hook 每 2s 发的 ACTION_HOOK_SHOW）：
            // 解除相机硬失败静默期——用户可能刚在系统设置/设备策略里放开了相机，
            // 应该能立刻再试一次，而不是等满静默期。
            if (overlay != null) overlay.clearHardFailureBackoff();
        }
        // SHOW / 未带指令的启动（系统重建）：按当前配置显示。
        // 手动模式下服务活着 = 路况常显，服务被系统回收重建后也要恢复显示。
        applyFromConfig(action != null ? intent.getStringExtra("mode") : null);
        return START_STICKY;
    }

    private void applyFromConfig(String modeOverride) {
        // 类型安全读取：历史上 rearCamOpacity 之类改过 int/float，
        // 裸 getBoolean 遇到脏数据会直接 ClassCastException 把服务打死
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)) {
            // 开关已经关了，但窗口可能还挂着（服务被系统重建、配置刚改）
            overlay.hide();
            reportPreviewState();
            stopGait();
            return;
        }
        // 自动跟随步行：开步态监听；手动常显：关监听（不需要检测走停）
        if (SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)) {
            startGait();
        } else {
            stopGait();
        }
        evaluateShow();
    }

    /**
     * 依据当前配置与本服务判定的 walking，决定显示还是收起。
     * 让路（hookHidden）期间保持收起，等 Hook 发恢复信号再来评估。
     */
    private void evaluateShow() {
        // 【BUG 修复 · 锁屏误报】锁屏/灭屏时绝不拉起后视预览。
        // HyperOS/MIUI 在锁屏、灭屏、后台省电策略下会禁用相机
        // （openCamera 抛 CAMERA_DISABLED / 会话回调 ERROR_CAMERA_DISABLED）。
        // 旧逻辑把这种「暂时不可用」当成故障弹「后视画面打不开」常驻通知，
        // 用户在锁屏上反复看到。真正走路时屏幕亮且已解锁，不受影响。
        if (!isScreenUsable()) {
            if (overlay != null && overlay.getShowing()) {
                overlay.hide();
                reportPreviewState();
            }
            return;
        }
        if (hookHidden) return;
        // 无障碍通道：相机 / 录像 / 视频通话类应用在前台时让路（避免遮挡取景、抢占摄像头）
        if (a11yCameraForeground) {
            if (overlay != null && overlay.getShowing()) {
                overlay.hide();
                reportPreviewState();
            }
            return;
        }
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)) return;
        if (SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)) {
            // 自动模式：走起来才显示，停下收起
            if (gaitWalking) doShow();
            else { overlay.hide(); reportPreviewState(); }
        } else {
            // 手动常显
            doShow();
        }
    }

    /**
     * 悬浮窗权限当前是否可用。
     *
     * 只有「明确拿到 false」或「查询本身抛异常」才算不可用 —— 后者按不可用处理是刻意的：
     * 与其让 addView 抛 BadTokenException 后被静默吞掉（用户完全不知道发生了什么），
     * 不如拦住并明确提示去重新授权。minSdk 33 上 canDrawOverlays 行为稳定。
     */
    private boolean canDrawOverlayNow() {
        try {
            return android.provider.Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            DiagLog.log("[overlay] 查询悬浮窗权限失败：" + t.getClass().getSimpleName()
                    + "，按不可用处理（避免 addView 抛 BadTokenException 后被静默吞掉）");
            return false;
        }
    }

    private void doShow() {
        // 【HyperOS 适配真正落地 · 悬浮窗复核】
        // HyperOsCompat 里那条 "strict" 建议此前只打日志、从不执行。HOS3/4 都会在
        // 系统升级、一键清理、省电策略触发后把 SYSTEM_ALERT_WINDOW 这个 AppOps 收回去，
        // 此时 WindowManager.addView 抛 BadTokenException，被外层 catch 吞掉后
        // 用户只看到「走起来没反应」。这里每次显示前复核一次（AppOps 查询开销极低，
        // doShow 的调用频率也只有「走起来 / 停下」级别），失权就明确告知，不静默失败。
        if (!canDrawOverlayNow()) {
            overlayLostCount++;
            DiagLog.log("[overlay] ⛔ 悬浮窗权限不可用（系统已回收）→ 本次不显示；"
                    + "累计 " + overlayLostCount + " 次。请在应用内「权限与无障碍」重新授予「悬浮窗」");
            try {
                new SafeWalkNotificationManager(this).overlayLost();
            } catch (Throwable ignored) {
            }
            reportPreviewState();
            return;
        }
        String mode = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamMode, RearCameraOverlay.MODE_FULLSCREEN);
        if (mode == null) mode = RearCameraOverlay.MODE_FULLSCREEN;
        int opacity = SafePrefs.getInt(sp, SafeWalkPrefKey.rearCamOpacity, 50);
        String corner = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamCorner, RearCameraOverlay.CORNER_BR);
        if (corner == null) corner = RearCameraOverlay.CORNER_BR;
        // 【新增对比度 UI 模块】把「增强对比度」开关透传给悬浮窗。
        // 悬浮窗会把 HUD 文字层（描边/阴影/深色衬底）单独渲染，且不受 opacity 影响。
        boolean contrast = SafePrefs.getBool(sp, SafeWalkPrefKey.enablePreviewContrast, false);
        // 【对比度强度进度条】0~100，默认 50。强度只作用于 HUD 文字层的
        // 描边/阴影/衬底，绝不改相机采集，也不改画面层 alpha。
        int contrastLevel = SafePrefs.getInt(sp, SafeWalkPrefKey.previewContrastLevel, 50);
        contrastLevel = Math.max(0, Math.min(100, contrastLevel));

        // 【BUG 修复 · 夜间手电联动对象纠正】
        // 用户明确：「闪光灯是跟角落小窗一起打开，联动的是角落小窗」。
        // 上一轮误做成「按 Hook 侧 walking 步态点灯」，躺床上假步数直接把灯点亮
        // （最新日志 01:23/01:33 [torch:gait] 自动点亮手电）。
        // 正确语义：夜间时段 + 开关开 + **角落小窗正在显示** → 闪光灯常亮；
        // 小窗收起（停下 / 让路 / 关开关）→ 预览会话关闭，闪光灯随之熄灭。
        //
        // 关键实现点：闪光灯与预览共用同一颗后置摄像头，CameraManager.setTorchMode()
        // 会因 CAMERA_IN_USE 失败，所以灯必须开在**预览会话的 CaptureRequest 里**
        // （RearCameraOverlay 的 FLASH_MODE_TORCH），不另起 setTorchMode 抢设备。
        boolean linkEnabled = SafePrefs.getBool(sp, SafeWalkPrefKey.enableNightTorchGait, false);
        boolean night = isNightTime();
        boolean torchWithPreview = linkEnabled && night;
        overlay.show(mode, opacity, corner, contrast, torchWithPreview, contrastLevel);
        DiagLog.log("[torch:overlay] 小窗联动闪光灯 | 开关=" + linkEnabled
                + " 夜间区间=" + night + " → " + (torchWithPreview ? "随小窗点亮" : "不点亮")
                + " | 生命周期=小窗显示即亮、收起即灭（预览会话内 FLASH_MODE_TORCH）");
        DiagLog.log("[overlay] doShow | mode=" + mode + " opacity=" + opacity
                + " corner=" + corner + " 增强对比度=" + contrast + "(" + contrastLevel + "%)"
                + " | 窗口生命周期=attach/update（容器含画面层+HUD层）");
        reportPreviewState();
    }

    /**
     * 夜间区间判定（App 进程本地读 sp，不依赖 Hook 的跨进程配置通道）。
     * 与智能提醒页的时段设置共用 nightTorchStartHour / nightTorchEndHour，默认 22:00~6:00。
     */
    private boolean isNightTime() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int start = SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchStartHour, 22);
        int end = SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchEndHour, 6);
        boolean hit = hour >= start || hour < end;
        DiagLog.log("[torch:time] 小窗联动时段 | 当前=" + hour + " 时 生效区间="
                + start + ":00~" + end + ":00 命中=" + hit);
        return hit;
    }

    /** 启动应用进程内的加速度计步态监听（自动模式用）。 */
    /** 上一次 [gait] 节流诊断的时间戳，避免每帧刷屏 */
    private long lastGaitDiagTs = 0L;
    /** 上一次 [ACTIVE] 激活判定节流诊断的时间戳（达标但被拦截时每 2s 一条） */
    private long lastActiveDiagTs = 0L;
    /** 上一次「收起冷却」诊断的时间戳（冷却期内本方法按帧调用） */
    private long lastCooldownDiagTs = 0L;
    /**
     * 上一次判定时的累计合格步，用于识别「刚刚跨过门槛」这个瞬间。
     *
     * 真机日志（13:11:40~13:19:10，7.5 分钟）里 [GUARD] 打了 139 条——
     * 沙发场景累计步在 1~8 之间反复波动，每跨过一次 5 就打一次，5 秒节流
     * 也压不住。改为只在「从 <门槛 跳到 >=门槛」那一帧留痕，一轮一条。
     */
    private int lastGuardCountSnapshot = 0;
    /** 上一次 [GUARD] 姿态/节律闸门拦截诊断的时间戳（独立节流，不与 ACTIVE 抢） */
    private long lastGuardDiagTs = 0L;

    private void startGait() {
        if (gaitListener != null) return; // 已在监听
        if (sm == null) return;
        accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accSensor == null) {
            DiagLog.log("[walk] 本机无加速度计，自动步态不可用");
            return;
        }
        gait.reset();
        gait.setConfirmSteps(Math.max(2, Math.min(10,
                SafePrefs.getInt(sp, SafeWalkPrefKey.gaitConfirmSteps, 5))));
        gaitWalking = false;
        lastStepTs = 0L;
        walkingStreakStartTs = 0L;
        lastGuardCountSnapshot = 0;
        lastGaitDiagTs = 0L;
        accelStepTs.clear();
        lastAccelCountSeen = 0;
        lastAccelDiagReason = "";
        lastAccelDiagTs = 0L;
        accelFallbackLogged = false;
        registerStepCounter();
        gaitListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e == null || e.values == null || e.values.length < 3) return;
                long now = System.currentTimeMillis();

                // ==== [STEP] 计步回调 ====
                // feedAcc() 返回 true 表示「这一帧构成一个合格步」。它内部已同时校验
                // 步频窗口、主轴一致率，并带 LAY_FLAT（平躺刷手机）一票否决 —— 这是
                // 原生采集模块自带的防床上误触闸门，本轮修复不再绕开它。
                boolean stepTick = gait.feedAcc(e.values[0], e.values[1], e.values[2]);

                // ==== 合格步时间戳入队（纯加速度判定的形态依据）====
                // 不能用 `confirmedStepCount()` 的增量来判断：那是滑动窗口大小，
                // 满格 10 之后不再增长，新一步进来会同时挤掉最老一步，增量为 0。
                // 所以以 stepTick（本帧构成一个合格步）为准；窗口未满时补一条增量判断。
                int accelCnt = gait.confirmedStepCount();
                if (stepTick || (accelCnt > lastAccelCountSeen && accelCnt > 0)) {
                    recordAccelStep(now);
                }
                lastAccelCountSeen = accelCnt;

                // ==== [GUARD] 姿态 / 节律可信度闸门（修复「床上打开角落小窗」）====
                // 上一轮为绕开「水平持握走路被 LAY_FLAT 卡住」加的并列判据
                //   countReached = confirmedStepCount() >= confirmSteps
                // 在真机上被躺床翻身 / 抖动的**无节律波动**凑满（最新日志 01:33:44
                // 「累计=6/6 → 累计合格步达标(绕开LAY_FLAT门控) → 显示预览」），
                // 等于把 GaitDetector 内部的 LAY_FLAT 门控整个短路，直接弹出小窗。
                //
                // 本轮改为：解卡路径必须同时满足「姿态可信」+「节律稳定」；
                // 平躺但硬件计步器确实出数时，才认作「水平持握真在走路」放行。
                // 全部只读 GaitDetector 的公开状态（getAttitude / hasStableGait /
                // confirmedStepCount），**不改动采集算法本身**，遵守用户约束。
                PhoneAttitude attitude = gait.getAttitude();
                boolean layFlat = attitude == PhoneAttitude.LAY_FLAT;
                boolean stable = gait.hasStableGait();
                int hwSteps = stepCountInWindow();
                boolean credible;
                if (stepTick) {
                    credible = true;                        // 主路径：采集模块已判合格步
                } else if (!layFlat && stable) {
                    credible = true;                        // 解卡路径：非平躺 + 节律稳定
                } else if (layFlat && stable && hwSteps >= STEP_CONFIRM_MIN) {
                    // 水平持握 / 放包里走路也可能判 LAY_FLAT，但硬件计步器真出了步数，
                    // 这是「确实在走路」的硬证据，放行，避免误杀正常行走。
                    credible = true;
                } else {
                    credible = false;                       // 平躺抖动 / 无节律乱动
                }
                boolean countReached = credible
                        && gait.confirmedStepCount() >= gait.getConfirmSteps();
                boolean qualified = stepTick || countReached;

                if (qualified) {
                    // 【沙发误触修复】维护「连续步流」计时：间隙超过 STREAK_GAP_MS
                    // 即视为走断，从当前帧重新起算。真人走路步间隔通常 <1s，
                    // 远小于 3s；沙发上间歇性挪动几秒就断，连续计时攒不起来。
                    if (walkingStreakStartTs == 0L || now - lastStepTs > STREAK_GAP_MS) {
                        walkingStreakStartTs = now;
                    }
                    lastStepTs = now;   // 刷新「上一步」时间戳，防静置巡检秒判停
                } else if (lastStepTs != 0L && now - lastStepTs > STREAK_GAP_MS) {
                    // 步流中断超过 STREAK_GAP_MS：清掉连续计时，下次要重新连续走满才放行
                    walkingStreakStartTs = 0L;
                    lastGuardCountSnapshot = 0;   // 新一轮，允许再留一条 GUARD 痕迹
                    // 纯加速度的步流同样作废：留着上一段的时间戳会让「跨度」虚长
                    accelStepTs.clear();
                    lastAccelCountSeen = 0;
                }

                // 被闸门拦下时留痕，真机自测一眼能看出「为什么没激活」。
                //
                // 【修复 · 日志洪泛 + 文案与判据不符】
                // 旧条件只要求 confirmedStepCount() > 0 就打，于是 20 分钟刷了
                // 319 条、每 2 秒一条；文案还写死「步数达标」，可日志里大量
                // 「累计=2/5」「累计=1/5」根本没达标 —— 自测时极易被误导成
                // 「明明没走也在拦」，实际只是正常累积过程。
                // 现在只在「确实攒够门槛却被闸门拦下」这个真异常时刻留痕，
                // 节流 5 秒，正常累积过程不再输出。
                int guardCnt = gait.confirmedStepCount();
                int guardNeed = gait.getConfirmSteps();
                if (!gaitWalking && !credible && !stepTick
                        && guardCnt >= guardNeed
                        && lastGuardCountSnapshot < guardNeed   // 本帧才刚跨过门槛
                        && now - lastGuardDiagTs > 3000L) {
                    lastGuardDiagTs = now;
                    DiagLog.log("[GUARD] ⛔ 累计步已达门槛但姿态/节律不可信，抑制激活 | 累计="
                            + guardCnt + "/" + guardNeed
                            + " 姿态=" + attitude + " 节律稳定=" + stable
                            + " 硬件计步(10s)=" + hwSteps
                            + " | 防床上/沙发平放抖动误触");
                }
                lastGuardCountSnapshot = guardCnt;

                // ==== [ACTIVE] 激活判定（每次判定都留痕，区分卡在哪道闸）====
                if (!gaitWalking && (stepTick || countReached)) {
                    boolean hw = hardwareStepConfirm();
                    boolean cool = shouldShowNow();
                    if (hw && cool) {
                        lastStepTs = now;
                        gaitWalking = true;
                        DiagLog.log("[ACTIVE] ✅ 激活行走模式 | 触发源="
                                + (stepTick ? "feedAcc合格步" : "累计合格步达标(姿态/节律闸门已放行)")
                                + " 累计=" + gait.confirmedStepCount() + "/"
                                + gait.getConfirmSteps() + " 姿态=" + attitude
                                + " 硬件确认=过 冷却=过 → 显示预览");
                        evaluateShow();
                    } else if (now - lastActiveDiagTs > 2000L) {
                        lastActiveDiagTs = now;
                        DiagLog.log("[ACTIVE] ⏸ 达标但被拦截：硬件计步确认=" + (hw ? "过" : "拦")
                                + " 收起冷却=" + (cool ? "过" : "拦")
                                + " 累计=" + gait.confirmedStepCount() + "/"
                                + gait.getConfirmSteps());
                    }
                }

                // 节流诊断：每 10 秒打一次步数累计，走没走、卡在哪一眼可见。
                // （原为 2 秒一次，20 分钟刷了 504 行，日志被噪声淹没，真出问题时
                //   反而找不到有用信息；关键状态变化由 [ACTIVE]/[GUARD] 负责留痕。）
                if (now - lastGaitDiagTs > 10_000L) {
                    lastGaitDiagTs = now;
                    // 「本帧合格步」几乎恒为 false（一个合格步只占一帧，日志大概率落在
                    // 普通帧上），旧文案会让人误以为一步都没走成。改成最近 10 秒的出步数。
                    DiagLog.log("[STEP] 计步回调 | 连续合格步 " + gait.confirmedStepCount()
                            + "/" + gait.getConfirmSteps()
                            + " 最近10s出步=" + accelStepsInWindow(10_000L)
                            + " | " + gait.debugInfo()
                            + " | walking=" + gaitWalking);
                }
            }
            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        try {
            sm.registerListener(gaitListener, accSensor, SensorManager.SENSOR_DELAY_UI);
            DiagLog.log("[walk] 自动步态监听已启动（应用进程，传感器可靠）");
        } catch (Throwable t) {
            gaitListener = null;
            DiagLog.log("[walk] 步态监听启动失败: " + t.getMessage());
        }
    }

    /** 停止步态监听并复位状态（手动模式 / 开关关闭时）。 */
    private void stopGait() {
        if (gaitListener != null) {
            try { sm.unregisterListener(gaitListener); } catch (Throwable ignored) {}
            gaitListener = null;
        }
        unregisterStepCounter();
        gaitWalking = false;
        lastStepTs = 0L;
        walkingStreakStartTs = 0L;
        lastGuardCountSnapshot = 0;
        accelStepTs.clear();
        lastAccelCountSeen = 0;
    }

    // ========== 硬件计步器（防误触确认） ==========

    /** 注册系统硬件计步器：真迈步才计数，拿手机乱动不计数 */
    private void registerStepCounter() {
        if (stepCounterListener != null) return;
        if (sm == null) return;
        stepCounterSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepCounterSensor == null) {
            stepCounterAlive = false;
            DiagLog.log("[gait] 无硬件计步器：防误触确认停用（纯加速度判定）");
            return;
        }
        stepEventQueue.clear();
        stepCounterAlive = false;
        everSteppedTotal = 0L;
        lastTotalSteps = -1L;
        stepCounterListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e == null || e.values == null || e.values.length < 1) return;
                long now = SystemClock.elapsedRealtime();
                stepCounterAlive = true;
                // TYPE_STEP_COUNTER 是累计传感器：values[0]=总步数，按增量计步
                long total = (long) e.values[0];
                if (lastTotalSteps < 0L) {
                    // 首次回调只记基线，不算一步
                    lastTotalSteps = total;
                } else if (total > lastTotalSteps) {
                    long delta = total - lastTotalSteps;
                    lastTotalSteps = total;
                    everSteppedTotal += delta;
                    for (long i = 0; i < delta; i++) {
                        stepEventQueue.addLast(now);
                    }
                }
                while (!stepEventQueue.isEmpty()
                        && now - stepEventQueue.peekFirst() > STEP_CONFIRM_WINDOW_MS) {
                    stepEventQueue.pollFirst();
                }
            }
            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        try {
            sm.registerListener(stepCounterListener, stepCounterSensor,
                    SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable t) {
            stepCounterListener = null;
            stepCounterAlive = false;
            DiagLog.log("[gait] 硬件计步器注册失败，防误触确认停用: " + t.getMessage());
        }
    }

    private void unregisterStepCounter() {
        if (stepCounterListener != null) {
            try { sm.unregisterListener(stepCounterListener); } catch (Throwable ignored) {}
            stepCounterListener = null;
        }
        stepEventQueue.clear();
        stepCounterAlive = false;
        everSteppedTotal = 0L;
        lastTotalSteps = -1L;
    }

    /** 最近 10 秒内硬件计步数 */
    private int stepCountInWindow() {
        long now = SystemClock.elapsedRealtime();
        while (!stepEventQueue.isEmpty()
                && now - stepEventQueue.peekFirst() > STEP_CONFIRM_WINDOW_MS) {
            stepEventQueue.pollFirst();
        }
        return stepEventQueue.size();
    }

    /** 当前连续步流已持续的毫秒数；未起步返回 0 */
    private long streakMs() {
        return (walkingStreakStartTs == 0L)
                ? 0L : (System.currentTimeMillis() - walkingStreakStartTs);
    }

    /**
     * 硬件计步确认（自适应，既不误杀走路、也不放过床上/沙发误触）：
     * 1) 无计步器 / 传感器从未回调 → **不再无条件放行**，走纯加速度严格判定
     *    [accelOnlyConfirm]（旧版 `return true` 是沙发误触的第二道闸失效点）。
     * 2) 计步器在线但从未出数（ACTIVITY_RECOGNITION 未授予或被后台掐断）→ 同上严格判定。
     * 3) 计步器在线且出过数、但最近 10 秒 0 步 → 直接拦。TYPE_STEP_COUNTER 是
     *    硬件物理计数，真迈步必然 +1；10 秒 0 步就说明没在走路，此时若还按
     *    「纯加速度累计达标」放行，等于给严格校验开后门（真机 13:08:15 现场）。
     * 4) 计步器正常出数 → 10 秒内 ≥2 步直接放行（硬件背书最可靠）；
     *    0 < 步数 < 2 时保留兜底，但必须同时满足「节律稳定 + 连续步流够久」。
     */
    private boolean hardwareStepConfirm() {
        // 【沙发误触修复】计步器不可用时绝不无条件放行：旧版这里直接 return true，
        // 让第二道防误触闸形同虚设，沙发上有节奏地晃手机就能弹出小窗（真机日志
        // 12:47:33 / 12:49:23 两次）。改为纯加速度严格判定。
        if (!stepCounterAlive) return accelOnlyConfirm();
        if (everSteppedTotal == 0L) {
            // 计步器在线但从未出数：同样退回严格判定，而不是「非平躺就放行」。
            return accelOnlyConfirm();
        }
        int n = stepCountInWindow();
        if (n >= STEP_CONFIRM_MIN) return true;

        // 【沙发误触修复 v2 · 真机日志 13:08:15 / 13:08:52 / 13:09:56】
        // 计步器在线、历史上出过数，但最近 10 秒一步未出 —— 这是「没在走路」的
        // 硬证据（TYPE_STEP_COUNTER 是硬件物理计数，真迈一步必然 +1）。
        //
        // 旧版此时仍允许「累计合格步 ≥ 门槛×2」放行，而 confirmedStepCount() 是
        // 纯加速度自己数出来的，于是沙发上晃手机只要攒够 10 个加速度峰就必然弹窗：
        // 日志里 13:08:07 / 13:08:10 / 13:08:13 连续三次被拦，13:08:15 一到 10 步
        // 立刻「放行显示」，此后每 40 秒弹一次小窗（13:08:52、13:09:5x 循环）。
        // 这条兜底等于给严格校验开了后门，必须堵掉。
        if (n == 0) {
            DiagLog.log("[gait] ⛔ 硬件计步器 10 秒 0 步（真走路不可能不出数）"
                    + " → 判未行走，抑制激活 | 纯加速度累计="
                    + gait.confirmedStepCount());
            return false;
        }

        // 0 < n < 门槛：计步器刚起步或轻微漏计。兜底保留，但必须同时满足
        // 「节律稳定」+「连续步流够久」，不能让纯加速度计数单独决定放行。
        long streak = streakMs();
        if (gait.confirmedStepCount() >= gait.getConfirmSteps() * 2
                && gait.hasStableGait() && streak >= ACCEL_MIN_STREAK_MS) {
            DiagLog.log("[gait] 计步 " + n + "<" + STEP_CONFIRM_MIN
                    + " 但持续判走（累计 " + gait.confirmedStepCount()
                    + " 步 / 连续 " + (streak / 1000) + "s），放行显示");
            return true;
        }
        DiagLog.log("[gait] 加速度像走但硬件计步 " + n + "/" + STEP_CONFIRM_MIN
                + "（10s内）/连续 " + (streak / 1000) + "s → 疑似拿手机乱动，不显示");
        return false;
    }

    /**
     * 【沙发/床上误触修复 v3】计步器不可用时（无传感器 / 无 ACTIVITY_RECOGNITION /
     * 被后台掐断 / 从未出数）的纯加速度确认。
     *
     * 判定交给 [WalkConfirmPolicy]，看的是**步流形态**而不是「累计多少步」：
     *   ① 非平躺（LAY_FLAT 直接拦）；
     *   ② 连续合格步 ≥ 8~10（受采集层 10 步窗口约束，可达）；
     *   ③ 相邻步最大间隔 ≤ 1.6s、平均步间隔 350~1400ms、整段跨度 ≥ 4s、
     *      相邻间隔离散度受控 —— 真人走路是连续步流，沙发抖动是间歇的。
     *
     * v2 的 `≥15 步` 是死条件（窗口上限 10），已由真机日志证实会让自动跟随彻底失效。
     */
    private boolean accelOnlyConfirm() {
        if (accelPolicy == null) {
            int pref = Math.max(2, Math.min(10,
                    SafePrefs.getInt(sp, SafeWalkPrefKey.gaitConfirmSteps, 5)));
            accelPolicy = new WalkConfirmPolicy(WalkConfirmPolicy.requiredStepsFor(pref));
        }
        if (!accelFallbackLogged) {
            // 只在每段监听里说一次：用户反馈「自动跟随不灵」时，先看这行就能分清
            // 是「本机没有计步器」还是「权限被拒/被后台掐断」，不用再猜。
            accelFallbackLogged = true;
            DiagLog.log("[gait] 本机走纯加速度判定 | 计步传感器="
                    + (stepCounterSensor != null ? "有" : "无")
                    + " 已出步=" + everSteppedTotal
                    + (stepCounterSensor != null && everSteppedTotal == 0L
                       ? "（疑似未授予「身体活动」权限或被后台限制，建议在系统设置里允许）" : ""));
        }
        WalkConfirmPolicy.Verdict v = accelPolicy.evaluate(
                gait.getAttitude() == PhoneAttitude.LAY_FLAT,
                gait.confirmedStepCount(),
                new java.util.ArrayList<>(accelStepTs),
                System.currentTimeMillis());
        logAccelVerdict(v);
        return v.pass;
    }

    /**
     * 纯加速度判定的诊断日志（节流）。
     *
     * 这一路每帧都会被调用（窗口满格后 `countReached` 恒真），直接 if + log 会在
     * 无硬件计步器的机型上刷出上百行/秒（真机 13:48:05~13:48:13 共 968 行），
     * 既是主线程文件 IO、又让日志彻底失去可读性。改为：原因变化时才打（且至少隔
     * 2 秒），原因不变则 10 秒一条。
     */
    private void logAccelVerdict(WalkConfirmPolicy.Verdict v) {
        long now = System.currentTimeMillis();
        boolean changed = !v.reason.equals(lastAccelDiagReason);
        long interval = changed ? 2_000L : 10_000L;
        if (now - lastAccelDiagTs < interval) return;
        lastAccelDiagTs = now;
        lastAccelDiagReason = v.reason;
        DiagLog.log("[gait] " + (v.pass ? "✅ 纯加速度判走放行" : "⛔ 纯加速度暂不放行")
                + " | 原因=" + v.reason
                + " 连续步=" + v.steps + "/" + accelPolicy.requiredSteps()
                + " 跨度=" + v.spanMs + "ms 最大间隔=" + v.maxGapMs + "ms 均隔=" + v.avgIntervalMs + "ms"
                + " | 本机无硬件计步器，靠步流形态判走（间歇抖动过不了）");
    }

    /** 记录一个合格步的时间戳；队列只留最近一段步流，断步由调用方清空 */
    private void recordAccelStep(long ts) {
        accelStepTs.addLast(ts);
        while (accelStepTs.size() > WalkConfirmPolicy.WINDOW_CAP + 4) {
            accelStepTs.pollFirst();
        }
        while (!accelStepTs.isEmpty() && ts - accelStepTs.peekFirst() > 30_000L) {
            accelStepTs.pollFirst();
        }
    }

    /** 最近 [windowMs] 内记录的合格步数（诊断日志用） */
    private int accelStepsInWindow(long windowMs) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (java.util.Iterator<Long> it = accelStepTs.descendingIterator(); it.hasNext(); ) {
            if (now - it.next() <= windowMs) n++;
            else break;
        }
        return n;
    }

    /**
     * 亮屏且已解锁：只有这种状态才允许打开相机。
     * 取不到系统服务时保守放行，避免个别 ROM 上把功能彻底锁死。
     */
    private boolean isScreenUsable() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return false;
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 收起后冷却（防「显示→收起→秒开」死循环，也就是用户反馈的「没完没了」）。
     * 判定停下收起后 [STOP_COOLDOWN_MS] 内，手持乱动几秒又凑够 confirmSteps 步时
     * **不立即显示**，必须达到「持续判走」（累计步 ≥ 门槛两倍）才放行。
     * 真走路停下后再走，几秒内能攒满两倍步数，照常显示；手持乱动很难连续攒满，被压住。
     */
    private boolean shouldShowNow() {
        long now = System.currentTimeMillis();
        if (lastStopTs == 0L || now - lastStopTs >= STOP_COOLDOWN_MS) return true;
        // 本方法在判定帧上会被高频调用，日志必须节流，否则冷却期内同样会刷屏
        boolean diag = now - lastCooldownDiagTs > 5_000L;
        if (gait.confirmedStepCount() >= gait.getConfirmSteps() * 2) {
            if (diag) {
                lastCooldownDiagTs = now;
                DiagLog.log("[gait] 判定停下冷却内持续判走（累计 "
                        + gait.confirmedStepCount() + " 步），放行显示");
            }
            return true;
        }
        if (diag) {
            lastCooldownDiagTs = now;
            DiagLog.log("[gait] 判定停下冷却期内（累计 " + gait.confirmedStepCount()
                    + " 步），暂不显示，等待持续判走");
        }
        return false;
    }

    /** 把预览的真实显示状态回执给 SystemUI 里的 Hook，校准它的去重记录 */
    private void notifyPreviewState(boolean showing) {
        try {
            Intent i = new Intent(ACTION_PREVIEW_STATE);
            i.setPackage("com.android.systemui");
            i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            i.putExtra(EXTRA_SHOWING, showing);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按 overlay 的真实显示状态回执，且只在状态真正跳变时才发。
     * 所有「显示/收起」动作后统一走这里：显示/收起成了 no-op（本来就是这个状态）
     * 时不发回执，避免驱动 Hook 端的发送乒乓、也避免走路时每一步都回一条 ack。
     */
    private void reportPreviewState() {
        boolean showing = overlay != null && overlay.getShowing();
        if (showing == lastReportedShowing) return;
        lastReportedShowing = showing;
        notifyPreviewState(showing);
    }

    // ========== 保活：心跳 + 被杀后自动恢复 ==========
    //
    // 心跳只发广播、不落盘、不常驻唤醒。Hook（SystemUI 进程）本来就每 2 秒巡检，
    // 连续 90 秒收不到心跳就判定本进程已被回收，拉起透明 AutoStartActivity
    // 合法重启 camera 前台服务。完整判定与边界见 KeepAlivePolicy。
    private boolean heartbeatStarted = false;

    private final Runnable heartbeatTick = new Runnable() {
        @Override
        public void run() {
            sendHeartbeat();
            main.postDelayed(this, KeepAlivePolicy.HEARTBEAT_INTERVAL_MS);
        }
    };

    private void startHeartbeat() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;
        sendHeartbeat();   // 起来立刻报一次，让看门狗尽快从「失联」恢复
        main.postDelayed(heartbeatTick, KeepAlivePolicy.HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        heartbeatStarted = false;
        main.removeCallbacks(heartbeatTick);
    }

    private void sendHeartbeat() {
        // 与 notifyPreviewState() 同一条 SystemUI 通道；补一个小米变体包名，
        // 兼容把 SystemUI 放到 com.miui.systemui 的 ROM。两条都发不会重复计数
        // （Hook 只更新一个时间戳）。接收器挂 signature 级权限，第三方发不进来。
        String[] targets = {"com.android.systemui", "com.miui.systemui"};
        for (String pkg : targets) {
            try {
                Intent i = new Intent(KeepAlivePolicy.ACTION_HEARTBEAT);
                i.setPackage(pkg);
                i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN,
                        SafeWalkPrefKey.BROADCAST_TOKEN);
                sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 用户从最近任务划掉 App。AOSP 上 stopWithTask=false 的服务会继续跑并收到
     * 本回调，但国产 ROM 常直接把整个进程杀掉。这里不无条件重启，而是排一个
     * 1.5 秒后的自检广播：服务真不在了才由 KeepAliveReceiver 拉起，避免重复
     * 启动让前台通知闪一下。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            boolean desired = KeepAlivePolicy.desired(
                    SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true),
                    SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false));
            if (!desired) {
                cancelReviveCheck();
            } else {
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) {
                    Intent i = new Intent(this, KeepAliveReceiver.class)
                            .setAction(KeepAlivePolicy.ACTION_REVIVE)
                            .putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN,
                                    SafeWalkPrefKey.BROADCAST_TOKEN)
                            .putExtra(KeepAlivePolicy.EXTRA_REVIVE_SOURCE, "task_removed");
                    PendingIntent pi = PendingIntent.getBroadcast(this, 77, i,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime()
                                    + KeepAlivePolicy.TASK_REMOVED_CHECK_DELAY_MS, pi);
                    DiagLog.log("[keep] 最近任务被移除 → 已排 1.5s 后自检预览服务");
                }
            }
        } catch (Throwable t) {
            DiagLog.log("[keep] 排自检失败: " + t);
        }
        super.onTaskRemoved(rootIntent);
    }

    /** 用户主动关掉行走模式：撤掉待执行的自检，别让它把服务又拉回来。 */
    private void cancelReviveCheck() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, KeepAliveReceiver.class)
                    .setAction(KeepAlivePolicy.ACTION_REVIVE);
            PendingIntent pi = PendingIntent.getBroadcast(this, 77, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Throwable ignored) {
        }
    }

    /** 自动恢复后一次性回执：让「通知重新出现」这件事有个明确解释。 */
    private void notifyRevivedIfNeeded() {
        try {
            long now = System.currentTimeMillis();
            long last = sp.getLong(SafeWalkPrefKey.lastReviveNoticeTs, 0L);
            if (!KeepAlivePolicy.shouldNotifyRevive(last, now)) return;
            sp.edit().putLong(SafeWalkPrefKey.lastReviveNoticeTs, now).apply();
            new SafeWalkNotificationManager(this).serviceRevived();
            DiagLog.log("[keep] 行走模式已自动恢复（系统曾回收预览进程）");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 提起前台。
     *
     * 【为什么不再按 ROM 版本猜 type】3.3 之前有一份「HyperOS 4 就用 plain 类型」的建议，
     * 但它只出现在日志里，从没落地；而且真按它落地反而更糟：去掉 camera 语义后，
     * Android 11 起相机属于 while-in-use 权限，后台根本打不开相机，小窗就再也显示不出来。
     *
     * 现在改成**运行时降级链**，对所有 ROM 一视同仁：
     *   camera（首选，后台能用相机的唯一合法类型）
     *     → specialUse（camera 被 ROM 拒绝时，先把服务立起来，保住进程与悬浮窗）
     *     → 无 type（API 28 及以下，或前面都失败）
     *
     * 这样做的好处是：HyperOS 3 上第一条就成功、根本不进降级分支；HyperOS 4 上自动
     * 走降级；将来 HyperOS 5 换了限制，也不需要改代码 —— 不存在「版本判断猜错了就废掉」。
     */
    private void goForeground() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_WALK, "行走模式", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("行走模式开启期间保持后台运行；关闭本渠道会导致常驻通知不显示");
        ch.setShowBadge(false);
        nmBase().createNotificationChannel(ch);

        // 【通知消失 ≠ 服务停止】用户（或 ROM 的清理）把渠道设成 IMPORTANCE_NONE 时，
        // 前台服务通知一条都不显示，但服务其实还在跑。createNotificationChannel
        // 不会覆盖用户改过的等级，所以这里读回来判一次：被关掉就换一个 HIGH 渠道
        // 明确说明，别让用户只能靠「通知在不在」猜服务死活。
        NotificationChannel current = nmBase().getNotificationChannel(CHANNEL_WALK);
        if (current != null && current.getImportance() == NotificationManager.IMPORTANCE_NONE
                && !channelDisabledNotified) {
            channelDisabledNotified = true;
            try {
                new SafeWalkNotificationManager(this).walkChannelDisabled();
            } catch (Throwable ignored) {
            }
        }

        // 原来这条通知没设 contentIntent，点了没反应；用户想确认「到底还在不在跑」
        // 时最容易顺手去划它。现在点一下直接回主界面看状态。
        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_WALK)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("行走模式运行中")
                .setContentText("身后路况持续显示中 · 点击查看")
                .setContentIntent(openPi)
                .setOngoing(true)
                .build();

        final String osTag = fgsOsTag();
        if (Build.VERSION.SDK_INT >= 29) {
            for (int type : FGS_TYPE_CHAIN) {
                try {
                    startForeground(NOTIF_ID, n, type);
                    fgsType = type;
                    DiagLog.log("[FGS] 前台服务已提起｜type=" + fgsTypeName(type) + "｜" + osTag);
                    if (type != ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) {
                        // 降级后相机在后台会被拒，这一点必须写进日志，
                        // 否则用户看到「服务活着、小窗不来」会以为又是别的 bug。
                        DiagLog.log("[FGS] 已从 camera 降级到 " + fgsTypeName(type)
                                + "：进程与悬浮窗保住了，但后台访问相机会被系统拒绝，"
                                + "小窗只在应用可见时能出画面");
                        // 同一进程只提示一次：每次走起来都弹一条谁都受不了，
                        // 但完全不提示又会变成「黑箱」。
                        if (!fgsDegradeNotified) {
                            fgsDegradeNotified = true;
                            try {
                                new SafeWalkNotificationManager(this).fgsDegraded();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    return;
                } catch (Throwable t) {
                    DiagLog.log("[FGS] type=" + fgsTypeName(type) + " 被拒｜" + osTag
                            + "｜" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage()));
                }
            }
        }
        try {
            startForeground(NOTIF_ID, n);
            fgsType = -1;
            DiagLog.log("[FGS] 已用无 type 方式提起前台｜" + osTag);
        } catch (Throwable t) {
            fgsType = -2;
            DiagLog.log("[FGS] 前台服务完全无法提起｜" + osTag
                    + "｜" + t.getClass().getSimpleName()
                    + "；预览将只在本应用可见时可用，请把本应用加入电池优化白名单");
        }
    }

    /**
     * camera 必须排第一，理由见 {@link #goForeground()}。
     * 第二档 specialUse 在 manifest 里已声明，且不带相机语义，用来在 camera 被拒时
     * 至少把前台服务立起来（否则服务会被系统在 5 秒内杀掉，悬浮窗一起没）。
     */
    private static final int[] FGS_TYPE_CHAIN = {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    };

    /** 最近一次成功的前台服务类型：-1=无 type，-2=完全失败，0=尚未提起。仅用于诊断。 */
    private volatile int fgsType = 0;

    private static String fgsTypeName(int type) {
        if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) return "camera";
        if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) return "specialUse";
        if (type == -1) return "none";
        if (type == -2) return "failed";
        return String.valueOf(type);
    }

    /**
     * 只用于日志的一行 ROM 说明。**不参与任何行为决策** —— 决策全部基于运行时结果，
     * 避免「版本识别错了导致功能被错误降级」。
     *
     * 4.3 起固定用自动识别（[HyperOsCompat.detect]），不再读 `hyperOsMode`：
     * 首页那张「HyperOS 版本适配」卡片已删，旧配置里的手动值不该继续影响别人。
     */
    private String fgsOsTag() {
        try {
            HyperOsCompat.Os os = HyperOsCompat.detect();
            return "os=" + os.name() + " sdk=" + Build.VERSION.SDK_INT
                    + " 已用=" + fgsTypeName(fgsType);
        } catch (Throwable t) {
            return "os=unknown sdk=" + Build.VERSION.SDK_INT;
        }
    }

    private NotificationManager nmBase() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public void onDestroy() {
        if (hookCmdReceiver != null) {
            try { unregisterReceiver(hookCmdReceiver); } catch (Throwable ignored) {}
            hookCmdReceiver = null;
        }
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
            screenReceiver = null;
        }
        stopGait();
        stopHeartbeat();
        // 进程可能还活着而服务已销毁：不清掉弱引用，isAlive() 会短暂误报，
        // 让划任务自检以为服务还在、放弃恢复。
        INSTANCE = null;
        PlaceArrivalMonitor.stop();
        gaitHandler.removeCallbacks(gaitTick);
        if (overlay != null) overlay.hide();
        // 服务被系统回收时也要通知 Hook：画面已经不在了，
        // 否则它以为还开着，下次该显示时指令被去重吞掉
        reportPreviewState();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
