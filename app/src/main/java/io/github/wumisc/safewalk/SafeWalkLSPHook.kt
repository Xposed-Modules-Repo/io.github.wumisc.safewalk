package io.github.wumisc.safewalk

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * SafeWalk LSP 低功耗状态机 + 步态判定。
 * 仅 Hook 系统进程 android。
 */
class SafeWalkLSPHook : IXposedHookLoadPackage, SensorEventListener {

    private enum class State { SLEEP, LIGHT_MONITOR, VERIFYING }

    private var state = State.SLEEP
    private var xsp: XSharedPreferences? = null
    private var nm: SafeWalkNotificationManager? = null
    private var sm: SensorManager? = null
    private var ctx: Context? = null

    private val steps = mutableListOf<Long>()
    private var lastStepTs = 0L
    private var lastIdRemindTs = 0L
    private var lastSitRemindTs = 0L
    private var lastTriggerTs = 0L
    private var lastBatteryWarnTs = 0L
    private var batteryReceiverRegistered = false
    private var verifyStartTs = 0L

    private val WINDOW_MS = 5 * 60 * 1000L
    private val TRIGGER_COOLDOWN = 5 * 60 * 1000L // 触发后5分钟冷却
    private fun threshold(): Int = xsp?.getInt(SafeWalkPrefKey.stepThreshold, 800) ?: 800
    private fun staticResetMs(): Long = ((xsp?.getInt(SafeWalkPrefKey.staticResetSec, 180) ?: 180) * 1000L)
    private fun masterEnable(): Boolean = xsp?.getBoolean("masterEnable", true) ?: true

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName != "android") return

        // 延迟拿到 context，系统进程启动早期 currentApplication 可能为 null
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ctx = getSystemContext()
                if (ctx == null) return@postDelayed

                xsp = XSharedPreferences("io.github.wumisc.safewalk", "safewalk_xsp")
                nm = SafeWalkNotificationManager(ctx!!)
                sm = ctx!!.getSystemService(Context.SENSOR_SERVICE) as SensorManager

                sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                    sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }

                if (!batteryReceiverRegistered) {
                    registerBattery(ctx!!)
                    registerTorchReceiver(ctx!!)
                    batteryReceiverRegistered = true
                }

                Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        try { check() } catch (_: Throwable) {}
                        Handler(Looper.getMainLooper()).postDelayed(this, 15_000L)
                    }
                }, 5_000L)
            } catch (_: Throwable) {}
        }, 2_000L)
    }

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

    private fun check() {
        if (!masterEnable()) return

        val now = System.currentTimeMillis()
        xsp?.reload()

        // VERIFYING超时兜底：超过6秒还没结束，回LIGHT_MONITOR
        if (state == State.VERIFYING && now - verifyStartTs > 6000) {
            state = State.LIGHT_MONITOR
            steps.clear()
        }

        // 静置重置
        if (now - lastStepTs > staticResetMs()) {
            steps.clear()
            // 久坐提醒：2小时没动过
            if (now - lastStepTs > 2 * 60 * 60 * 1000L && now - lastSitRemindTs > 24 * 60 * 60 * 1000L) {
                if (xsp?.getBoolean(SafeWalkPrefKey.enableSitHealthRemind, true) == true) {
                    nm?.sitRemind()
                    lastSitRemindTs = now
                }
            }
            state = State.SLEEP
            return
        }

        steps.removeAll { it < now - WINDOW_MS }

        // 触发冷却
        if (now - lastTriggerTs < TRIGGER_COOLDOWN) return

        if (steps.size >= threshold() && state == State.LIGHT_MONITOR) {
            state = State.VERIFYING
            verifyStartTs = now
            verifyGait()
        }
    }

    private val gait = GaitDetector()

    private fun verifyGait() {
        val s = sm ?: return
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            state = State.LIGHT_MONITOR
            return
        }
        gait.reset()
        val end = System.currentTimeMillis() + 4000

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

    private fun triggerMovement() {
        xsp?.reload()
        lastTriggerTs = System.currentTimeMillis()
        steps.clear()

        val enableId = xsp?.getBoolean(SafeWalkPrefKey.enableIdReminder, true) ?: true
        val mode = xsp?.getString(SafeWalkPrefKey.travelMode, "求职") ?: "求职"

        if (enableId && System.currentTimeMillis() - lastIdRemindTs > 24 * 60 * 60 * 1000L) {
            nm?.idReminder()
            lastIdRemindTs = System.currentTimeMillis()
        }

        val enableSocial = xsp?.getBoolean(SafeWalkPrefKey.enableSocialTipCard, true) ?: true
        if (enableSocial) {
            when (mode) {
                "约会" -> nm?.socialTip("守时，不打探隐私，提前商量消费方式")
                "聚会" -> nm?.socialTip("不强行劝酒，AA制，离场打招呼")
            }
        }

        // 夜间（22:00~06:00）走路 → 提醒开手电
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 22 || hour < 6) {
            nm?.flashlightSuggest()
        }

        state = State.SLEEP
    }

    private fun getSystemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) { null }
    }

    private fun registerBattery(context: Context) {
        try {
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    xsp?.reload()
                    if (!(xsp?.getBoolean(SafeWalkPrefKey.enableBatteryWarning, true) ?: true)) return
                    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
                    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    if (level * 100 / scale <= 15) {
                        val now = System.currentTimeMillis()
                        if (now - lastBatteryWarnTs > 60 * 60 * 1000L) { // 1小时冷却
                            nm?.batteryWarning()
                            lastBatteryWarnTs = now
                        }
                    }
                }
            }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {}
    }

    private var torchOn = false
    private fun registerTorchReceiver(context: Context) {
        try {
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    try {
                        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val id = cm.cameraIdList[0]
                        torchOn = !torchOn
                        cm.setTorchMode(id, torchOn)
                    } catch (_: Throwable) {}
                }
            }, IntentFilter("io.github.wumisc.safewalk.TORCH_TOGGLE"))
        } catch (_: Throwable) {}
    }
}
