package io.github.wumisc.safewalk;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/* loaded from: classes3.dex */
public final class WalkDetector implements SensorEventListener {
    private static final int STEPS_TO_RESTART = 15;
    private static final int STEPS_TO_START = 100;
    private static WalkDetector inst;
    private Sensor accel;
    private boolean enabled;
    private float gx;
    private float gy;
    private float gz;
    private boolean hasEverWalked;
    private boolean haveGravity;
    private int idleWindows;
    private boolean lastHigh;
    private long lastStepTime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int pendingSteps;
    private SensorManager sm;
    private int stepCount;
    private boolean walking;
    private long windowStart;

    private WalkDetector(SensorManager sensorManager) {
        this.sm = sensorManager;
        this.accel = sensorManager.getDefaultSensor(1);
    }

    public static void init(SensorManager sensorManager) {
        if (inst == null) {
            inst = new WalkDetector(sensorManager);
        }
    }

    public static void setAutoEnabled(boolean on) {
        if (inst == null || on == inst.enabled) {
            return;
        }
        inst.enabled = on;
        inst.walking = false;
        inst.stepCount = 0;
        inst.windowStart = 0L;
        inst.idleWindows = 0;
        inst.pendingSteps = 0;
        if (on) {
            if (inst.accel != null && inst.sm != null) {
                inst.sm.registerListener(inst, inst.accel, 2, inst.main);
                return;
            }
            return;
        }
        if (inst.sm != null) {
            inst.sm.unregisterListener(inst);
        }
    }

    public static boolean isWalking() {
        return inst != null && inst.enabled && inst.walking;
    }

    @Override // android.hardware.SensorEventListener
    public void onSensorChanged(SensorEvent e) {
        long now = e.timestamp / 1000000;
        float ax = e.values[0];
        float ay = e.values[1];
        float az = e.values[2];
        if (!this.haveGravity) {
            this.gx = ax;
            this.gy = ay;
            this.gz = az;
            this.haveGravity = true;
        } else {
            this.gx = (this.gx * 0.8f) + (ax * 0.2f);
            this.gy = (this.gy * 0.8f) + (ay * 0.2f);
            this.gz = (this.gz * 0.8f) + (0.2f * az);
        }
        float mag = (float) Math.sqrt((ax * ax) + (ay * ay) + (az * az));
        boolean high = mag - 9.8f > 2.5f;
        if (high && !this.lastHigh && now - this.lastStepTime > 350) {
            this.stepCount++;
            this.pendingSteps++;
            this.lastStepTime = now;
            SafetyFeatures.onStep();
        }
        this.lastHigh = high;
        if (this.windowStart == 0) {
            this.windowStart = now;
            return;
        }
        long windowMs = now - this.windowStart;
        if (windowMs >= 4000) {
            if (this.walking) {
                if (this.stepCount < 2) {
                    this.idleWindows++;
                    if (this.idleWindows >= 2) {
                        this.walking = false;
                        this.pendingSteps = 0;
                    }
                } else {
                    this.idleWindows = 0;
                }
            } else {
                int threshold = this.hasEverWalked ? 15 : 100;
                if (this.pendingSteps >= threshold) {
                    this.walking = true;
                    this.hasEverWalked = true;
                    this.pendingSteps = 0;
                    this.idleWindows = 0;
                }
            }
            this.windowStart = now;
            this.stepCount = 0;
        }
    }

    public static boolean isHeadDown() {
        if (inst == null || !inst.haveGravity) {
            return false;
        }
        float ax = Math.abs(inst.gx);
        float ay = Math.abs(inst.gy);
        float az = Math.abs(inst.gz);
        return az > 6.5f && az > ax && az > ay;
    }

    @Override // android.hardware.SensorEventListener
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
