package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

/**
 * 智能提醒二级菜单。
 *
 * 原首页的 5 个提醒磁贴（电量/证件/久坐/夜间/社交）统一收进这里，
 * 首页只保留一个「智能提醒」入口。顶部沿用磁贴图标做视觉锚点。
 *
 * 所有改动即时写入 SharedPreferences 并导出 JSON，无需点保存。
 */
public final class SmartReminderActivity extends Activity {

    private SharedPreferences sp;

    private SwitchCompat swBattery, swIdcard, swSit, swNightTorch, swSocial;
    private TextView heroSummary, tvTorchMinutes;
    private SeekBar seekTorch;
    private TextView[] modeChips;
    private String travelMode = "求职";

    /** 初始化控件时屏蔽监听，避免 setChecked 触发写盘 */
    private boolean binding = true;

    private static final String[] MODES = {"求职", "约会", "聚会", "旅游"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_smart_reminder);
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);

        bindViews();
        loadConfig();
        bindListeners();
        binding = false;

        updateSummary();
        refreshModeChips();

        DiagLog.init(getApplicationContext());
        DiagLog.log("[smart] 打开智能提醒页 battery=" + swBattery.isChecked()
                + " id=" + swIdcard.isChecked()
                + " sit=" + swSit.isChecked()
                + " torch=" + swNightTorch.isChecked()
                + " social=" + swSocial.isChecked()
                + " master=" + SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true));
    }

    private void bindViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        heroSummary = findViewById(R.id.hero_summary);

        swBattery = findViewById(R.id.sw_battery);
        swIdcard = findViewById(R.id.sw_idcard);
        swSit = findViewById(R.id.sw_sit);
        swNightTorch = findViewById(R.id.sw_night_torch);
        swSocial = findViewById(R.id.sw_social);

        tvTorchMinutes = findViewById(R.id.tv_torch_minutes);
        seekTorch = findViewById(R.id.seek_torch_minutes);

        modeChips = new TextView[]{
                findViewById(R.id.chip_job),
                findViewById(R.id.chip_date),
                findViewById(R.id.chip_party),
                findViewById(R.id.chip_travel)
        };

        // 整行可点，命中区域比开关本身大，避免误触
        findViewById(R.id.card_battery).setOnClickListener(v -> swBattery.toggle());
        findViewById(R.id.card_idcard).setOnClickListener(v -> swIdcard.toggle());
        findViewById(R.id.card_sit).setOnClickListener(v -> swSit.toggle());
        findViewById(R.id.card_night_torch).setOnClickListener(v -> swNightTorch.toggle());
        findViewById(R.id.card_social).setOnClickListener(v -> swSocial.toggle());

        findViewById(R.id.btn_all_on).setOnClickListener(v -> setAll(true));
        findViewById(R.id.btn_all_off).setOnClickListener(v -> setAll(false));

        for (int i = 0; i < MODES.length; i++) {
            final String mode = MODES[i];
            modeChips[i].setOnClickListener(v -> selectMode(mode));
        }
    }

    private void loadConfig() {
        // 统一走 SafePrefs：历史上某个 key 的类型变过（int↔float）也不会在 onCreate 里 CCE
        swBattery.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableBatteryWarning, true));
        swIdcard.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableIdReminder, true));
        swSit.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableSitHealthRemind, true));
        swNightTorch.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableNightTorch, true));
        swSocial.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableSocialTipCard, true));

        travelMode = SafePrefs.getString(sp, SafeWalkPrefKey.travelMode, "求职");
        if (travelMode == null) travelMode = "求职";

        int minutes = Math.round(SafePrefs.getFloat(sp, SafeWalkPrefKey.torchStaticMinutes, 20f));
        minutes = Math.max(5, Math.min(60, minutes));
        seekTorch.setProgress(minutes - 5);
        updateTorchLabel(minutes);
    }

    private void bindListeners() {
        swBattery.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableBatteryWarning, c));
        swIdcard.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableIdReminder, c));
        swSit.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableSitHealthRemind, c));
        swNightTorch.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableNightTorch, c));
        swSocial.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableSocialTipCard, c));

        seekTorch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateTorchLabel(progress + 5);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveTorchMinutes(sb.getProgress() + 5);
            }
        });
    }

    // ========== 写入 ==========

    private void saveSwitch(String key, boolean value) {
        if (binding) return;
        sp.edit().putBoolean(key, value).commit();
        SafeWalkConfig.exportConfig(this);
        DiagLog.log("[smart] " + key + " → " + value);
        updateSummary();
    }

    private void setAll(boolean on) {
        binding = true;
        swBattery.setChecked(on);
        swIdcard.setChecked(on);
        swSit.setChecked(on);
        swNightTorch.setChecked(on);
        swSocial.setChecked(on);
        binding = false;

        sp.edit()
                .putBoolean(SafeWalkPrefKey.enableBatteryWarning, on)
                .putBoolean(SafeWalkPrefKey.enableIdReminder, on)
                .putBoolean(SafeWalkPrefKey.enableSitHealthRemind, on)
                .putBoolean(SafeWalkPrefKey.enableNightTorch, on)
                .putBoolean(SafeWalkPrefKey.enableSocialTipCard, on)
                .commit();
        SafeWalkConfig.exportConfig(this);
        updateSummary();
    }

    private void selectMode(String mode) {
        travelMode = mode;
        sp.edit().putString(SafeWalkPrefKey.travelMode, mode).commit();
        SafeWalkConfig.exportConfig(this);
        refreshModeChips();
    }

    private void saveTorchMinutes(int minutes) {
        sp.edit().putFloat(SafeWalkPrefKey.torchStaticMinutes, (float) minutes).commit();
        SafeWalkConfig.exportConfig(this);
    }

    // ========== 刷新显示 ==========

    private void updateSummary() {
        int on = 0;
        if (swBattery.isChecked()) on++;
        if (swIdcard.isChecked()) on++;
        if (swSit.isChecked()) on++;
        if (swNightTorch.isChecked()) on++;
        if (swSocial.isChecked()) on++;

        // 总开关关掉时，这里写「5 项全部开启」是在骗人：
        // 五项确实都勾着，但一条都不会弹。先说清楚为什么不生效。
        boolean master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true);
        String text;
        if (!master) {
            text = "总开关已关闭 · 以下提醒暂不生效";
        } else if (on == 5) {
            text = "5 项提醒 · 全部开启";
        } else if (on == 0) {
            text = "5 项提醒 · 已全部关闭";
        } else {
            text = "5 项提醒 · 已开启 " + on + " 项";
        }

        heroSummary.setText(text);
    }

    private void refreshModeChips() {
        for (int i = 0; i < MODES.length; i++) {
            boolean on = MODES[i].equals(travelMode);
            modeChips[i].setBackgroundResource(
                    on ? R.drawable.bg_choice_chip_on : R.drawable.bg_choice_chip);
            modeChips[i].setTextColor(getColor(on ? R.color.card_bg : R.color.text_body));
        }
    }

    private void updateTorchLabel(int minutes) {
        tvTorchMinutes.setText("手电开着静置 " + minutes + " 分钟后提醒省电");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 兜底：手指还按在滑块上就被切走时，onStopTrackingTouch 不会触发，
        // 刚才拖到的位置会丢。这里按当前进度落库再导出。
        // （原注释写的是「兜底导出」，但只 exportConfig 是没用的——
        //   JSON 是从 SharedPreferences 读的，SP 里还是旧值，等于什么都没兜住）
        if (!binding && seekTorch != null) {
            saveTorchMinutes(seekTorch.getProgress() + 5);
        } else {
            SafeWalkConfig.exportConfig(this);
        }
    }
}
