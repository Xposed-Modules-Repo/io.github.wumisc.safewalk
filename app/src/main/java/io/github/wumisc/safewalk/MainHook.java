package io.github.wumisc.safewalk;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, SensorEventListener {
    private SafeConfig config;
    private WalkDetector walkDetector;
    private CameraOverlay cameraOverlay;
    private GlassCapsuleBar capsuleBar;
    private SafeReminderManager reminderManager;
    private SensorManager sensorManager;
    private Sensor accSensor;
    private boolean lastWalkState = false;
    private Context sysUiCtx;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if(!lpparam.packageName.equals("com.android.systemui")) return;
        XposedBridge.log("[SafeWalk V2.0 Base] Hook SystemUI");
        DiagLogger.getInstance(null).log("Hook加载到SystemUI进程");

        XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook(){
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sysUiCtx = (Context) param.thisObject;
                        initModule(sysUiCtx);
                    }
                }
        );
    }

    private void initModule(Context ctx){
        DiagLogger.getInstance(ctx).log("V2.0 Base init开始");
        config = new SafeConfig();
        walkDetector = new WalkDetector();
        cameraOverlay = new CameraOverlay(ctx);
        capsuleBar = new GlassCapsuleBar(ctx);
        reminderManager = new SafeReminderManager(ctx, config);

        sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_NORMAL);

        DiagLogger.getInstance(ctx).log("V2.0 Base init完成");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        WalkDetector.WalkResult res = walkDetector.feed(event);

        boolean newWalk = res.isWalking;
        if(newWalk != lastWalkState){
            lastWalkState = newWalk;
            if(newWalk){
                DiagLogger.getInstance(sysUiCtx).log("步态检测：开始行走");
                if(config.getBool(SafeConstant.KEY_CAMERA_OVERLAY, true)){
                    cameraOverlay.showOverlay();
                }
                capsuleBar.showBar();
                reminderManager.onWalkStart();
            }else{
                DiagLogger.getInstance(sysUiCtx).log("步态检测：停止行走");
                cameraOverlay.hideOverlay();
                capsuleBar.hideBar();
                reminderManager.onWalkStop();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
