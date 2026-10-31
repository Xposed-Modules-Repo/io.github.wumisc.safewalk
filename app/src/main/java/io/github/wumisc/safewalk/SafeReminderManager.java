package io.github.wumisc.safewalk;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import java.util.Calendar;

public class SafeReminderManager {
    private final Context ctx;
    private final SafeConfig cfg;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long sedentaryStartTs = 0;

    public SafeReminderManager(Context c, SafeConfig conf){
        ctx = c;
        cfg = conf;
    }

    public void onWalkStart(){
        sedentaryStartTs = System.currentTimeMillis();
        checkNightTorch();
        checkBattery();
        checkEtiquetteCard();
        checkIdCard();
    }

    public void onWalkStop(){
        checkSedentary();
    }

    private boolean isNightTime(){
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return h >= SafeConstant.NIGHT_START || h < SafeConstant.NIGHT_END;
    }

    private void checkNightTorch(){
        if(!cfg.getBool(SafeConstant.KEY_NIGHT_TORCH, true)) return;
        if(!isNightTime()) return;
        SafeUIUtils.sendNotify(ctx,"夜间出行","是否开启手电筒");
        DiagLogger.getInstance(ctx).log("夜间手电提醒触发");
    }

    private void checkBattery(){
        if(!cfg.getBool(SafeConstant.KEY_BATTERY_ALERT,true)) return;
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batIntent = ctx.registerReceiver(null, filter);
        int level = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = level * 100 / scale;
        if(percent <= 20){
            SafeUIUtils.sendNotify(ctx,"电量提醒","当前电量："+percent+"%");
        }
    }

    private void checkEtiquetteCard(){
        if(!cfg.getBool(SafeConstant.KEY_ETIQUETTE_CARD,true)) return;
        SafeUIUtils.showToast(ctx,"出行礼仪提示已加载");
    }

    private void checkIdCard(){
        if(!cfg.getBool(SafeConstant.KEY_ID_CARD,true)) return;
        SafeUIUtils.showToast(ctx,"证件卡片快捷入口");
    }

    private void checkSedentary(){
        if(!cfg.getBool(SafeConstant.KEY_SEDENTARY,true)) return;
        long durMin = (System.currentTimeMillis() - sedentaryStartTs)/60000;
        if(durMin >= SafeConstant.SEDENTARY_THRESHOLD){
            SafeUIUtils.sendNotify(ctx,"久坐提醒","已经连续行走40分钟，建议休息");
        }
    }
}
