package io.github.wumisc.safewalk;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/* loaded from: classes3.dex */
public final class LookdownReminder {
    private static LookdownReminder inst;
    private Context ctx;
    private boolean enabled;
    private long lastRemind;
    private PowerManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = new Runnable() { // from class: io.github.wumisc.safewalk.LookdownReminder.1
        @Override // java.lang.Runnable
        public void run() {
            if (!LookdownReminder.this.enabled) {
                return;
            }
            try {
                LookdownReminder.this.check();
            } catch (Throwable t) {
                SafeWalkInit.diag("remind check: " + t);
            }
            LookdownReminder.this.main.postDelayed(this, 1000L);
        }
    };
    private long satisfyStart = -1;

    private LookdownReminder(Context context, SensorManager ignored) {
        this.ctx = context.getApplicationContext();
        this.pm = (PowerManager) this.ctx.getSystemService("power");
    }

    public static void init(Context context, SensorManager sensorManager) {
        if (inst == null) {
            inst = new LookdownReminder(context, sensorManager);
        }
    }

    public static void setEnabled(boolean on) {
        if (inst == null || on == inst.enabled) {
            return;
        }
        inst.enabled = on;
        inst.satisfyStart = -1L;
        if (on) {
            inst.main.post(inst.checkRunnable);
        }
    }

    public void check() {
        boolean walking = WalkDetector.isWalking();
        boolean screenOn = this.pm != null && this.pm.isInteractive();
        boolean headDown = WalkDetector.isHeadDown();
        long now = System.currentTimeMillis();
        if (walking && screenOn && headDown) {
            if (this.satisfyStart < 0) {
                this.satisfyStart = now;
            }
            if (now - this.satisfyStart >= Config.lookdownSec * 1000) {
                showReminder();
                this.satisfyStart = -1L;
            }
        } else {
            this.satisfyStart = -1L;
        }
        SafeWalkInit.diag("remind: walking=" + walking + " screen=" + screenOn + " headDown=" + headDown + " satisfy=" + (this.satisfyStart < 0 ? "-" : Long.valueOf((now - this.satisfyStart) / 1000)) + "s");
    }

    private void showReminder() {
        long now = System.currentTimeMillis();
        if (now - this.lastRemind < 20000) {
            return;
        }
        this.lastRemind = now;
        try {
            Toast.makeText(this.ctx, "看路！走路时请留意四周", 1).show();
        } catch (Throwable t) {
            SafeWalkInit.diag("reminder toast: " + t);
        }
    }
}
