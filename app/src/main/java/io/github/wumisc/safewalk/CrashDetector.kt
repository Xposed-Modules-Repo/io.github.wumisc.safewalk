package io.github.wumisc.safewalk

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * 车祸检测器（运行在 SystemUI Hook 进程）。
 *
 * 持续监听加速度计，用低通滤波分离重力分量，动态加速度（去重力后的幅值）
 * 突增超过阈值即判定为「疑似撞击」，广播给 App 弹急救卡倒计时。
 *
 * Pixel 的车祸检测用 ML 模型；这里用简化的动态加速度阈值，
 * 灵敏度三档可调，误报交给 App 端「我没事」按钮兜底。
 */
class CrashDetector(
    private val ctx: Context,
    private val thresholdProvider: () -> Float
) : SensorEventListener {

    companion object {
        /** 撞击广播：SystemUI → App，跨进程必须 EXPORTED + 令牌 */
        const val ACTION_CRASH_DETECTED = "io.github.wumisc.safewalk.CRASH_DETECTED"

        /** 触发后冷却：60 秒内不再重复触发，避免一次碰撞连弹好几张急救卡 */
        private const val TRIGGER_COOLDOWN_MS = 60_000L
        /** 连续超阈值的采样点个数达到该值才触发，滤掉单点毛刺 */
        private const val HIT_COUNT_REQUIRED = 2

        /** 低通滤波系数：越大越平滑，重力估计越滞后 */
        private const val ALPHA = 0.8f

        /** 灵敏度档位 → 动态加速度阈值 (m/s²)。0=低 1=中 2=高 */
        private val SENSITIVITY_THRESHOLD = floatArrayOf(25f, 20f, 15f)

        /** 按配置档位取阈值，越界夹到 [0,2] */
        @JvmStatic
        fun thresholdFor(sensitivity: Int): Float {
            val idx = sensitivity.coerceIn(0, 2)
            return SENSITIVITY_THRESHOLD[idx]
        }
    }

    private var sm: SensorManager? = null
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var hitCount = 0
    private var lastTriggerTs = 0L
    @Volatile
    private var registered = false

    fun start(sensorManager: SensorManager) {
        if (registered) return
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = sensorManager
        try {
            // SENSOR_DELAY_UI ≈ 60ms，足够捕捉撞击瞬时峰值；比 GAME 省电
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
            registered = true
            gx = 0f; gy = 0f; gz = 0f
            hitCount = 0
            Log.i("SafeWalk", "[crash] 加速度监听已启动")
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        if (!registered) return
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        registered = false
        hitCount = 0
        Log.i("SafeWalk", "[crash] 加速度监听已停止")
    }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]

        // 低通滤波估计重力（重力是低频分量，撞击是高频分量）
        gx = ALPHA * gx + (1f - ALPHA) * x
        gy = ALPHA * gy + (1f - ALPHA) * y
        gz = ALPHA * gz + (1f - ALPHA) * z

        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        val dyn = sqrt(dx * dx + dy * dy + dz * dz)

        if (dyn > thresholdProvider()) {
            hitCount++
            if (hitCount >= HIT_COUNT_REQUIRED) {
                hitCount = 0
                val now = System.currentTimeMillis()
                if (now - lastTriggerTs >= TRIGGER_COOLDOWN_MS) {
                    lastTriggerTs = now
                    onCrashDetected(dyn)
                }
            }
        } else {
            hitCount = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onCrashDetected(magnitude: Float) {
        Log.w("SafeWalk", "[crash] 检测到疑似撞击，动态加速度=$magnitude m/s²")
        try {
            SafeWalkLSPHook.diag("[crash] 疑似撞击 magnitude=$magnitude")
        } catch (_: Throwable) {
        }
        try {
            ctx.sendBroadcast(
                Intent(ACTION_CRASH_DETECTED).apply {
                    setPackage(SafeWalkLSPHook.APP_PKG)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                    putExtra("magnitude", magnitude)
                }
            )
        } catch (_: Throwable) {
        }
    }
}
