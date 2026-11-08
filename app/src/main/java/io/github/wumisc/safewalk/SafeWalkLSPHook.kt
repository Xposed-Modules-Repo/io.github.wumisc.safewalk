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
 * 状态机：SLEEP → LIGHT_MONITOR（有步数）→ 步数达标即触发提醒 → SLEEP
 * （2.9.4 去掉了中间的 VERIFYING 关卡：它要求用户走出教科书级别的稳定步频，
 *   真机上几乎通不过，结果就是「走了半天不提醒」）
 */
class SafeWalkLSPHook : IXposedHookLoadPackage, SensorEventListener {

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
    private var configJson: org.json.JSONObject? = null
    private var nm: SafeWalkNotificationManager? = null
    private var sm: SensorManager? = null
    private var ctx: Context? = null
    private var torchNotifyManager: TorchNotificationManager? = null
    private var torchReminder: TorchStaticReminder? = null
    private var torchActionReceiver: BroadcastReceiver? = null
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
    private var lastIdRemindTs = 0L
    private var lastSitRemindTs = 0L
    private var lastTriggerTs = 0L
    private var lastBatteryWarnTs = 0L
    private var batteryReceiverRegistered = false
    /** 防止重复初始化（重复注册广播接收器 = 一条提醒弹两次） */
    @Volatile
    private var inited = false
    // 配置缓存时间戳：避免每 2 秒跨进程查一次 Provider 把应用进程反复唤醒（耗电）
    private var lastConfigTs = 0L

    // ========== 行走模式「开机自启」==========
    /**
     * 本 SystemUI 进程内是否已经主动拉起过 AutoStartActivity。
     *
     * WalkPreviewService 是 camera 类型前台服务，Android 14+ 禁止从后台直接启动
     * （CAMERA 是 while-in-use 权限），Android 15 又禁止 BOOT_COMPLETED 启动 camera FGS。
     * 唯一稳定豁免是「启动瞬间本应用有可见 Activity」，所以由 Hook 拉起一个透明页
     * 代启服务。这里保证每个进程只拉一次，避免 tick 每 2 秒闪一下透明窗。
     */
    private var autoStartLaunched = false
    /** 上次尝试拉起的时间戳：失败后按 60 秒冷却重试 */
    private var lastAutoStartTry = 0L

    // ========== 计步诊断状态（「走了半天没反应」时必须能从日志看出卡在哪） ==========
    /** 收到的步数事件总数（系统计步器 + 兜底合计） */
    private var stepEventCount = 0
    /** 系统计步器是否注册成功 */
    private var stepSensorRegistered = false
    /** 加速度计兜底是否启用 */
    private var fallbackActive = false
    /** init 完成时间，用于判断「注册成功却长时间零事件」 */
    private var initTs = 0L
    private var lastStepLogTs = 0L
    private var lastSummaryTs = 0L
    /** 上次尝试拉起兜底计步的时间 */
    private var lastFallbackTryTs = 0L
    /** 上次「零步数」探针的时间 */
    private var lastStepProbeTs = 0L

    // 加速度计计步的运行时状态（算法本身在 GaitDetector 里，这里只持有实例）
    private var fbListener: SensorEventListener? = null
    private var fbDetector: GaitDetector? = null

    // ========== 常量 ==========
    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L        // 5分钟滑动窗口
        const val TRIGGER_COOLDOWN = 5 * 60 * 1000L // 触发后5分钟冷却
        const val SIT_REMIND_MS = 2 * 60 * 60 * 1000L  // 久坐2小时
        const val DAILY_COOLDOWN = 24 * 60 * 60 * 1000L // 每日冷却
        const val BATTERY_COOLDOWN = 60 * 60 * 1000L   // 电量1小时冷却
        const val NIGHT_START_HOUR = 22  // 夜间开始
        const val NIGHT_END_HOUR = 6      // 夜间结束

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

        /** 诊断日志：统一走 DiagLog（logcat + 用户可见文件，多级降级落盘）。
         *  是否落盘由 refreshConfig 同步到 DiagLog.enabled 控制（companion 里读不到实例配置） */
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
        // 同步日志总开关：关了就不再写日志文件，省 I/O 省电
        DiagLog.enabled = pref(SafeWalkPrefKey.enableDiagLog, true)
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
                // 环境光监测：夜间自动开灯前判断「太黑才亮」
                startLightMonitor()
                // 预览已搬到 App 进程（WalkPreviewService）：Hook 这里只做步态判定，
                // 判定结果通过广播指挥 App 显示/收起，本进程不再持有相机

