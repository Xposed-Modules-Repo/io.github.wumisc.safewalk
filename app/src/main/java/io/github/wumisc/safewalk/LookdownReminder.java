package io.github.wumisc.safewalk;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * 看路提醒：行走中低头看手机（屏幕朝上）持续超过阈值，弹 Toast 提醒。
 * 用"低头检测"判断，用摄像头看路况时手机立着不会误报。
 */
public final class LookdownReminder {

    private static LookdownReminder inst;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context ctx;
    private PowerManager pm;
    private boolean enabled;
    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!enabled) {
                return;
            }
            try {
                check();
            } catch (Throwable t) {
                SafeWalkInit.diag("remind check: " + t);
            }
            main.postDelayed(this, 1000);
        }
    };

    private long satisfyStart = -1; // 满足"行走+亮屏+低头"的起始时刻
    private long lastRemind;        // 上次提醒时间（节流 20s）

    private LookdownReminder(Context context, SensorManager ignored) {
        this.ctx = context.getApplicationContext();
        this.pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    public static void init(Context context, SensorManager sensorManager) {
        if (inst == null) {
            inst = new LookdownReminder(context, sensorManager);
        }
    }

    public static void setEnabled(boolean on) {
        if (inst == null) {
            return;
        }
        if (on == inst.enabled) {
            return;
        }
        inst.enabled = on;
        inst.satisfyStart = -1;
        if (on) {
            inst.main.post(inst.checkRunnable);
        }
    }

    private void check() {
        boolean walking = WalkDetector.isWalking();
        boolean screenOn = pm != null && pm.isInteractive();
        boolean headDown = WalkDetector.isHeadDown(); // 低头看手机（屏幕朝上）
        long now = System.currentTimeMillis();

        // 触发：行走中 + 屏幕亮 + 手机被低头看。
        if (walking && screenOn && headDown) {
            if (satisfyStart < 0) {
                satisfyStart = now;
            }
            if (now - satisfyStart >= (long) Config.lookdownSec * 1000L) {
                showReminder();
                satisfyStart = -1; // 本次已提醒，重新累计
            }
        } else {
            satisfyStart = -1;
        }
        // 诊断：每轮记录触发条件
        SafeWalkInit.diag("remind: walking=" + walking + " screen=" + screenOn
                + " headDown=" + headDown + " satisfy=" + (satisfyStart < 0 ? "-" : (now - satisfyStart) / 1000) + "s");
    }

    private void showReminder() {
        long now = System.currentTimeMillis();
        if (now - lastRemind < 20000) {
            return;
        }
        lastRemind = now;
        try {
            Toast.makeText(ctx, "看路！走路时请留意四周", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            SafeWalkInit.diag("reminder toast: " + t);
        }
    }
}
