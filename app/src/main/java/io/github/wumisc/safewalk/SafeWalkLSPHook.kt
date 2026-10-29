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
    private var batteryReceiverRegistered = false

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

        // 静置重置
        if (now - lastStepTs > staticResetMs()) {
            steps.clear()
            state = State.SLEEP
            return
        }

        steps.removeAll { it < now - WINDOW_MS }

        // 触发冷却
        if (now - lastTriggerTs < TRIGGER_COOLDOWN) return

        if (steps.size >= threshold() && state == State.LIGHT_MONITOR) {
            state = State.VERIFYING
            verifyGait()
        }
    }

    private fun verifyGait() {
        val s = sm ?: return
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            state = State.LIGHT_MONITOR
            return
        }

        var peaks = 0
        var prevZ = 0f
        var totalSamples = 0
        var uprightSamples = 0  // Z轴>7.5才算手机竖直拿着
        val start = System.currentTimeMillis()
        val end = start + 4000

        s.registerListener(object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null) return
                val z = e.values[2]
                totalSamples++
                // 手机竖直拿着时 z≈9.8；躺床上/枕头旁边 z<7.5
                if (z > 7.5f) uprightSamples++
                // 任何一次 z<4 就直接判定躺床，取消
                if (z < 4.0f) {
                    s.unregisterListener(this)
                    state = State.SLEEP
                    steps.clear()
                    return
                }
                if (z - prevZ > 3.0f) peaks++
                prevZ = z
                if (System.currentTimeMillis() > end) {
                    s.unregisterListener(this)
                    // 必须：大部分时间手机竖直拿着 + 有足够走路起伏
                    val uprightRatio = if (totalSamples > 0) uprightSamples.toFloat() / totalSamples else 0f
                    if (peaks >= 6 && uprightRatio > 0.7f) {
                        triggerMovement()
                    } else {
                        state = State.LIGHT_MONITOR
                        steps.clear()
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }, acc, SensorManager.SENSOR_DELAY_NORMAL)
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
                    if (level * 100 / scale <= 15) nm?.batteryWarning()
                }
            }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {}
    }
}
