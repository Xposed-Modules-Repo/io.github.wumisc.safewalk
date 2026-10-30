package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 后置看路设置页：形态切换 + 自动模式开关。
 * 从首页「后置看路」磁贴长按进入。
 */
public class RearCamSettingsActivity extends Activity {

    private SharedPreferences sp;
    private TextView modeFullscreen, modeSmallwindow;
    private TextView autoSwitch;
    private String currentMode;
    private boolean autoMode;

    private static final int COLOR_ON = 0xFF2D7A3E;
    private static final int COLOR_OFF = 0xFF8B7D6B;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_rear_cam_settings);

        sp = getSharedPreferences("safewalk_xsp", MODE_WORLD_READABLE);
        currentMode = sp.getString(SafeWalkPrefKey.rearCamMode, "fullscreen");
        autoMode = sp.getBoolean(SafeWalkPrefKey.rearCamAuto, true);

        bindViews();
        refreshUI();
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.tv_title)).setText("后置看路设置");
        ((TextView) findViewById(R.id.tv_subtitle)).setText("走路时用后置摄像头看前方路况");

        modeFullscreen = findViewById(R.id.option_fullscreen);
        modeSmallwindow = findViewById(R.id.option_smallwindow);
        autoSwitch = findViewById(R.id.option_auto);

        modeFullscreen.setOnClickListener(v -> selectMode("fullscreen"));
        modeSmallwindow.setOnClickListener(v -> selectMode("smallwindow"));
        autoSwitch.setOnClickListener(v -> toggleAuto());
    }

    private void refreshUI() {
        // 形态选项高亮
        modeFullscreen.setTextColor("fullscreen".equals(currentMode) ? COLOR_ON : COLOR_OFF);
        modeSmallwindow.setTextColor("smallwindow".equals(currentMode) ? COLOR_ON : COLOR_OFF);
        modeFullscreen.setTypeface(null, "fullscreen".equals(currentMode) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        modeSmallwindow.setTypeface(null, "smallwindow".equals(currentMode) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        // 自动模式开关
        autoSwitch.setText(autoMode ? "已开启" : "已关闭");
        autoSwitch.setTextColor(autoMode ? COLOR_ON : COLOR_OFF);
    }

    private void selectMode(String mode) {
        currentMode = mode;
        sp.edit().putString(SafeWalkPrefKey.rearCamMode, mode).commit();
        refreshUI();
        Toast.makeText(this, "已切换为" + ("fullscreen".equals(mode) ? "全屏半透明" : "角落小窗"), Toast.LENGTH_SHORT).show();
    }

    private void toggleAuto() {
        autoMode = !autoMode;
        sp.edit().putBoolean(SafeWalkPrefKey.rearCamAuto, autoMode).commit();
        refreshUI();
        Toast.makeText(this, autoMode ? "自动模式已开启" : "自动模式已关闭", Toast.LENGTH_SHORT).show();
    }
}
