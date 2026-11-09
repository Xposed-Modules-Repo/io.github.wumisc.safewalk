package io.github.wumisc.safewalk;

import android.app.Notification;
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

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, WalkPreviewService.class);
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
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        // 静置巡检常驻（每秒一次，仅自动模式且有步时才有动作）
        gaitHandler.postDelayed(gaitTick, 1000L);
        overlay = new RearCameraOverlay(this,
                reason -> {
                    new SafeWalkNotificationManager(WalkPreviewService.this).previewFailed(reason);
                    // 看门狗判定失败后会自己 hide()，服务这边必须同步回执，
                    // 否则 Hook 以为画面还在，下一次「该显示」的指令会被去重吞掉
                    reportPreviewState();
                    return kotlin.Unit.INSTANCE;
                },
                step -> {
                    DiagLog.log("[cam] " + step);
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
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        goForeground();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            overlay.hide();
            reportPreviewState();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
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
        if (hookHidden) return;
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

    private void doShow() {
        String mode = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamMode, RearCameraOverlay.MODE_FULLSCREEN);
        if (mode == null) mode = RearCameraOverlay.MODE_FULLSCREEN;
        int opacity = SafePrefs.getInt(sp, SafeWalkPrefKey.rearCamOpacity, 50);
        String corner = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamCorner, RearCameraOverlay.CORNER_BR);
        if (corner == null) corner = RearCameraOverlay.CORNER_BR;
        // 【新增对比度 UI 模块】把「增强对比度」开关透传给悬浮窗。
        // 悬浮窗会把 HUD 文字层（描边/阴影/深色衬底）单独渲染，且不受 opacity 影响。
        boolean contrast = SafePrefs.getBool(sp, SafeWalkPrefKey.enablePreviewContrast, false);

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
        overlay.show(mode, opacity, corner, contrast, torchWithPreview);
        DiagLog.log("[torch:overlay] 小窗联动闪光灯 | 开关=" + linkEnabled
                + " 夜间区间=" + night + " → " + (torchWithPreview ? "随小窗点亮" : "不点亮")
                + " | 生命周期=小窗显示即亮、收起即灭（预览会话内 FLASH_MODE_TORCH）");
        DiagLog.log("[overlay] doShow | mode=" + mode + " opacity=" + opacity
                + " corner=" + corner + " 增强对比度=" + contrast
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
        lastGaitDiagTs = 0L;
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

                if (stepTick || countReached) {
                    lastStepTs = now;   // 刷新「上一步」时间戳，防静置巡检秒判停
                }

                // 被闸门拦下时留痕，真机自测一眼能看出「为什么没激活」
                if (!gaitWalking && !credible && !stepTick
                        && gait.confirmedStepCount() > 0
                        && now - lastGuardDiagTs > 2000L) {
                    lastGuardDiagTs = now;
                    DiagLog.log("[GUARD] ⛔ 步数达标但姿态/节律不可信，抑制激活 | 累计="
                            + gait.confirmedStepCount() + "/" + gait.getConfirmSteps()
                            + " 姿态=" + attitude + " 节律稳定=" + stable
                            + " 硬件计步(10s)=" + hwSteps
                            + " | 防床上/沙发平放抖动误触");
                }

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

                // 节流诊断：每 2 秒打一次步数累计，走没走、卡在哪一眼可见
                if (now - lastGaitDiagTs > 2000L) {
                    lastGaitDiagTs = now;
                    DiagLog.log("[STEP] 计步回调 | 累计合格步 " + gait.confirmedStepCount()
                            + "/" + gait.getConfirmSteps() + " 本帧合格步=" + stepTick
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

    /**
     * 硬件计步确认（自适应，既不误杀走路、也不放过床上误触）：
     * 1) 无计步器 / 传感器从未回调 → 不拦，纯加速度判走（外层 GUARD 闸门把关）。
     * 2) 计步器启动后从未产生任何步数 → 平躺姿态直接拦（床上/沙发误触）；
     *    非平躺才停用确认、降级纯加速度判走，保证走路不显示问题不复发。
     *    【2026 修复】旧版此分支无条件放行，是「床上弹小窗」的第二道闸失效点。
     * 3) 计步器正常出数 → 保留确认：10 秒内 ≥2 步放行；计步不足但持续判走
     *    （累计步达门槛两倍）放行；否则拦（疑似拿手机乱动）。
     */
    private boolean hardwareStepConfirm() {
        if (!stepCounterAlive) return true;
        if (everSteppedTotal == 0L) {
            // 【BUG 修复】上一轮这里无条件 return true，等于「躺床上硬件当然一步没走」
            // 时把第二道防误触闸也常开；与消费侧那条绕过 LAY_FLAT 的判据叠加，
            // 就是最新真机日志「bug2 角落小窗床上打开」的完整成因。
            // 现在收紧：硬件从没出过数时，平躺姿态一律拦；非平躺才降级为纯加速度判走，
            // 不误杀正常走路（外层 GUARD 闸门已经先挡掉了无节律抖动）。
            boolean layFlat = gait.getAttitude() == PhoneAttitude.LAY_FLAT;
            if (layFlat) {
                DiagLog.log("[gait] ⛔ 硬件计步器从未出数且姿态=平躺 → 判定为床上误触，抑制预览");
                return false;
            }
            DiagLog.log("[gait] 硬件计步器从未出数，但姿态非平躺 → 降级纯加速度判走（不误杀走路）");
            return true;
        }
        int n = stepCountInWindow();
        if (n >= STEP_CONFIRM_MIN) return true;
        // 计步不足但持续判走：累计步远超门槛 → 放行
        if (gait.confirmedStepCount() >= gait.getConfirmSteps() * 2) {
            DiagLog.log("[gait] 计步 " + n + "<" + STEP_CONFIRM_MIN
                    + " 但持续判走（累计 " + gait.confirmedStepCount()
                    + " 步），放行显示");
            return true;
        }
        DiagLog.log("[gait] 加速度像走但硬件计步 " + n + "/" + STEP_CONFIRM_MIN
                + "（10s内）→ 疑似拿手机乱动，不显示");
        return false;
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
        if (gait.confirmedStepCount() >= gait.getConfirmSteps() * 2) {
            DiagLog.log("[gait] 判定停下冷却内持续判走（累计 "
                    + gait.confirmedStepCount() + " 步），放行显示");
            return true;
        }
        DiagLog.log("[gait] 判定停下冷却期内（累计 " + gait.confirmedStepCount()
                + " 步），暂不显示，等待持续判走");
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

    private void goForeground() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_WALK, "行走模式", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("行走模式开启期间保持后台运行，收起通知不影响使用");
        ch.setShowBadge(false);
        nmBase().createNotificationChannel(ch);

        Notification n = new Notification.Builder(this, CHANNEL_WALK)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("行走模式运行中")
                .setContentText("身后路况持续显示中")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            // 个别 ROM 对 camera 类型有额外限制：降级尝试普通方式，
            // 再不行就让服务继续跑（预览在前台打开 App 时依然可用）
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
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
        stopGait();
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
