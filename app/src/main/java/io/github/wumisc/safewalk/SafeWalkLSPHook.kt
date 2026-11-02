package io.github.wumisc.safewalk

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
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage

import de.robv.android.xposed.callbacks.XC_LoadPackage
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
 * 状态机：SLEEP → LIGHT_MONITOR（有步数）→ VERIFYING（步态校验）→ 触发提醒 → SLEEP
 */
class SafeWalkLSPHook : IXposedHookLoadPackage, SensorEventListener {

    private enum class State { SLEEP, LIGHT_MONITOR, VERIFYING }

    // ========== 运行时状态 ==========
    private var state = State.SLEEP
    /** 当前是否处于「行走中」：由步数与静置超时共同决定，供悬浮窗与自动模式使用 */
    private var walking = false
    /** 手动预览截止时间戳：期间 updateOverlay 不干预，便于调参时实时看效果 */
    private var previewUntil = 0L
    private var configJson: org.json.JSONObject? = null
    private var nm: SafeWalkNotificationManager? = null
    private var sm: SensorManager? = null
    private var ctx: Context? = null
    private var torchNotifyManager: TorchNotificationManager? = null
    private var torchReminder: TorchStaticReminder? = null
    private var torchActionReceiver: BroadcastReceiver? = null
    private var crashDetector: CrashDetector? = null

    // ========== 时间戳（冷却用） ==========
    private val steps = mutableListOf<Long>()
    private var lastStepTs = 0L
    private var lastIdRemindTs = 0L
    private var lastSitRemindTs = 0L
    private var lastTriggerTs = 0L
    private var lastBatteryWarnTs = 0L
    private var verifyStartTs = 0L
    private var batteryReceiverRegistered = false
    /** 防止重复初始化（重复注册广播接收器 = 一条提醒弹两次） */
    @Volatile
    private var inited = false
    // 配置缓存时间戳：避免每 2 秒跨进程查一次 Provider 把应用进程反复唤醒（耗电）
    private var lastConfigTs = 0L

    // ========== 常量 ==========
    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L        // 5分钟滑动窗口
        const val TRIGGER_COOLDOWN = 5 * 60 * 1000L // 触发后5分钟冷却
        const val VERIFY_TIMEOUT_MS = 6_000L        // VERIFYING 超时兜底
        const val SIT_REMIND_MS = 2 * 60 * 60 * 1000L  // 久坐2小时
        const val DAILY_COOLDOWN = 24 * 60 * 60 * 1000L // 每日冷却
        const val BATTERY_COOLDOWN = 60 * 60 * 1000L   // 电量1小时冷却
        const val NIGHT_START_HOUR = 22  // 夜间开始
        const val NIGHT_END_HOUR = 6      // 夜间结束
        /** App 手动点「预览」后发来的标记：Hook 据此暂缓「停下就收」半分钟 */
        const val ACTION_PREVIEW_MANUAL = "io.github.wumisc.safewalk.PREVIEW_MANUAL"

        /** 重启系统界面：App 发指令，Hook 收到后自杀，由 system_server 拉起新进程 */
        const val ACTION_RESTART_SYSTEMUI = "io.github.wumisc.safewalk.RESTART_SYSTEMUI"
        const val ACTION_RESTART_ACK = "io.github.wumisc.safewalk.RESTART_ACK"
        const val EXTRA_RESTART_TOKEN = "token"
        const val RESTART_TOKEN = "safewalk-restart-2.4"
        const val APP_PKG = "io.github.wumisc.safewalk"

