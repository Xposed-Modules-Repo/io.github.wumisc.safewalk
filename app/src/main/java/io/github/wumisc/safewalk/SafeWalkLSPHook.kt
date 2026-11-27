package io.github.wumisc.safewalk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.content.BroadcastReceiver
import android.widget.Toast
import io.github.wumisc.safewalk.torch.TorchHelper
import io.github.wumisc.safewalk.torch.TorchNotificationManager
import io.github.wumisc.safewalk.torch.TorchStaticReminder
import io.github.wumisc.safewalk.torch.createTorchNotifyChannel

/**
 * SafeWalk LSPosed 状态机 + 步态判定。
 * 仅 Hook 系统界面（com.android.systemui）。
 *
 * 状态机：SLEEP → LIGHT_MONITOR（有步数）→ 步数达标即触发提醒 → SLEEP
 * （2.9.4 去掉了中间的 VERIFYING 关卡：它要求用户走出教科书级别的稳定步频，
 *   真机上几乎通不过，结果就是「走了半天不提醒」）
 */
class SafeWalkLSPHook : SensorEventListener {

    private enum class State { SLEEP, LIGHT_MONITOR }

    // ========== 运行时状态 ==========
    private var state = State.SLEEP
    /** 当前是否处于「行走中」：由步数与静置超时共同决定，供悬浮窗与自动模式使用 */
    private var walking = false
    /** 手动预览截止时间戳：期间 updateOverlay 不干预，便于调参时实时看效果 */
    private var previewUntil = 0L
    /**
     * 手电占用摄像头的截止时间戳：期间 updateOverlay 不自动拉起预览。
     *
     * 开灯后如果行走逻辑立刻把预览拉起来，预览 openCamera 会撞上被手电占用的
     * 摄像头（CAMERA_IN_USE），既看不到画面，又可能把刚点亮的手电顶掉。
     * 这个字段让「手电优先」持续一小段时间，关灯或超时后预览自然恢复。
     */
    private var torchHoldUntil = 0L

    /**
     * 【BUG 修复】夜间手电「步态联动」通道当前是否点亮了灯。
     *
     * 这个状态位很关键：check() 每 2 秒跑一次，如果每次都无脑调
     * setTorchWithRetry(true) 去点灯，就会反复和相机预览抢摄像头、
     * 把日志刷爆、把闪光灯点成频闪。只在「目标状态发生翻转」的
     * 那一刻各执行一次开/关，既省电又稳定。
     */
    private var nightTorchGaitOn = false
    /** 步态联动心跳日志节流时间戳：状态没变时也周期性证明「通道活着」 */
    private var lastNightTorchGaitLogTs = 0L

    /**
     * 配置快照。**只在后台线程整体替换，主线程只读引用**，所以标 volatile 就够安全
     * （没有人原地修改这个 JSONObject，永远是新对象换旧对象）。
     */
    @Volatile
    private var configJson: org.json.JSONObject? = null
    private var sm: SensorManager? = null
    private var ctx: Context? = null
    private var torchNotifyManager: TorchNotificationManager? = null
    private var torchReminder: TorchStaticReminder? = null
    private var torchActionReceiver: BroadcastReceiver? = null
    /** 电量变化接收器：独立于 receiverRefs，热重载时也要显式反注册 */
    private var batteryReceiver: BroadcastReceiver? = null
    private var crashDetector: CrashDetector? = null

    // ===== 环境光（lux）监测：夜间自动开灯用，暗光才亮、亮着不亮 =====
    private var luxListener: SensorEventListener? = null
    /** 最近一次环境光照度(lux)；Float.MAX_VALUE 表示还没读到 */
    @Volatile
    private var lastLux = Float.MAX_VALUE
    private var lastLuxTs = 0L
    /** 低于此值(lux)判定为「太黑看不清」：月夜 0.1~1、路灯下 5~20、傍晚室内 20+ */
    private val luxDarkThreshold = 15f

    // ========== 时间戳（冷却用） ==========
    private val steps = mutableListOf<Long>()
    private var lastStepTs = 0L
    private var lastSitRemindTs = 0L
    private var lastTriggerTs = 0L
    private var lastBatteryWarnTs = 0L
    private var batteryReceiverRegistered = false
    /**
     * 初始化状态机。
     *
     * 原来是 `inited = true` 写在 try **之前**：只要后面任何一步抛异常
     * （DiagLog.init / getSystemService / registerReceiver 在部分 ROM 上都会抛），
     * 这个 SystemUI 进程就永久停在「已初始化」的假状态——不重试、不再注册任何监听的，
     * 模块静默失效，直到下一次重启 SystemUI 才可能恢复。
     *
     * 现在拆成「正在跑 / 已成功」两个标志：失败可以退避重试，只有全部核心步骤
     * 走完才封口。
     */
    private val initLock = Any()

    @Volatile
    private var initRunning = false

    @Volatile
    private var initSucceeded = false

    /** 核心步骤（拿 Context / 初始化日志）连续失败次数，用于退避 */
    private var initAttempts = 0

    /** 每个接收器的注册只做一次：init 重试时重复注册 = 一条提醒弹两次 */
    private val registeredReceivers = mutableSetOf<String>()

    /** 【5.7.3 热重载】name → 已注册的动态接收器，重载时逐个反注册，不留下旧一代 */
    private val receiverRefs = mutableMapOf<String, BroadcastReceiver>()

    /** tick 链是否已启动：init 重试可能走两遍，不能挂两条心跳 */
    private var tickStarted = false

    /**
     * 【API 102 规则】旧一代已被请求退役。
     *
     * 热重载后旧代码仍可能在「已经在跑」的线程 / 已排队的 Handler 消息里继续执行：
     * Context 兜底线程最多轮询 20 秒，tick 消息也还挂在主线程队列上。它们若继续跑，
     * 会把旧一代的 Context 重新塞回静态字段，等于「释放后又复活」。
     * 所有入口看到这个标记都必须立刻退出。
     */
    @Volatile
    private var disposed = false

    // 配置缓存时间戳：避免每 2 秒跨进程查一次 Provider 把应用进程反复唤醒（耗电）
    @Volatile
    private var lastConfigTs = 0L

    /** 是否已有一次配置读取在后台飞：避免 tick 每 2 秒排一个任务 */
    @Volatile
    private var configLoading = false

    /**
     * 读取期间又来了强制刷新请求。
     *
     * 典型场景：用户连点两个开关，第二次的 ACTION_CONFIG_CHANGED 撞上第一次还没读完的
     * 任务。直接丢掉的话新配置要等 60 秒轮询才生效——"改完开关半天不响应"。
     * 标记一下，读完立刻再读一次。
     */
    @Volatile
    private var configReloadPending = false

    /**
     * 配置读取专用单线程。
     *
     * `contentResolver.query` 是同步 Binder 调用，对面是 App 的 ConfigProvider；
     * App 进程已被回收时，这一次 query 还会把它冷启动起来，耗时几百毫秒到数秒。
     * 原来它跑在主线程的 tick 里——等于每 60 秒让 SystemUI 主线程赌一次「会不会卡住」，
     * 卡住就是状态栏掉帧甚至 ANR。现在全部挪到这个守护线程，主线程只读内存快照。
     */
    /**
     * 配置读取专用单线程。热重载释放后会 shutdown，新一代用到时再重建（见 [ensureConfigExecutor]）。
     */
    private var configExecutor: java.util.concurrent.ExecutorService? = null

