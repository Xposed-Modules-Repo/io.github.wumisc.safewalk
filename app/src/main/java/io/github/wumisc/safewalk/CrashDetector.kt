package io.github.wumisc.safewalk

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 车祸检测器（运行在 SystemUI Hook 进程）。
 *
 * 【算法骨架保持 v1.0 不变】低通滤波 α=0.8 分离重力 → 动态加速度幅值；
 * 三档阈值 25/20/15 m/s²（≈2.5/2.0/1.5g）；连续 2 点命中；60s 冷却；
 * SENSOR_DELAY_UI≈60ms；广播带静态 token。
 *
 * 【v1.1 增量】在一级门控命中瞬间，把最近 200ms 的既有样本喂给 [CrashFusionPolicy] 做
 * 二级融合（速度突变 + 姿态 + 屏幕 + 跌倒排除）。不新注册传感器、不新增轮询/wake lock。
 */
class CrashDetector(
    private val ctx: Context,
    private val thresholdProvider: () -> Float
) : SensorEventListener {

    companion object {
        /** 撞击广播：SystemUI → App，跨进程必须 EXPORTED + 令牌 */
        const val ACTION_CRASH_DETECTED = "io.github.wumisc.safewalk.CRASH_DETECTED"
        /** 融合事件 ID：App 侧 publish 一次性 display token 时用 */
        const val EXTRA_CRASH_EVENT_ID = "crash_event_id"
        const val EXTRA_CRASH_DELTA_V = "crash_delta_v"
        const val EXTRA_CRASH_CONFIDENCE = "crash_confidence"

        /** 触发后冷却：60 秒内不再重复触发，避免一次碰撞连弹好几张急救卡 */
        private const val TRIGGER_COOLDOWN_MS = 60_000L
        /** 连续超阈值的采样点个数达到该值才触发，滤掉单点毛刺 */
        private const val HIT_COUNT_REQUIRED = 2
        /** 低通滤波系数：越大越平滑，重力估计越滞后 */
        private const val ALPHA = 0.8f
        /** 融合窗口保留的原始样本时长（略大于 FUSION_WINDOW_MS，避免边界丢帧） */
        private const val SAMPLE_KEEP_MS = 400L
        /** 窗口最多保留帧数：60ms 一帧，400ms ≈ 7 帧，10 帧是硬上限 */
        private const val SAMPLE_MAX = 10

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

    /** 既有回调里顺手攒的短窗样本；只在门控命中时融合一次，不做常驻计算 */
    private val samples = ArrayDeque<CrashSample>()

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
            samples.clear()
            Log.i("SafeWalk", "[crash] 加速度监听已启动")
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        if (!registered) return
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        registered = false
        hitCount = 0
        samples.clear()
        Log.i("SafeWalk", "[crash] 加速度监听已停止")
    }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val now = System.currentTimeMillis()

        // ---- 融合层样本采集：复用同一帧数据，不新增监听器 ----
        samples.addLast(CrashSample(x, y, z, now))
        while (samples.isNotEmpty() &&
            (samples.size > SAMPLE_MAX || now - samples.first().timestampMs > SAMPLE_KEEP_MS)
        ) {
            samples.removeFirst()
        }

        // ---- 一级门控：v1.0 算法骨架，逐字保留 ----
        // 低通滤波估计重力（重力是低频分量，撞击是高频分量）
        gx = ALPHA * gx + (1f - ALPHA) * x
        gy = ALPHA * gy + (1f - ALPHA) * y
        gz = ALPHA * gz + (1f - ALPHA) * z

        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        val dyn = sqrt(dx * dx + dy * dy + dz * dz)
        val threshold = thresholdProvider()

        if (dyn > threshold) {
            hitCount++
            if (hitCount >= HIT_COUNT_REQUIRED) {
                hitCount = 0
                // 冷却只在融合确认为真后更新：跌倒误报不该吃掉真实事故的 60s 窗口
                if (now - lastTriggerTs >= TRIGGER_COOLDOWN_MS) {
                    // ---- 二级融合：只在门控命中瞬间算一次，屏幕状态也只在此刻取一次 ----
                    val poseAngle = poseAngleFromGravity()
                    val screenOff = isScreenOff()
                    val event = CrashFusionPolicy.evaluate(samples.toList(), poseAngle, screenOff, threshold)
                    if (event.shouldAlert()) {
                        lastTriggerTs = now
                        onCrashDetected(dyn, event)
                    } else {
                        EmergencyDiag.event(
                            EmergencyDiag.P_CRASH,
                            "fusion_excluded fall=${event.fallExcluded} " +
                                "dv=${String.format(java.util.Locale.US, "%.2f", event.deltaV)} " +
                                "conf=${String.format(java.util.Locale.US, "%.2f", event.confidence)}"
                        )
                    }
                }
            }
        } else {
            hitCount = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 重力向量与 z 轴夹角：0°=平放，90°=竖直握持；用低通后的稳定重力估计 */
    private fun poseAngleFromGravity(): Float {
        val mag = sqrt(gx * gx + gy * gy + gz * gz)
        if (mag < 1f) return 90f
        val ratio = (gz / mag).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(ratio).toDouble()).toFloat()
    }

    /** 仅在撞击门控命中时读一次屏幕状态，不订阅 SCREEN_ON/OFF 广播常驻 */
    private fun isScreenOff(): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive != true
    } catch (_: Throwable) {
        false
    }

    private fun onCrashDetected(magnitude: Float, event: CrashEvent) {
        Log.w("SafeWalk", "[crash] 融合确认撞击，动态加速度=$magnitude m/s², conf=${event.confidence}")
        EmergencyDiag.event(
            EmergencyDiag.P_CRASH,
            "detected magnitude=${String.format(java.util.Locale.US, "%.1f", magnitude)} " +
                "dv=${String.format(java.util.Locale.US, "%.2f", event.deltaV)} " +
                "conf=${String.format(java.util.Locale.US, "%.2f", event.confidence)}"
        )
        try {
            ctx.sendBroadcast(
                Intent(ACTION_CRASH_DETECTED).apply {
                    setPackage(SafeWalkLSPHook.APP_PKG)
                    putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
                    putExtra("magnitude", magnitude)
                    putExtra(EXTRA_CRASH_EVENT_ID, event.eventId)
                    putExtra(EXTRA_CRASH_DELTA_V, event.deltaV)
                    putExtra(EXTRA_CRASH_CONFIDENCE, event.confidence)
                }
            )
        } catch (_: Throwable) {
        }
    }
}
