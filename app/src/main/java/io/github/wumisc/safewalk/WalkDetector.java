package io.github.wumisc.safewalk;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * 行走检测：加速度计模量识别步行节奏。
 *
 * 动态步数状态机：
 *  - 静止状态下累计 100 步才判定为行走（防误触）
 *  - 行走中连续 4 秒窗口 <2 步判定静止，立即关闭
 *  - 从静止恢复时只需 15 步即重新开启（快速响应）
 */
public final class WalkDetector implements SensorEventListener {

    private static WalkDetector inst;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private Sensor accel;
    private boolean enabled;
    private boolean walking;

    private long windowStart;
    private int stepCount;
    private long lastStepTime;
    private boolean lastHigh;
    private int idleWindows;
    // 重力低通滤波（低头判断用）
    private float gx, gy, gz;
    private boolean haveGravity;

    // 动态步数阈值：首次 100 步，恢复 15 步
    private static final int STEPS_TO_START = 100;
    private static final int STEPS_TO_RESTART = 15;
    private int pendingSteps; // 待累计步数（未达到开启阈值）

    private WalkDetector(SensorManager sensorManager) {
        this.sm = sensorManager;
        this.accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public static void init(SensorManager sensorManager) {
        if (inst == null) {
            inst = new WalkDetector(sensorManager);
        }
    }

    public static void setAutoEnabled(boolean on) {
        if (inst == null) return;
        if (on == inst.enabled) return;
        inst.enabled = on;
        inst.walking = false;
        inst.stepCount = 0;
        inst.windowStart = 0;
        inst.idleWindows = 0;
        inst.pendingSteps = 0;
        if (on) {
            if (inst.accel != null && inst.sm != null) {
                inst.sm.registerListener(inst, inst.accel, SensorManager.SENSOR_DELAY_NORMAL, inst.main);
            }
        } else {
            if (inst.sm != null) inst.sm.unregisterListener(inst);
        }
    }

    public static boolean isWalking() {
        return inst != null && inst.enabled && inst.walking;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = e.timestamp / 1000000L;
        float ax = e.values[0], ay = e.values[1], az = e.values[2];
        // 低通滤波重力
        if (!haveGravity) {
            gx = ax; gy = ay; gz = az; haveGravity = true;
        } else {
            gx = gx * 0.8f + ax * 0.2f;
            gy = gy * 0.8f + ay * 0.2f;
            gz = gz * 0.8f + az * 0.2f;
        }
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        // 落脚冲击：偏差 >2.5g 算一步
        boolean high = mag - 9.8f > 2.5f;
        if (high && !lastHigh) {
            if (now - lastStepTime > 350) {
                stepCount++;
                pendingSteps++;
                lastStepTime = now;
            }
        }
        lastHigh = high;

        if (windowStart == 0) {
            windowStart = now;
            return;
        }
        long windowMs = now - windowStart;
        if (windowMs >= 4000) {
            if (walking) {
                // 行走中：4秒窗口步数 <2 即判定静止
                if (stepCount < 2) {
                    idleWindows++;
                    if (idleWindows >= 2) {
                        walking = false;
                        pendingSteps = 0;
                    }
                } else {
                    idleWindows = 0;
                }
            } else {
                // 静止中：累计步数达到阈值才开启
                int threshold = (idleWindows == 0 && pendingSteps < STEPS_TO_RESTART)
                        ? STEPS_TO_START : STEPS_TO_RESTART;
                if (pendingSteps >= threshold) {
                    walking = true;
                    pendingSteps = 0;
                    idleWindows = 0;
                }
            }
            windowStart = now;
            stepCount = 0;
        }
    }

    /** 是否"低头看手机"：屏幕接近水平朝上 */
    public static boolean isHeadDown() {
        if (inst == null || !inst.haveGravity) return false;
        float ax = Math.abs(inst.gx), ay = Math.abs(inst.gy), az = Math.abs(inst.gz);
        return az > 6.5f && az > ax && az > ay;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