    private fun ensureConfigExecutor(): java.util.concurrent.ExecutorService {
        val ex = configExecutor
        if (ex == null || ex.isShutdown) {
            val fresh = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "SafeWalk-config").apply { isDaemon = true }
            }
            configExecutor = fresh
            return fresh
        }
        return ex
    }

    // ========== 预览服务看门狗（保活） ==========
    /**
     * App 侧每 30 秒发一次心跳，这里记录「上次收到」的单调时间戳。
     * 0 表示本 SystemUI 进程从未收到（刚开机，或心跳通道不通）。
     */
    private var lastHeartbeatAt = 0L
    /** Hook init 完成时刻（elapsedRealtime）：之前的宽限期不复活 */
    private var hookReadyAt = 0L
    /** 上次成功拉起透明页的时刻（elapsedRealtime），0 = 从未 */
    private var lastReviveAt = 0L
    /** 本 SystemUI 进程已发起的复活次数（含开机首拉） */
    private var reviveCount = 0
    /** 上一拍「配置是否期望预览在跑」：null=本进程还没判过。用于识别用户刚拨开开关 */
    private var lastWatchdogDesired: Boolean? = null
    /** 上次尝试拉起的时刻：失败后按 60 秒冷却重试（elapsedRealtime） */
    private var lastAutoStartTry = 0L
    /** 心跳日志节流（wall clock 即可，仅用于打印） */
    private var lastHeartbeatLogTs = 0L

    /**
     * App → SystemUI 的心跳接收器。
     *
     * 注册时挂了 signature 级 CONTROL 权限，第三方广播在分发阶段就被丢掉；
     * 令牌是第二道校验。本接收器只更新一个时间戳，不做任何耗时操作 ——
     * 它跑在 SystemUI 主线程上，每 30 秒一次，绝不能碰 I/O。
     */
    private val heartbeatReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) return
            lastHeartbeatAt = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            // App 有 ACTIVITY_RECOGNITION，SystemUI 没有；智能提醒的 50 步窗口
            // 不能只靠本进程的加速度计（锁屏/口袋会断流）。心跳带累计步数时
            // 直接用它补窗口，没带才退回本地传感器。
            val appStepTotal = intent.getLongExtra(KeepAlivePolicy.EXTRA_STEP_TOTAL, -1L)
            if (appStepTotal >= 0L) {
                feedAppStepTotal(
                    total = appStepTotal,
                    session = intent.getLongExtra(KeepAlivePolicy.EXTRA_STEP_SESSION, 0L),
                    wall = wall,
                    elapsed = SystemClock.elapsedRealtime()
                )
            }
            if (wall - lastHeartbeatLogTs > 120_000L) {
                lastHeartbeatLogTs = wall
                diag("[keep] 收到预览服务心跳（本进程已复活 $reviveCount 次）")
            }
        }
    }

    /**
     * 接收 App 侧硬件计步的会话累计值。
     *
     * [total] 是「本次服务实例注册计步器以来」的累计步数，不是开机累计值；
     * [session] 变一次只重建基线，不把上一会话的步数补进来，避免服务重启后
     * 一次性灌满 50 步窗口。供数在线期间 [recordStep] 会忽略本地传感器事件，
     * 防止同一物理步被两条通道各记一次。
     */
    private fun feedAppStepTotal(total: Long, session: Long, wall: Long, elapsed: Long) {
        if (session != lastAppStepSession) {
            lastAppStepSession = session
            lastAppStepTotal = total
            lastAppStepFeedAt = elapsed
            steps.clear()
            diag("[step:feed] App 会话切换 → 重建步数基线=$total（不补历史）")
            return
        }
        if (lastAppStepTotal < 0L || total < lastAppStepTotal) {
            lastAppStepTotal = total
            lastAppStepFeedAt = elapsed
            diag("[step:feed] App 步数回退/首次建基线 → total=$total")
            return
        }
        val delta = total - lastAppStepTotal
        val previousTotal = lastAppStepTotal
        lastAppStepTotal = total
        lastAppStepFeedAt = elapsed
        if (delta <= 0L) return

        // App 第一次从 0 开始报步时，窗口里可能已经攒了本地传感器的步数；
        // 先清掉，避免 App 的首批步数和本地重复计算。
        if (previousTotal == 0L) steps.clear()

        // 一次心跳最多补 300 步（正常人 45 秒走不出这个量）；多出来的只用于
        // 统计，不把 5 分钟窗口瞬间灌爆。真正走路时每拍增量很小，不受影响。
        val add = minOf(delta, 300L).toInt()
        repeat(add) { steps.add(wall - it.toLong()) }
        steps.removeAll { it < wall - WINDOW_MS }
        walking = true
        lastStepTs = wall
        // 与 recordStep 对齐：供数补步同样代表「在走」，把状态从 SLEEP 抬到 LIGHT_MONITOR，
        // 否则摘要行会一直显示 SLEEP，和实际行走状态不一致（纯展示，不影响触发判据）。
        if (state == State.SLEEP) state = State.LIGHT_MONITOR
        stepEventCount += add
        diag("[step:feed] App 硬件供数 +$add（本拍差 $delta）→ 窗口 ${steps.size}/${stepThreshold()}，来源=App")
    }

    // ========== 计步诊断状态（「走了半天没反应」时必须能从日志看出卡在哪） ==========
    /** 收到的合格步数事件总数（系统计步器 + 兜底合计，去重后） */
    private var stepEventCount = 0

    // ===== App 侧硬件计步供数（SystemUI 没有 ACTIVITY_RECOGNITION 的替代） =====
    /** App 心跳带来的会话累计步数基线；-1 = 还没收到过。 */
    private var lastAppStepTotal = -1L
    /** App 本次服务实例的会话标识；变化时只重建基线，不补历史步数。 */
    private var lastAppStepSession = 0L
    /** 最近一次 App 供数的单调时刻：供数在线时抑制本地传感器，避免同一步算两遍。 */
    private var lastAppStepFeedAt = 0L
    /**
     * 传感器**原始回调**总数（不管有没有凑成合格步）。
     *
     * 4.4.0 的零步数探针只盯 [stepEventCount]，把「静止 / 持握看日志但传感器
     * 每帧都在回调」误报成「传感器静默失效」，还引导用户去步态自检。
     * 分开计数后，[StepProbePolicy] 才能区分「没在走路」和「传感器真坏了」。
     */
    private var rawSensorCallbackCount = 0L
    /** 系统计步器是否注册成功 */
    private var stepSensorRegistered = false
    /** 加速度计兜底是否启用 */
    private var fallbackActive = false
    /** init 完成时间，用于判断「注册成功却长时间零事件」 */
    private var initTs = 0L
    private var lastStepLogTs = 0L
    private var lastSummaryTs = 0L

    /**
     * 【状态概览 · 心跳】上次给 App 发「我还在」广播的时间。
     *
     * 复用 [RestartReminderPolicy.ACTION_MODULE_READY]：初次就绪发一次，之后每 60 秒一次。
     * 有了它，App 首页才能实时显示「框架模式 · 锚点 3/3 · 12 秒前」，
     * SystemUI 重启后钩子没回来时也能立刻变成「注入失联」而不是一直显示旧的“已就绪”。
     */
    private var lastStatusPingTs = 0L

    /** 【状态概览】运行框架名（LSPosed / KernelSU 侧 libxposed 实现名），由模块入口注入。 */
    private var hookFrameworkName = ""
    /** 【状态概览】libxposed API 版本号，0 = 未知。 */
    private var hookApiVersion = 0
    /** 上次尝试拉起兜底计步的时间 */
    private var lastFallbackTryTs = 0L
    /** 本启动会话是否已打过探针结论（每个会话只报一次，不再 45s 复刷） */
    private var stepProbeReported = false

    // 加速度计计步的运行时状态（算法本身在 GaitDetector 里，这里只持有实例）
    private var fbListener: SensorEventListener? = null
    private var fbDetector: GaitDetector? = null

    // 步态复核采样（仅写日志）：保存监听器/Handler 引用，热重载时能立刻摘掉，
    // 否则旧一代的采样回调会在最多 4.6 秒内继续往旧 GaitDetector 喂加速度数据。
    private var gaitListener: SensorEventListener? = null
    private var gaitHandler: Handler? = null

    // ========== 常量 ==========
    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L        // 5分钟滑动窗口
        const val TRIGGER_COOLDOWN = 5 * 60 * 1000L // 触发后5分钟冷却
        const val SIT_REMIND_MS = 2 * 60 * 60 * 1000L  // 久坐2小时
        const val DAILY_COOLDOWN = 24 * 60 * 60 * 1000L // 每日冷却
        const val BATTERY_COOLDOWN = 60 * 60 * 1000L   // 电量1小时冷却

        /**
         * 「显示/收起预览」指令的去重保鲜期。
         *
         * App 进程被系统直接杀掉时不会走 onDestroy，回执永远不来，
         * 只发变化的去重会把 lastSentShow 永久锁死。超过这个时间就无条件补发一次，
         * 让状态能自愈 —— 一条本地广播，成本可以忽略。
         */
        const val OVERLAY_RESYNC_MS = 60_000L
        const val NIGHT_START_HOUR = 22  // 夜间开始
        const val NIGHT_END_HOUR = 6      // 夜间结束

        // ===== 相机避让（5.7）=====
        /** 看到相机后至少保持让路这么久：覆盖 ROM 层级 3 在相机/桌面之间跳的抖动窗口 */
        const val CAM_AVOID_HOLD_MS = 6_000L
        /** 真的离开相机后，要连续这么多次「非相机」才解除让路（tick 2 秒 → 约 4 秒） */
        const val CAM_CLEAR_STREAK = 2

        // ===== 加速度计计步 =====
        /** 系统计步器注册成功但这么久一步都没来，视作「名存实亡」 */
        const val STEP_PROBE_WINDOW_MS = 45_000L
        /** App 手动点「预览」后发来的标记：Hook 据此暂缓「停下就收」半分钟 */
        const val ACTION_PREVIEW_MANUAL = "io.github.wumisc.safewalk.PREVIEW_MANUAL"

        /**
         * 点灯重试间隔。
         *
         * 关掉预览后 HAL 才真正放开摄像头，这段延迟在真机上是 300ms~1.5s 不等，
         * 只试一次必然「点了没反应」。四次累计约 4 秒，覆盖最慢的机型，
         * 又不会让用户觉得「按下去半天才亮」——第一次通常就成功了。
         */
        val TORCH_RETRY_DELAYS = longArrayOf(300L, 600L, 1_200L, 2_000L)
        /** 点亮手电后抑制自动预览的时长：避免预览立刻回来把摄像头抢走 */
        const val TORCH_PREVIEW_HOLD_MS = 60_000L

        /** 重启系统界面：App 发指令，Hook 收到后自杀，由 system_server 拉起新进程 */
        const val ACTION_RESTART_SYSTEMUI = "io.github.wumisc.safewalk.RESTART_SYSTEMUI"
        const val ACTION_RESTART_ACK = "io.github.wumisc.safewalk.RESTART_ACK"
        const val EXTRA_RESTART_TOKEN = "token"
        const val RESTART_TOKEN = "safewalk-restart-2.4"
        const val APP_PKG = "io.github.wumisc.safewalk"

        /**
         * 控制指令广播的签名级权限（在 App 的 Manifest 里用 signature 级声明）。
         *
         * 本进程里注册的所有 EXPORTED 接收器都挂上这个权限：系统在分发阶段
         * 校验发送方签名，第三方应用即使拿到源码里的令牌字符串也发不进来。
         */
        const val CONTROL_PERMISSION = "io.github.wumisc.safewalk.permission.CONTROL"

        /** 诊断日志：统一走 DiagLog（logcat + 用户可见文件，多级降级落盘）。
         *  是否落盘由 refreshConfig 同步到 DiagLog.enabled 控制（companion 里读不到实例配置） */
        fun diag(msg: String) {
            DiagLog.log(msg)
        }

    }

    // ========== 配置读取（统一入口） ==========

    /**
     * 拉取配置，默认 60 秒内复用缓存；force=true 强制刷新。
     *
     * **绝不阻塞调用方**：真正的读取在 [configExecutor] 上做，这里只负责去重和发起。
     * 调用点全是主线程（tick / 广播回调），原来那一版在主线程直接 query Provider，
     * App 没活着时会把它冷启动起来并等 Binder 返回——SystemUI 主线程卡在那儿，
     * 轻则状态栏掉帧，重则 ANR。
     *
     * 读失败时保留上一份配置（不能把用户开关全重置成默认值），并把缓存时间戳
     * 往前拨，让下一次 tick 尽快重试。
     */
    private fun refreshConfig(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // 配置改动会通过广播即时推过来（见 configChangedReceiver），
        // 这里的 60 秒轮询只是兜底
        if (!force && configJson != null && now - lastConfigTs < 60_000L) return
        if (configLoading) {
            // 正在读的时候又要求强制刷新：记下来，读完立刻补一次，别丢
            if (force) configReloadPending = true
            return
        }
        configLoading = true
        val appCtx = ctx
        try {
            ensureConfigExecutor().execute {
                val loaded = try {
                    SafeWalkConfig.loadConfig(appCtx)
                } catch (t: Throwable) {
                    Log.e("SafeWalk", "读配置异常", t)
                    null
                }
                Handler(Looper.getMainLooper()).post {
                    configLoading = false
                    if (loaded != null) {
                        // 整体替换引用，读侧永远看到一份完整快照
                        configJson = loaded
                        lastConfigTs = System.currentTimeMillis()
                        // 同步日志总开关：关了就不再写日志文件，省 I/O 省电
                        DiagLog.enabled = pref(SafeWalkPrefKey.enableDiagLog, true)
                        // 配置到位后重算依赖开关的监听器与悬浮窗状态
                        try {
                            updateCrashDetection()
                        } catch (_: Throwable) {
                        }
                        try {
                            updateOverlay()
                        } catch (_: Throwable) {
                        }
                    } else {
                        // 读不到就先沿用旧配置，50 秒后（tick 的 60 秒兜底）再试
                        lastConfigTs = System.currentTimeMillis() - 50_000L
                    }
                    if (configReloadPending) {
                        // 读取期间被要求再读一次（连点开关）：立刻补，不要让用户等轮询
                        configReloadPending = false
                        refreshConfig(force = true)
                    }
                }
            }
        } catch (t: Throwable) {
            // 线程池已关闭等极端情况：不能让 tick 因为读配置而中断
            configLoading = false
            diag("❌ 提交配置读取任务失败: $t")
        }
    }

    private fun pref(key: String, default: Boolean): Boolean = configJson?.optBoolean(key, default) ?: default
    private fun pref(key: String, default: Int): Int = configJson?.optInt(key, default) ?: default
    private fun pref(key: String, default: Long): Long = configJson?.optLong(key, default) ?: default
    private fun pref(key: String, default: String): String = configJson?.optString(key, default) ?: default
    /** 历史版本把手电静置分钟存成 Int，老配置里是整数；optDouble 两种情况都能读 */
    private fun prefFloat(key: String, default: Float): Float =
        configJson?.optDouble(key, default.toDouble())?.toFloat() ?: default

    private fun masterEnabled() = pref(SafeWalkPrefKey.masterEnable, true)
    // 默认步数阈值 50、静置重置 20 秒，均可在「行走模式」二级菜单调整
    private fun stepThreshold() = pref(SafeWalkPrefKey.stepThreshold, 50)
    private fun staticResetMs() = pref(SafeWalkPrefKey.staticResetSec, 20) * 1000L

    // ========== Hook 入口 ==========

    /** 成功挂上的初始化锚点数量：0 表示只能靠后面的轮询兜底 */
    @Volatile
    private var anchorsHooked = 0

    /** 第一个真正触发初始化的入口，只记一次（用户反馈 ROM 差异时看这一行） */
    @Volatile
    private var initTrigger = ""

    @Volatile
    private var contextFallbackStarted = false

    /**
     * Hook SystemUI 进程，**三个锚点并联**，谁先命中都能起来。
     *
     * 以前只有 `SystemUIApplication.onCreate` 一个点：OEM 把这个类换掉、或者改了
     * 启动顺序，模块就整个静默失效，日志里只留一行「找不到 SystemUIApplication 类」，
     * 用户看到的现象是「装了但什么都不动」。
     *
     * 现在三层：
     * 1. `SystemUIApplication.onCreate` —— AOSP 与多数 ROM 的主入口
     * 2. `Instrumentation.callApplicationOnCreate` —— 不认类名，任何 Application 都必经，
     *    ROM 改了 Application 类名也拦不住
     * 3. `SystemUIService.onCreate` —— SystemUI 自己的 Service，启动很早，作为旁证
     *
     * 三个都不中还有第四条路：进程内起线程反射 `ActivityThread` 自取 Context（见
     * [startContextFallback]）。四条全废才是真的没戏。
     */
    /** 挂一个锚点，成功/失败都留日志。返回 false 表示该类在这台机器上不存在（不算错误） */
    internal fun hookAnchor(name: String, block: () -> Boolean) {
        try {
            if (block()) {
                anchorsHooked++
                Log.i("SafeWalk", "Hook $name 成功")
                diag("[hook] 锚点已挂上: $name")
            } else {
                Log.w("SafeWalk", "Hook 点不存在: $name")
                diag("[hook] 锚点不存在（该 ROM 无此入口）: $name")
            }
        } catch (t: Throwable) {
            Log.e("SafeWalk", "Hook $name 失败", t)
            diag("[hook] 锚点挂载失败: $name → ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 所有锚点的统一落点。
     *
     * 三个锚点可能先后都触发（正常现象），[runInit] 自己有幂等保护，这里只负责
     * 记下「第一个命中的是谁」——用户反馈"某 ROM 上没反应"时，这一行能立刻说明
     * 是哪个入口活了、还是根本没有入口。
     */
    /** 模块入口在首次初始化前注入框架信息（本类不是 XposedModule 子类，拿不到那两个属性）。 */
    internal fun setFrameworkInfo(name: String, api: Int) {
        hookFrameworkName = name ?: ""
        hookApiVersion = api
    }

    internal fun triggerInit(appCtx: Context, source: String) {
        if (disposed) return
        if (initSucceeded) return
        if (initTrigger.isEmpty()) {
            initTrigger = source
            Log.i("SafeWalk", "初始化触发源: $source")
            diag("[hook] 初始化触发源: $source")
        }
        init(appCtx)
    }

    /**
     * 【API 102 规则】热重载完成后，新一代主动接管。
     *
     * 文档明确写着：**package 生命周期回调不会在热重载后自动重放**
     * （`onModuleLoaded` / `onPackageLoaded` 都不会再来一遍）。所以不能像旧版那样
     * 在 `onHotReloaded` 里只打一行日志等 `onPackageLoaded` —— 那样这一代永远不会初始化，
     * 表现就是「热重载成功了，但模块从此死了，直到 SystemUI 重启」。
     *
     * 新代码里没有 SystemUI 的 ClassLoader，重新挂锚点没有意义（Application.onCreate 也已经过去了），
     * 直接反射 `ActivityThread` 取当前 Application 并初始化；取不到再走 500ms 轮询兜底。
     */
    internal fun afterHotReload(processName: String) {
        disposed = false
        diag("[reload] 新一代接管（process=$processName）：主动取 Context 初始化")
        val app = currentApplication()
        if (app != null) {
            triggerInit(app, "热重载后 ActivityThread 直取")
        } else {
            diag("[reload] 新一代暂时取不到 Application，转 500ms 轮询兜底")
            startContextFallback()
        }
    }

    /**
     * Context 兜底通道：不依赖任何 Hook 点，直接反射拿当前进程的 Application。
     *
     * 适用于锚点全部挂不上的 ROM。每 500ms 试一次，最多 20 秒；拿到就初始化并
     * 立即退出线程。锚点已生效时会提前返回，不会白跑（前 8 秒还会先等一等，
     * 正常机型 2 秒内就初始化完成，这个线程基本不会真正开始轮询）。
     */
    internal fun startContextFallback() {
        if (contextFallbackStarted) return
        contextFallbackStarted = true
        val t = Thread({ contextFallbackLoop() }, "SafeWalk-ctx-fallback")
        t.isDaemon = true
        t.start()
    }

    /** 兜底轮询的主体（独立函数，避免在 lambda 里用带标签的 return） */
    private fun contextFallbackLoop() {
        // 锚点挂上了就多给点时间，别和正常路径抢
        val graceMs = if (anchorsHooked > 0) 8_000L else 1_000L
        var waited = 0L
        while (waited < graceMs) {
            if (disposed || initSucceeded) return
            sleepQuietly(500L)
            waited += 500L
        }
        var tries = 0
        while (tries < 40) {
            if (disposed || initSucceeded) return
            val app = currentApplication()
            if (app != null) {
                diag("[hook] 兜底通道拿到 Context（第 ${tries + 1} 次；锚点命中 $anchorsHooked 个），触发初始化")
                triggerInit(app, "ActivityThread 轮询兜底")
                return
            }
            tries++
            sleepQuietly(500L)
        }
        diag("[hook] ⚠️ 兜底轮询 20 秒仍拿不到 Context：本进程内模块无法启动")
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    /** 反射取当前进程的 Application；还没 attach 时返回 null */
    private fun currentApplication(): Context? = try {
        val atCls = Class.forName("android.app.ActivityThread")
        val thread = atCls.getMethod("currentActivityThread").invoke(null)
        atCls.getMethod("getApplication").invoke(thread) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun init(appCtx: Context) {
        Handler(Looper.getMainLooper()).postDelayed({ runInit(appCtx) }, 2_000L)
    }

    /**
     * 真正执行初始化。可重入、可重试：核心步骤失败按退避重来，成功才封口。
     *
     * 每一步单独兜底（见 [step]）。以前整段包在一个 try 里，第一处抛异常就把后面
     * 的计步器、广播、心跳全部跳过，现象是「模块装了但什么都不动」，日志里只有
     * 一行「初始化失败」。现在单步失败只损失那一步，其余照常跑，并且能看出是哪一步。
     */
    private fun runInit(appCtx: Context) {
        if (disposed) return
        synchronized(initLock) {
            // 防御性：个别 ROM 会重复走 SystemUIApplication.onCreate
            if (initSucceeded) {
                diag("init 已执行过，跳过重复初始化")
                return
            }
            if (initRunning) return
            initRunning = true
        }

        var coreOk = false
        try {
            ctx = appCtx.applicationContext
            DiagLog.init(ctx!!)
            coreOk = true
            diag("core init: 第 ${initAttempts + 1} 次尝试")
        } catch (t: Throwable) {
            Log.e("SafeWalk", "初始化失败（核心步骤）", t)
            diag("❌ 初始化失败（核心步骤，第 ${initAttempts + 1} 次）: $t")
        }

        if (coreOk) {
            // 配置读取改到后台线程，这里只发起，不阻塞（见 refreshConfig 注释）
            refreshConfig(force = true)

            step("传感器服务") {
                sm = ctx!!.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            }
            // 车祸预警：总开关+车祸开关都开着才启动持续加速度监听
            step("车祸检测") { updateCrashDetection() }
            // 【v1.1 T04】急救覆盖层 + 一次性显示取件客户端。
            // 两步各自 try/catch（step 已保证），失败只损失这两路，绝不影响心跳看门狗。
            step("急救覆盖层") { EmergencyOverlay.install(ctx!!) }
            step("显示取件客户端") { DisplayClient.install(ctx!!, EmergencyOverlay) }
            // 环境光监测：夜间自动开灯前判断「太黑才亮」
            step("环境光监测") { startLightMonitor() }
            // 预览已搬到 App 进程（WalkPreviewService）：Hook 这里只做步态判定，
            // 判定结果通过广播指挥 App 显示/收起，本进程不再持有相机

            // 初始化手电筒通知模块
            step("手电通知渠道") {
                createTorchNotifyChannel(ctx!!)
                torchNotifyManager = TorchNotificationManager(ctx!!)
                torchNotifyManager?.startMonitor()
            }
            // 阈值直接从 Hook 已缓存的配置读，不再单独开一条 60 秒的跨进程轮询
            step("手电静置提醒") {
                val mgr = torchNotifyManager ?: return@step
                torchReminder = TorchStaticReminder(mgr, ctx!!) {
                    (prefFloat(SafeWalkPrefKey.torchStaticMinutes, 12f) * 60_000L).toLong()
                }
            }

            // 控制类跨进程广播（手电 / 重启 / 配置变更 / 日志回传 / 预览回执）
            step("控制广播") { registerControlReceivers() }

            // 注册计步器（含失败检测与加速度计兜底）
            step("计步器") { registerStepDetector() }
            // 加速度计计步恒定开启，和系统计步器并行工作。
            //
            // 以前是「系统计步器失败、或 5 分钟零事件」才启用兜底，
            // 而这台设备上 SystemUI 进程注册计步器「成功」却一步都不回
            // （ACTIVITY_RECOGNITION 是运行时权限，模块挂在系统界面进程里拿不到，
            //  传感器在、注册也不报错，就是永远没有回调）。
            // 结果：走 5 分钟内一步都记不到，用户看到的就是「走半天没反应」。
            // 现在两条腿走路，recordStep 里按 300ms 去重，谁先报算谁的。
            step("加速度计兜底") {
                startAccFallback("恒定启用（与系统计步器并行，按 300ms 去重）")
            }

            step("电量监听") {
                if (!batteryReceiverRegistered) {
                    registerBatteryReceiver()
                    batteryReceiverRegistered = true
                }
            }

            step("心跳") { startTick() }
        }

        // 【HyperOS 版本适配模块】SystemUI 侧只打一次环境快照 + 识别结论。
        // 放在 init 收尾而不是 2s 的 check() 里，避免高频刷屏；Hook 与 App
        // 两个进程的日志写到同一份 diag.log，现场对照「识别成 3 还是 4」很方便。
        try {
            HyperOsCompat.logEnvironment()
            diag("[VER] Hook(SystemUI) 适配结论: " + HyperOsCompat.describe(
                    HyperOsCompat.detect(), HyperOsCompat.MODE_AUTO))
            // 【5.3.2】把「进程里实际加载的代码版本」写进日志开头。
            // 覆盖安装不会重启 SystemUI，于是「磁盘上是新版、内存里是旧类」这种
            // 状态在界面上完全看不出来 —— 5.3.0、5.3.1 两次真机日志都栽在这上面：
            // 新增的诊断行一条没有，我还以为是代码写错了。
            // 有这一行，下次回传日志开头就能判断到底跑的是哪一版。
            diag("[VER] 模块实际运行版本=" + BuildConfig.VERSION_NAME
                + "(" + BuildConfig.VERSION_CODE + ")  APK 安装包版本以首页显示为准")
        } catch (_: Throwable) {
        }

        var justReady = false
        synchronized(initLock) {
            initRunning = false
            if (coreOk && !initSucceeded) {
                initSucceeded = true
                justReady = true
            }
        }
        if (!coreOk) {
            scheduleInitRetry(appCtx)
        } else if (justReady) {
            // 跟热重载状态对齐：只在模块真正初始化完成后，才告诉 App「当前版本已经在跑」。
            // 装完 APK 但 SystemUI 里还是旧代码时不会走到这里，首页的重启提示照旧。
            notifyModuleReady(appCtx)
        }
    }

    /**
     * 通知 App：Hook 这一代已经初始化完成，当前实际运行 versionCode 是多少。
     *
     * 用显式 `setPackage(APP_PKG)` 只投给本应用，并带令牌；App 侧的静态接收器
     * 收到后把「运行版本 + 本次安装指纹」写进 SP。首页拿这组值判断是否需要
     * 重启：热重载成功时标记匹配 → 不弹误报；热重载失败 / 模块没加载 → 没标记
     * 或标记属于上一次安装 → 正常提示重启。
     */
    private fun notifyModuleReady(appCtx: Context) {
        try {
            val i = Intent(RestartReminderPolicy.ACTION_MODULE_READY).apply {
                setPackage(APP_PKG)
                putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                putExtra(RestartReminderPolicy.EXTRA_MODULE_VERSION_CODE, BuildConfig.VERSION_CODE)
                putExtra(RestartReminderPolicy.EXTRA_MODULE_VERSION_NAME, BuildConfig.VERSION_NAME)
                putExtra(RestartReminderPolicy.EXTRA_MODULE_INIT_SOURCE, initTrigger)
                // 【状态概览】锚点数 / 框架名 / API 版本：App 首页据此显示「注入状态」
                putExtra(RestartReminderPolicy.EXTRA_HOOK_ANCHORS, anchorsHooked)
                // 【状态概览】框名/API 由 SafeWalkLibxModule 在初始化前塞进来：
                // frameworkName / apiVersion 是 XposedModule 的属性，本内核类不是它的子类，取不到。
                putExtra(RestartReminderPolicy.EXTRA_HOOK_FRAMEWORK, hookFrameworkName)
                putExtra(RestartReminderPolicy.EXTRA_HOOK_API, hookApiVersion)
            }
            appCtx.sendBroadcast(i)
            diag("[reload] 已通知 App：模块运行版本=" + BuildConfig.VERSION_NAME
                + "(" + BuildConfig.VERSION_CODE + ") 触发源=" + initTrigger)
        } catch (t: Throwable) {
            diag("[reload] 通知 App 模块已就绪失败: $t")
        }
    }


    /** 单步初始化兜底：一步失败不影响后面的步骤，也不会把整个 init 卡死 */
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("SafeWalk", "初始化步骤失败: $name", t)
            diag("❌ 初始化步骤失败[$name]: $t")
        }
    }

    /**
     * 核心步骤失败后的退避重试：2s → 4s → 8s … 最长 60s，最多 20 次。
     *
     * 不能无限重试：SystemUI 若本身处于残缺状态，每 2 秒重建一次只会刷满日志、
     * 白耗电。20 次约覆盖 15 分钟的临时性失败，之后就等下次 SystemUI 重启。
     */
    private fun scheduleInitRetry(appCtx: Context) {
        initAttempts++
        if (initAttempts > 20) {
            diag("❌ 初始化连续失败 $initAttempts 次，停止重试（重启 SystemUI 后会重新尝试）")
            return
        }
        val delay = minOf(60_000L, 2_000L shl minOf(initAttempts, 5))
        diag("↻ ${delay / 1000}s 后重试初始化（已失败 $initAttempts 次）")
        Handler(Looper.getMainLooper()).postDelayed({ runInit(appCtx) }, delay)
    }

    /**
     * 注册所有「控制类」跨进程接收器。
     *
     * 与以前唯一的区别：全部通过 [ReceiverCompat.registerExportedWithPermission]
     * 挂上 signature 级权限 [CONTROL_PERMISSION]。系统在**分发阶段**就校验发送方
     * 签名，第三方应用即使反编译拿到令牌字符串也发不进来——「反复发
     * RESTART_SYSTEMUI 把系统界面杀掉造成黑屏」那条 DoS 由此关闭。
     *
     * 每个接收器按名字登记，init 重试时不会重复注册（重复注册 = 一条提醒弹两次）。
     */
    private fun registerControlReceivers() {
        if (ctx == null) return

        // 在系统进程里注册手电动作接收器（系统进程有权限访问摄像头）
        val torchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                // 权限已在系统分发阶段校验（只有同签名应用能发）；
                // 令牌是第二道校验，防止同签名的不同构建互相误触发
                if (intent.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                    != SafeWalkPrefKey.BROADCAST_TOKEN
                ) {
                    Log.w("SafeWalk", "手电广播令牌不匹配，忽略")
                    return
                }
                Log.i("SafeWalk", "收到手电广播: ${intent.action}")
                val on = when (intent.action) {
                    "io.github.wumisc.safewalk.OPEN_TORCH" -> true
                    "io.github.wumisc.safewalk.CLOSE_TORCH" -> false
                    else -> return
                }
                diag("[torch] 收到${if (on) "开灯" else "关灯"}指令")
                if (on) {
                    // 后视预览开在应用进程里，正占着同一颗摄像头。
                    // 先让它让路（App 收到后立刻 close CameraDevice），
                    // 本进程随后重试点灯——不先把预览收掉，setTorchMode
                    // 必然 CAMERA_IN_USE，这就是「按了没反应」的全部原因。
                    releasePreviewForTorch()
                }
                setTorchWithRetry(on, 0)
            }
        }
        torchActionReceiver = torchReceiver
        val torchFilter = IntentFilter().apply {
            addAction("io.github.wumisc.safewalk.OPEN_TORCH")
            addAction("io.github.wumisc.safewalk.CLOSE_TORCH")
        }
        registerOnce("torch", torchReceiver, torchFilter)
        Log.i("SafeWalk", "手电广播接收器注册完成")

        // 手动预览标记：App 点「预览」后暂缓自动模式的「停下就收」
        registerOnce("preview_manual", manualPreviewReceiver, IntentFilter(ACTION_PREVIEW_MANUAL))

        // 重启系统界面：App 发指令 → 回执 → 自杀，由 system_server 拉起新进程
        registerOnce("restart", restartReceiver, IntentFilter(ACTION_RESTART_SYSTEMUI))

        // 配置变更：App 写完配置主动推过来，改完开关立刻生效（轮询只是兜底）
        registerOnce("config", configChangedReceiver, IntentFilter(SafeWalkPrefKey.ACTION_CONFIG_CHANGED))

        // 日志回传：App 启动时来要一份本进程日志，由 App 统一落盘。
        // SystemUI 自己的外部目录是 Android/data/com.android.systemui/…，
        // 用户根本进不去，只有回传到 App 才看得到。
        registerOnce(
            "log", logRequestReceiver,
            IntentFilter().apply {
                addAction(DiagLog.ACTION_REQUEST_LOG)
                addAction(DiagLog.ACTION_LOG_RESET)
            }
        )

        // 预览实际状态回执：App 侧手动开/关过预览后，本进程的
        // lastSentShow 必须跟着改，否则「去重」会把下一次显示指令吞掉
        registerOnce(
            "preview_state",
            previewStateReceiver,
            IntentFilter(WalkPreviewService.ACTION_PREVIEW_STATE)
        )

        // 预览服务心跳：看门狗据此判断 App 进程是否已被系统回收
        registerOnce(
            "heartbeat",
            heartbeatReceiver,
            IntentFilter(KeepAlivePolicy.ACTION_HEARTBEAT)
        )
    }

    private fun registerOnce(name: String, receiver: BroadcastReceiver, filter: IntentFilter) {
        val c = ctx ?: return
        if (!registeredReceivers.add(name)) return
        try {
            ReceiverCompat.registerExportedWithPermission(c, receiver, filter, CONTROL_PERMISSION)
            // 【5.7.3 热重载】留下引用，重载时才能真的反注册掉旧一代，
            // 否则旧闭包会一直挂在 SystemUI 上，收到广播还去操作已经作废的内核。
            receiverRefs[name] = receiver
        } catch (t: Throwable) {
            // 从登记表里摘掉，让下一次 init 重试还能补上
            registeredReceivers.remove(name)
            diag("❌ 注册广播失败[$name]: $t")
        }
    }

    /**
     * 【5.7.3 热重载】释放旧一代占用的全部资源。
     *
     * libxposed 102 的 `onHotReloading` 只负责换钩子；本内核在 SystemUI 里另外持有
     * 传感器监听、动态广播接收器、tick 链、看门狗状态和配置快照，这些**不会**
     * 随钩子替换自动消失。不处理的话会出现：旧闭包继续收广播（操作已作废的 ctx）、
     * 加速度计被两代同时监听（双倍耗电、重复计步）、tick 链续命导致日志翻倍。
     *
     * 这里的顺序：先停链 → 再断传感器 → 再反注册接收器 → 最后清状态与上下文。
     * 全部 try-catch 包住：任何一项失败都不能把 SystemUI 带崩。
     */
    fun disposeForReload(reason: String) {
        diag("[reload] 释放旧一代资源：$reason")
        disposed = true
        tickStarted = false
        // ① 传感器：主计步器监听（this）、加速度计兜底、环境光、撞击检测、步态复核采样
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        try { stopAccFallback() } catch (_: Throwable) {}
        try { luxListener?.let { sm?.unregisterListener(it) } } catch (_: Throwable) {}
        luxListener = null
        try { stopGaitSampling() } catch (_: Throwable) {}
        try { crashDetector?.stop() } catch (_: Throwable) {}
        crashDetector = null
        stepSensorRegistered = false
        // ② 动态广播：逐个反注册，清空两个登记表（新一代 init 会重新注册）
        val c = ctx
        if (c != null) {
            for ((name, r) in receiverRefs) {
                try { c.unregisterReceiver(r) } catch (_: Throwable) {
                    diag("[reload] 反注册失败[$name]（多半已随进程重建）")
                }
            }
            batteryReceiver?.let { try { c.unregisterReceiver(it) } catch (_: Throwable) {} }
        }
        receiverRefs.clear()
        registeredReceivers.clear()
        batteryReceiver = null
        batteryReceiverRegistered = false
        torchActionReceiver = null
        try { torchReminder?.reset() } catch (_: Throwable) {}
        // 手电回调必须显式反注册：它持有 CameraManager 与旧 Context，
        // 只把字段置 null 的话回调仍会活着，新一代再注册就变成两份。
        try { torchNotifyManager?.stopMonitor() } catch (_: Throwable) {}
        // 急救浮层是直接挂在 WindowManager 上的 View，必须收起并释放，否则热重载后
        // 旧一代的急救卡会一直留在屏幕上（静态持有旧 Context）。
        try { EmergencyOverlay.dispose() } catch (_: Throwable) {}
        // 显示取件客户端持有两个跨进程接收器与一条取件线程，随旧一代一并释放。
        try { DisplayClient.dispose() } catch (_: Throwable) {}
        torchReminder = null
        torchNotifyManager = null
        // ③ 内核状态：配置快照与各类时间戳必须作废，新一代重新读一遍真实配置
        configJson = null
        lastConfigTs = 0L
        configLoading = false
        configReloadPending = false
        steps.clear()
        lastStepTs = 0L
        lastAppStepTotal = -1L
        lastAppStepSession = 0L
        lastAppStepFeedAt = 0L
        lastSitRemindTs = 0L
        lastTriggerTs = 0L
        lastSentShow = null
        lastSentAt = 0L
        previewUntil = 0L
        lastHeartbeatAt = 0L
        hookReadyAt = 0L
        lastReviveAt = 0L
        reviveCount = 0
        lastAutoStartTry = 0L
        lastWatchdogDesired = null
        lastHeartbeatLogTs = 0L
        // 步态/行走状态：新一代 init 会按真实传感器与配置重新建立
        state = State.SLEEP
        walking = false
        nightTorchGaitOn = false
        lastNightTorchGaitLogTs = 0L
        torchHoldUntil = 0L
        lastNightTimeLogTs = 0L
        lastLux = Float.MAX_VALUE
        lastLuxTs = 0L
        fbDetector = null
        gaitSampling = false
        // 相机避让状态一并作废，避免旧层级/旧包名影响新一代判路
        cameraInFront = false
        camAvoidHoldUntil = 0L
        camClearStreak = 0
        camDetectLayer = 0
        camDetectBroken = false
        lastCamAvoidPkg = null
        // 计步诊断计数与探针结论：新一代重新统计
        stepEventCount = 0
        rawSensorCallbackCount = 0L
        stepProbeReported = false
        initTs = 0L
        lastStepLogTs = 0L
        lastSummaryTs = 0L
        lastStatusPingTs = 0L
        lastFallbackTryTs = 0L
        lastBatteryWarnTs = 0L
        // 重新挂锚点 / 兜底通道的标记也要放开，否则新一代会被旧标记挡住
        anchorsHooked = 0
        initTrigger = ""
        contextFallbackStarted = false
        // ④ 配置线程：单线程守护池，每重载一代就会多一条 "SafeWalk-config" 线程。
        // 旧实例不会再用，直接 shutdownNow；置空后新一代 ensureConfigExecutor 会重建。
        try { configExecutor?.shutdownNow() } catch (_: Throwable) {}
        configExecutor = null
        // ⑤ 上下文最后清：上面的反注册都要用到它
        sm = null
        ctx = null
        initSucceeded = false
        initRunning = false
        initAttempts = 0
        diag("[reload] 旧一代已释放完毕，等待新一代重新挂锚点")
    }

    private fun startTick() {
        if (tickStarted) return
        tickStarted = true
        hookReadyAt = SystemClock.elapsedRealtime()
        // 定时轮询（对齐2.0最好版：开启时2秒，关闭时5秒）
        Handler(Looper.getMainLooper()).postDelayed(tickRunnable, 2_000L)
    }

    // ========== 传感器回调 ==========
    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            rawSensorCallbackCount++
            // 不再「系统出数就关兜底」：两条链路并行，重复由 recordStep 的 300ms 去重挡掉。
            // 反过来关兜底会让「系统计步器偶尔给一个事件后彻底沉默」的设备重新变成瞎子。
            recordStep(System.currentTimeMillis())
        }
    }

    /**
     * 记一步。系统计步器与加速度计兜底共用这一条路径，
     * 保证两边的状态机行为完全一致。
     */
    private fun recordStep(now: Long) {
        // App 供数在线：硬件计步已经由心跳补进窗口，本进程的同一条物理步
        // 不能再记一次。等 App 心跳断掉超过一个看门狗周期，才退回本地兜底。
        if (lastAppStepTotal > 0L
            && SystemClock.elapsedRealtime() - lastAppStepFeedAt
            < KeepAlivePolicy.HEARTBEAT_TIMEOUT_MS
        ) return
        // 系统计步器与加速度计同时在跑，同一步会被两边各报一次。
        // 300ms 内的重复事件直接丢弃 —— 人走不出 3.3 步/秒，不会误杀真实步数。
        if (now - lastStepTs < 300L) return
        lastStepTs = now
        steps.removeAll { it < now - WINDOW_MS }
        steps.add(now)
        walking = true
        if (state == State.SLEEP) state = State.LIGHT_MONITOR
        stepEventCount++
        // 每 15 秒最多打一条，避免每步一行把日志刷爆
        if (now - lastStepLogTs > 15_000L) {
            lastStepLogTs = now
            diag("[Hook] 🚶 计步中：窗口 ${steps.size}/${stepThreshold()} 步，状态=$state，来源=${if (fallbackActive) "加速度计" else "系统计步器"}")
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ========== 计步器注册与兜底 ==========

    /**
     * 注册系统计步器。
     *
     * 原来是 `sm?.getDefaultSensor(...)?.let { registerListener(...) }` 一行，
     * 三个失败点全部静默：没有计步器、SystemUI 缺少 ACTIVITY_RECOGNITION、
     * 系统直接拒绝注册。表现完全一致 —— 走了半天一步都收不到，日志里一个字都没有。
     * 现在每一步失败都写日志，并在失败时启用加速度计自行计步。
     */
    private fun registerStepDetector() {
        initTs = System.currentTimeMillis()
        // 新启动会话：原始回调计数与探针结论一并重置，
        // 让本次会话的 45s 探针能正常判一次（SystemUI 重启后 initTs 也会重来）。
        rawSensorCallbackCount = 0L
        stepProbeReported = false
        val sensor = try {
            sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        } catch (t: Throwable) {
            diag("❌ 取计步器失败: $t")
            null
        }
        if (sensor == null) {
            diag("❌ 本机没有计步器(TYPE_STEP_DETECTOR)，改用加速度计自行计步")
            startAccFallback("设备无计步器")
            return
        }
        // 【5.7.3】SystemUI 一定没有 ACTIVITY_RECOGNITION（它是系统进程，用户无法给它授权），
        // 直接注册必然返回 false —— 旧版每次重启都要走一遍「❌ 注册被拒绝」再降级，
        // 日志里像出了故障，其实只是已知限制。先查权限，缺权限就静默走加速度计，
        // 只在真能注册的机器（少数 ROM 把 SystemUI 列进健康权限白名单）走系统计步器。
        if (!stepPermissionGranted()) {
            diag("⏭ SystemUI 无 ACTIVITY_RECOGNITION（系统进程无法授权），直接用加速度计计步")
            startAccFallback("系统界面无计步权限")
            return
        }
        val ok = try {
            sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) ?: false
        } catch (t: Throwable) {
            diag("❌ 计步器注册抛异常: $t")
            false
        }
        stepSensorRegistered = ok
        if (ok) {
            diag("✅ 计步器已注册: ${sensor.name} / ${sensor.vendor}")
        } else {
            diag("❌ 计步器注册被拒绝（系统界面多半没有 ACTIVITY_RECOGNITION 权限），改用加速度计自行计步")
            startAccFallback("注册被拒")
        }
    }

    /**
     * SystemUI 进程是否持有 ACTIVITY_RECOGNITION（Android 10+ 才需要）。
     *
     * 查询失败一律返回 false —— 宁可走加速度计兜底（功能等价、稍费电），
     * 也不要拿一个必然失败的注册去刷错误日志。
     */
    private fun stepPermissionGranted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return true
        return try {
            val c = ctx ?: return false
            c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 加速度计兜底计步。
     *
     * 一阶低通分离重力，线性加速度上穿阈值算一步，间隔限制在 280~1500ms
     * （对应 0.67~3.5 步/秒），把抖腿、摇手机挡在外面。
     * 只在系统计步器不可用时启用 —— 它比系统计步器费电，是兜底不是主力。
     */
    private fun startAccFallback(reason: String) {
        if (fallbackActive) return
        val s = sm ?: return
        val acc = try {
            s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } catch (t: Throwable) {
            null
        }
        if (acc == null) {
            diag("❌ 连加速度计都没有，步数功能不可用")
            return
        }
        fallbackActive = true
        // 计步直接复用步态检测器，而不是另写一套阈值判断。
        //
        // 原来兜底那段自带一份滤镜，系数是 `0.8*old + 0.2*new` —— 和 GaitDetector 里
        // 那个写反的 alpha 是同一个错误，残差小到永远越不过 1.2 的门槛，
        // 兜底看着"已启用"，实际一步都不计。两套实现各错各的，查起来还互相掩盖。
        // 现在只有一个算法，步频窗口、主轴自适应、包络阈值全部共用。
        val detector = GaitDetector()
        fbDetector = detector

        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                val v = e?.values ?: return
                if (v.size < 3) return
                // 原始回调先计数：这是「传感器到底有没有供货」的唯一证据。
                // 合格步（feedAcc 返回 true）只是它的子集，静止时可以是 0。
                rawSensorCallbackCount++
                // 【5.7.6】改用硬件时钟（e.timestamp，纳秒）换算，不再用 currentTimeMillis。
                // 这批 ROM 会把传感器事件按 ~66ms 一包批处理上报，逐条回调时墙钟早就走完，
                // 同一包里 6~8 帧拿到同一个毫秒 → 步间隔变 0/66ms，步频窗口整段失效。
                // 换算结果仍在墙钟域（见 [SensorClock]），lastStepTs / 判停 / 冷却都不用改。
                val wallNow = System.currentTimeMillis()
                val now = SensorClock.msFromNanos(e.timestamp, wallNow)
                if (detector.feedAcc(v[0], v[1], v[2], now)) recordStep(now)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            s.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
            fbListener = l
            diag("✅ 加速度计计步已启用（原因：$reason）")
        } catch (t: Throwable) {
            fallbackActive = false
            diag("❌ 加速度计计步启动失败: $t")
        }
    }

    private fun stopAccFallback() {
        if (!fallbackActive) return
        val l = fbListener
        if (l != null) {
            try {
                sm?.unregisterListener(l)
            } catch (_: Throwable) {
            }
        }
        fbListener = null
        fbDetector = null
        fallbackActive = false
    }

    /**
     * 注册环境光传感器：夜间自动开灯前先读 lux，暗光才亮、亮着不亮。
     * 光线传感器功耗极低，常开即可。读不到时 luxIsDark() 返回 false，
     * 自动开灯不生效，调用方降级为发「夜间照明」提醒。
     */
    private fun startLightMonitor() {
        if (luxListener != null) return
        val s = sm ?: return
        val light = try {
            s.getDefaultSensor(Sensor.TYPE_LIGHT)
        } catch (t: Throwable) {
            null
        }
        if (light == null) {
            diag("[lux] 本机无光线传感器，夜间自动开灯将降级为提醒")
            return
        }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                val v = e?.values ?: return
                if (v.isEmpty()) return
                lastLux = v[0]
                lastLuxTs = System.currentTimeMillis()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        try {
            s.registerListener(l, light, SensorManager.SENSOR_DELAY_NORMAL)
            luxListener = l
            diag("[lux] 环境光监测已启动")
        } catch (t: Throwable) {
            luxListener = null
            diag("[lux] 环境光监测启动失败: ${t.message}")
        }
    }

    /**
     * 当前环境是否「暗到需要手电」。
     * 读不到光线传感器、或读数超过 10 秒没更新，一律返回 false ——
     * 宁可不开、降级成提醒通知，也不要在不确定时乱开灯。
     */
    private fun luxIsDark(): Boolean {
        if (luxListener == null || lastLuxTs == 0L) return false
        if (System.currentTimeMillis() - lastLuxTs > 10_000L) return false
        return lastLux < luxDarkThreshold
    }

    // ========== 定时检查 ==========
    private fun check() {
        try {
            val now = System.currentTimeMillis()
            refreshConfig()

            // 【状态概览 · 60 秒心跳】只看时间戳，不改任何判定逻辑
            if (now - lastStatusPingTs >= 60_000L) {
                lastStatusPingTs = now
                currentApplication()?.let { notifyModuleReady(it) }
            }

            if (!masterEnabled()) {
                // 总开关关着就别一直采加速度计了，白耗电
                if (fallbackActive) stopAccFallback()
                // 总开关关闭期间也要把「静置计时」按下去，
                // 否则关了一整天再打开，第一轮就会判定「已静置 N 小时」直接弹提醒
                torchReminder?.onLongStaticDetected(false)
                // 总开关没开是「怎么不提醒」的头号原因，留一条痕迹免得只能靠猜
                if (now - lastSummaryTs > 120_000L) {
                    lastSummaryTs = now
                    diag("⏸ 总开关已关闭，所有提醒停用（计步暂停，本次启动累计 $stepEventCount 步）")
                }
                // 联动已迁移到 App 侧小窗；这里只清理旧版残留的点灯状态（幂等 no-op）。
                syncNightTorchGait(now)
                return
            }

            // 兜底计步意外停了（注册抛异常 / 被系统注销）就补回来。
            // 它是现在的主计步来源，悄悄没了等于整个提醒系统瞎掉，
            // 而表现和「没在走路」完全一样，不查日志根本发现不了。
            if (!fallbackActive && now - lastFallbackTryTs > 30_000L) {
                lastFallbackTryTs = now
                startAccFallback("兜底未运行，自动恢复")
            }

            // 步数探针：只用来发现「传感器注册成功却连一次原始回调都没有」的
            // 静默失效。判定交给 [StepProbePolicy]（纯逻辑、有单测）。
            //
            // 4.4.0 的旧探针只看「合格步数 == 0」，于是用户静止、拿在手里翻日志、
            // 传感器明明每帧都在回调，也会被报成「❌ 仍未收到任何步数事件 …
            // 确认传感器本身有没有数据」，且每 45 秒复刷一次（真机 19:07:33「46s」、
            // 19:08:19「92s」）。现在按原始回调数分级，且每个启动会话只报一次。
            val probe = StepProbePolicy.evaluate(
                masterOn = true,                 // 外层已判过 !masterEnabled() 直接 return
                initTs = initTs,
                now = now,
                windowMs = STEP_PROBE_WINDOW_MS,
                rawCallbacks = rawSensorCallbackCount,
                qualifiedSteps = stepEventCount,
                reported = stepProbeReported
            )
            when (probe) {
                StepProbePolicy.Verdict.SENSOR_ALIVE_NO_STEP -> {
                    stepProbeReported = true
                    diag("ℹ️ 启动 ${(now - initTs) / 1000}s 尚无合格步，但传感器已回调 "
                            + "$rawSensorCallbackCount 次（静止 / 持握属正常）"
                            + " | 系统计步器注册=$stepSensorRegistered 加速度计兜底=$fallbackActive"
                            + " | 计步链路正常，无需处理")
                }
                StepProbePolicy.Verdict.SENSOR_SILENT -> {
                    stepProbeReported = true
                    diag("❌ 启动 ${(now - initTs) / 1000}s 传感器 0 次回调，疑似静默失效"
                            + " | 系统计步器注册=$stepSensorRegistered 加速度计兜底=$fallbackActive"
                            + " | 请确认传感器权限与加速度计/计步器是否可用")
                }
                StepProbePolicy.Verdict.OK -> {
                    // 已有合格步，链路正常，标记已判定，之后不再重复探
                    stepProbeReported = true
                }
                StepProbePolicy.Verdict.NOT_READY -> Unit
            }

            // 每 2 分钟一条状态摘要：走没走、卡在哪一环，看日志一眼能定位
            if (now - lastSummaryTs > 120_000L) {
                lastSummaryTs = now
                val gap = if (lastStepTs == 0L) "从未" else "${(now - lastStepTs) / 1000}s 前"
                diag("📊 状态=$state 窗口步数=${steps.size}/${stepThreshold()} 上一步(Hook侧)=$gap 计步来源=${stepSource()} 累计=$stepEventCount（Hook侧时钟，与App判停不同源）"
                        + (fbDetector?.let { " | " + it.debugInfo() } ?: ""))
            }

            // 静置判定：一段时间没有新步 → 认为停下了。
            // 注意这里**不能清空 steps**：原来每停 20 秒就把整个 5 分钟窗口清零，
            // 等个红灯、进个电梯、系个鞋带，前面攒的几十步全部作废，
            // 走走停停的人永远凑不满阈值。滑动窗口自己会过期，交给它就行。
            if (lastStepTs == 0L || now - lastStepTs > staticResetMs()) {
                walking = false
                steps.removeAll { it < now - WINDOW_MS }
                checkSitRemind(now)
                state = State.SLEEP
                // 通知手电筒提醒器：现在是静置状态
                torchReminder?.onLongStaticDetected(true)
                // 【BUG 修复】不再按「停止行走」灭灯：灯现在跟随小窗显隐，
                // 由 App 侧会话生命周期负责。此处仅保留幂等清理。
                syncNightTorchGait(now)
                return
            }

            // 有步数 → 行走中
            walking = true
            torchReminder?.onLongStaticDetected(false)
            // 【BUG 修复】检测到行走步态不再点灯：避免床上假步数误亮闪光灯。
            // 闪光灯只跟随角落小窗显示（App 侧），此处仅保留幂等清理。
            syncNightTorchGait(now)

            steps.removeAll { it < now - WINDOW_MS }

            // 触发冷却
            if (now - lastTriggerTs < TRIGGER_COOLDOWN) return

            // 步数达标 → 直接提醒。
            //
            // 【这一版为什么把步态校验从「关卡」降为「旁证」】
            // 之前是：步数达标 → 开 4 秒加速度计做步态校验 → 通过才提醒，
            // 通不过就等 8 秒重试，连续两次失败还要步数翻倍（100 步）才兜底。
            // 这套流程在实验室波形上很漂亮，真机上几乎不可能通过：
            //   · 4 秒只够 5~6 步，peakIntervals 最多 3 个样本
            //   · 要求每个间隔与均值的差都 < 250ms，手机在口袋里晃一下就不满足
            //   · 于是走 50 步不提醒、走满 100 步才兜底 —— 用户体感「走了半天没反应」
            //
            // 步数本身就是充分证据：系统计步器是硬件计步，加速度计兜底走的是
            // 同一套峰检测。二次确认只保留为事后诊断（照采 4 秒，结果写日志），
            // 不再决定要不要提醒。判错顶多多弹一条，判不出是整趟夜路一条都没有。
            if (steps.size >= stepThreshold()) {
                diag("🚶 步数达标（窗口 ${steps.size} 步 / 阈值 ${stepThreshold()}），直接触发")
                triggerMovement()
                sampleGaitForLog()
            }
        } catch (e: Exception) {
            Log.e("SafeWalk", "check() 异常", e)
            diag("check() 异常: $e")
        }
    }

    private fun stepSource(): String = when {
        // App 心跳供数在线时，窗口步数来自 App 硬件计步；本地传感器事件已被 recordStep 抑制。
        lastAppStepTotal > 0L
            && SystemClock.elapsedRealtime() - lastAppStepFeedAt
            < KeepAlivePolicy.HEARTBEAT_TIMEOUT_MS -> "App 供数"
        fallbackActive -> "加速度计兜底"
        stepSensorRegistered -> "系统计步器"
        else -> "未注册"
    }

    /**
     * 手动预览期标记。
     *
     * App 侧点「预览」按钮时，预览服务会发广播过来；Hook 记一个 30 秒窗口，
     * 期间 updateOverlay 不干涉（不发收起指令），否则「停下就收」
     * 会在用户刚点开预览的一瞬间把它关掉。
     */
    private fun markManualPreview() {
        previewUntil = System.currentTimeMillis() + 30_000L
    }

    /**
     * 配置变更广播：App 每写一次配置就推一条过来。
     *
     * 只重算悬浮窗显示与否。**不**主动跑 check()：
     * check() 的其余部分是步态判定，2 秒后的 tick 会做，差不了这点时间。
     */
    private val configChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            // 无令牌的一律忽略：这个 action 谁都能发，放行等于给了别人一个
            // 「让 SystemUI 反复跨进程查配置」的按钮
            if (intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) return
            refreshConfig(force = true)
            try {
                updateOverlay()
                updateCrashDetection()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 日志回传：App 启动时来要一份本进程（SystemUI）最近的日志。
     *
     * 以前用户想在 `Android/Travel safely/` 里看日志，永远只有 App 侧那几行，
     * 而真正干活的步态判定、手电、车祸检测全在这个进程里 —— 断在哪一步根本看不见。
     */
    private val logRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) return
            // 【5.6.15】App 侧判定「新安装事件」后已把 diag.log 删掉重建。
            // 本进程还握着旧文件的写句柄（outFor 一直等于那个 File），不主动关掉，
            // 后续写入会落到已被 unlink 的 inode 上——用户看到的文件里从此
            // 只剩 App 进程的内容，Hook 的相机/避让日志整段消失。
            if (intent.action == DiagLog.ACTION_LOG_RESET) {
                DiagLog.resetForRebuild()
                diag("[log] 已按 App 请求释放旧文件流（日志已清空重建，后续写入落到新文件）")
                return
            }
            // 索取日志时先补一条「此刻的现场」，否则回传的只有两分钟一次的摘要，
            // 正好卡在「刚走完但还没到摘要时间」的瞬间会看不出任何动静
            val gapNow = if (lastStepTs == 0L) "从未" else "${(System.currentTimeMillis() - lastStepTs) / 1000}s 前"
            diag("📸 现场: 总开关=${if (masterEnabled()) "开" else "关"} 状态=$state 窗口步数=${steps.size}/${stepThreshold()} 上一步=$gapNow 计步来源=${stepSource()} 计步器注册=$stepSensorRegistered 累计=$stepEventCount")
            diag("[log] 收到日志索取请求，回传 ${DiagLog.snapshot().length} 字符")
            try {
                c?.sendBroadcast(Intent(DiagLog.ACTION_LOG_DUMP).apply {
                    setPackage(APP_PKG)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                    putExtra(DiagLog.EXTRA_LOG_TEXT, DiagLog.snapshot())
                })
            } catch (t: Throwable) {
                Log.e("SafeWalk", "[log] 日志回传失败", t)
            }
        }
    }

    /**
     * 预览实际状态回执。
     *
     * [sendOverlayCmd] 做了「只发变化」去重。问题在于：用户在 App 页面里
     * 手动点「关闭预览」时，App 只收起了窗口，Hook 这边**毫不知情**，
     * `lastSentShow` 还停在 true。下一次判定「走起来该显示」时，
     * 去重逻辑认为「已经是显示状态」，指令被吞掉 —— 表现就是
     * 「预览第一次能开，关掉之后再也开不出来」。
     *
     * 所以 App 侧每次真正显示/收起后都回一条回执，这里把记录校准成实际值。
     */
    private val previewStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) return
            val showing = intent.getBooleanExtra(WalkPreviewService.EXTRA_SHOWING, false)
            if (lastSentShow != showing) {
                diag("[preview] 实际状态校准: $lastSentShow → $showing")
            }
            lastSentShow = showing
        }
    }

    /**
     * 车祸预警开关生效点。
     *
     * 总开关 + 车祸预警开关都开着时启动加速度监听，否则停掉。
     * 灵敏度档位通过 lambda 每次回调时读最新配置，改完档位立刻生效，
     * 不必重建监听器。
     */
    private fun updateCrashDetection() {
        val enabled = masterEnabled() && pref(SafeWalkPrefKey.enableCrashDetection, false)
        val sm = this.sm
        if (!enabled || sm == null) {
            crashDetector?.stop()
            return
        }
        if (crashDetector == null) {
            crashDetector = CrashDetector(ctx ?: return) {
                CrashDetector.thresholdFor(pref(SafeWalkPrefKey.crashSensitivity, 1))
            }
        }
        crashDetector?.start(sm)
    }

    /** App 手动点「预览」时的标记：Hook 据此暂缓「停下就收」半分钟 */
    private val manualPreviewReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) return
            markManualPreview()
        }
    }

    /**
     * 「重启系统界面」：App 侧点按钮后发指令过来。
     *
     * 模块本身就跑在 SystemUI 进程里，所以最干净的做法是杀掉自己（不是别人的进程），
     * system_server 会立刻重新拉起 SystemUI —— 不需要 root，也不会误伤别的东西。
     * 先回执、再延迟 600ms 自杀，确保 App 能收到「已接管」，从而不去走 su 兜底。
     */
    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getStringExtra(EXTRA_RESTART_TOKEN) != RESTART_TOKEN) {
                Log.w("SafeWalk", "重启指令令牌不匹配，忽略")
                return
            }
            try {
                c?.sendBroadcast(
                    Intent(ACTION_RESTART_ACK).apply {
                        setPackage(APP_PKG)
                        putExtra(EXTRA_RESTART_TOKEN, RESTART_TOKEN)
                    }
                )
            } catch (_: Throwable) {
            }
            diag("收到「重启系统界面」指令，600ms 后重启 SystemUI")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {
                }
            }, 600L)
        }
    }

    private fun checkSitRemind(now: Long) {
        if (lastStepTs == 0L) return
        // 深夜不弹「久坐」：人躺在床上 8 小时不动，是睡觉，不是久坐。
        // 原来的判据只有「2 小时没走」，凌晨三点准时把人叫醒，
        // 这种提醒属于纯粹的骚扰，用户第一件事就是把它关掉。
        if (isNightTime()) return
        if (now - lastStepTs > SIT_REMIND_MS && now - lastSitRemindTs > DAILY_COOLDOWN) {
            if (pref(SafeWalkPrefKey.enableSitHealthRemind, true)) {
                notifyApp(WalkPreviewService.NOTIFY_SIT)
                lastSitRemindTs = now
            }
        }
    }

    // ========== 步态旁路采样 ==========

    private val gait = GaitDetector()
    /** 正在采样：避免多个监听器叠加 */
    private var gaitSampling = false

    /**
     * 触发提醒后，顺便采 4 秒加速度计，把波形特征写进日志。
     *
     * **它不再决定要不要提醒**，只回答「刚才那几十步像不像走路」。
     * 理由写在 [check] 里：把二次确认当关卡，等于要求用户走出教科书级别的
     * 稳定步频，手机在口袋里、在包里、拄拐、推婴儿车都做不到 ——
     * 结果就是完全不提醒，而用户完全无法从现象上区分「算法不认」
     * 和「根本没收到步数」。
     *
     * 保留它是因为误报率需要数据：哪天发现「坐公交也被提醒」，
     * 这些日志能直接看出是系统计步器在颠簸中误计，还是我们的峰检测太松。
     */
    private fun sampleGaitForLog() {
        val s = sm ?: return
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            diag("🔍 步态复核：本机没有加速度计，跳过")
            return
        }
        if (gaitSampling) return
        gaitSampling = true
        gait.reset()

        val end = System.currentTimeMillis() + 4_000
        val handler = Handler(Looper.getMainLooper())
        gaitHandler = handler
        var finished = false
        lateinit var listener: SensorEventListener

        fun finish() {
            if (finished) return
            finished = true
            gaitSampling = false
            try { s.unregisterListener(listener) } catch (_: Throwable) {}
            handler.removeCallbacksAndMessages(null)
            gaitListener = null
            gaitHandler = null
            val stable = gait.hasStableGait()
            diag("🔍 步态复核：${gait.debugInfo()} 稳定=${if (stable) "是" else "否"}"
                    + if (stable) "（波形也确认是步行）"
                    else "（波形不像标准步行，但步数已达标，提醒不受影响）")
        }

        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null || finished) return
                gait.feedAcc(e.values[0], e.values[1], e.values[2])
                if (System.currentTimeMillis() > end) finish()
            }
            override fun onAccuracyChanged(sensor: Sensor?, a: Int) {}
        }
        gaitListener = listener

        try {
            s.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME)
            // 部分 ROM 在省电模式下会停采，4.6 秒兜底收尾，别让监听器永久挂着
            handler.postDelayed({ finish() }, 4_600L)
        } catch (t: Throwable) {
            finished = true
            gaitSampling = false
            gaitListener = null
            gaitHandler = null
            diag("步态复核启动失败: $t")
        }
    }

    /** 热重载：立刻摘掉步态复核采样的监听器与待办消息，不把旧采样留在传感器上。 */
    private fun stopGaitSampling() {
        val l = gaitListener
        val h = gaitHandler
        gaitListener = null
        gaitHandler = null
        gaitSampling = false
        if (l != null) try { sm?.unregisterListener(l) } catch (_: Throwable) {}
        h?.removeCallbacksAndMessages(null)
    }

    /** 确认持续步行 → 触发所有外出提醒 */
    private fun triggerMovement() {
        refreshConfig(force = true)
        val now = System.currentTimeMillis()
        val fired = ArrayList<String>(3)
        lastTriggerTs = now
        val counted = steps.size
        steps.clear()
        state = State.SLEEP

        // 【BUG 修复 · 触发条件与内容语义错配】
        // 原来这里有两段：
        //   1) 证件提醒（每日最多 1 次）——只要「确认在持续步行」就弹；
        //   2) 社交礼仪提示——取用户设置的出行模式模板，拼前 3 条弹一大段。
        // 结果是在家走动、沙发上起身都会收到「身份证、护照一定要贴身放好」和
        // 「【求职】优先青旅，只订1晚…」，跟你人在哪儿毫无关系。
        // 这两条内容的语义是「到了某类场合该注意什么」，属于地点触发，不属于
        // 步态触发。现已整体迁到 App 侧 PlaceArrivalMonitor：
        //   定位 → 反编码 → 地点词库判类 → 停留确认 → 按类别发一条到场提示。
        // Hook 侧不再无条件推送，避免「走路就念一遍」。

        // 1. 夜间手电提醒
        // 补回原始设计的两个门槛：
        // ① 屏幕必须亮着——黑屏时弹「开手电」通知没有意义；
        // ② 行走模式正在显示后置画面时，相机被预览占用，此时开闪光灯会失败，
        //    避让这一次，等下一轮出门再提醒。
        val screenOn = try {
            (ctx?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive ?: true
        } catch (_: Throwable) { true }
        // 判据是「预览真的在屏幕上」，不是「行走模式开关开着」。
        // 原来写成 enableRearCamera && walking，而 walking 在进这个函数前就被置 true，
        // 等于「只要开了行走模式就永远不提醒手电」—— 自动模式下没在显示画面时也白跳过。
        val rearCamShowing = lastSentShow == true
        if (isNightTime() && screenOn && !rearCamShowing) {
            // 单一「夜间手电」开关（enableNightTorchGait）走 App 侧「角落小窗显示」
            // 预览会话内联动点亮，由 WalkPreviewService 负责，这里不再点灯。
            // 旧版「lux 暗光 + SystemUI 侧 setTorchMode 独立点灯」链路已删除：
            // 它会与预览抢摄像头、且被床上假步数误触发（即用户反馈的
            // 「bug1 闪光灯在床上自动打开」），不再保留。
            if (pref(SafeWalkPrefKey.enableNightTorchGait, false)) {
                diag("[torch] 小窗联动开关已开：闪光灯随角落小窗显示时点亮、收起时熄灭，跳过旧自动点灯")
            } else if (pref(SafeWalkPrefKey.enableNightTorch, true)) {
                notifyApp(WalkPreviewService.NOTIFY_TORCH)
                fired.add("夜间手电")
            }
        }

        // 4. 行走模式由 updateOverlay 统一驱动，这里只保证行走状态为真，
        //    避免「这里 show、下一轮 tick 又 hide」的自相矛盾
        walking = true

        // 这一段是「到底有没有触发」的唯一证据。
        // 以前触发后一个字都不写，用户说「没反应」时无法区分
        // 「没走到 50 步」和「走到了但通知没弹出来」——两者处理方式完全不同。
        if (fired.isEmpty()) {
            diag("✅ 判定为持续步行（窗口 $counted 步），本轮没有需要发的提醒"
                    + "（证件/到场提示已改为「到达场所」触发，由 App 侧定位判定）")
        } else {
            diag("✅ 判定为持续步行（窗口 $counted 步）→ 已发提醒：${fired.joinToString("、")}")
        }
    }

    /** 时间区间明细日志节流时间戳：check() 每 2s 调一次，别刷屏 */
    private var lastNightTimeLogTs = 0L

    /**
     * 【调试增强】夜间时间区间判定。
     *
     * 原来这里是一行 `return hour >= start || hour < end`，用户抱怨「到设定时间
     * 也不亮」时，日志里完全看不到「现在几点 / 区间是几点到几点 / 到底命没命中」，
     * 只能靠猜。现在每 60 秒打一条 [torch:time] 明细，时间条件是否成立一目了然。
     */
    private fun isNightTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val start = pref(SafeWalkPrefKey.nightTorchStartHour, NIGHT_START_HOUR)
        val end = pref(SafeWalkPrefKey.nightTorchEndHour, NIGHT_END_HOUR)
        val hit = hour >= start || hour < end
        val now = System.currentTimeMillis()
        if (now - lastNightTimeLogTs > 60_000L) {
            lastNightTimeLogTs = now
            diag("[torch:time] 区间判定 | 当前=$hour 时 生效区间=$start:00~$end:00 命中=$hit"
                + if (hit) "" else "（未到设定时间，小窗联动不会点灯）")
        }
        return hit
    }

    /**
     * 【BUG 修复 · 联动对象纠正】夜间手电**不再**由 Hook 侧按 walking 点灯。
     *
     * 最新真机日志（01:23 / 01:33）证明：躺床上 LAY_FLAT 时手机翻身 / 抖动被
     * SystemUI 侧加速度计兜底误判成 walking=true，上一版这个状态机随即
     * 「[torch:gait] ✅ 条件满足 → 自动点亮手电」，也就是用户反馈的
     * 「bug1 闪光灯在床上自动打开」。
     *
     * 用户已明确纠正需求：「夜间手电也就是闪光灯是跟角落小窗一起打开，
     * 联动的是角落小窗」。因此点灯职责整体迁到 **App 进程**：
     *   WalkPreviewService.doShow() → RearCameraOverlay 预览会话内 FLASH_MODE_TORCH
     * 条件 = 夜间时段 + 联动开关开 + **角落小窗正在显示**；小窗收起 → 会话关闭 → 灯灭。
     *
     * Hook 侧从此不再调用 setTorchWithRetry 点灯、也不再发 ACTION_HOOK_HIDE
     * 让路（否则会把刚要联动的小窗收掉）。这样既不依赖本进程不可靠的 walking
     * 假步数，也不碰用户要求保留的原生计步采集模块（GaitDetector.kt）。
     *
     * 本方法保留为空壳，只做一件事：从旧版升级上来时若 nightTorchGaitOn 还残留
     * 为 true，补一次灭灯并复位，避免孤儿常亮。check() 里三处调用保持不变，
     * 正常运行是幂等 no-op。
     */
    private fun syncNightTorchGait(now: Long) {
        if (now == 0L) return   // 保留形参，避免改动 check() 的三处调用
        if (!nightTorchGaitOn) return
        nightTorchGaitOn = false
        diag("[torch:gait] 🔧 联动已迁移到角落小窗（App 侧预览会话内点灯），"
            + "清理旧版残留的步态点灯状态并熄灭")
        setTorchWithRetry(false, 0)
    }

    // ========== 电量广播接收器 ==========
    private fun registerBatteryReceiver() {
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    refreshConfig()
                    if (!pref(SafeWalkPrefKey.enableBatteryWarning, true)) return
                    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
                    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    if (level * 100 / scale <= 15) {
                        val now = System.currentTimeMillis()
                        if (now - lastBatteryWarnTs > BATTERY_COOLDOWN) {
                            notifyApp(WalkPreviewService.NOTIFY_BATTERY)
                            lastBatteryWarnTs = now
                        }
                    }
                }
            }
            ctx?.let {
                ReceiverCompat.registerNotExported(it, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryReceiver = receiver
            }
        } catch (_: Throwable) {}
    }

    // ========== 定时轮询：单一入口 ==========
    private val tickRunnable = object : Runnable {
        override fun run() {
            // 【5.7.3 热重载】旧一代被释放后，已排队的那一帧不能再跑：
            // 否则它会拿着已经置空的 ctx 再发一轮指令，并把链自己续上。
            if (!tickStarted) return
            try {
                // 【v1.1 T04】急救覆盖层 TTL 兜底：Activity 可见 ACK 丢失时，
                // 20s 后自动 hide()，复用既有 2s/5s tick，不另起定时器、不开 wake lock。
                try {
                    EmergencyOverlay.expireIfDue(SystemClock.elapsedRealtime())
                } catch (_: Throwable) {
                }
                // check() 负责步态与提醒；updateOverlay() 只负责悬浮窗。
                // 之前 tick 里漏掉 check()，导致整套提醒逻辑从未被执行过。
                check()
                updateOverlay()
            } catch (t: Throwable) {
                diag("tick: $t")
            }
            val now = System.currentTimeMillis()
            // 【省电计划】自适应节拍：只在「正在走 / 预览显示中」维持 2 秒响应，
            // 静置时放慢到 5 秒。计步是传感器事件驱动，放慢轮询不影响走路检测延迟。
            // 【5.7】让路保持期间也维持 2 秒：否则收窗后立刻降到 5 秒节拍，
            // 用户离开相机要等 5 秒才恢复，而相机再打开又要等 5 秒才收。
            val previewActive = lastSentShow == true || previewUntil > now || lastCamAvoidPkg != null
            val interval = PowerPolicy.tickIntervalMs(masterEnabled(), walking, previewActive)
            Handler(Looper.getMainLooper()).postDelayed(this, interval)
        }
    }

    /**
     * 悬浮窗显示的唯一控制点。
     *
     * 相机已搬到 App 进程（WalkPreviewService），这里只算「该不该显示」，
     * 结论变化时才广播给 App 显示/收起。只发变化、不每次 tick 都发，
     * 避免每 2 秒一次跨进程广播白白耗电。
     */
    private var lastSentShow: Boolean? = null
    /** 上一次真正发出显示/收起指令的时刻，用于 [OVERLAY_RESYNC_MS] 的保鲜期补发 */
    private var lastSentAt = 0L

    private fun updateOverlay() {
        if (!masterEnabled() || !pref(SafeWalkPrefKey.enableRearCamera, false)) {
            previewUntil = 0L
            // 关掉预览的这段时间看门狗不该再判活：显式记住「这一拍期望是关」，
            // 否则 lastWatchdogDesired 会一直停在 true，用户隔几分钟重新拨开时
            // 被当成「一起就是开的」，拿早已过期的旧心跳立刻拉起透明页，
            // 和用户自己的 startForegroundService 撞成两条启动链（重复 doShow）。
            lastWatchdogDesired = false
            sendOverlayCmd(false)
            return
        }

        // 手电正占着摄像头：此刻拉起预览只会得到黑屏，还会和手电抢设备。
        // 判据两个：刚点灯后的冷静期、TorchCallback 报告的实况
        // （用户从控制中心点亮的手电也要算进来）。
        //
        // 【BUG 修复】旧版第三个判据 nightTorchGaitOn（步态联动手电持续亮灯）
        // 已删除：新版闪光灯是「跟着角落小窗一起亮」，若这里因为它亮着就抑制预览，
        // 会形成「要亮灯就得先收小窗、小窗收了灯又灭」的死循环。
        // 而且预览会话内的 FLASH_MODE_TORCH 不会触发 TorchCallback，两个判据互不干扰。
        if (System.currentTimeMillis() < torchHoldUntil
            || torchNotifyManager?.torchIsOn == true
        ) return

        // 前台是相机应用时暂时让位，否则悬浮窗会挡住取景
        if (cameraInForeground()) {
            // 同时作废预览窗口：否则用户开着预览去拍了张照，回来时
            // 预览期还没过，updateOverlay 直接 return，悬浮窗就「再也不回来」了
            previewUntil = 0L
            sendOverlayCmd(false)
            return
        }

        // 【2.10.2 修复】自动模式的「走动」判定已迁回应用进程（WalkPreviewService，
        // 一个 camera 前台服务，传感器回调不会被后台策略掐断）。SystemUI 里的计步在
        // 多数 ROM 上「注册成功但永不回调」，导致 walking 永远为 false、自动模式永远
        // 不显示——这正是用户「只有手动好用」的根因。
        //
        // 因此这里不再依赖本进程的 walking 去决定自动显隐，只发「可以让路恢复显示了」
        // 的 true 信号；具体显不显示由 App 依据它自己可靠的 walking 决定。
        // 让路（总开关关 / 相机前台 / 手电占摄像头）仍由上面的分支发 false 收起。

        // 配置允许显示 → 看门狗确认预览服务还活着（心跳超时就自动拉回来）。
        // 开机/重启 SystemUI 后不必再手动拨开关，进程被 ROM 回收也能自愈。
        maybeAutoStartPreview()
        sendOverlayCmd(true)
    }

    /**
     * 看门狗：确保「配置期望开」时预览服务真的活着。
     *
     * 旧实现用 `autoStartLaunched` 保证每个 SystemUI 进程只拉一次，只解决了
     * 「开机免手动拨开关」。但 App 进程一旦被 ROM 回收，Hook 之后就再也不管了 ——
     * 用户看到的就是「行走模式运行中」那条常驻通知消失，而且不会自己回来。
     *
     * 现在 App 侧每 30 秒发一次心跳，本进程记录接收时刻；配置期望开而心跳
     * 超过 90 秒没来，就重新拉起透明页代启服务（同一条可见 Activity 豁免）。
     * 判定、冷却与「心跳通道不通」的限速全在 [KeepAlivePolicy]，纯逻辑、有单测。
     */
    private fun maybeAutoStartPreview() {
        val c = ctx ?: return
        val now = SystemClock.elapsedRealtime()
        val desired = KeepAlivePolicy.desired(
            masterEnabled(), pref(SafeWalkPrefKey.enableRearCamera, false)
        )
        // 用户刚拨开开关（上一拍是关、这一拍是开）：把心跳基线挪到此刻。
        // 服务还在 onCreate 里，第一条心跳最快也要几十毫秒后才到；不能让看门狗
        // 拿旧心跳判死，否则会和用户自己的启动抢跑，重复 startForegroundService
        // 在 30 秒后触发前台服务超时，直接把 App 进程杀掉。
        val resetBaseline = KeepAlivePolicy.resetBaselineOnStart(lastWatchdogDesired, desired)
        lastWatchdogDesired = desired
        if (resetBaseline) {
            lastHeartbeatAt = now
            diag("[keep] 行走模式刚开启 → 看门狗重置心跳基线，等首个心跳")
        }
        if (!KeepAlivePolicy.shouldRevive(
                desired, lastHeartbeatAt, now, hookReadyAt, lastReviveAt, reviveCount
            )
        ) return

        // 亮屏 + 已解锁才尝试：锁屏时 startActivity 会被拦，透明窗也可能压在锁屏上
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isInteractive != true) return
            val km = c.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (km?.isKeyguardLocked == true) return
        } catch (_: Throwable) {
            return
        }

        // 失败重试冷却：startActivity 抛异常时别每 2 秒刷一次日志。
        // 初值 0 不设限，保证开机首拉不被这条挡住。
        if (lastAutoStartTry != 0L && now - lastAutoStartTry < KeepAlivePolicy.REVIVE_COOLDOWN_MS) {
            return
        }
        lastAutoStartTry = now

        try {
            val i = Intent().apply {
                setClassName(APP_PKG, "$APP_PKG.AutoStartActivity")
                // 从系统进程跨应用拉起必须 NEW_TASK；不带动画，尽量减少存在感
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                // 带上令牌，兼容给 SystemUI 分配了应用段 uid 的 ROM
                putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                // 只有「曾经收到过心跳、后来失联」才是真正的被杀后恢复；
                // 开机首拉（lastHeartbeatAt==0）不算，避免每次开机都弹恢复通知。
                if (lastHeartbeatAt != 0L) {
                    putExtra(KeepAlivePolicy.EXTRA_FROM_REVIVE, true)
                    putExtra(KeepAlivePolicy.EXTRA_REVIVE_SOURCE, "watchdog")
                }
            }
            c.startActivity(i)
            lastReviveAt = now
            reviveCount++
            val gap = if (lastHeartbeatAt == 0L) "从未收到心跳"
            else "${(now - lastHeartbeatAt) / 1000}s 未收到心跳"
            diag("[keep] 看门狗判定预览服务失联（$gap）→ 拉起透明页恢复，本进程第 $reviveCount 次")
        } catch (t: Throwable) {
            diag("[keep] 拉起自启页失败，稍后重试: $t")
        }
    }

    private fun sendOverlayCmd(show: Boolean) {
        val now = System.currentTimeMillis()
        // 去重，但带「保鲜期」：同一条指令超过 [OVERLAY_RESYNC_MS] 就重发一次。
        //
        // 只靠「只发变化」太脆——App 进程被系统直接杀掉时不会走 onDestroy，
        // 回执永远不来，lastSentShow 就会永久停在 true，之后所有「该显示」
        // 都被去重吞掉，用户看到的是「预览突然再也不出来了」。
        // 一分钟补发一次的成本可以忽略（一条本地广播），换来的是状态自愈。
        if (lastSentShow == show && now - lastSentAt < OVERLAY_RESYNC_MS) return
        lastSentShow = show
        lastSentAt = now
        try {
            ctx?.sendBroadcast(
                Intent(
                    if (show) WalkPreviewService.ACTION_HOOK_SHOW
                    else WalkPreviewService.ACTION_HOOK_HIDE
                ).apply {
                    setPackage(APP_PKG)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                }
            )
        } catch (_: Throwable) {
            // 没发出去就不能记账：否则下一次同样的指令会被去重吞掉
            lastSentShow = null
        }
    }

    // ========== 手电（系统进程侧） ==========

    /**
     * 【5.6.16】把「该提醒了」的结论交给 App 进程去发通知。
     *
     * 为什么不在本进程直接 notify：通知的归属包名由发通知的 Context 决定。
     * SystemUI 进程里发出去的提醒，package 是 `com.android.systemui`——
     * 系统界面自己的通知会被系统压制，超级岛（以及「超级岛增强」模块）也
     * 不会把它算成本应用的岛。用户侧看到的正是「只有前台服务那条上岛」。
     * 现在只保留判定，真正 notify 由 App 侧执行，包名回到本应用。
     */
    private fun notifyApp(kind: String) {
        try {
            ctx?.sendBroadcast(
                Intent(WalkPreviewService.ACTION_HOOK_NOTIFY).apply {
                    setPackage(APP_PKG)
                    putExtra(WalkPreviewService.EXTRA_NOTIFY_KIND, kind)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                }
            )
        } catch (t: Throwable) {
            diag("[notif] 委托 App 发通知失败($kind): ${t.message}")
        }
    }

    /**
     * 让应用进程收起后视预览，把摄像头让给手电。
     *
     * 手电和预览用的是同一颗后置摄像头，同一时刻只能有一个使用者。
     * 预览开在 App 进程里，本进程没有任何办法直接关掉它，只能发指令；
     * App 侧 Service 常驻、接收器是动态注册的，这条通道就是自动模式下
     * 显示/收起预览用的同一条，链路已经验证过。
     */
    private fun releasePreviewForTorch() {
        try {
            ctx?.sendBroadcast(
                Intent(WalkPreviewService.ACTION_HOOK_HIDE).apply {
                    setPackage(APP_PKG)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                }
            )
            // 同步记账：否则 updateOverlay 的「只发变化」去重会把下一次显示指令吞掉
            lastSentShow = false
            diag("[torch] 已通知应用收起后视预览")
        } catch (t: Throwable) {
            diag("[torch] 收起预览指令发送失败: ${t.message}")
        }
    }

    /**
     * 点灯/灭灯，摄像头被占用时按 [TORCH_RETRY_DELAYS] 重试。
     *
     * 为什么必须重试：`CameraDevice.close()` 只是发起断开，HAL 真正释放摄像头
     * 要几百毫秒到一秒多。预览刚收起就点灯，仍然会拿到 CAMERA_IN_USE。
     * 旧实现只尝试一次、失败只打一行日志，用户看到的就是「按了没反应」。
     */
    private fun setTorchWithRetry(on: Boolean, attempt: Int) {
        val c = ctx ?: return
        val r = TorchHelper.setTorchEx(c, on)
        if (r.ok) {
            torchHoldUntil = if (on) System.currentTimeMillis() + TORCH_PREVIEW_HOLD_MS else 0L
            diag("[torch] ✅ 已${if (on) "开启" else "关闭"}手电（第 ${attempt + 1} 次尝试）")
            return
        }
        if (r.inUse && attempt < TORCH_RETRY_DELAYS.size) {
            val wait = TORCH_RETRY_DELAYS[attempt]
            diag("[torch] 摄像头仍被占用（${r.message}），${wait}ms 后第 ${attempt + 2} 次尝试")
            // 第一次重试前再催一次：有的场景是「窗口收了但设备没释放干净」，
            // 此时上一次广播可能被 App 侧的「已在隐藏中」逻辑吞掉
            if (attempt == 0) releasePreviewForTorch()
            Handler(Looper.getMainLooper()).postDelayed(
                { setTorchWithRetry(on, attempt + 1) }, wait
            )
            return
        }
        diag("[torch] ❌ ${if (on) "开启" else "关闭"}失败: ${r.reason} ${r.message}")
        // 兜底：本进程权限/策略受限时，转给应用进程（它有 CAMERA 权限，
        // 也能自己收预览）。跨进程到达不确定，但这时主路径已经失败了，多一条路不亏。
        relayTorchToApp(on)
    }

    /** 兜底通道：请应用进程代为点灯 */
    private fun relayTorchToApp(on: Boolean) {
        try {
            ctx?.sendBroadcast(
                Intent(
                    if (on) io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_ON
                    else io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_OFF
                ).apply {
                    setPackage(APP_PKG)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                }
            )
            diag("[torch] 已转交应用进程重试")
        } catch (t: Throwable) {
            diag("[torch] 转交应用进程失败: ${t.message}")
        }
    }


    // 相机前台检测：**每次 tick 都查**（5.7 去掉了旧的 5 秒结果缓存）。
    //
    // 旧实现缓存 5 秒、tick 又是 2 秒，最坏要 5~7 秒才收窗，用户看到的就是
    // 「打开相机后小窗还在那儿停一会儿」。检测本身只是一次 getRunningTasks 反射，
    // 2 秒一次的开销可以忽略，没有必要缓存。
    private var cameraInFront = false

    /** 让路保持到的时刻：真机日志显示层级 3 会在相机/桌面之间来回跳，靠它压住抖动 */
    private var camAvoidHoldUntil = 0L

    /** 连续多少次检测「不是相机」才算真的离开（每次 tick 一次，约 2 秒） */
    private var camClearStreak = 0

    /** 上次成功的检测层级（1..4），下次优先用它，别每次都从失败的那层开始试 */
    private var camDetectLayer = 0

    /** 四级全失败只报一次，避免每 5 秒刷一行日志 */
    private var camDetectBroken = false

    /** 上一次因哪个相机包让路，用于「进入/离开」只记一次日志 */
    private var lastCamAvoidPkg: String? = null

    /**
     * 前台是不是相机类应用（决定小窗让不让路）。
     *
     * 【5.7 修复 · 「不避让 / 停一会儿才关 / 关了又冒出来」】
     * 1. **不再缓存 5 秒**：每次 tick（小窗显示中 2 秒）都实时查一次，收窗延迟从
     *    5~7 秒压到 2 秒内。
     * 2. **不对称迟滞**：看到相机立刻让路，并且至少保持 [CAM_AVOID_HOLD_MS]；
     *    解除让路要连续 [CAM_CLEAR_STREAK] 次非相机。真机日志里 `getRunningTasks`
     *    会在 `com.android.camera` 与 `com.miui.home` 之间反复跳（09:58:31 相机、
     *    09:58:36 桌面、09:58:42 又相机），旧实现每跳一次就解除让路 → 小窗被重新
     *    挂出来，于是「关了又回来」。
     */
    private fun cameraInForeground(): Boolean {
        val now = System.currentTimeMillis()

        val pkg = foregroundPackage()
        val hit = pkg != null && isCameraPackage(pkg)
        // 诊断：记录前台包名 / 命中层级 / 是否判为相机类，区分「检测失败(null)」与「包名不匹配漏判」
        DiagLog.logThrottled(
            "cam.fg", "dump",
            "[cam] 前台检测 pkg=${pkg ?: "null"} 层级=$camDetectLayer 相机=$hit"
                + if (lastCamAvoidPkg != null) "（让路保持中）" else "",
            2_000L, 120_000L
        )

        if (hit) {
            if (lastCamAvoidPkg == null) {
                diag("[cam] 前台是相机应用 $pkg → 立即让路收起预览")
            }
            lastCamAvoidPkg = pkg
            camClearStreak = 0
            camAvoidHoldUntil = now + CAM_AVOID_HOLD_MS
            cameraInFront = true
            return true
        }

        if (lastCamAvoidPkg != null) {
            // 还在保持期：ROM 报一次桌面不代表用户离开了相机，继续让路
            if (now < camAvoidHoldUntil) {
                cameraInFront = true
                return true
            }
            camClearStreak++
            if (camClearStreak < CAM_CLEAR_STREAK) {
                cameraInFront = true
                return true
            }
            diag(
                "[cam] 离开相机应用（${lastCamAvoidPkg}）→ 解除让路，恢复按步态判定"
                    + " | 前台=${pkg ?: "null"} 连续非相机=$camClearStreak 次"
            )
            lastCamAvoidPkg = null
            camClearStreak = 0
        } else if (pkg == null && !camDetectBroken) {
            camDetectBroken = true
            diag("[cam] ⚠️ 四级前台检测全部失败：相机避让失效（在相机界面悬浮窗不会收起）")
        }

        cameraInFront = false
        return false
    }

    /**
     * 取前台应用包名，**四级降级**，任意一级拿到就用。
     *
     * 1. `ActivityTaskManager.getFocusedRootTaskInfo().topActivity` —— Android 10+ 主流
     * 2. `ActivityTaskManager.getTasks(1)` —— 部分 ROM 屏蔽了 focused 接口，但 getTasks 还能用
     * 3. `ActivityManager.getRunningTasks(1)` —— 老 API，Android 10+ 一般只返回自己，
     *    但系统进程（SystemUI uid 1000）常能拿到
     * 4. `UsageStatsManager.queryEvents` —— 最后兜底，按最近一次 RESUMED 事件推断
     *
     * 原来只有第 1 级，抛异常被 `catch (_: Throwable) { false }` 吞掉：表现是
     * 「在相机界面悬浮窗不收起，挡住取景」，而日志里干干净净什么都看不到。
     *
     * 四级全失败返回 null —— 调用方据此**不避让**（宁可显示，也不要因为检测坏了
     * 就永远不显示），并记一条一次性告警。
     */
    private fun foregroundPackage(): String? {
        // 上次成功的层级优先：正常机型每次都只跑第 1 级，坏的那几级不会再被反复踩
        val order: IntArray = if (camDetectLayer in 1..4) {
            intArrayOf(camDetectLayer) + (1..4).filter { it != camDetectLayer }
        } else {
            intArrayOf(1, 2, 3, 4)
        }
        val failures = ArrayList<String>()
        for (layer in order) {
            val pkg = try {
                when (layer) {
                    1 -> pkgFromFocusedRootTask()
                    2 -> pkgFromTasks()
                    3 -> pkgFromRunningTasks()
                    else -> pkgFromUsageEvents()
                }
            } catch (t: Throwable) {
                failures.add("$layer:${t.javaClass.simpleName}")
                null
            }
            if (!pkg.isNullOrEmpty()) {
                if (camDetectLayer != layer) {
                    camDetectLayer = layer
                    diag("[cam] 前台检测启用第 $layer 级（${camLayerName(layer)}）")
                }
                return pkg
            } else {
                failures.add(if (pkg == null) "$layer:空" else "$layer:空白")
            }
        }
        if (failures.isNotEmpty()) {
            DiagLog.logThrottled(
                "cam.detect", "layers",
                "[cam] 四级前台检测均未取到包名 | ${failures.joinToString(",")}",
                5_000L, 120_000L
            )
        }
        return null
    }

    private fun camLayerName(layer: Int): String = when (layer) {
        1 -> "getFocusedRootTaskInfo"
        2 -> "ActivityTaskManager.getTasks"
        3 -> "ActivityManager.getRunningTasks"
        else -> "UsageStatsManager"
    }

    private fun pkgFromFocusedRootTask(): String? {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val atm = atmCls.getMethod("getInstance").invoke(null)
        val info = atmCls.getMethod("getFocusedRootTaskInfo").invoke(atm)
        return pkgOfTaskInfo(info)
    }

    private fun pkgFromTasks(): String? {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val atm = atmCls.getMethod("getInstance").invoke(null)
        // 各版本签名不一致：getTasks(int) / (int, boolean) / (int, boolean, boolean)
        val m = atmCls.methods.firstOrNull {
            it.name == "getTasks" && it.parameterTypes.isNotEmpty()
        } ?: return null
        val args = arrayOfNulls<Any>(m.parameterTypes.size)
        args[0] = 1
        for (i in 1 until args.size) {
            args[i] = if (m.parameterTypes[i] == java.lang.Boolean.TYPE) false else null
        }
        val list = m.invoke(atm, *args) as? List<*> ?: return null
        return pkgOfTaskInfo(list.firstOrNull())
    }

    @Suppress("DEPRECATION")
    private fun pkgFromRunningTasks(): String? {
        val c = ctx ?: return null
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return null
        val top = am.getRunningTasks(1)?.firstOrNull() ?: return null
        return top.topActivity?.packageName ?: top.baseActivity?.packageName
    }

    // 「使用情况访问」是 appop，未授予时 queryEvents 会抛 SecurityException（外层已 catch）。
    // 清单里显式声明了 PACKAGE_USAGE_STATS，这里标注只是为了让 lint 不再重复报同一条。
    @SuppressLint("MissingPermission")
    private fun pkgFromUsageEvents(): String? {
        val c = ctx ?: return null
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 15_000L, now) ?: return null
        val ev = android.app.usage.UsageEvents.Event()
        // package -> 最后一次"进入前台"的时间；一旦收到后台/停止事件就移除
        val foregroundTs = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkgName = ev.packageName ?: continue
            when (ev.eventType) {
                1 -> foregroundTs[pkgName] = ev.timeStamp               // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                2, 23 -> foregroundTs.remove(pkgName)                   // MOVE_TO_BACKGROUND / ACTIVITY_STOPPED
            }
        }
        return foregroundTs.maxByOrNull { it.value }?.key
    }

    /** 从 RootTaskInfo / RunningTaskInfo 里掏 `topActivity.packageName` */
    private fun pkgOfTaskInfo(taskInfo: Any?): String? {
        if (taskInfo == null) return null
        for (fieldName in arrayOf("topActivity", "baseActivity", "origActivity")) {
            val comp = try {
                taskInfo.javaClass.getField(fieldName).get(taskInfo)
            } catch (_: Throwable) {
                continue
            } ?: continue
            val pkg = try {
                comp.javaClass.getField("packageName").get(comp) as? String
            } catch (_: Throwable) {
                null
            }
            if (!pkg.isNullOrEmpty()) return pkg
        }
        return null
    }

    /**
     * 是否「会全屏占用取景」的相机类应用。
     *
     * 只收相机本体，**不收微信 / 支付宝 / 抖音 / 云闪付**这些「带扫码但主要不是
     * 取景」的应用：那会让悬浮窗在聊天、支付这些最常用的场景里全收起来，等于功能失效。
     */
    private fun isCameraPackage(pkg: String): Boolean {
        if (pkg.contains("safewalk", ignoreCase = true)) return false
        if (CAMERA_PACKAGES.contains(pkg)) return true
        return pkg.contains("camera", ignoreCase = true)
    }

    private val CAMERA_PACKAGES = setOf(
        "com.android.camera", "com.android.camera2", "com.huawei.camera",
        "com.hihonor.camera", "com.miui.camera", "com.xiaomi.camera",
        "com.oplus.camera", "com.coloros.camera", "com.vivo.camera",
        "com.bbk.camera", "com.samsung.android.camera", "com.sec.android.app.camera",
        "com.oneplus.camera", "com.google.android.GoogleCamera",
        "com.meizu.camera", "com.sonyericsson.android.camera",
        "com.motorola.camera", "com.motorola.camera2", "com.asus.camera",
        "com.zte.camera", "com.org.codeaurora.camera", "com.mediatek.camera"
    )}
