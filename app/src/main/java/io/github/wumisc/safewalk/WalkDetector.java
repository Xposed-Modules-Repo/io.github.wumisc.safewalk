package io.github.wumisc.safewalk;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * 行走检测：用加速度计模量识别步行节奏（周期性垂直冲击）。
 * 4 秒窗口内步数 >=5 判定为行走中；<2 判定为静止。
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
    // 重力低通滤波（用于"低头看手机"判断）：屏幕接近水平朝上 = 低头
    private float gx, gy, gz;
    private boolean haveGravity;

    private WalkDetector(SensorManager sensorManager) {
        this.sm = sensorManager;
        this.accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public static void init(SensorManager sensorManager) {
        if (inst == null) {
            inst = new WalkDetector(sensorManager);
        }
    }

    /** 自动行走检测开关：开启时注册加速度计 */
    public static void setAutoEnabled(boolean on) {
        if (inst == null) {
            return;
        }
        if (on == inst.enabled) {
            return;
        }
        inst.enabled = on;
        inst.walking = false;
        inst.stepCount = 0;
        inst.windowStart = 0;
        if (on) {
            if (inst.accel != null && inst.sm != null) {
                inst.sm.registerListener(inst, inst.accel, SensorManager.SENSOR_DELAY_NORMAL, inst.main);
            }
        } else {
            if (inst.sm != null) {
                inst.sm.unregisterListener(inst);
            }
        }
    }

    public static boolean isWalking() {
        return inst != null && inst.enabled && inst.walking;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = e.timestamp / 1000000L; // 毫秒
        float ax = e.values[0], ay = e.values[1], az = e.values[2];
        // 低通滤波重力（静止时≈重力向量）
        if (!haveGravity) {
            gx = ax; gy = ay; gz = az; haveGravity = true;
        } else {
            gx = gx * 0.8f + ax * 0.2f;
            gy = gy * 0.8f + ay * 0.2f;
            gz = gz * 0.8f + az * 0.2f;
        }
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        boolean high = mag - 9.8f > 1.5f; // 一次明显的垂直冲击（抬脚/落脚）
        if (high && !lastHigh) {
            if (now - lastStepTime > 300) {
                stepCount++;
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
            int perSec = (int) (stepCount * 1000L / windowMs);
            if (perSec >= 1) {
                walking = perSec >= 2 || stepCount >= 6; // 步行节奏：4s 内 >=6 步
            } else if (perSec == 0) {
                walking = false;
            }
            // 重置窗口
            windowStart = now;
            stepCount = 0;
        }
    }

    /**
     * 是否"低头看手机"：屏幕接近水平朝上（重力主要由 Z 轴承担）。
     * 立着用摄像头看前方时重力在 X/Y 轴，此值为 false。
     */
    public static boolean isHeadDown() {
        if (inst == null || !inst.haveGravity) {
            return false;
        }
        float ax = Math.abs(inst.gx), ay = Math.abs(inst.gy), az = Math.abs(inst.gz);
        return az > 6.5f && az > ax && az > ay;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
