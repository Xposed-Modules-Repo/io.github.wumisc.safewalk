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
 * 仅 Hook 系统进程 android。
 *
 * 状态机：SLEEP → LIGHT_MONITOR（有步数）→ VERIFYING（步态校验）→ 触发提醒 → SLEEP
 */
class SafeWalkLSPHook : IXposedHookLoadPackage, SensorEventListener {

    private enum class State { SLEEP, LIGHT_MONITOR, VERIFYING }

    // ========== 运行时状态 ==========
    private var state = State.SLEEP
    private var configJson: org.json.JSONObject? = null
    private var nm: SafeWalkNotificationManager? = null
    private var sm: SensorManager? = null
    private var ctx: Context? = null
    private var rearCam: RearCameraOverlay? = null
    private var torchNotifyManager: TorchNotificationManager? = null
    private var torchReminder: TorchStaticReminder? = null
    private var torchActionReceiver: BroadcastReceiver? = null

    // ========== 时间戳（冷却用） ==========
    private val steps = mutableListOf<Long>()
    private var lastStepTs = 0L
    private var lastIdRemindTs = 0L
    private var lastSitRemindTs = 0L
    private var lastTriggerTs = 0L
    private var lastBatteryWarnTs = 0L
    private var verifyStartTs = 0L
    private var batteryReceiverRegistered = false

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

        /** 手电筒广播 Action */
        const val ACTION_TORCH_TOGGLE = "io.github.wumisc.safewalk.TORCH_TOGGLE"

