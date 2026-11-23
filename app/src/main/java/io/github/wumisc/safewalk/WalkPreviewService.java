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

    /**
     * 【5.3.0 横竖屏】上一次看到的屏幕朝向（Configuration.ORIENTATION_*）。
     *
     * 用它判断「这次配置变化是不是方向变了」：深色模式切换也会走
     * onConfigurationChanged，但那时不该重排窗口、也不该重算画面方向。
     */
    private int lastOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED;

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
    // 【5.6.5】今日步数：dayKey / 当天基线 / 当天加速度累计 / 最近一次硬件绝对值
    private String stepDayKey = "";
    private long stepBaseTotal = TodayStepPolicy.NO_BASE;
    private long stepAccelToday = 0L;
    private long stepHwTotalLatest = -1L;
    private long todayStepsCache = 0L;
    /** 最近一次「真实步」的时间（硬件回调或加速度合格步），只用于步数徽章的隐显 */
    private long lastMotionStepTs = 0L;
    /**
     * 【5.6.8 功耗】徽章文案缓存：`今日 N 步` 只在步数真的变了才重新拼一次。
     * 旧实现每个 tick 都 format + 拼接（哪怕这一秒一步没走），走路时每秒一次无谓分配。
     */
    private String stepBadgeTextCache = null;
    /** [stepBadgeTextCache] 对应的步数；-1 = 未构建 */
    private long stepBadgeStepsBuilt = -1L;
    private long lastStepFlushTs = 0L;
    /** dayKey 缓存：上次算出的日 key 与其时间戳（见 [DAY_KEY_CACHE_MS]） */
    private String stepDayKeyCache = "";
    private long stepDayKeyCacheTs = 0L;
    /** 【沙发误触修复】连续行走步流的起始时间戳，计步器不可用时用它卡「连续走够久」 */
    private long walkingStreakStartTs = 0L;
    /** 判定停下（静置超时收起）的时刻，用于「收起后冷却」防秒开死循环 */
    private long lastStopTs = 0L;
    /** 收起后冷却时长：冷却内需更强的持续判走才放行，打断「显示→收起→秒开」 */
    private static final long STOP_COOLDOWN_MS = 30_000L;
    /** 【5.3.9】上一次生效的「自动跟随步行」开关值；null=服务冷启动尚未定，用于识别 false→true 跃变 */
    private Boolean lastAppliedAuto = null;

    /**
     * 【5.3.3】判停门槛的有效下限（秒）。
     *
     * 用户可以把「静置多久算停下」滑到 5 秒，但**步态回调本身是 10 秒一次**
     * （`[STEP] 计步回调` 的固定周期，硬件计步 STALE 时更慢）。门槛低于回调周期，
     * 等于每两个回调之间必然出现一段「按时间算早就该停了」的窗口：
     *
     * ```
     * 13:29:56  窗口挂载
     * 13:30:05  计步回调 walking=true（这一步之后用户站着没动）
     * 13:30:12  静置超时 8s → 收起          ← 8s < 10s，必然触发
     * 13:30:15  计步回调 walking=false
     * 13:30:17  冷却 5s 到期 → 再次挂载      ← 用户看到大框「又变回来了」
     * ```
     *
     * 12 秒 = 一个回调周期 + 2 秒余量。低于它的设置一律按 12 秒执行，
     * 界面上也把滑块下限提到 12 秒（见 WalkModeActivity）。
     */
    private static final int MIN_STOP_SEC = 12;
    /**
     * 本服务会话的起始时刻。服务被保活重新拉起 / START_STICKY 重建时刷新，
     * 交给 [StaticStopPolicy] 区分「上一会话残留的 lastStepTs」和本会话的步，
     * 避免刚复活 8 秒就被判静置超时收掉预览。
     */
    private long sessionStartTs = 0L;

    // ========== 防误触：硬件计步器确认 ==========
    // 加速度计判「走」只是波形像走。拿在手里乱动、刷屏、放桌上颠，波形都像走，
    // 只有一样东西不会骗人：系统硬件计步器（TYPE_STEP_COUNTER）——真迈步才计数。
    // 所以加速度判走通过后，还要看「最近 10 秒硬件计步 ≥ 8 步」才显示预览
    // （5.5.0 由 2 步抬到 8 步：见 [STEP_CONFIRM_MIN] 的说明）。
    // 设备无计步器 / 传感器从不回调时自动降级不拦，保证功能不被改崩。
    private Sensor stepCounterSensor;
    private SensorEventListener stepCounterListener;
    /** 注册是否成功（getDefaultSensor 非空 ≠ registerListener 成功，两者要分开记） */
    private boolean stepCounterRegistered = false;
    private final ArrayDeque<Long> stepEventQueue = new ArrayDeque<>();
    /**
     * 【5.2.0】逐帧计步通道（TYPE_STEP_DETECTOR）。
     *
     * COUNTER 是累计型，回调时机由 HAL 决定，小米/三星实测间隔 10~60 秒；
     * DETECTOR 是事件型，**每走一步回调一次**，才是判「最近有没有在走」的正确信号源。
     * 两路都注册、都往同一个队列里塞步数：哪路先到用哪路，互为备份。
     */
    private Sensor stepDetectorSensor;
    private SensorEventListener stepDetectorListener;
    private boolean stepDetectorRegistered = false;
    /** 最近一次（任一通道）回调的时刻，elapsedRealtime */
    private long lastStepCallbackTs = 0L;
    /** 硬件计步回调间隔的滑动平均，用于自适应统计窗口（见 StepHealthPolicy.windowMs） */
    private long stepCallbackIntervalEma = 0L;
    /** 本次监听周期内是否已经说明过「为什么走纯加速度」，避免每帧刷屏 */
    private int lastLoggedChannelOrdinal = -1;
    /** 是否收到过硬件计步事件（没收到 = 无计步器或传感器失效，不拦） */
    private boolean stepCounterAlive = false;
    /**
     * 硬件计步确认窗口（毫秒）与最少步数。
     *
     * 【5.5.0 为什么从 2 抬到 8】
     * 2 步太松：真机自测里「举着手机伸个懒腰」「翻身带两下」「在手里晃三下」
     * 都能在 10 秒窗口内凑出 2~3 个硬件步（不少 ROM 的 STEP_DETECTOR 对
     * 重心位移敏感，不是严格迈步计数），于是波形 + 两步就把预览弹出来。
     * 用户要求「连续走 8 步为最低标准」——8 步是"人真的开始走路"的最小单位
     * （正常步频 8 步 ≈ 4~5 秒，慢走 ≈ 10 秒，都还能落在这个 10 秒窗口内），
     * 而抖动/伸展/翻身几乎不可能连续凑出 8 步节律。
     * 窗口本身不放大：抬门槛比拉长窗口更安全，不会把"刚起步"误判成"没在走"。
     */
    private static final long STEP_CONFIRM_WINDOW_MS = 10_000L;
    private static final int STEP_CONFIRM_MIN = 8;
    /**
     * 【5.6.4】硬件计步通道放行前要求的「连续步流时长」下限（毫秒）。
     *
     * 真机反馈「手机在手里晃一下、拿起来拍张照，小窗哐当就弹出来了」。
     * 数量门槛（10 秒 8 步）挡不住这类动作：不少 ROM 的 STEP_DETECTOR 对重心位移敏感，
     * 手心轻微往复也能数出 8~10 步；而波形侧的垂直轴占比闸门只能证明
     * 「这一帧的峰是上下冲击」，证明不了「这件事持续了多久」。
     *
     * 真人走路必然是连续的：3 秒内至少再走 3~5 步。所以硬件通道达标后再要一条
     * 时间证据 —— 连续步流已经持续满 3 秒，才允许放行。晃动/举手机拍照
     * 都是瞬时动作，3 秒后就散了，过不了这条。
     */
    private static final long LIVE_CONFIRM_STREAK_MS = 3_000L;
    // ==================== 【5.6.5】今日步数 ====================

    /**
     * 【5.6.7 功耗】dayKey 计算缓存窗口。
     *
     * 旧写法每记一步都 new 一个 Calendar + String.format：走路时 1~2 次/秒、
     * 一天几千次分配，只为判断「是不是换天了」。日期只在 0 点变一次，
     * 缓存 30 秒完全够用 —— 跨天最多晚 30 秒归零，用户不可能察觉。
     */
    private static final long DAY_KEY_CACHE_MS = 30_000L;
    /** 今日步数状态落盘键（值可能是跨天前的，读出后按 dayKey 判定是否作废） */
    private static final String SP_KEY_STEP_DAY = "step_today_day";
    private static final String SP_KEY_STEP_BASE = "step_today_base";
    private static final String SP_KEY_STEP_ACCEL = "step_today_accel";

    /**
     * 步数徽章「不动就隐藏」的时长：最近一次真实步过去这么久之后，HUD 下方的小框收起来。
     *
     * 小窗本身就可能在判停后收起，但手动常显 / 调试场景下窗口会一直在，
     * 静止时还挂着一个不变化的「今日 N 步」没有意义，还挡画面。
     */
    private static final long STEP_BADGE_HIDE_MS = 60_000L;

    /** 步数状态落盘节流：走路时每步都写 SP 太重，攒够间隔再写一次 */
    private static final long STEP_STATE_FLUSH_MS = 30_000L;
    /**
     * 【沙发误触修复 v3 · 真机日志 2026-11-10 13:48~14:02】
     *
     * v2 把纯加速度门槛定成「累计 ≥ max(确认步数×2, 15) 步」，但采集层
     * [GaitDetector] 的 `peakIntervals` 队列**上限只有 10**（超过就 removeAt(0)），
     * 所以 `confirmedStepCount()` 永远 ≤ 10，`≥15` 是**永不成立的死条件**。
     * 真机日志里 `累计=7/15`、`累计=10/7` 刷了 968 条，自动跟随一次都没激活。
     *
     * 现在门槛交给 [WalkConfirmPolicy]：不看「累计多少步」，改看**步流的时间形态**
     * （连续 8~10 步、最大步间隔 ≤1.4s、平均步间隔 380~950ms、
     * 整段跨度 ≥ 3.15~4.05s（按门槛推导，见 WalkConfirmPolicy.minSpanFor），
     * 且相邻间隔离散度受控），既在窗口上限内可达，又能把间歇抖动挡在外面。
     */
    private WalkConfirmPolicy accelPolicy;
    /** 纯加速度判定用的合格步时间戳队列（每个真实合格步入队一次，断步清空） */
    private final java.util.ArrayDeque<Long> accelStepTs = new java.util.ArrayDeque<>();
    /** 上一帧看到的采集层合格步数，用于识别「本帧新增了一步」 */
    private int lastAccelCountSeen = 0;
    /** 本段监听是否已说明过「为什么走纯加速度」（每段只说一次） */
    private boolean accelFallbackLogged = false;
    /**
     * 有硬件计步器、但 10 秒内只出 0~1 步时的兜底要求：连续步流最短时长。
     * 真机日志 13:08:07→13:08:15 只隔 8 秒就放行过一次，说明 8s 太短。
     */
    private static final long ACCEL_MIN_STREAK_MS = 15_000L;
    /** 连续步流允许的最大间隙：超过它即视为「走断了」，连续计时清零 */
    private static final long STREAK_GAP_MS = 3_000L;
    /**
     * 【5.5.0 加固】最小连续跨度校验：最近 [ACCEL_MIN_SPAN_STEPS] 个合格步的时间跨度
     * 至少要这么长，否则按「短促抖动」处理，不刷新步流、不激活预览。
     *
     * 为什么要它：「连续 8 步」只约束了**数量**，没约束**时长**。「举着手机在 3 秒内
     * 连抖 8 下」（伸懒腰、甩手、翻身拍床）在波形上与走路几乎一样，光靠数量照样能凑满。
     * 真人走 8 步：正常步频 ~4~5 秒、慢走 ~10 秒，都远大于 3 秒；而采集层步频窗口
     * 上限是 2.5Hz，8 个合格步**理论最短** 7×400=2800ms —— 所以「< 3 秒的 8 步」
     * 只可能落在 2.4Hz 以上的慢跑边界上，不是正常步行。
     *
     * 阈值取 3000ms 的效果：真人步行（含慢走、含口袋晃动造成的漏峰拉长）100% 通过，
     * 只有"3 秒内连抖 8 下"被挡。样本不足 8 个时**不判**（见 [accelStreamLongEnough]）。
     */
    private static final int ACCEL_MIN_SPAN_STEPS = 8;
    private static final long ACCEL_MIN_SPAN_MS = 3_000L;
    /** 启动以来计步器累计产生的步数（自本次注册起）。从未出数 = 该设备计步器不可信，停用确认 */
    private long everSteppedTotal = 0L;
    /** TYPE_STEP_COUNTER 是累计传感器，values[0]=总步数；记录上次回调的总步数以算增量 */
    private long lastTotalSteps = -1L;

    /** 静置超过该时长判定为停下（与 Hook 默认 20s 一致） */
    private static final long GAIT_STATIC_MS = 10_000L;
    /**
     * 【5.1.0】自动模式判停的默认静置时长（秒）。
     *
     * 4.x 是 10 秒。真机日志（13:57~16:25，3839 行）里「静置超时，判定停下 → 收起预览」
     * 出现了 60 多次，其中大量发生在「刚弹出来十几秒」的时候：纯加速度把手持晃动
     * 判成走路 → 弹窗 → 用户其实站着没走 → 10 秒后收起 → 又晃两下再弹。
     * 用户描述成「在手里经常跳预览小窗」，看到的就是这个循环。
     *
     * 提到 15 秒：真人过马路、等红灯、低头看手机再抬头继续走，中间停 10~15 秒很常见，
     * 15 秒能把这类正常停顿容纳进去，而不至于让误触发的小窗一闪就没。
     */
    private static final int DEFAULT_STOP_SEC = 15;
    /**
     * 【5.6.3】自动显示的悬挂保险丝时长（毫秒）。
     *
     * 自动模式的小窗只由「走起来」拉起、只由判停收起，判据一旦被异常数据顶住
     * （历史上有过 gaitWalking=false 导致判停整体不生效的悬挂），窗口就永久挂在屏幕上。
     * 这里加一道与步态正交的超时：自动显示满 3 分钟、且最近 60 秒合格步数不足 6
     * （连 0.1Hz 都不到），直接按「确认停下」收起。真走路 60 秒内至少几十步，
     * 永远不会命中这道兜底。
     */
    private static final long AUTO_SHOW_SAFETY_MS = 180_000L;
    private static final long AUTO_SHOW_SAFETY_STEP_WINDOW_MS = 60_000L;
    private static final int AUTO_SHOW_SAFETY_MIN_STEPS = 6;
    /** 本次自动显示的时刻，用于「显示驻留」判定（见 [StaticStopPolicy]） */
    private long gaitShowTs = 0L;
    private final Handler gaitHandler = new Handler(Looper.getMainLooper());
    /**
     * 静置巡检：自动模式下超过 [GAIT_STATIC_MS] 无新步 → 判停 → 收起预览。
     * 步行时 GaitDetector 会持续产出步、刷新 lastStepTs，巡检不会误收。
     */

    private final Runnable gaitTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long stopMs = Math.max(MIN_STOP_SEC, Math.min(30,
                    SafePrefs.getInt(sp, SafeWalkPrefKey.gaitStopSec, DEFAULT_STOP_SEC))) * 1000L;
            // 会话基线：服务被保活复活 / START_STICKY 重建时，上一会话残留的
            // lastStepTs 会让这里在刚拉起几秒就判「静置超时」把预览收掉
            // （真机 19:06:49 复活 → 19:06:57 收起，只隔 8 秒）。
            // 判定交给 StaticStopPolicy（纯逻辑、有单测），旧时间戳作废 + 30s 宽限。
            if (gaitListener != null
                    && SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)
                    && StaticStopPolicy.shouldStop(lastStepTs, sessionStartTs, now, stopMs,
                            StaticStopPolicy.SESSION_GRACE_MS, gaitShowTs,
                            StaticStopPolicy.DEFAULT_MIN_SHOW_MS, gaitWalking,
                            observedForStop())) {
                // 【5.3.8】判停诊断快照：下面会把 lastStepTs/gaitShowTs/gaitWalking 复位，
                // 先留住「决策那一刻」的值，六元组才不会全变成复位后的 0 / false。
                // 【审查返工 · M-2】观测步时钟也在此一次性快照，日志改用该快照，
                // 禁止再直接读实时字段，同一行日志的时钟取值才自洽。
                onStopConfirmed(now, "静置超时判停", stopMs);
            } else if (gaitWalking && gaitShowTs > 0L
                    && now - gaitShowTs > AUTO_SHOW_SAFETY_MS
                    && accelStepsInWindow(AUTO_SHOW_SAFETY_STEP_WINDOW_MS) < AUTO_SHOW_SAFETY_MIN_STEPS) {
                // 【5.6.3】悬挂保险丝：判据被异常数据顶住时，窗口不能永远挂在屏幕上。
                // 真走路时最近 60s 至少几十步，不可能命中；只有「久坐 / 蹲着但采集层
                // 仍偶发报步」这类异常才会走到这里。
                onStopConfirmed(now, "自动显示超时兜底（最近 "
                        + (AUTO_SHOW_SAFETY_STEP_WINDOW_MS / 1000) + "s 合格步="
                        + accelStepsInWindow(AUTO_SHOW_SAFETY_STEP_WINDOW_MS) + "）", stopMs);
            }

            // 低头提醒：仅在「已确认行走（gaitWalking）+ 亮屏 + 屏幕朝上偏水平」
            // 同时成立、且持续 8 秒时才提醒，3 分钟冷却。判定全部在 HeadDownPolicy，
            // 这里只负责取数与发通知，不碰任何步态 / 防误触判据。
            try {
                boolean headDownOn = SafePrefs.getBool(sp, SafeWalkPrefKey.enableHeadDownRemind, false);
                // 【5.6.8 功耗】开关关着就整段跳过：旧写法无论开没开都要取 PowerManager、
                // 打一次 isInteractive() IPC、再算一遍 tilt，一天上万次全是为一个关着的功能。
                if (headDownOn) {
                    boolean screenOn = screenInteractive;
                    float tilt = gait == null ? -1f : gait.tiltDegrees();
                    if (HeadDownPolicy.evaluate(true, gaitWalking, screenOn, tilt, now, headDownState)) {
                        new SafeWalkNotificationManager(WalkPreviewService.this).headDownRemind();
                        DiagLog.log("[head-down] 行走中持续低头 → 已提醒抬头 | tilt="
                                + Math.round(tilt) + "°");
                    }
                }
            } catch (Throwable t) {
                DiagLog.logThrottled("head.down", "err", "[head-down] 评估异常: " + t.getMessage());
            }

            // 久走提醒：累计（不是连续）走满 45 分钟才提醒一次，等红灯这类停顿不清零。
            // 判定全在 LongWalkPolicy（纯逻辑、有单测），这里只负责取数与发通知。
            try {
                boolean longWalkOn =
                        SafePrefs.getBool(sp, SafeWalkPrefKey.enableLongWalkRemind, false);
                if (LongWalkPolicy.evaluate(longWalkOn, gaitWalking, now, longWalkState,
                        LongWalkPolicy.DEFAULT_WALK_MS,
                        LongWalkPolicy.DEFAULT_PAUSE_GRACE_MS,
                        LongWalkPolicy.DEFAULT_COOLDOWN_MS)) {
                    int minutes = (int) (LongWalkPolicy.DEFAULT_WALK_MS / 60_000L);
                    new SafeWalkNotificationManager(WalkPreviewService.this).longWalkRemind(minutes);
                    DiagLog.log("[long-walk] 累计行走满 " + minutes + " 分钟 → 已提醒歇脚");
                }
            } catch (Throwable t) {
                DiagLog.logThrottled("long.walk", "err", "[long-walk] 评估异常: " + t.getMessage());
            }

            // 夜间独行提醒：只在夜间时段内累计（白天 enabled=false，累计清零）。
            // 与夜间手电共用同一组时段配置，用户改时段两条提醒一起跟着变。
            try {
                boolean nightSoloOn =
                        SafePrefs.getBool(sp, SafeWalkPrefKey.enableNightSoloRemind, false);
                boolean nightWindow = nightSoloOn && nightHourHit();
                if (LongWalkPolicy.evaluate(nightWindow, gaitWalking, now, nightSoloState,
                        LongWalkPolicy.NIGHT_WALK_MS,
                        LongWalkPolicy.NIGHT_PAUSE_GRACE_MS,
                        LongWalkPolicy.NIGHT_COOLDOWN_MS)) {
                    int minutes = (int) (LongWalkPolicy.NIGHT_WALK_MS / 60_000L);
                    new SafeWalkNotificationManager(WalkPreviewService.this).nightSoloRemind(minutes);
                    DiagLog.log("[night-solo] 夜间累计行走满 " + minutes + " 分钟 → 已提醒留意周边");
                }
            } catch (Throwable t) {
                DiagLog.logThrottled("night.solo", "err", "[night-solo] 评估异常: " + t.getMessage());
            }
            updateStepBadge(now);
            gaitHandler.postDelayed(this, nextGaitIntervalMs());
        }
    };

    /** 行走中 / 正在显示预览：判停与收窗要看住，1 秒一次 */
    private static final long GAIT_TICK_ACTIVE_MS = 1000L;

    /** 静止且未显示：3 秒一次就够，省掉 2/3 的唤醒 */
    private static final long GAIT_TICK_IDLE_MS = 3000L;

    /**
     * 【5.6.0 省电】gaitTick 自适应节拍。
     *
     * 5.5.1 里这个 tick 是**无条件 1 秒**：从 onCreate 起跑到服务销毁，不管用户
     * 有没有开自动跟随、有没有在显示预览、是不是一整天躺在桌上 —— 一天 8.6 万次唤醒，
     * 每次还要读一圈 SharedPreferences 并写一条日志。
     *
     * 关键前提：判停用的是 `elapsedRealtime()` 精确时间戳（静置门槛 12s），
     * 不是靠 tick 累加，所以把节拍放宽到 3 秒**不改变任何判据**，只让「执行判停
     * 的那一刻」最多晚到 2 秒。行走中与显示中仍保持 1 秒，用户能感知的路径零变化。
     */
    private long nextGaitIntervalMs() {
        boolean active = gaitWalking || overlay.getShowing();
        return active ? GAIT_TICK_ACTIVE_MS : GAIT_TICK_IDLE_MS;
    }
    /**
     * 被 Hook 让路（手电/相机应用/总开关关闭）强制收起时置 true，
     * 期间 evaluateShow 不再主动显示，等 Hook 发 ACTION_HOOK_SHOW 恢复。
     */
    private boolean hookHidden = false;

    /** 低头提醒的计时 / 冷却状态（纯逻辑策略，见 HeadDownPolicy） */
    private final HeadDownPolicy.State headDownState = new HeadDownPolicy.State();

    /**
     * 久走提醒的累计状态（见 [LongWalkPolicy]）。
     *
     * 两条提醒各持一个 State，互不干扰：久走的 45 分钟累计不能因为夜间提醒
     * 刚触发过就被清空，反之亦然。
     */
    private final LongWalkPolicy.State longWalkState = new LongWalkPolicy.State();

    /** 夜间独行提醒的累计状态（规则同久走，阈值更短） */
    private final LongWalkPolicy.State nightSoloState = new LongWalkPolicy.State();

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
    /**
     * 【5.6.8 功耗】亮屏状态缓存。
     *
     * `PowerManager.isInteractive()` 是 binder 调用：旧实现每个 gaitTick 都问一次
     * （1~3 秒一次，一天上万次），而屏幕亮灭事件本来就由 [screenReceiver] 送上门。
     * 现在只在「本次 service 启动」与「收到 SCREEN_ON/OFF」时更新，tick 里读字段。
     * 取不到时按亮屏处理（宁可漏拦低头提醒，也不误拦）。
     */
    private boolean screenInteractive = true;

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
        // 【5.6.8】亮屏状态初值：之后由 screenReceiver 的 SCREEN_ON/OFF 维护
        try {
            android.os.PowerManager pm0 =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm0 != null) screenInteractive = pm0.isInteractive();
        } catch (Throwable ignored) {
        }
        // 【配置防丢】服务可能先于主界面被拉起：先尝试从外部备份恢复配置，
        // 否则开机自启时会按空 SP 的默认值（行走模式=关）判断，导致「开机后是关闭的」。
        ConfigBackup.restoreIfNeeded(getApplicationContext());
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        loadStepState();
        // 【到达提醒】证件 / 到场提示不再由「确认步行」触发，改由 App 侧低频定位
        // 判定「到达某类场所」后再发（见 PlaceArrivalMonitor）。
        // 服务是常驻的，挂在这里最稳；start 内部幂等，多入口重复调用无害。
        PlaceArrivalMonitor.start(getApplicationContext());
        // 心跳先于一切：让 SystemUI 里的看门狗知道「本进程活着」，
        // 否则它会在 90 秒后误判失联并再拉一次服务。
        startHeartbeat();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        // 【5.3.0 横竖屏】记下服务启动时的朝向，之后对比才能判断「方向真的变了」。
        // 不初始化的话第一次 onConfigurationChanged 会被当成「竖→横」，
        // 即使只是切了深色模式，也会白重排一次窗口。
        try {
            lastOrientation = getResources().getConfiguration().orientation;
        } catch (Throwable ignored) {
            lastOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED;
        }
        // 会话基线：服务实例创建即为一个新会话。StaticStopPolicy 用它作废
        // 上一会话残留的步时间戳，避免复活后立刻判「静置超时」收起预览。
        sessionStartTs = System.currentTimeMillis();
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
                    screenInteractive = false;
                    if (overlay != null) overlay.hide();
                    reportPreviewState();
                } else {
                    screenInteractive = true;
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
        // 裸启动（AutoStart 复活 / START_STICKY 重建 / 用户重新打开预览）视为新会话：
        // 刷新基线，让上一会话残留的 lastStepTs 立刻作废。
        // 注意：带 action 的 SHOW/HIDE/STOP 指令**不刷新** —— WalkMode 里拖静置
        // 秒数滑杆会高频 send(ACTION_SHOW)，若每次都续期 30s 宽限，预览将永不收起。
        if (action == null) {
            sessionStartTs = System.currentTimeMillis();
            // 新会话让关键诊断第一条立刻可见，不必等上一会话的节流窗口过期
            DiagLog.resetThrottle("gait.hw");
            DiagLog.resetThrottle("gait.accel");
        }
        if (ACTION_STOP.equals(action)) {
            // 用户主动关闭：停心跳、撤掉待执行的自检。否则看门狗会看到
            // 「配置没变但心跳停了」把服务又拉回来 —— 变成关不掉的功能。
            stopHeartbeat();
            cancelReviveCheck();
            overlay.hide();
            reportPreviewState();
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundEstablished = false;  // 已撤掉前台，下次启动允许重新提起
            DiagLog.flushNow();            // 【5.6.0】批量日志，停止前刷盘
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
        boolean newAuto = SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false);
        // 【5.3.9】自动跟随步行 false→true 跃变：一次性显示权交接。
        // 三个入口都汇聚 applyFromConfig，这里一处覆盖；冷启动首帧 lastAppliedAuto==null 不交接
        // （无旧常显窗可收，也不该无谓清冷却）。高频 ACTION_SHOW 因 lastAppliedAuto 已置 true 不会重复。
        if (lastAppliedAuto != null && !lastAppliedAuto && newAuto) {
            handoffToAutoMode();
        }
        lastAppliedAuto = newAuto;
        if (newAuto) {
            startGait();
        } else {
            stopGait();
        }
        evaluateShow();
        // 【5.6.8】自动/手动切换会改副标题语义（检测中 vs 持续显示中），
        // 而 reportPreviewState 只对「显示状态变化」去重，这里补一次刷新。
        refreshWalkNotification();
    }

    /**
     * 【5.3.9】自动跟随步行 false→true 的一次性显示权交接。
     * 只做「清场交接」：收起切换前的常显窗 + 复位判停 / 显示基线与收起冷却，
     * 让后续 startGait() + evaluateShow() 从干净状态开始。
     * 刻意不在这里调 startGait() / evaluateShow() / doShow()：步态未激活时 evaluateShow
     * 不显示正是期望，等真实步态命中由 onSensorChanged 激活分支经 evaluateShow 拉起。
     */
    private void handoffToAutoMode() {
        // 1. 收起切换前的常显窗，夺回显示权（本 bug 核心：旧窗因 gaitWalking=false 永不判停而永久悬挂）
        if (overlay != null && overlay.getShowing()) {
            overlay.hide();
            reportPreviewState();
        }
        // 2. 复位显示驻留基线，让步态从干净时刻开始计时
        gaitShowTs = 0L;
        // 3. 复位合格步时钟，即使 startGait 幂等 return 也保证干净
        lastStepTs = 0L;
        // 4. 复位连续步行起点
        walkingStreakStartTs = 0L;
        // 5. 解除 30s 收起冷却，否则切到开后短时间走了也不显示
        lastStopTs = 0L;
        // 6. 真机 grep 自证：交接确实发生
        DiagLog.log("[walk] 自动模式交接 | 收起常显窗 → 显示权交步态 | 已清 gaitShowTs/lastStepTs/lastStopTs，等真实步态拉起");
        // 7. 保持磁贴 UI 一致
        WalkTileService.refresh(this);
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
            // 自动模式：走起来就显示。
            //
            // 【5.3.3】这里刻意**不**在 gaitWalking=false 时收起。
            // evaluateShow 会被每一步回调、每次配置刷新、每次亮屏解锁调用，
            // 而「收起」的语义应该是「确认停下」——15 秒无新步 + 12 秒显示驻留，
            // 那套判据在 gaitTick 的 StaticStopPolicy 里，这里看不到 gaitShowTs。
            // 旧写法一看到 false 就 hide，任何一步没踩准（硬件计步 STALE、
            // 姿态闸门短暂关闭）都会让窗口消失一次再弹回来。真机 12:37 的
            // 「一分钟 25 次挂载」里，即时收起贡献了其中一部分。
            if (gaitWalking) doShow();
        } else {
            // 手动常显
            doShow();
        }
    }

    /**
     * 确认停下后的收起。
     *
     * 只由 gaitTick 里 StaticStopPolicy 判停之后调用：那是「真的停了」，
     * 不是「这一秒没踩到步」。不再借道 [evaluateShow]，
     * 因为后者已改成按 walking 只显示、不收起（见那里的注释）。
     */
    private void hideAfterStopConfirmed() {
        if (overlay != null && overlay.getShowing()) {
            overlay.hide();
            reportPreviewState();
        }
    }

    /**
     * 【5.6.3】判停用的「观测峰时钟」：只有窗口内确有连续步流时才返回非 0。
     *
     * 背景：蹲下 / 坐下后，身体微动每隔 1~2 秒也会产生一个越阈峰，把
     * [GaitDetector.stepActivityTs] 一路推新。旧实现把这个时钟直接交给
     * [StaticStopPolicy]，于是 `now - effectiveTs` 永远小于门槛，判停永不触发 ——
     * 用户看到的正是「走几步到厕所蹲下，路况预览一直显示不消失」。
     *
     * 现在要求窗口攒满 confirmSteps 才认作「还在走」：
     * 真走路攒 10 步只需 6~11 秒，必然够数；稀疏微动（蹲着每 2 秒一次）
     * 在 15s 时效窗口里最多 8 个峰，凑不满，判停时钟自然停住。
     */
    private long observedForStop() {
        if (gait == null) return 0L;
        long ts = gait.stepActivityTs();
        if (ts == 0L) return 0L;
        // 「还在走」要求窗口攒满 confirmSteps（用户档位 8~10）。门槛之内本来就没走起来，
        // qualified 也不会推进 lastStepTs，观测时钟没有独立贡献；放开只会让蹲着时的
        // 孤立峰把判停一直拖住（真机就是「蹲下后路况预览一直显示」）。
        return gait.confirmedStepCount() >= gait.getConfirmSteps() ? ts : 0L;
    }

    /**
     * 【5.6.3】确认停下后的统一收尾：复位判停状态 → 写诊断 → 收起预览。
     *
     * 抽出来是为了让「静置超时判停」与「悬挂保险丝」走同一条复位路径：
     * 两处各写一遍，迟早有一处漏清 gaitShowTs / lastStepTs，留下的正是
     * 「walking=false、窗口还挂在屏幕上」这种最难查的状态。
     *
     * 必须直接 overlay.hide()，不能借道 evaluateShow —— 后者只按 walking 显示、
     * 不负责收起（见那里的注释），走它就等于判停之后窗口永远挂着。
     */
    private void onStopConfirmed(long now, String reason, long stopMs) {
        final long diagObservedStepTs = gait != null ? gait.stepActivityTs() : 0L;
        final long diagEffectiveTs = Math.max(lastStepTs, diagObservedStepTs);
        final long diagQualifiedStepTs = lastStepTs;
        final long diagShowTs = gaitShowTs;
        final boolean diagWalking = gaitWalking;   // 能进本方法必然是「在走时走到收尾」
        gaitWalking = false;
        // 清零 lastStepTs：否则「超过门槛无步」这个条件会一直成立，
        // 每秒 tick 都重复打一条判停日志刷屏。下次真走会重新置位。
        // 判停时钟来自 GaitDetector 的合格步峰时间戳，只有 gait.reset() 才归零；
        // 判停动作本身不触碰它，下次激活由 maxOf 取更新值。
        lastStepTs = 0L;
        gaitShowTs = 0L;
        lastStopTs = now;
        // 六元组 + 生效时钟 + 横竖屏：字段名保持一致便于下轮真机 grep 定位。
        // getResources() 用 try/catch 兜底；整段吞异常，日志绝不影响判停与收起。
        try {
            boolean portrait = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            DiagLog.log("[walk] " + reason + " | 生效上一步="
                    + ((now - diagEffectiveTs) / 1000) + "s前"
                    + " 合格步时钟=" + diagQualifiedStepTs
                    + " 观测步时钟=" + diagObservedStepTs
                    + " 显示驻留=" + ((now - diagShowTs) / 1000) + "s前"
                    + " 门槛=" + (stopMs / 1000) + "s"
                    + " walking=" + diagWalking
                    + " 竖屏=" + portrait + " | 收起预览");
        } catch (Throwable ignored) {
            // 日志取值/输出异常一律吞掉：判停已决策，收起与 1s 巡检循环照常进行
        }
        hideAfterStopConfirmed();
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
        // 语义：夜间时段 + 开关开 + 角落小窗正在显示 → 闪光灯随预览会话常亮；
        // 小窗收起（停下 / 让路 / 关开关）→ 预览会话关闭，闪光灯随之熄灭。
        boolean torchWithPreview = linkEnabled && night;

        // 【4.9.0 小窗自定义】HUD 文案默认关：早期那句「路况预览中 · 增强对比度」
        // 是调试遗留，用户明确反馈「没有任何价值」。这里保持空字符串 → 不显示，
        // 只有用户在「小窗自定义」里主动打开并填写内容时才渲染文字层。
        boolean hudEnabled = SafePrefs.getBool(sp, SafeWalkPrefKey.previewHudEnabled, false);
        String hudText = hudEnabled
                ? SafePrefs.getString(sp, SafeWalkPrefKey.previewHudText,
                        SafeWalkPrefKey.PREVIEW_HUD_TEXT_DEFAULT)
                : null;
        if (hudText != null && hudText.trim().isEmpty()) {
            hudText = SafeWalkPrefKey.PREVIEW_HUD_TEXT_DEFAULT;
        }
        int hudSize = SafePrefs.getInt(sp, SafeWalkPrefKey.previewHudTextSize,
                RearCameraOverlay.HUD_TEXT_SIZE_DEFAULT_SP);
        int smallWidth = SafePrefs.getInt(sp, SafeWalkPrefKey.previewSmallWidthDp,
                RearCameraOverlay.SMALL_WIDTH_DEFAULT_DP);
        int smallMargin = SafePrefs.getInt(sp, SafeWalkPrefKey.previewSmallMarginDp,
                RearCameraOverlay.SMALL_MARGIN_DEFAULT_DP);

        // 【5.0.0】取景范围 / 描边 / 低光增强。三者都是纯显示参数，
        // 与「走没走」的判定无关，读不到时一律退回默认值（完整画面、无描边、不增强）。
        int framing = SafePrefs.getInt(sp, SafeWalkPrefKey.previewFramingPct,
                RearCameraOverlay.FRAMING_DEFAULT_PCT);
        int border = SafePrefs.getInt(sp, SafeWalkPrefKey.previewBorderDp,
                RearCameraOverlay.BORDER_DEFAULT_DP);
        boolean lowLight = SafePrefs.getBool(sp, SafeWalkPrefKey.previewLowLight, false);

        // 【5.1.0 画面增强】这三个值才是「让画面本身看得清」：对比度、暗部提亮、饱和度。
        // 与上面那个 contrast（文字描边）是两件完全不同的事，所以名字里带 Screen 以示区分。
        boolean screenEnhance = SafePrefs.getBool(sp, SafeWalkPrefKey.previewScreenEnhance, false);
        int screenContrast = SafePrefs.getInt(sp, SafeWalkPrefKey.previewScreenContrast,
                RearCameraOverlay.SCREEN_CONTRAST_DEFAULT);
        int screenShadow = SafePrefs.getInt(sp, SafeWalkPrefKey.previewScreenShadowLift,
                RearCameraOverlay.SCREEN_SHADOW_DEFAULT);
        int screenSat = SafePrefs.getInt(sp, SafeWalkPrefKey.previewScreenSaturation,
                RearCameraOverlay.SCREEN_SATURATION_DEFAULT);

        // 【5.1.0 智能方案】跟随系统深色模式，浅色模式用户按自己设的夜间时段判断。
        //
        // 只在「跟随」开启时生效，且**不写回 SharedPreferences**：用户手动调过的
        // 滑块值原样保留，关掉跟随立刻恢复 —— 自动化的东西一旦偷偷改配置，
        // 用户就再也回不到他调好的那套参数了。
        //
        // 文字开关（hudEnabled）刻意不在方案里被覆盖：三个预设的 hud 都是 false，
        // 照搬会把用户主动打开的状态文字关掉，那不是「智能」，是自作主张。
        if (SafePrefs.getBool(sp, SafeWalkPrefKey.presetAuto, true)) {
            boolean darkMode = isSystemDarkMode();
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            int startH = SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchStartHour, 22);
            int endH = SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchEndHour, 6);
            PresetResolve.Params preset = PresetResolve.resolveParams(darkMode, hour, startH, endH);
            framing = preset.framing;
            border = preset.borderDp;
            lowLight = preset.lowLight;
            screenEnhance = preset.screenEnhance;
            screenContrast = preset.screenContrast;
            screenShadow = preset.screenShadowLift;
            screenSat = preset.screenSaturation;
            DiagLog.log("[style] 智能方案 | " + PresetResolve.reason(darkMode, hour, startH, endH)
                    + " → 取景=" + framing + "% 描边=" + border + "dp 低光=" + lowLight
                    + " 画面对比=" + screenContrast + "% 暗部=" + screenShadow + "%");
        }

        // 【5.3.0 画面方向】auto / fixed / flip。刷不到值时按 auto ——
        // 方向自动纠正是「看得见路」的前提，宁可多转一次也不能不转。
        String rotationMode = SafePrefs.getString(sp, SafeWalkPrefKey.previewRotationMode,
                PreviewOrientationPolicy.MODE_AUTO);
        rotationMode = PreviewOrientationPolicy.normalizeMode(rotationMode);

        // 【5.4.15 手动方向 / 5.4.16 起】三项新选择。两项窗口形状刷不到值按 auto
        // （保持 5.4.14 行为）；后摄转正方向 5.4.16 起默认 left/270°，开箱即正、无需手选。
        // 归一也放在这里做一次，渲染层只读到合法档位，不必每个分支再兜底错字。
        String landscapeWindowShape = ManualOrientation.normalizeShape(
                SafePrefs.getString(sp, SafeWalkPrefKey.landscapeWindowShape,
                        ManualOrientation.SHAPE_AUTO));
        String portraitWindowShape = ManualOrientation.normalizeShape(
                SafePrefs.getString(sp, SafeWalkPrefKey.portraitWindowShape,
                        ManualOrientation.SHAPE_AUTO));
        String rearCameraFacing = ManualOrientation.normalizeFacing(
                SafePrefs.getString(sp, SafeWalkPrefKey.rearCameraFacing,
                        ManualOrientation.FACING_LEFT));
        // 【路况预览 · 镜头】auto(默认，等价超广角)/main/ultrawide；渲染层按焦距挑选，
        // 无超广角自动回退主摄。归一放在这里做一次，渲染层只读合法档位。
        String rearCameraLens = CameraLensPolicy.normalize(
                SafePrefs.getString(sp, SafeWalkPrefKey.rearCameraLens,
                        CameraLensPolicy.MODE_AUTO));

        // 【5.6.5】步数奖励开关：小窗自定义里能关，关掉后描边保持白色、不挂小红旗。
        // 每次显示前读一次，改完配置下一次显示就生效（不需要重启服务）。
        overlay.setStepRewardEnabled(
                SafePrefs.getBool(sp, SafeWalkPrefKey.stepRewardEnabled, true));
        overlay.show(mode, opacity, corner, contrast, torchWithPreview, contrastLevel,
                hudText, hudSize, smallWidth, smallMargin, framing, border, lowLight,
                screenEnhance, screenContrast, screenShadow, screenSat, rotationMode,
                landscapeWindowShape, portraitWindowShape, rearCameraFacing, rearCameraLens);
        DiagLog.log("[torch:overlay] 小窗联动闪光灯 | 开关=" + linkEnabled
                + " 夜间区间=" + night
                + " → " + (torchWithPreview ? "随小窗点亮" : "不点亮")
                + " | 生命周期=小窗显示即亮、收起即灭（预览会话内 FLASH_MODE_TORCH）");
        DiagLog.log("[overlay] doShow | mode=" + mode + " opacity=" + opacity
                + " corner=" + corner + " 文字描边=" + contrast + "(" + contrastLevel + "%)"
                + " 文字=" + (hudText == null ? "关" : hudText + "/" + hudSize + "sp")
                + " 小窗=" + smallWidth + "dp 边距=" + smallMargin + "dp"
                + " 取景=" + framing + "% 描边=" + border + "dp 低光增强=" + lowLight
                + " | 画面增强=" + screenEnhance + "(对比 " + screenContrast
                + "% 暗部 " + screenShadow + "% 饱和 " + screenSat + "%)"
                + " | 方向=" + rotationMode
                + " 手动[横窗=" + landscapeWindowShape + " 竖窗=" + portraitWindowShape
                + " 后摄=" + rearCameraFacing + "]"
                + " 镜头=" + rearCameraLens);
        // 【5.6.5】窗口刚挂上就按当前状态算一次步数徽章：不等下一次 gaitTick（最长 3 秒）
        updateStepBadge(System.currentTimeMillis());
        reportPreviewState();
    }

    /**
     * 系统当前是否处于深色模式。
     *
     * 直接读 Configuration，而不是去问 PowerManager / UiModeManager：
     * 配置里的 UI_MODE_NIGHT_YES 就是「系统现在是深色」这个事实本身，
     * 而且深色模式切换时本进程会收到 onConfigurationChanged（见下方覆写），
     * 判定与刷新走的是同一份数据，不会出现「日志说深色、界面还是白天」。
     */
    private boolean isSystemDarkMode() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【5.1.0 智能方案 / 5.3.0 横竖屏】配置变化时立刻跟上。
     *
     * 两件事共用这一个回调，但**必须各自独立判断**：
     *  · 屏幕方向变了 → 窗口要换尺寸、画面要重新算旋转。与「智能方案开不开」无关，
     *    方向错了就是看不了路。旧实现第一行就 `if (!presetAuto) return;`，
     *    于是关掉智能方案的用户把手机横过来，小窗画面永远侧躺。
     *  · 深色模式变了 → 只有在「跟随自动方案」开启时才重算白天/夜间那套参数。
     *
     * 没有这个覆写，日落时系统自动切深色，小窗还停在白天那套（无增强、完整取景），
     * 要么等到下次「走起来」才更新 —— 而人往往正在走，正是最需要看清的时候。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlay == null || !overlay.getShowing()) return;

        boolean rotated = lastOrientation != android.content.res.Configuration.ORIENTATION_UNDEFINED
                && newConfig.orientation != lastOrientation;
        lastOrientation = newConfig.orientation;
        if (rotated) {
            boolean landscape = newConfig.orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            DiagLog.log("[style] 屏幕方向变化 → " + (landscape ? "横屏" : "竖屏")
                    + "，重排窗口并重算画面方向");
            try {
                overlay.onDisplayChanged(landscape);
            } catch (Throwable t) {
                DiagLog.log("[style] 方向重排失败: " + t.getMessage());
            }
        }

        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.presetAuto, true)) return;
        DiagLog.log("[style] 系统配置变化（深色模式切换）→ 重算智能方案");
        doShow();
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

    /**
     * 只算不记日志的夜间判定。
     *
     * [isNightTime] 每调一次都会写一行 `[torch:time]`。夜间独行提醒跑在 1 秒一次的
     * 巡检里，直接复用它等于每秒刷一行日志——正是 4.5.0 修掉的日志洪泛。
     * 判定逻辑与它保持同一组配置项，只是把日志出口让给调用方按需节流。
     */
    private boolean nightHourHit() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int start = SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchStartHour, 22);
        int end = SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchEndHour, 6);
        return hour >= start || hour < end;
    }

    /**
     * 上一次判定时的累计合格步，用于识别「刚刚跨过门槛」这个瞬间。
     *
     * 真机日志（13:11:40~13:19:10，7.5 分钟）里 [GUARD] 打了 139 条——
     * 沙发场景累计步在 1~8 之间反复波动，每跨过一次 5 就打一次，5 秒节流
     * 也压不住。改为只在「从 <门槛 跳到 >=门槛」那一帧留痕，一轮一条。
     *
     * 诊断日志的时间戳节流已全部迁到 [DiagLog.logThrottled]（按 key 隔离，
     * 判定在 [DiagThrottle]），这里不再散落一堆 lastXxxDiagTs 字段。
     */
    private int lastGuardCountSnapshot = 0;

    /** 启动应用进程内的加速度计步态监听（自动模式用）。 */
    private void startGait() {
        if (gaitListener != null) return; // 已在监听
        if (sm == null) return;
        accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accSensor == null) {
            DiagLog.log("[walk] 本机无加速度计，自动步态不可用");
            return;
        }
        gait.reset();
        // 5.5.0：门槛下限从 2 抬到 8（用户要求「连续走 8 步为最低标准」），
        // 与 [GaitDetector.confirmSteps] 默认值、UI 滑条 8~10 档保持一致。
        gait.setConfirmSteps(Math.max(8, Math.min(10,
                SafePrefs.getInt(sp, SafeWalkPrefKey.gaitConfirmSteps, 8))));
        gaitWalking = false;
        lastStepTs = 0L;
        walkingStreakStartTs = 0L;
        lastGuardCountSnapshot = 0;
        accelStepTs.clear();
        lastAccelCountSeen = 0;
        accelFallbackLogged = false;
        // 新的一段监听：清掉节流状态，第一条诊断立刻可见
        DiagLog.resetThrottle("gait.hw");
        DiagLog.resetThrottle("gait.accel");
        DiagLog.resetThrottle("gait.active");
        DiagLog.resetThrottle("gait.guard");
        DiagLog.resetThrottle("gait.step");
        DiagLog.resetThrottle("gait.cooldown");
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
                // 【5.6.5】硬件计步通道不可用时，把加速度合格步当作今日步数的兜底来源；
                // 通道 LIVE 时不重复累加（硬件绝对值会对齐，重复只会把数字顶高）。
                if (stepTick && stepChannel() != StepHealthPolicy.Channel.LIVE) {
                    onStepObserved(1L, null);
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
                // 【5.6.0 位移证据】与波形正交的第二条证据：到底有没有在移动、是不是坐在车上。
                // 数据来自 PlaceArrivalMonitor 已有的 60s/150m 定位流，不新增任何唤醒。
                MotionContext.Verdict motion = MotionContext.verdictNow();
                boolean motionWalks = MotionContext.allowsFlatWalking(motion);
                boolean credible;
                if (stepTick && !gait.lastPeakWasFlat()) {
                    credible = true;                        // 主路径：采集模块已判合格步（且不是平放帧）
                } else if (stepTick) {
                    // 【5.6.0 平放修复】平放帧不再无条件可信。
                    // 5.5.1 这里恒 true，于是床上平放抖动只要凑满步数就弹窗；
                    // 而采集层的 LAY_FLAT 否决又在另一处把「兜里走路」一并杀掉。
                    // 现在改成「平放帧必须有一条独立证据」：
                    //   ① 硬件计步器真出步数（最强证据：真迈步才计数）；
                    //   ② 或定位证明你真的在移动（补上无计步权限机型的唯一出路）。
                    // 两条都没有 → 按未可信处理，不显示。
                    credible = hwSteps >= STEP_CONFIRM_MIN || motionWalks;
                } else if (!layFlat && stable) {
                    credible = true;                        // 解卡路径：非平躺 + 节律稳定
                } else if (layFlat && stable && hwSteps >= STEP_CONFIRM_MIN) {
                    // 水平持握 / 放包里走路也可能判 LAY_FLAT，但硬件计步器真出了步数，
                    // 这是「确实在走路」的硬证据，放行，避免误杀正常行走。
                    credible = true;
                } else if (layFlat && stable && motionWalks) {
                    // 【5.6.0】无计步器权限的机型（计步通道 NONE/DENIED/STALE，hwSteps 恒 0）
                    // 在这里得到唯一放行路径：平放 + 节律稳 + 定位显示真在移动。
                    credible = true;
                } else {
                    credible = false;                       // 平躺抖动 / 无节律乱动
                }
                // 车载硬否决：在驾驶/乘车时波形再像走路也不能弹。
                // 放在 credible 之后是为了让上面的分支保持「证据判断」的单一语义，
                // 由这一条统一收口。
                if (MotionContext.vetoesActivation(motion)) {
                    if (credible) {
                        DiagLog.logThrottled("gait.motion", "vehicle",
                                "[MOTION] ⛔ 判定在车上（" + MotionContext.describe(motion)
                                        + "）→ 抑制激活 | 累计=" + gait.confirmedStepCount()
                                        + "/" + gait.getConfirmSteps(),
                                3_000L, 30_000L);
                    }
                    credible = false;
                }
                boolean countReached = credible
                        && gait.confirmedStepCount() >= gait.getConfirmSteps();
                boolean qualified = stepTick || countReached;

                // ==== [SPAN] 最小连续跨度校验（5.5.0 加固：数量之外再看时长）====
                // 「连续 8 步」若发生在 3 秒内，说明是短促抖动而不是走路：
                // 真人走 8 步需要 4~10 秒。这里只拦「够快又够多」这一种特例，
                // 不改变 GaitDetector 内部的峰值/包络/步频/LAY_FLAT/不应期任何机制。
                if (qualified && !accelStreamLongEnough()) {
                    qualified = false;
                    DiagLog.logThrottled("gait.span", "short",
                            "[SPAN] ⛔ 最近 " + ACCEL_MIN_SPAN_STEPS + " 步跨度 < "
                                    + ACCEL_MIN_SPAN_MS + "ms，判为短促抖动，不刷新步流",
                            2_000L, 10_000L);
                }

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
                        && lastGuardCountSnapshot < guardNeed) {   // 本帧才刚跨过门槛
                    DiagLog.logThrottled("gait.guard", "att=" + attitude + ",stable=" + stable,
                            "[GUARD] ⛔ 累计步已达门槛但姿态/节律不可信，抑制激活 | 累计="
                                    + guardCnt + "/" + guardNeed
                                    + " 姿态=" + attitude + " 节律稳定=" + stable
                                    + " 硬件计步=" + hwSteps + "/" + (stepCallbackIntervalEma / 1000)
                                    + "s窗 通道=" + stepChannel().name()
                                    + " | 防床上/沙发平放抖动误触",
                            3_000L, 3_000L);
                }
                lastGuardCountSnapshot = guardCnt;

                // ==== [ACTIVE] 激活判定（每次判定都留痕，区分卡在哪道闸）====
                if (!gaitWalking && (stepTick || countReached)) {
                    boolean hw = hardwareStepConfirm();
                    boolean cool = shouldShowNow();
                    if (hw && cool) {
                        lastStepTs = now;
                        gaitWalking = true;
                        // 记录本次自动显示的时刻：StaticStopPolicy 用它做「显示驻留」，
                        // 保证误判弹出的小窗不会在 12 秒内一闪就消失
                        gaitShowTs = now;
                        DiagLog.log("[ACTIVE] ✅ 激活行走模式 | 触发源="
                                + (stepTick ? "feedAcc合格步" : "累计合格步达标(姿态/节律闸门已放行)")
                                + " 累计=" + gait.confirmedStepCount() + "/"
                                + gait.getConfirmSteps() + " 姿态=" + attitude
                                + " 硬件确认=过 冷却=过 → 显示预览");
                        evaluateShow();
                    } else {
                        DiagLog.logThrottled("gait.active",
                                "hw=" + hw + ",cool=" + cool + ",ch=" + stepChannel(),
                                "[ACTIVE] ⏸ 达标但被拦截：硬件计步确认=" + (hw ? "过" : "拦")
                                        + "(" + stepChannel().name() + ")"
                                        + " 收起冷却=" + (cool ? "过" : "拦")
                                        + " 累计=" + gait.confirmedStepCount() + "/"
                                        + gait.getConfirmSteps(),
                                2_000L, 2_000L);
                    }
                }

                // 节流诊断：每 10 秒打一次步数累计，走没走、卡在哪一眼可见。
                // （原为 2 秒一次，20 分钟刷了 504 行，日志被噪声淹没，真出问题时
                //   反而找不到有用信息；关键状态变化由 [ACTIVE]/[GUARD] 负责留痕。）
                // 「本帧合格步」几乎恒为 false（一个合格步只占一帧，日志大概率落在
                // 普通帧上），旧文案会让人误以为一步都没走成。改成最近 10 秒的出步数。
                DiagLog.logThrottled("gait.step", "tick",
                        "[STEP] 计步回调 | 连续合格步 " + gait.confirmedStepCount()
                                + "/" + gait.getConfirmSteps()
                                + " 最近10s出步=" + accelStepsInWindow(10_000L)
                                + " | " + gait.debugInfo()
                                + " | walking=" + gaitWalking,
                        10_000L, 10_000L);
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
        gaitShowTs = 0L;
        walkingStreakStartTs = 0L;
        lastGuardCountSnapshot = 0;
        accelStepTs.clear();
        lastAccelCountSeen = 0;
    }

    // ========== 硬件计步器（防误触确认） ==========

    /**
     * 注册系统硬件计步通道：真迈步才计数，拿手机乱动不计数。
     *
     * 【5.2.0 双通道】
     * 只注册 TYPE_STEP_COUNTER 是 5.1.0 那个「走了十几分钟一次没弹」的直接原因：
     * COUNTER 是**累计型**传感器，回调时机由 HAL 决定，小米/三星实测 **10~60 秒**
     * 才上报一次（省电策略会合并高频回调）。代码却用「最近 10 秒内步数」判定有没有
     * 在走路 —— 只要上报间隔超过 10 秒，这个窗口**恒为 0**，于是一路
     * `[gait] ⛔ 硬件计步器 10 秒 0 步 → 判未行走`，`return false` 之后再也
     * 走不到纯加速度兜底，形成死锁。
     *
     * 现在：
     *   · **TYPE_STEP_DETECTOR 为主**：事件型，每走一步回调一次，实时性够；
     *   · **TYPE_STEP_COUNTER 为备**：DETECTOR 不可用的机型（少数 ROM 裁掉了）兜底；
     *   · 两路都往 [stepEventQueue] 塞，共用同一套窗口与健康度判定。
     */
    private void registerStepCounter() {
        if (stepCounterListener != null || stepDetectorListener != null) return;
        if (sm == null) return;
        stepEventQueue.clear();
        stepCounterAlive = false;
        everSteppedTotal = 0L;
        lastTotalSteps = -1L;
        lastStepCallbackTs = 0L;
        stepCallbackIntervalEma = 0L;
        lastLoggedChannelOrdinal = -1;

        registerStepDetector();
        registerStepCounterSensor();
    }

    /**
     * 逐帧计步通道（每步一次回调）。
     *
     * 部分 ROM（含少数三星旧版本）为了省电会把这个传感器藏起来，
     * 拿不到时静默跳过，交给 COUNTER 与纯加速度兜底。
     */
    private void registerStepDetector() {
        stepDetectorSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        stepDetectorRegistered = false;
        if (stepDetectorSensor == null) {
            DiagLog.log("[gait] 本机无逐帧计步器(TYPE_STEP_DETECTOR)，仅用累计计步器");
            return;
        }
        stepDetectorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e == null || e.values == null || e.values.length < 1) return;
                long now = SystemClock.elapsedRealtime();
                markStepCallback(now);
                stepCounterAlive = true;
                everSteppedTotal++;
                stepEventQueue.addLast(now);
                trimStepQueue(now, StepHealthPolicy.MAX_WINDOW_MS);
                onStepObserved(1L, null);
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        try {
            stepDetectorRegistered = sm.registerListener(
                    stepDetectorListener, stepDetectorSensor, SensorManager.SENSOR_DELAY_UI);
            DiagLog.log("[gait] 逐帧计步器注册" + (stepDetectorRegistered ? "成功" : "被拒")
                    + " | " + stepDetectorSensor.getName());
        } catch (Throwable t) {
            stepDetectorRegistered = false;
            stepDetectorListener = null;
            DiagLog.log("[gait] 逐帧计步器注册抛异常: " + t.getMessage());
        }
    }

    /** 累计计步通道（HAL 决定上报时机，通常 10~60 秒一次） */
    private void registerStepCounterSensor() {
        stepCounterSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepCounterRegistered = false;
        if (stepCounterSensor == null) {
            DiagLog.log("[gait] 本机无累计计步器(TYPE_STEP_COUNTER)");
            return;
        }
        stepCounterListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e == null || e.values == null || e.values.length < 1) return;
                long now = SystemClock.elapsedRealtime();
                // TYPE_STEP_COUNTER 是累计传感器：values[0]=总步数，按增量计步
                long total = (long) e.values[0];
                if (lastTotalSteps < 0L) {
                    // 首次回调只记基线，不算一步；但今日步数的基线要在这里建立，
                    // 否则第一次走完还得等下一次回调才显示
                    lastTotalSteps = total;
                    onStepObserved(0L, total);
                    return;
                }
                if (total > lastTotalSteps) {
                    long delta = total - lastTotalSteps;
                    lastTotalSteps = total;
                    markStepCallback(now);
                    stepCounterAlive = true;
                    everSteppedTotal += delta;
                    for (long i = 0; i < delta; i++) {
                        stepEventQueue.addLast(now);
                    }
                    trimStepQueue(now, StepHealthPolicy.MAX_WINDOW_MS);
                    onStepObserved(delta, total);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        try {
            stepCounterRegistered = sm.registerListener(
                    stepCounterListener, stepCounterSensor, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable t) {
            stepCounterListener = null;
            stepCounterRegistered = false;
            DiagLog.log("[gait] 累计计步器注册失败: " + t.getMessage());
        }
        if (!stepCounterRegistered && !stepDetectorRegistered) {
            DiagLog.log("[gait] 两路计步器均不可用 → 纯加速度判定（建议到系统设置允许「身体活动」）");
        }
    }

    /**
     * 记录一次计步回调，并维护回调间隔的滑动平均。
     *
     * 间隔 EMA 是自适应窗口的输入：COUNTER 上报越稀疏，窗口就自动放得越宽，
     * 避免「窗口 10 秒、回调 30 秒」这种**窗口必然为空**的错配。
     */
    private void markStepCallback(long now) {
        if (lastStepCallbackTs > 0L && now > lastStepCallbackTs) {
            long d = now - lastStepCallbackTs;
            // 上限夹到 60 秒：息屏唤醒等异常间隔不该把平均值拉爆
            d = Math.min(d, StepHealthPolicy.MAX_WINDOW_MS);
            stepCallbackIntervalEma = (stepCallbackIntervalEma == 0L)
                    ? d : (stepCallbackIntervalEma * 7L + d * 3L) / 10L;
        }
        lastStepCallbackTs = now;
    }

    /** 丢掉窗口外的旧步，避免长期运行后队列无限增长 */
    private void trimStepQueue(long now, long windowMs) {
        while (!stepEventQueue.isEmpty() && now - stepEventQueue.peekFirst() > windowMs) {
            stepEventQueue.pollFirst();
        }
    }

    /**
     * 当前计步通道健康度。
     *
     * 详见 [StepHealthPolicy]：只有 LIVE 才有资格「否决」一次激活；
     * NONE / DENIED / STALE 一律降级到纯加速度判定，而不是把功能判死。
     */
    private StepHealthPolicy.Channel stepChannel() {
        boolean has = stepCounterSensor != null || stepDetectorSensor != null;
        boolean ok = stepCounterRegistered || stepDetectorRegistered;
        long age = (lastStepCallbackTs == 0L)
                ? Long.MAX_VALUE
                : Math.max(0L, SystemClock.elapsedRealtime() - lastStepCallbackTs);
        return StepHealthPolicy.channelOf(has, ok, everSteppedTotal > 0L, age);
    }

    /**
     * 通道降级时只说明一次，避免每帧刷屏。
     *
     * 用户看日志时最需要分清的是「我手机没有计步器」「权限没给」「被系统限流」
     * 这三种完全不同的处境 —— 它们以前都表现为同一句「10 秒 0 步」。
     */
    private void logChannelOnce(StepHealthPolicy.Channel channel) {
        if (lastLoggedChannelOrdinal == channel.ordinal()) return;
        lastLoggedChannelOrdinal = channel.ordinal();
        DiagLog.log("[gait] 计步通道降级 → " + channel.name()
                + " | " + StepHealthPolicy.describe(channel)
                + " | 已出步=" + everSteppedTotal);
    }

    private void unregisterStepCounter() {
        if (stepCounterListener != null) {
            try { sm.unregisterListener(stepCounterListener); } catch (Throwable ignored) {}
            stepCounterListener = null;
        }
        if (stepDetectorListener != null) {
            try { sm.unregisterListener(stepDetectorListener); } catch (Throwable ignored) {}
            stepDetectorListener = null;
        }
        stepCounterRegistered = false;
        stepDetectorRegistered = false;
        stepEventQueue.clear();
        stepCounterAlive = false;
        everSteppedTotal = 0L;
        lastTotalSteps = -1L;
        lastStepCallbackTs = 0L;
        stepCallbackIntervalEma = 0L;
        lastLoggedChannelOrdinal = -1;
    }

    /** 最近 [STEP_CONFIRM_WINDOW_MS] 内的硬件计步数（仅日志用，判定用自适应窗口） */
    private int stepCountInWindow() {
        return stepCountInWindow(STEP_CONFIRM_WINDOW_MS);
    }

    /**
     * 指定窗口内的硬件计步数（**只读**，不改动队列）。
     *
     * 【5.2.0 改成只读的原因】
     * 旧版这里一边数一边 `pollFirst()` 把窗口外的步丢掉。5.2.0 引入自适应窗口后，
     * 同一个队列会被不同窗口长度分别查询（诊断日志用 10s、判定用 10~60s），
     * 谁先调用就把队列裁到自己的长度 —— 10s 的那次会**永久删掉** 10~60s 区间的步，
     * 让随后按 60s 判定的那次数不到数据，等于把自适应窗口废掉。
     *
     * 裁剪只在回调里做（[trimStepQueue] 按 [StepHealthPolicy.MAX_WINDOW_MS]），
     * 那是唯一需要控制内存的地方；查询一律不改状态。
     */
    private int stepCountInWindow(long windowMs) {
        long now = SystemClock.elapsedRealtime();
        int n = 0;
        // 队列按时间升序（addLast 单调递增），从尾部往前数到出窗为止最省
        java.util.Iterator<Long> it = stepEventQueue.descendingIterator();
        while (it.hasNext()) {
            long ts = it.next();
            if (now - ts > windowMs) break;
            n++;
        }
        return n;
    }

    // ==================== 【5.6.5】今日步数 ====================

    /**
     * 当天 key（本地时区 yyyyMMdd）：跨天即重置今日步数。
     *
     * 【5.6.7 功耗】成果缓存 [DAY_KEY_CACHE_MS]：30 秒内复用同一个字符串，
     * 避免每一步都建 Calendar 再格式化。跨天最多晚 30 秒识别，可接受。
     */
    private String stepDayKeyOf(long ms) {
        long dt = ms - stepDayKeyCacheTs;
        if (dt >= 0L && dt < DAY_KEY_CACHE_MS && !stepDayKeyCache.isEmpty()) {
            return stepDayKeyCache;
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        String key = String.format(java.util.Locale.US, "%04d%02d%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
        stepDayKeyCache = key;
        stepDayKeyCacheTs = ms;
        return key;
    }

    /** 从 SP 恢复今日步数；跨天的旧记录直接作废（基线清空，重新建立） */
    private void loadStepState() {
        try {
            stepDayKey = SafePrefs.getString(sp, SP_KEY_STEP_DAY, "");
            stepBaseTotal = sp.getLong(SP_KEY_STEP_BASE, TodayStepPolicy.NO_BASE);
            stepAccelToday = sp.getLong(SP_KEY_STEP_ACCEL, 0L);
        } catch (Throwable t) {
            stepDayKey = "";
            stepBaseTotal = TodayStepPolicy.NO_BASE;
            stepAccelToday = 0L;
        }
        long now = System.currentTimeMillis();
        String today = stepDayKeyOf(now);
        if (TodayStepPolicy.shouldReset(stepDayKey, today)) {
            stepDayKey = today;
            stepBaseTotal = TodayStepPolicy.NO_BASE;
            stepAccelToday = 0L;
            flushStepState();
        }
        recomputeTodaySteps();
    }

    /** 把「当天基线 + 加速度累计」落盘（节流由调用方控制） */
    private void flushStepState() {
        if (sp == null) return;
        try {
            sp.edit()
                    .putString(SP_KEY_STEP_DAY, stepDayKey)
                    .putLong(SP_KEY_STEP_BASE, stepBaseTotal)
                    .putLong(SP_KEY_STEP_ACCEL, stepAccelToday)
                    .apply();
            lastStepFlushTs = System.currentTimeMillis();
        } catch (Throwable t) {
            DiagLog.logThrottled("step.state", "save", "[step] 今日步数落盘失败: " + t.getMessage(),
                    5_000L, 60_000L);
        }
    }

    private void recomputeTodaySteps() {
        // stepHwTotalLatest < 0 时原样交给策略层（它把负数按「拿不到」处理）
        todayStepsCache = TodayStepPolicy.today(
                stepHwTotalLatest, stepBaseTotal, stepAccelToday);
    }

    /**
     * 记录一次「步」并更新今日总量。
     *
     * @param delta  本次新增步数（DETECTOR 恒为 1；COUNTER 为增量，可为 0）
     * @param hwTotal STEP_COUNTER 的绝对值；null 表示这次不是硬件累计通道报的
     */
    private void onStepObserved(long delta, Long hwTotal) {
        long now = System.currentTimeMillis();
        String today = stepDayKeyOf(now);
        if (TodayStepPolicy.shouldReset(stepDayKey, today)) {
            stepDayKey = today;
            stepBaseTotal = TodayStepPolicy.NO_BASE;
            stepAccelToday = 0L;
        }
        if (hwTotal != null) {
            stepHwTotalLatest = hwTotal;
            // 基线未建立 / 设备重启导致回落：以当前值为新起点
            if (stepBaseTotal == TodayStepPolicy.NO_BASE || hwTotal < stepBaseTotal) {
                stepBaseTotal = TodayStepPolicy.freshBase(hwTotal);
            }
        } else if (delta > 0L) {
            stepAccelToday += delta;
        }
        if (delta > 0L) lastMotionStepTs = now;
        recomputeTodaySteps();
        if (now - lastStepFlushTs > STEP_STATE_FLUSH_MS) flushStepState();
    }

    /**
     * 把今日步数画到 HUD 下方的小框里；静止超过 [STEP_BADGE_HIDE_MS] 就藏起来。
     * 只在预览正在显示时下发，不显示时什么也不做（避免无意义的 View 调用）。
     */
    private void updateStepBadge(long now) {
        if (overlay == null || !overlay.getShowing()) return;
        // 【5.6.5 步数奖励】描边颜色 / 小红旗跟今日步数全天绑定：
        // 这里每 tick 下发一次绝对值，由悬浮窗自己按档位换色（同值早退，零开销）。
        overlay.setTodaySteps(todayStepsCache);
        boolean fresh = lastMotionStepTs > 0L && now - lastMotionStepTs <= STEP_BADGE_HIDE_MS;
        if (!fresh || todayStepsCache <= 0L) {
            overlay.setStepText(null);
            return;
        }
        // 【5.6.8 功耗】步数没变就复用上次的字符串；跨天 / 走新一步才重建。
        if (stepBadgeTextCache == null || stepBadgeStepsBuilt != todayStepsCache) {
            stepBadgeTextCache = "今日 " + TodayStepPolicy.format(todayStepsCache) + " 步";
            stepBadgeStepsBuilt = todayStepsCache;
        }
        overlay.setStepText(stepBadgeTextCache);
    }

    /** 当前连续步流已持续的毫秒数；未起步返回 0 */
    private long streakMs() {
        return (walkingStreakStartTs == 0L)
                ? 0L : (System.currentTimeMillis() - walkingStreakStartTs);
    }

    /**
     * 硬件计步确认（自适应，既不误杀走路、也不放过床上/沙发误触）：
     *
     * 【5.2.0 引入健康度，修的是死锁】
     * 5.1.0 只看两个布尔量：`stepCounterAlive` 与 `everSteppedTotal`。二者成立之后，
     * 「最近 10 秒 0 步」就直接 `return false` —— 看似合理，实则把**稀疏上报**
     * 误判成**没在走路**。真机日志（2026-11-14，Redmi / HyperOS 4）18 分钟内
     * 这条分支命中 27 次，`[ACTIVE] ✅ 激活` **0 次**，自动跟随全程失效。
     *
     * 现在先问 [stepChannel]：
     * | 健康度 | 处理 |
     * |---|---|
     * | NONE / DENIED | 纯加速度严格判定（[accelOnlyConfirm]） |
     * | STALE（回调已停 >45s） | 同样走纯加速度 —— 信号不可用 ≠ 你没走 |
     * | LIVE | 用**自适应窗口**（按实际回调间隔放大）数步，再判 |
     *
     * 只有 LIVE 才有否决权：这是 [StepHealthPolicy.canVeto] 的唯一用途。
     */
    private boolean hardwareStepConfirm() {
        StepHealthPolicy.Channel channel = stepChannel();

        // 非 LIVE 一律降级：把「信号不可用」当成「没走路」正是 5.1.0 的死锁来源
        if (channel != StepHealthPolicy.Channel.LIVE) {
            logChannelOnce(channel);
            return accelOnlyConfirm();
        }

        // 自适应窗口：COUNTER 上报越稀疏，窗口越宽，避免「10 秒窗口 / 30 秒回调」错配
        long window = StepHealthPolicy.windowMs(stepCallbackIntervalEma);
        int n = stepCountInWindow(window);
        long streak = streakMs();
        if (n >= STEP_CONFIRM_MIN) {
            // 【5.6.4 瞬时动作闸门】数量够了不等于人在走：晃手机、举起来拍照、
            // 情侣举着手机找角度，重心往复都可能被 STEP_DETECTOR 数成 8~10 步。
            // 硬件计数只能算「数量证据」，再加一条「时间证据」：
            // 连续步流已持续 ≥ [LIVE_CONFIRM_STREAK_MS]，证明这是一段走动而不是一个动作。
            if (streak >= LIVE_CONFIRM_STREAK_MS) return true;
            DiagLog.logThrottled("gait.hw", "brief",
                    "[gait] ⛔ 计步 " + n + " 步达标但连续时长 " + (streak / 1000)
                            + "s < " + (LIVE_CONFIRM_STREAK_MS / 1000)
                            + "s → 判为瞬时动作（晃手机/拍照），不显示 | 垂直占比="
                            + String.format(java.util.Locale.US, "%.2f", gait.verticalRatio())
                            + " 姿态=" + gait.getAttitude(),
                    2_000L, 10_000L);
            return false;
        }

        // 【沙发误触修复 v2 · 真机日志 13:08:15 / 13:08:52 / 13:09:56】
        // 通道 LIVE（刚刚还在正常上报）却在这个窗口里一步未出 —— 这才是「没在走路」
        // 的硬证据。旧版此时仍允许「累计合格步 ≥ 门槛×2」放行，等于给严格校验开后门，
        // 沙发上有节奏地晃手机就能弹窗，必须堵掉。
        if (n == 0) {
            DiagLog.logThrottled("gait.hw", "zero",
                    "[gait] ⛔ 计步通道在线但 " + (window / 1000) + " 秒 0 步"
                            + "（真走路不可能不出数）→ 判未行走，抑制激活 | 纯加速度累计="
                            + gait.confirmedStepCount()
                            + " 回调间隔≈" + (stepCallbackIntervalEma / 1000) + "s",
                    2_000L, 10_000L);
            return false;
        }

        // 0 < n < 门槛：计步器刚起步或轻微漏计。兜底保留，但必须同时满足
        // 「节律稳定」+「连续步流够久」，不能让纯加速度计数单独决定放行。
        //
        // 【5.5.0】「步数」这一项从 `确认步数×2` 改成 `min(窗口上限, 确认步数×2)`：
        // 确认步数抬到 8 后 `8×2=16` 超过采集层队列上限 10（peakIntervals 恒定 ≤10），
        // 原式会变成**永不成立的死条件**，兜底整条失效 —— 这正是 5.1.0 那次
        // 「自动跟随全程一次没弹」的同款事故。用一个可达上限把它夹住，
        // 默认档（8）等价于「窗口满格 10 步」，与抬门槛前的放行强度完全一致。
        int sustainedSteps = Math.min(WalkConfirmPolicy.WINDOW_CAP,
                gait.getConfirmSteps() * 2);
        if (gait.confirmedStepCount() >= sustainedSteps
                && gait.hasStableGait() && streak >= ACCEL_MIN_STREAK_MS) {
            // 状态跳变（放行是一次性决定），但同样按帧可达，仍走节流出口
            DiagLog.logThrottled("gait.hw", "pass",
                    "[gait] 计步 " + n + "<" + STEP_CONFIRM_MIN
                            + " 但持续判走（累计 " + gait.confirmedStepCount()
                            + " 步 / 连续 " + (streak / 1000) + "s），放行显示",
                    2_000L, 10_000L);
            return true;
        }
        DiagLog.logThrottled("gait.hw", "low-" + n,
                "[gait] 加速度像走但硬件计步 " + n + "/" + STEP_CONFIRM_MIN
                        + "（10s内）/连续 " + (streak / 1000) + "s → 疑似拿手机乱动，不显示",
                2_000L, 10_000L);
        return false;
    }

    /**
     * 【沙发/床上误触修复 v3】计步器不可用时（无传感器 / 无 ACTIVITY_RECOGNITION /
     * 被后台掐断 / 从未出数）的纯加速度确认。
     *
     * 判定交给 [WalkConfirmPolicy]，看的是**步流形态**而不是「累计多少步」：
     *   ① 非平躺（LAY_FLAT 直接拦）；
     *   ② 连续合格步 ≥ 8~10（受采集层 10 步窗口约束，可达）；
     *   ③ 相邻步最大间隔 ≤ 1.4s、平均步间隔 380~950ms、
     *      整段跨度随门槛推导（8 步 3.15s / 10 步 4.05s），
     *      相邻间隔离散度受控 —— 真人走路是连续步流，沙发抖动是间歇的。
     *
     * v2 的 `≥15 步` 是死条件（窗口上限 10），已由真机日志证实会让自动跟随彻底失效。
     */
    private boolean accelOnlyConfirm() {
        if (accelPolicy == null) {
            // 5.5.0：下限 8（原为 2），与硬件计步门槛、UI 滑条保持一致
            int pref = Math.max(8, Math.min(10,
                    SafePrefs.getInt(sp, SafeWalkPrefKey.gaitConfirmSteps, 8)));
            accelPolicy = new WalkConfirmPolicy(WalkConfirmPolicy.requiredStepsFor(pref));
        }
        if (!accelFallbackLogged) {
            // 只在每段监听里说一次：用户反馈「自动跟随不灵」时，先看这行就能分清
            // 是「本机没有计步器」还是「权限被拒/被后台掐断」，不用再猜。
            accelFallbackLogged = true;
            StepHealthPolicy.Channel ch = stepChannel();
            DiagLog.log("[gait] 本机走纯加速度判定 | 计步传感器="
                    + (stepCounterSensor != null || stepDetectorSensor != null ? "有" : "无")
                    + " 已出步=" + everSteppedTotal
                    + " 通道=" + ch.name()
                    + " | " + StepHealthPolicy.describe(ch));
        }
        // 【5.6.0 位移证据】纯加速度路径同样先看环境结论：
        //   · 在车上 → 直接否（波形再像走路也不算）；
        //   · 平放 + 定位证明在移动 → 不再被「姿态平放」这一票否决。
        MotionContext.Verdict mv = MotionContext.verdictNow();
        if (MotionContext.vetoesActivation(mv)) {
            DiagLog.logThrottled("gait.motion", "vehicle-accel",
                    "[MOTION] ⛔ 判定在车上（" + MotionContext.describe(mv)
                            + "）→ 纯加速度判定直接不出手",
                    3_000L, 30_000L);
            return false;
        }
        boolean flatBlocked = gait.getAttitude() == PhoneAttitude.LAY_FLAT
                && !MotionContext.allowsFlatWalking(mv);
        WalkConfirmPolicy.Verdict v = accelPolicy.evaluate(
                flatBlocked,
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
        // 节流判定统一交给 DiagLog/DiagThrottle：原因变化时 2s 可重发，
        // 原因不变时 10s 一条。业务类不再自己持有 lastXxxDiagTs 时间戳。
        DiagLog.logThrottled("gait.accel", v.reason,
                "[gait] " + (v.pass ? "✅ 纯加速度判走放行" : "⛔ 纯加速度暂不放行")
                        + " | 原因=" + v.reason
                        + " 连续步=" + v.steps + "/" + accelPolicy.requiredSteps()
                        + " 跨度=" + v.spanMs + "ms 最大间隔=" + v.maxGapMs + "ms 均隔=" + v.avgIntervalMs + "ms"
                        + " | 本机无硬件计步器，靠步流形态判走（间歇抖动过不了）",
                2_000L, 10_000L);
    }

    /**
     * 最近 [ACCEL_MIN_SPAN_STEPS] 个合格步的时间跨度是否已经够长（≥[ACCEL_MIN_SPAN_MS]）。
     *
     * 语义刻意做成「没有足够样本时返回 true」：本方法是一道**否决闸**，
     * 只在证据充分（已经攒到 8 个时间戳）时才允许否决。
     * 这样真走路刚起步、或采集层漏了几个峰导致时间戳不足 8 个时，
     * 不会被误判成「抖动」而让自动跟随失效 —— 宁可少拦一次，不可误杀走路。
     */
    private boolean accelStreamLongEnough() {
        if (accelStepTs.size() < ACCEL_MIN_SPAN_STEPS) return true;
        java.util.Iterator<Long> it = accelStepTs.descendingIterator();
        long newest = 0L;
        long oldest = 0L;
        int n = 0;
        while (it.hasNext() && n < ACCEL_MIN_SPAN_STEPS) {
            long ts = it.next();
            if (n == 0) newest = ts;
            oldest = ts;
            n++;
        }
        return newest - oldest >= ACCEL_MIN_SPAN_MS;
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
            // 【5.6.8 功耗】读缓存而不是每次 IPC：缓存初值在 onCreate 取一次，
            // SCREEN_ON/OFF 广播会同步更新，不存在读旧值的窗口。
            if (!screenInteractive) return false;
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
     * **不立即显示**，必须达到「持续判走」才放行。
     * 真走路停下后再走，几秒内能攒满「持续判走」门槛，照常显示；手持乱动很难连续攒满，被压住。
     *
     * 【5.5.0】「持续判走」从 `确认步数×2` 改为 `min(窗口上限, 确认步数×2)`：
     * 确认步数抬到 8 后 `8×2=16` 超出采集层队列上限 10，原式恒不成立 ——
     * 冷却期内重新走起来就再也放不出来（用户观感是「走一段停一下之后就再也不弹了」）。
     * 夹到上限后默认档等于「窗口满格 10 步」，与抬门槛前的行为逐位一致。
     */
    private boolean shouldShowNow() {
        long now = System.currentTimeMillis();
        if (lastStopTs == 0L || now - lastStopTs >= STOP_COOLDOWN_MS) return true;
        // 本方法在判定帧上会被高频调用，日志必须节流，否则冷却期内同样会刷屏
        int sustainedSteps = Math.min(WalkConfirmPolicy.WINDOW_CAP,
                gait.getConfirmSteps() * 2);
        if (gait.confirmedStepCount() >= sustainedSteps) {
            DiagLog.logThrottled("gait.cooldown", "pass",
                    "[gait] 判定停下冷却内持续判走（累计 "
                            + gait.confirmedStepCount() + " 步），放行显示",
                    5_000L, 5_000L);
            return true;
        }
        DiagLog.logThrottled("gait.cooldown", "wait",
                "[gait] 判定停下冷却期内（累计 " + gait.confirmedStepCount()
                        + " 步），暂不显示，等待持续判走",
                5_000L, 5_000L);
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
        // 【5.6.8】小窗显示/收起时同步刷新常驻通知副标题：
        // 旧实现通知文案在服务启动时写死，收起后仍宣称「持续显示中」。
        refreshWalkNotification();
    }

    /** 常驻通知副标题（三态，纯逻辑见 [WalkNotifText]） */
    private String walkNotifSubtitle() {
        boolean showing = overlay != null && overlay.getShowing();
        boolean auto = SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false);
        return WalkNotifText.subtitle(showing, auto);
    }

    /** 上次写进常驻通知的副标题；同文案不重发（reportPreviewState 高频调用） */
    private String lastWalkNotifText = null;

    /** 【通知防误拦】常驻通知存在性校验的最小间隔与上次校验时刻（uptimeMillis） */
    private static final long NOTIF_GUARD_INTERVAL_MS = 60_000L;
    private long lastNotifGuardMs = 0L;

    /**
     * 刷新常驻通知副标题。只在「前台已提起 + 文案真的变了」时 notify 一次；
     * 失败不抛（通知被 ROM 限流/渠道被关都属正常，不影响服务本体）。
     */
    private void refreshWalkNotification() {
        if (!foregroundEstablished) return;
        try {
            String text = walkNotifSubtitle();
            if (text.equals(lastWalkNotifText)) return;
            lastWalkNotifText = text;
            PendingIntent openPi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_WALK)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("行走模式运行中")
                    .setContentText(text)
                    .setContentIntent(openPi)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .build();
            nmBase().notify(NOTIF_ID, n);
        } catch (Throwable ignored) {
        }
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
            guardWalkNotification(SystemClock.elapsedRealtime());
            main.postDelayed(this, KeepAlivePolicy.HEARTBEAT_INTERVAL_MS);
        }
    };

    /**
     * 【通知防误拦 · 兜底】校验本应用常驻通知是否还在，被第三方模块撤掉就重发一次。
     *
     * 为什么需要：用户装了「营销通知拦截器」这类 LSPosed 模块并勾选了系统界面，
     * 它按自己的规则在 SystemUI 侧撤通知，正常的前台服务通知也可能被误伤。
     * category 声明只能降低误判概率，真正「一定不被拦」只能靠自己发现丢了再补上。
     *
     * 只查自己的通知（getActiveNotifications 读的是本应用发布的活动通知，无需任何权限），
     * 60 秒一次，代价可忽略；若拦截器是「展示层过滤」（通知对象仍在列表里）则不会触发，
     * 也就不会和它来回打架。
     */
    private void guardWalkNotification(long nowRealtime) {
        if (!foregroundEstablished) return;
        if (nowRealtime - lastNotifGuardMs < NOTIF_GUARD_INTERVAL_MS) return;
        lastNotifGuardMs = nowRealtime;
        try {
            boolean present = false;
            for (android.service.notification.StatusBarNotification sbn
                    : nmBase().getActiveNotifications()) {
                if (sbn.getId() == NOTIF_ID) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                // 置空缓存让 refreshWalkNotification 的「文案没变就早退」失效，强制重发
                lastWalkNotifText = null;
                refreshWalkNotification();
                DiagLog.log("[notif] 常驻通知被外部清除/拦截 → 已重发（疑似营销拦截器误伤）");
            }
        } catch (Throwable ignored) {
            // ROM 限制 / 服务已退出：什么都不做，下一次心跳再看
        }
    }

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
        // 【省电专项】已提起则跳过：避免设置页频繁操作时同一秒重复 startForeground。
        // 完全失败(fgsType==-2)不跳过，保留原有降级重试机会。
        if (foregroundEstablished && fgsType != -2) return;
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
                // 【5.6.8】副标题按三态生成（显示中 / 步态检测等待 / 手动常显），
                // 不再写死「持续显示中」——自动跟随用户看到那句话会以为小窗一直挂着。
                .setContentText(walkNotifSubtitle())
                .setContentIntent(openPi)
                // 【通知防误拦】显式声明 SERVICE 类别：第三方「营销通知拦截器」类模块
                // 普遍按 CATEGORY_PROMOTIONS / 无类别低优先级启发式过滤，前台服务
                // 通知带 SERVICE + ongoing 会被其规则跳过，不再被当成营销通知撤掉。
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

        final String osTag = fgsOsTag();
        if (Build.VERSION.SDK_INT >= 29) {
            for (int type : FGS_TYPE_CHAIN) {
                try {
                    startForeground(NOTIF_ID, n, type);
                    fgsType = type;
                    foregroundEstablished = true;
                    lastWalkNotifText = walkNotifSubtitle();
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
            foregroundEstablished = true;
            lastWalkNotifText = walkNotifSubtitle();
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

    /**
     * 【省电专项】前台服务是否已在本进程成功提起。
     *
     * 每次 send(ACTION_SHOW) 都会走 onStartCommand → goForeground，用户在设置页
     * 来回点镜头/参数时同一秒能连提 4~14 次。startForeground 成功后进程即处于
     * 前台，"再提一次"除了重建通知渠道、跑一遍降级链、刷日志之外没有任何作用。
     * 这里记住已提起的事实，重复调用直接跳过；只有 ACTION_STOP 里 stopForeground
     * 之后才清零，允许下次启动重新提起。
     */
    private boolean foregroundEstablished = false;

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
        flushStepState();
        // 【5.6.0】日志改成批量落盘后，退出前必须把缓冲刷下去，否则最后几条（
        // 往往正是「为什么被拦/为什么收起」的原因）会留在内存里随进程消失。
        DiagLog.flushNow();
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