        /** 诊断日志：统一走 DiagLog（logcat + 用户可见文件，多级降级落盘） */
        fun diag(msg: String) {
            DiagLog.log(msg)
        }

    }

    // ========== 配置读取（统一入口） ==========

    /** 拉取配置，默认 60 秒内复用缓存；force=true 强制刷新 */
    private fun refreshConfig(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // 配置改动会通过广播即时推过来（见 configChangedReceiver），
        // 这里的 60 秒轮询只是兜底：原来 10 秒一次，等于每 10 秒跨进程查一次
        // Provider，会把已经被系统回收的应用进程重新拉起来，纯属自己耗自己的电
        if (!force && configJson != null && now - lastConfigTs < 60_000L) return
        // 读失败时保留上一份配置，别把已有开关全部重置成默认值
        SafeWalkConfig.loadConfig(ctx)?.let { configJson = it }
        lastConfigTs = now
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
    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        Log.i("SafeWalk", "===== Hook 加载到进程: ${lp.packageName} =====")
        diag("===== Hook 加载到进程: ${lp.packageName} =====")
        // Hook系统界面 SystemUI，和以前好用的版本一致
        if (lp.packageName != "com.android.systemui") {
            diag("不是SystemUI，跳过: ${lp.packageName}")
            return
        }
        Log.i("SafeWalk", "成功挂载至 SystemUI【系统界面】进程")
        diag("成功挂载至 SystemUI【系统界面】进程")

        // 和最好版一致：Hook SystemUIApplication.onCreate，在onCreate里初始化
        try {
            val appCls = de.robv.android.xposed.XposedHelpers.findClassIfExists(
                "com.android.systemui.SystemUIApplication",
                lp.classLoader
            )
            if (appCls != null) {
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    appCls, "onCreate",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val appCtx = param.thisObject as? Context ?: return
                            Log.i("SafeWalk", "SystemUIApplication.onCreate 触发，开始初始化")
                            init(appCtx)
                        }
                    }
                )
                Log.i("SafeWalk", "Hook SystemUIApplication.onCreate 成功")
            } else {
                Log.e("SafeWalk", "找不到 SystemUIApplication 类")
            }
        } catch (e: Exception) {
            Log.e("SafeWalk", "Hook SystemUIApplication 失败", e)
        }
    }

    private fun init(appCtx: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            // 防御性：个别 ROM 会重复走 SystemUIApplication.onCreate，
            // 重复初始化会把广播接收器注册两遍，一条提醒变两条
            if (inited) {
                diag("init 已执行过，跳过重复初始化")
                return@postDelayed
            }
            inited = true
            try {
                ctx = appCtx.applicationContext
                DiagLog.init(ctx!!)
                // 读配置JSON文件（新版方案，不用XSharedPreferences）
                configJson = SafeWalkConfig.loadConfig(ctx)
                nm = SafeWalkNotificationManager(ctx!!)
                sm = ctx!!.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                // 车祸预警：总开关+车祸开关都开着才启动持续加速度监听
                updateCrashDetection()
                // 预览已搬到 App 进程（WalkPreviewService）：Hook 这里只做步态判定，
                // 判定结果通过广播指挥 App 显示/收起，本进程不再持有相机

                // 初始化手电筒通知模块
                createTorchNotifyChannel(ctx!!)
                torchNotifyManager = TorchNotificationManager(ctx!!)
                torchNotifyManager?.startMonitor()
                // 阈值直接从 Hook 已缓存的配置读，不再单独开一条 60 秒的跨进程轮询
                torchReminder = TorchStaticReminder(torchNotifyManager!!, ctx!!) {
                    (prefFloat(SafeWalkPrefKey.torchStaticMinutes, 20f) * 60_000L).toLong()
                }

                // 在系统进程里注册手电动作接收器（系统进程有权限访问摄像头）
                val torchReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        intent ?: return
                        // 与 RESTART / PREVIEW 保持一致：这个接收器为了跨进程是 EXPORTED 的，
                        // 没令牌的话任何应用发一条 OPEN_TORCH 就能把闪光灯点亮且不关
                        if (intent.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                            != SafeWalkPrefKey.BROADCAST_TOKEN
                        ) {
                            Log.w("SafeWalk", "手电广播令牌不匹配，忽略")
                            return
                        }
                        Log.i("SafeWalk", "收到手电广播: ${intent.action}")
                        when (intent.action) {
                            "io.github.wumisc.safewalk.OPEN_TORCH" -> {
                                val result = TorchHelper.setTorch(ctx!!, true)
                                Log.i("SafeWalk", "开启手电结果: $result")
                            }
                            "io.github.wumisc.safewalk.CLOSE_TORCH" -> {
                                val result = TorchHelper.setTorch(ctx!!, false)
                                Log.i("SafeWalk", "关闭手电结果: $result")
                            }
                        }
                    }
                }
                torchActionReceiver = torchReceiver
                val filter = IntentFilter().apply {
                    addAction("io.github.wumisc.safewalk.OPEN_TORCH")
                    addAction("io.github.wumisc.safewalk.CLOSE_TORCH")
                }
                // 三参数 registerReceiver 是 API 33 才有的重载，Android 11/12 上会 NoSuchMethodError
                ReceiverCompat.registerExported(ctx!!, torchReceiver, filter)
                Log.i("SafeWalk", "手电广播接收器注册成功")

                // 手动预览标记：App 点「预览」后暂缓自动模式的「停下就收」
                val manualFilter = IntentFilter(ACTION_PREVIEW_MANUAL)
                ReceiverCompat.registerExported(ctx!!, manualPreviewReceiver, manualFilter)

                // 重启系统界面：App 发指令 → 回执 → 自杀，由 system_server 拉起新进程
                val restartFilter = IntentFilter(ACTION_RESTART_SYSTEMUI)
                ReceiverCompat.registerExported(ctx!!, restartReceiver, restartFilter)

                // 配置变更：App 写完配置主动推过来，改完开关立刻生效（轮询只是兜底）
                val configFilter = IntentFilter(SafeWalkPrefKey.ACTION_CONFIG_CHANGED)
                ReceiverCompat.registerExported(ctx!!, configChangedReceiver, configFilter)

                // 日志回传：App 启动时来要一份本进程日志，由 App 统一落盘。
                // SystemUI 自己的外部目录是 Android/data/com.android.systemui/…，
                // 用户根本进不去，只有回传到 App 才看得到。
                val logFilter = IntentFilter(DiagLog.ACTION_REQUEST_LOG)
                ReceiverCompat.registerExported(ctx!!, logRequestReceiver, logFilter)

                // 预览实际状态回执：App 侧手动开/关过预览后，本进程的
                // lastSentShow 必须跟着改，否则「去重」会把下一次显示指令吞掉
                val previewStateFilter = IntentFilter(WalkPreviewService.ACTION_PREVIEW_STATE)
                ReceiverCompat.registerExported(ctx!!, previewStateReceiver, previewStateFilter)

                // 注册计步器
                sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                    sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }

                // 注册广播接收器
                if (!batteryReceiverRegistered) {
                    registerBatteryReceiver()
                    batteryReceiverRegistered = true
                }

                // 定时轮询（对齐2.0最好版：开启时2秒，关闭时5秒）
                Handler(Looper.getMainLooper()).postDelayed(tickRunnable, 2_000L)
            } catch (t: Throwable) {
                // 之前这里静默吞异常，初始化失败时日志里什么都看不到
                Log.e("SafeWalk", "初始化失败", t)
                diag("❌ 初始化失败: $t")
            }
        }, 2_000L)
    }

    // ========== 传感器回调 ==========
    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            val now = System.currentTimeMillis()
            lastStepTs = now
            steps.removeAll { it < now - WINDOW_MS }
            steps.add(now)
            walking = true
            if (state == State.SLEEP) state = State.LIGHT_MONITOR
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ========== 定时检查 ==========
    private fun check() {
        try {
            val now = System.currentTimeMillis()
            refreshConfig()
            Log.d("SafeWalk", "check() 定时轮询执行")

            if (!masterEnabled()) {
                // 总开关关闭期间也要把「静置计时」按下去，
                // 否则关了一整天再打开，第一轮就会判定「已静置 N 小时」直接弹提醒
                torchReminder?.onLongStaticDetected(false)
                return
            }

            // VERIFYING 超时兜底
            if (state == State.VERIFYING && now - verifyStartTs > VERIFY_TIMEOUT_MS) {
                state = State.LIGHT_MONITOR
                steps.clear()
            }

            // 静置重置：无持续位移 → 判定室内停留，不触发外出提醒
            if (lastStepTs == 0L || now - lastStepTs > staticResetMs()) {
                steps.clear()
                walking = false
                checkSitRemind(now)
                state = State.SLEEP
                // 通知手电筒提醒器：现在是静置状态
                torchReminder?.onLongStaticDetected(true)
                return
            }

            // 有步数 → 行走中
            walking = true
            torchReminder?.onLongStaticDetected(false)

            steps.removeAll { it < now - WINDOW_MS }

            // 触发冷却
            if (now - lastTriggerTs < TRIGGER_COOLDOWN) return

            // 步数达标 → 开始步态校验
            if (steps.size >= stepThreshold() && state == State.LIGHT_MONITOR) {
                state = State.VERIFYING
                verifyStartTs = now
                verifyGait()
            }
        } catch (e: Exception) {
            Log.e("SafeWalk", "check() 异常", e)
        }
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
                nm?.sitRemind()
                lastSitRemindTs = now
            }
        }
    }

    // ========== 步态校验 ==========
    private val gait = GaitDetector()

    private fun verifyGait() {
        val s = sm ?: return
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            state = State.LIGHT_MONITOR; return
        }
        gait.reset()
        // 步态验证采样3秒：步频1.4~2.2Hz，3秒足够检测到3步以上
        val end = System.currentTimeMillis() + 3000
        val handler = Handler(Looper.getMainLooper())

        var finished = false
        lateinit var listener: SensorEventListener

        fun finish() {
            if (finished) return
            finished = true
            try { s.unregisterListener(listener) } catch (_: Throwable) {}
            handler.removeCallbacksAndMessages(null)
            if (gait.hasStableGait()) triggerMovement()
            else {
                state = State.LIGHT_MONITOR
                steps.clear()
            }
        }

        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null || finished) return
                gait.feedAcc(e.values[0], e.values[1], e.values[2])
                if (System.currentTimeMillis() > end) finish()
            }
            override fun onAccuracyChanged(sensor: Sensor?, a: Int) {}
        }

        try {
            s.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME)
            // 兜底：手机静止时加速度计仍会回调，但部分 ROM 会省电停采，
            // 若 3.5 秒内一次回调都没有，监听器会永久挂着（耗电 + 状态卡死）
            handler.postDelayed({ finish() }, 3500L)
        } catch (t: Throwable) {
            finished = true
            state = State.LIGHT_MONITOR
            diag("步态验证启动失败: $t")
        }
    }

    /** 确认持续步行 → 触发所有外出提醒 */
    private fun triggerMovement() {
        refreshConfig(force = true)
        val now = System.currentTimeMillis()
        lastTriggerTs = now
        steps.clear()
        state = State.SLEEP

        // 1. 证件提醒（每日最多1次）
        if (pref(SafeWalkPrefKey.enableIdReminder, true) && now - lastIdRemindTs > DAILY_COOLDOWN) {
            nm?.idReminder()
            lastIdRemindTs = now
        }

        // 2. 社交礼仪提示（4 种出行模式都有模板，原先只有约会/聚会两个分支）
        if (pref(SafeWalkPrefKey.enableSocialTipCard, true)) {
            val mode = pref(SafeWalkPrefKey.travelMode, "求职")
            val tips = TravelTemplate.getTemplate(mode)
            if (tips.isNotEmpty()) {
                nm?.socialTip("【$mode】" + tips.take(3).joinToString("；"))
            }
        }

        // 3. 夜间手电提醒
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
        if (isNightTime() && screenOn && !rearCamShowing
                && pref(SafeWalkPrefKey.enableNightTorch, true)) {
            nm?.flashlightSuggest()
        }

        // 4. 行走模式由 updateOverlay 统一驱动，这里只保证行走状态为真，
        //    避免「这里 show、下一轮 tick 又 hide」的自相矛盾
        walking = true
    }

    private fun isNightTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
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
                            nm?.batteryWarning()
                            lastBatteryWarnTs = now
                        }
                    }
                }
            }
            ctx?.let {
                ReceiverCompat.registerNotExported(it, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        } catch (_: Throwable) {}
    }

    // ========== 定时轮询：单一入口 ==========
    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                // check() 负责步态与提醒；updateOverlay() 只负责悬浮窗。
                // 之前 tick 里漏掉 check()，导致整套提醒逻辑从未被执行过。
                check()
                updateOverlay()
            } catch (t: Throwable) {
                diag("tick: $t")
            }
            val interval = if (masterEnabled()) 2_000L else 5_000L
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

    private fun updateOverlay() {
        if (!masterEnabled() || !pref(SafeWalkPrefKey.enableRearCamera, false)) {
            previewUntil = 0L
            sendOverlayCmd(false)
            return
        }

        // 前台是相机应用时暂时让位，否则悬浮窗会挡住取景
        if (cameraInForeground()) {
            // 同时作废预览窗口：否则用户开着预览去拍了张照，回来时
            // 预览期还没过，updateOverlay 直接 return，悬浮窗就「再也不回来」了
            previewUntil = 0L
            sendOverlayCmd(false)
            return
        }

        // 手动预览期优先：调透明度/位置/形态时不被自动模式立刻收掉
        if (System.currentTimeMillis() < previewUntil) return

        val auto = pref(SafeWalkPrefKey.rearCamAuto, true)
        sendOverlayCmd(!auto || walking)
    }

    private fun sendOverlayCmd(show: Boolean) {
        if (lastSentShow == show) return
        lastSentShow = show
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

    // 相机前台检测节流：每 5 秒最多反射查一次，避免高频调用 system_server
    private var lastCameraCheckTs = 0L
    private var cameraInFront = false

    private fun cameraInForeground(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCameraCheckTs < 5_000L) return cameraInFront
        lastCameraCheckTs = now
        cameraInFront = isCameraAppInForeground()
        return cameraInFront
    }

    /** 反射查前台是否相机类应用（原在 RearCameraOverlay 上，相机搬走后逻辑收编到这里） */
    private fun isCameraAppInForeground(): Boolean {
        return try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getInstance").invoke(null)
            val info = atmClass.getMethod("getFocusedRootTaskInfo").invoke(atm) ?: return false
            val topActivity = info.javaClass.getField("topActivity").get(info) ?: return false
            val pkg = topActivity.javaClass.getField("packageName").get(topActivity) as? String
                ?: return false
            if (CAMERA_PACKAGES.contains(pkg)) return true
            pkg.contains("camera", ignoreCase = true) && !pkg.contains("safewalk", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private val CAMERA_PACKAGES = setOf(
        "com.android.camera", "com.huawei.camera", "com.miui.camera",
        "com.xiaomi.camera", "com.oplus.camera", "com.vivo.camera",
        "com.samsung.android.camera", "com.oneplus.camera",
        "com.google.android.GoogleCamera"
    )}