                // 初始化手电筒通知模块
                createTorchNotifyChannel(ctx!!)
                torchNotifyManager = TorchNotificationManager(ctx!!)
                torchNotifyManager?.startMonitor()
                // 阈值直接从 Hook 已缓存的配置读，不再单独开一条 60 秒的跨进程轮询
                torchReminder = TorchStaticReminder(torchNotifyManager!!, ctx!!) {
                    (prefFloat(SafeWalkPrefKey.torchStaticMinutes, 12f) * 60_000L).toLong()
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

                // 注册计步器（含失败检测与加速度计兜底）
                registerStepDetector()
                // 加速度计计步恒定开启，和系统计步器并行工作。
                //
                // 以前是「系统计步器失败、或 5 分钟零事件」才启用兜底，
                // 而这台设备上 SystemUI 进程注册计步器「成功」却一步都不回
                // （ACTIVITY_RECOGNITION 是运行时权限，模块挂在系统界面进程里拿不到，
                //  传感器在、注册也不报错，就是永远没有回调）。
                // 结果：走 5 分钟内一步都记不到，用户看到的就是「走半天没反应」。
                // 现在两条腿走路，recordStep 里按 300ms 去重，谁先报算谁的。
                startAccFallback("恒定启用（与系统计步器并行，按 300ms 去重）")

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
            diag("🚶 计步中：窗口 ${steps.size}/${stepThreshold()} 步，状态=$state，来源=${if (fallbackActive) "加速度计" else "系统计步器"}")
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
                val now = System.currentTimeMillis()
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
                return
            }

            // 兜底计步意外停了（注册抛异常 / 被系统注销）就补回来。
            // 它是现在的主计步来源，悄悄没了等于整个提醒系统瞎掉，
            // 而表现和「没在走路」完全一样，不查日志根本发现不了。
            if (!fallbackActive && now - lastFallbackTryTs > 30_000L) {
                lastFallbackTryTs = now
                startAccFallback("兜底未运行，自动恢复")
            }

            // 步数探针：系统计步器「注册成功但一步都不报」是这台设备最常见的一种
            // 静默失效（SystemUI 没有 ACTIVITY_RECOGNITION 时注册不抛异常，只是永远没回调）。
            // 不主动探一次，日志里只有「计步器已注册」，然后用户走半天一个字都没有。
            if (stepEventCount == 0 && initTs != 0L && now - initTs > STEP_PROBE_WINDOW_MS
                && now - lastStepProbeTs > STEP_PROBE_WINDOW_MS
            ) {
                lastStepProbeTs = now
                diag("❌ 启动 ${(now - initTs) / 1000}s 仍未收到任何步数事件"
                        + " | 系统计步器注册=$stepSensorRegistered 加速度计兜底=$fallbackActive"
                        + " | 先到「功能实验区 → 步态自检」确认传感器本身有没有数据")
            }

            // 每 2 分钟一条状态摘要：走没走、卡在哪一环，看日志一眼能定位
            if (now - lastSummaryTs > 120_000L) {
                lastSummaryTs = now
                val gap = if (lastStepTs == 0L) "从未" else "${(now - lastStepTs) / 1000}s 前"
                diag("📊 状态=$state 窗口步数=${steps.size}/${stepThreshold()} 上一步=$gap 计步来源=${stepSource()} 累计=$stepEventCount")
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
                return
            }

            // 有步数 → 行走中
            walking = true
            torchReminder?.onLongStaticDetected(false)

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
                nm?.sitRemind()
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
        var finished = false
        lateinit var listener: SensorEventListener

        fun finish() {
            if (finished) return
            finished = true
            gaitSampling = false
            try { s.unregisterListener(listener) } catch (_: Throwable) {}
            handler.removeCallbacksAndMessages(null)
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

        try {
            s.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME)
            // 部分 ROM 在省电模式下会停采，4.6 秒兜底收尾，别让监听器永久挂着
            handler.postDelayed({ finish() }, 4_600L)
        } catch (t: Throwable) {
            finished = true
            gaitSampling = false
            diag("步态复核启动失败: $t")
        }
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

        // 1. 证件提醒（每日最多1次）
        if (pref(SafeWalkPrefKey.enableIdReminder, true) && now - lastIdRemindTs > DAILY_COOLDOWN) {
            nm?.let { it.idReminder(); fired.add("证件") }
            lastIdRemindTs = now
        }