        /** 写文件日志，不用logcat也能看 */
        fun diag(msg: String) {
            try {
                val line = "${System.currentTimeMillis() / 1000} [safe] $msg\n"
                val fos = java.io.FileOutputStream("/data/local/tmp/safewalk_diag.log", true)
                fos.write(line.toByteArray())
                fos.close()
            } catch (_: Throwable) {}
        }
    }

    // ========== 配置读取（统一入口） ==========
    private fun pref(key: String, default: Boolean): Boolean = configJson?.optBoolean(key, default) ?: default
    private fun pref(key: String, default: Int): Int = configJson?.optInt(key, default) ?: default
    private fun pref(key: String, default: String): String = configJson?.optString(key, default) ?: default

    private fun masterEnabled() = pref(SafeWalkPrefKey.masterEnable, true)
    // 参数调整：步数阈值150，静置重置20秒
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
            try {
                ctx = appCtx.applicationContext
                // 读配置JSON文件（新版方案，不用XSharedPreferences）
                configJson = SafeWalkConfig.loadConfigFromFile()
                nm = SafeWalkNotificationManager(ctx!!)
                sm = ctx!!.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                rearCam = RearCameraOverlay(ctx!!)

                // 初始化手电筒通知模块
                createTorchNotifyChannel(ctx!!)
                torchNotifyManager = TorchNotificationManager(ctx!!)
                torchNotifyManager?.startMonitor()
                torchReminder = TorchStaticReminder(torchNotifyManager!!, ctx!!)

                // 在系统进程里注册手电动作接收器（系统进程有权限访问摄像头）
                torchActionReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        intent ?: return
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
                val filter = IntentFilter().apply {
                    addAction("io.github.wumisc.safewalk.OPEN_TORCH")
                    addAction("io.github.wumisc.safewalk.CLOSE_TORCH")
                }
                ctx!!.registerReceiver(torchActionReceiver, filter, Context.RECEIVER_EXPORTED)
                Log.i("SafeWalk", "手电广播接收器注册成功")

                // 注册计步器
                sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                    sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }

                // 注册广播接收器
                if (!batteryReceiverRegistered) {
                    registerBatteryReceiver()
                    registerTorchReceiver()
                    batteryReceiverRegistered = true
                }

                // 定时检查循环
                Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        try { check() } catch (_: Throwable) {}
                        Handler(Looper.getMainLooper()).postDelayed(this, 15_000L)
                    }
                }, 5_000L)
            } catch (_: Throwable) {}
        }, 2_000L)
    }

    // ========== 传感器回调 ==========
    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            val now = System.currentTimeMillis()
            lastStepTs = now
            steps.removeAll { it < now - WINDOW_MS }
            steps.add(now)
            if (state == State.SLEEP) state = State.LIGHT_MONITOR
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ========== 定时检查 ==========
    private fun check() {
        try {
            val now = System.currentTimeMillis()
            configJson = SafeWalkConfig.loadConfigFromFile()
            Log.d("SafeWalk", "check() 定时轮询执行, rearCam=null? ${rearCam == null}")

            // ===== 后置看路测试指令处理（不管总开关开没开都能测试） =====
            val testAction = pref(SafeWalkPrefKey.testRearCamAction, "")
            Log.d("SafeWalk", "读取到测试指令: '$testAction'")
            if (testAction.isNotEmpty()) {
                Log.i("SafeWalk", "执行后置看路测试指令: $testAction")
                when (testAction) {
                    "open_full" -> rearCam?.show(RearCameraOverlay.MODE_FULLSCREEN)
                    "open_small" -> rearCam?.show(RearCameraOverlay.MODE_SMALLWINDOW)
                    "close" -> rearCam?.hide()
                }
                // 执行完清空指令
                clearTestRearCamAction()
            }

            if (!masterEnabled()) return

            // VERIFYING 超时兜底
            if (state == State.VERIFYING && now - verifyStartTs > VERIFY_TIMEOUT_MS) {
                state = State.LIGHT_MONITOR
                steps.clear()
            }

            // 静置重置：无持续位移 → 判定室内停留，不触发外出提醒
            if (lastStepTs == 0L || now - lastStepTs > staticResetMs()) {
                steps.clear()
                checkSitRemind(now)
                // 自动模式：停下自动收起后置看路
                if (pref(SafeWalkPrefKey.enableRearCamera, false)
                    && pref(SafeWalkPrefKey.rearCamAuto, true)) {
                    rearCam?.hide()
                }
                state = State.SLEEP
                // 通知手电筒提醒器：现在是静置状态
                torchReminder?.onLongStaticDetected(true)
                return
            }

            // 有步数 → 不是静置，通知手电筒提醒器
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

    /** 清空后置看路测试指令（Hook端写回JSON） */
    private fun clearTestRearCamAction() {
        try {
            val path = "/data/data/io.github.wumisc.safewalk/files/${SafeWalkPrefKey.CONFIG_FILE}"
            val f = java.io.File(path)
            if (!f.exists()) return
            val json = org.json.JSONObject(f.readText())
            json.remove(SafeWalkPrefKey.testRearCamAction)
            f.writeText(json.toString())
        } catch (e: Exception) {
            Log.w("SafeWalk", "清空测试指令失败", e)
        }
    }

    private fun checkSitRemind(now: Long) {
        if (lastStepTs == 0L) return
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

        s.registerListener(object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null) return
                gait.feedAcc(e.values[0], e.values[1], e.values[2])
                if (System.currentTimeMillis() > end) {
                    s.unregisterListener(this)
                    if (gait.hasStableGait()) triggerMovement()
                    else { state = State.LIGHT_MONITOR; steps.clear() }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    /** 确认持续步行 → 触发所有外出提醒 */
    private fun triggerMovement() {
        configJson = SafeWalkConfig.loadConfigFromFile()
        val now = System.currentTimeMillis()
        lastTriggerTs = now
        steps.clear()
        state = State.SLEEP

        // 1. 证件提醒（每日最多1次）
        if (pref(SafeWalkPrefKey.enableIdReminder, true) && now - lastIdRemindTs > DAILY_COOLDOWN) {
            nm?.idReminder()
            lastIdRemindTs = now
        }

        // 2. 社交礼仪提示（按出行模式）
        if (pref(SafeWalkPrefKey.enableSocialTipCard, true)) {
            when (pref(SafeWalkPrefKey.travelMode, "求职")) {
                "约会" -> nm?.socialTip("守时，不打探隐私，提前商量消费方式")
                "聚会" -> nm?.socialTip("不强行劝酒，AA制，离场打招呼")
            }
        }

        // 3. 夜间手电提醒
        if (isNightTime() && pref(SafeWalkPrefKey.enableNightTorch, true)) {
            nm?.flashlightSuggest()
        }

        // 4. 后置看路：自动模式下，确认持续步行后自动打开
        if (pref(SafeWalkPrefKey.enableRearCamera, false)
            && pref(SafeWalkPrefKey.rearCamAuto, true)) {
            val mode = pref(SafeWalkPrefKey.rearCamMode, RearCameraOverlay.MODE_FULLSCREEN)
            rearCam?.show(mode)
        }
    }

    private fun isNightTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    // ========== 工具：反射拿系统Context ==========
    private fun getSystemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) { null }
    }

    // ========== 电量广播接收器 ==========
    private fun registerBatteryReceiver() {
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    configJson = SafeWalkConfig.loadConfigFromFile()
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
            ctx?.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {}
    }

    // ========== 手电筒广播接收器 ==========
    private var torchOn = false
    private fun registerTorchReceiver() {
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    try {
                        android.util.Log.i("SafeWalk", "手电广播收到")
                        toggleTorch(ctx ?: return)
                    } catch (t: Throwable) { android.util.Log.e("SafeWalk", "手电失败", t) }
                }
            }
            val filter = IntentFilter(ACTION_TORCH_TOGGLE)
            // 仅本模块自用广播，RECEIVER_NOT_EXPORTED 更安全稳定
            ctx?.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {}
    }

    /** 切换手电筒：找到首个带闪光灯的摄像头，切换 torchOn 状态 */
    private fun toggleTorch(context: Context) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager ?: return
        val flashId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)[android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
        } ?: run {
            android.util.Log.w("SafeWalk", "无闪光灯")
            return
        }
        torchOn = !torchOn
        cm.setTorchMode(flashId, torchOn)
        android.util.Log.i("SafeWalk", "手电筒: $torchOn")
    }
}