        // 2. 社交礼仪提示（4 种出行模式都有模板，原先只有约会/聚会两个分支）
        if (pref(SafeWalkPrefKey.enableSocialTipCard, true)) {
            val mode = pref(SafeWalkPrefKey.travelMode, "求职")
            val tips = TravelTemplate.getTemplate(mode)
            if (tips.isNotEmpty()) {
                nm?.let {
                    it.socialTip("【$mode】" + tips.take(3).joinToString("；"))
                    fired.add("社交")
                }
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
        if (isNightTime() && screenOn && !rearCamShowing) {
            // 夜间自动开灯：先看环境光，暗光(默认 <15 lux)才直接点亮，亮着不开。
            // luxIsDark() 在「无光线传感器 / 读数过旧」时返回 false，自动开灯不生效，
            // 这时下面再按旧逻辑发一条「夜间照明」提醒，不会两头落空。
            if (pref(SafeWalkPrefKey.enableNightTorchAuto, false) && luxIsDark()) {
                diag("[torch] 夜间暗光(%.1f lux < %.0f) → 自动开灯"
                    .format(lastLux, luxDarkThreshold))
                releasePreviewForTorch()
                setTorchWithRetry(true, 0)
                fired.add("夜间自动开灯")
            } else if (pref(SafeWalkPrefKey.enableNightTorch, true)) {
                nm?.let {
                    it.flashlightSuggest()
                    fired.add("夜间手电")
                }
            }
        }

        // 4. 行走模式由 updateOverlay 统一驱动，这里只保证行走状态为真，
        //    避免「这里 show、下一轮 tick 又 hide」的自相矛盾
        walking = true

        // 这一段是「到底有没有触发」的唯一证据。
        // 以前触发后一个字都不写，用户说「没反应」时无法区分
        // 「没走到 50 步」和「走到了但通知没弹出来」——两者处理方式完全不同。
        if (fired.isEmpty()) {
            diag("✅ 判定为持续步行（窗口 $counted 步），但三条提醒的开关都关着，没有可发的通知")
        } else {
            diag("✅ 判定为持续步行（窗口 $counted 步）→ 已发提醒：${fired.joinToString("、")}")
        }
    }

    private fun isNightTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val start = pref(SafeWalkPrefKey.nightTorchStartHour, NIGHT_START_HOUR)
        val end = pref(SafeWalkPrefKey.nightTorchEndHour, NIGHT_END_HOUR)
        return hour >= start || hour < end
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

        // 手电正占着摄像头：此刻拉起预览只会得到黑屏，还会和手电抢设备。
        // 判据有两个：刚点灯后的 60 秒冷静期，以及 TorchCallback 报告的实况
        // （用户从控制中心点亮的手电也要算进来）。
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

        // 配置允许显示 → 先确保预览服务活着。这样开机/重启 SystemUI 之后，
        // 用户不必再打开 App 手动拨一次「行走模式」开关。
        maybeAutoStartPreview()
        sendOverlayCmd(true)
    }

    /**
     * 确保「行走模式」开机后无需手动拨开关即可就绪。
     *
     * 链路：Hook(SystemUI，系统进程) --startActivity--> AutoStartActivity(透明)
     *       --startForegroundService--> WalkPreviewService。
     * 透明页存在的那一瞬，本应用处于可见状态，camera 类型 FGS 的启动才合法
     * （Android 14+ while-in-use 限制 / Android 15 BOOT_COMPLETED 限制都拦不住
     *  "有可见 Activity" 这条豁免）。透明页自己 400ms 后 finish，用户基本无感。
     *
     * 只在「亮屏 + 已解锁」时尝试：锁屏时 startActivity 会被拦，透明窗也可能
     * 压在锁屏上。每个进程最多成功一次；失败按 60 秒冷却重试。
     */
    private fun maybeAutoStartPreview() {
        if (autoStartLaunched) return
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        if (now - lastAutoStartTry < 60_000L) return

        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isInteractive != true) return
            val km = c.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (km?.isKeyguardLocked == true) return
        } catch (_: Throwable) {
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
            }
            c.startActivity(i)
            autoStartLaunched = true
            diag("[walk] 已拉起透明自启页，代启预览服务（开机免手动开关）")
        } catch (t: Throwable) {
            // 不置位，60 秒后重试（例如解锁动画期间 startActivity 被拦）
            diag("[walk] 拉起自启页失败，稍后重试: $t")
        }
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

    // ========== 手电（系统进程侧） ==========

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
