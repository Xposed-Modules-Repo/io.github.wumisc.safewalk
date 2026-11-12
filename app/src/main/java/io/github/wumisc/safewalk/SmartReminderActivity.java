package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;

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
    /** 低头提醒（Pixel 数字健康 Heads Up）：走路 + 亮屏 + 屏幕朝上持续 8s 才提醒 */
    private SwitchCompat swHeadDown;
    private SwitchCompat swAutoMode;
    /** 【BUG 修复】夜间手电·步态联动开关：时间区间 + walking 同步点灯、停止自动灭灯 */
    private SwitchCompat swNightTorchGait;
    private TextView heroSummary, tvTorchMinutes, tvNightTime, tvAutoModeStatus;
    private View rowModeChips;

    // ===== 定位自动识别 =====
    private LocationManager lm;
    private LocationListener locListener;
    private final HashMap<String, Integer> autoVotes = new HashMap<>();
    private String lastVotedMode = null;
    /** 反编码是否还在后台跑：上一轮没结束就跳过新的定位回调，避免线程堆积 */
    private volatile boolean locBusy = false;
    private static final int VOTE_WINDOW = 3;   // 连续 3 次同建议才落地（防抖）
    private static final int REQ_LOC = 3001;
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
        swHeadDown = findViewById(R.id.sw_head_down);
        swNightTorch = findViewById(R.id.sw_night_torch);
        swNightTorchGait = findViewById(R.id.sw_night_torch_gait);
        swSocial = findViewById(R.id.sw_social);
        tvNightTime = findViewById(R.id.tv_night_time);
        swAutoMode = findViewById(R.id.sw_auto_mode);
        tvAutoModeStatus = findViewById(R.id.tv_auto_mode_status);
        rowModeChips = findViewById(R.id.row_mode_chips);

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
        findViewById(R.id.card_head_down).setOnClickListener(v -> swHeadDown.toggle());
        findViewById(R.id.card_night_torch).setOnClickListener(v -> swNightTorch.toggle());
        findViewById(R.id.card_night_torch_gait).setOnClickListener(v -> swNightTorchGait.toggle());
        findViewById(R.id.card_social).setOnClickListener(v -> swSocial.toggle());
        findViewById(R.id.row_night_time).setOnClickListener(v -> pickNightTime());
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
        swHeadDown.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableHeadDownRemind, false));
        swNightTorch.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableNightTorch, true));
        // 小窗联动（单夜间手电开关）默认关：用户主动打开才接管
        swNightTorchGait.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableNightTorchGait, false));
        swSocial.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableSocialTipCard, true));
        swAutoMode.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableAutoTravelMode, false));
        updateNightTimeLabel();
        updateAutoUi();

        travelMode = SafePrefs.getString(sp, SafeWalkPrefKey.travelMode, "求职");
        if (travelMode == null) travelMode = "求职";

        int minutes = Math.round(SafePrefs.getFloat(sp, SafeWalkPrefKey.torchStaticMinutes, 12f));
        minutes = Math.max(5, Math.min(60, minutes));
        seekTorch.setProgress(minutes - 5);
        updateTorchLabel(minutes);
    }

    private void bindListeners() {
        swBattery.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableBatteryWarning, c));
        swIdcard.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableIdReminder, c));
        swSit.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableSitHealthRemind, c));
        swHeadDown.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableHeadDownRemind, c));
        swNightTorch.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableNightTorch, c));
        // 【BUG 修复】小窗联动开关：写入即 commit + exportConfig（后端实时保存），
        // 下一轮预览显示/收起时 WalkPreviewService 就会按新值决定是否联动闪光灯。
        swNightTorchGait.setOnCheckedChangeListener((v, c) -> {
            saveSwitch(SafeWalkPrefKey.enableNightTorchGait, c);
            DiagLog.log("[torch:overlay] 用户" + (c ? "开启" : "关闭")
                    + "小窗联动闪光灯：夜间时段角落小窗显示时一起点亮、收起熄灭");
        });
        swSocial.setOnCheckedChangeListener((v, c) -> saveSwitch(SafeWalkPrefKey.enableSocialTipCard, c));

        swAutoMode.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enableAutoTravelMode, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[loc] 定位自动识别 → " + (c ? "开" : "关"));
            updateAutoUi();
            if (c) startAutoLocate(); else stopAutoLocate();
        });

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
        swHeadDown.setChecked(on);
        swNightTorch.setChecked(on);
        swNightTorchGait.setChecked(on);
        swSocial.setChecked(on);
        binding = false;

        sp.edit()
                .putBoolean(SafeWalkPrefKey.enableBatteryWarning, on)
                .putBoolean(SafeWalkPrefKey.enableIdReminder, on)
                .putBoolean(SafeWalkPrefKey.enableSitHealthRemind, on)
                .putBoolean(SafeWalkPrefKey.enableHeadDownRemind, on)
                .putBoolean(SafeWalkPrefKey.enableNightTorch, on)
                .putBoolean(SafeWalkPrefKey.enableNightTorchGait, on)
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
        if (swHeadDown.isChecked()) on++;
        if (swNightTorch.isChecked()) on++;
        if (swSocial.isChecked()) on++;

        // 总开关关掉时，这里写「6 项全部开启」是在骗人：
        // 六项确实都勾着，但一条都不会弹。先说清楚为什么不生效。
        boolean master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true);
        String text;
        if (!master) {
            text = "总开关已关闭 · 以下提醒暂不生效";
        } else if (on == 6) {
            text = "6 项提醒 · 全部开启";
        } else if (on == 0) {
            text = "6 项提醒 · 已全部关闭";
        } else {
            text = "6 项提醒 · 已开启 " + on + " 项";
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

    // ========== 基于定位自动识别出行模式 ==========

    /** 自动模式接管 UI：开启时隐藏手动 chips（由定位决定），显示状态 */
    private void updateAutoUi() {
        boolean auto = swAutoMode.isChecked();
        rowModeChips.setVisibility(auto ? View.GONE : View.VISIBLE);
        if (auto) {
            tvAutoModeStatus.setText("正在定位识别你所在的场合…");
            tvAutoModeStatus.setTextColor(getColor(R.color.primary));
        } else {
            tvAutoModeStatus.setText("自动判断你在什么场合，切换对应模式");
            tvAutoModeStatus.setTextColor(getColor(R.color.text_hint));
        }
    }

    private void startAutoLocate() {
        // 先要定位权限；没有就去系统申请，授权后自动开始
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        beginLocation();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_LOC) {
            boolean granted = results.length > 0
                    && (results[0] == PackageManager.PERMISSION_GRANTED
                    || (results.length > 1 && results[1] == PackageManager.PERMISSION_GRANTED));
            if (granted) {
                beginLocation();
            } else {
                binding = true;
                swAutoMode.setChecked(false);
                binding = false;
                sp.edit().putBoolean(SafeWalkPrefKey.enableAutoTravelMode, false).commit();
                SafeWalkConfig.exportConfig(this);
                updateAutoUi();
                Toast.makeText(this, "未授权定位，自动识别已关闭", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void beginLocation() {
        try {
            if (lm == null) lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locListener == null) {
                locListener = new LocationListener() {
                    @Override public void onLocationChanged(Location l) { onAutoLocation(l); }
                    @Override public void onProviderDisabled(String p) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onStatusChanged(String p, int s, android.os.Bundle e) {}
                };
            }
            boolean fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (coarseOk) {
                Location last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) onAutoLocation(last);
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 50f, locListener, Looper.getMainLooper());
            }
            if (fineOk) {
                Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) onAutoLocation(last);
                // 网络定位优先（省电）；GPS 兜底。30 秒一次、位移 50 米才更新
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 50f, locListener, Looper.getMainLooper());
            }
            tvAutoModeStatus.setText("已开始定位识别…");
        } catch (Throwable t) {
            DiagLog.log("[loc] 定位启动失败: " + t.getMessage());
        }
    }

    private void stopAutoLocate() {
        try {
            if (lm != null && locListener != null) lm.removeUpdates(locListener);
        } catch (Throwable ignored) {}
    }

    /** 每次定位回调：地址→关键词→建议模式，连续 VOTE_WINDOW 次同建议才落地 */
    private void onAutoLocation(Location loc) {
        // 【本次修复】Geocoder.getFromLocation() 是**同步阻塞**调用：系统反编码
        // 要走网络 / 本地地理数据库，离线或冷启动时能卡住几百毫秒甚至数秒。
        // 原来它直接跑在定位回调里，而 locListener 注册时用的是
        // Looper.getMainLooper() —— 等于在主线程做阻塞 IO，定位一刷新就掉帧，
        // 网络差时会直接 ANR。这里改到后台线程，并做「上一轮未完成则跳过」。
        if (loc == null || locBusy) return;
        final Location snapshot = new Location(loc);
        locBusy = true;
        new Thread(() -> {
            String suggest = null;
            try {
                LocationModeEngine.load(getApplicationContext());
                suggest = LocationModeEngine.judge(getApplicationContext(), snapshot);
            } catch (Throwable ignored) {
            } finally {
                locBusy = false;
            }
            final String s = suggest;
            runOnUiThread(() -> applyLocationVote(s));
        }, "loc-geocode").start();
    }

    /** 定位建议投票（主线程）：连续 VOTE_WINDOW 次同建议才真正切换 */
    private void applyLocationVote(String suggest) {
        if (isFinishing() || isDestroyed()) return;
        if (suggest == null) {
            // 拿不到地址或置信不足：维持现状，不打扰
            return;
        }
        if (!suggest.equals(lastVotedMode)) {
            autoVotes.clear();
            lastVotedMode = suggest;
        }
        Integer c = autoVotes.get(suggest);
        autoVotes.put(suggest, (c == null ? 0 : c) + 1);
        if (autoVotes.get(suggest) >= VOTE_WINDOW) {
            applyAutoMode(suggest);
            autoVotes.clear();
        }
    }

    private void applyAutoMode(String mode) {
        travelMode = mode;
        sp.edit().putString(SafeWalkPrefKey.travelMode, mode).commit();
        SafeWalkConfig.exportConfig(this);
        refreshModeChips();
        tvAutoModeStatus.setText("当前：·" + mode + "·（自动识别）");
        tvAutoModeStatus.setTextColor(getColor(R.color.primary));
        DiagLog.log("[loc] 自动识别 → " + mode);
        Toast.makeText(this, "自动识别到「" + mode + "」场合，已切换出行模式",
                Toast.LENGTH_SHORT).show();
    }

    private void updateTorchLabel(int minutes) {
        tvTorchMinutes.setText("手电开着静置 " + minutes + " 分钟后提醒省电");
    }

    private int nightStartHour() { return SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchStartHour, 22); }
    private int nightEndHour() { return SafePrefs.getInt(sp, SafeWalkPrefKey.nightTorchEndHour, 6); }

    private void updateNightTimeLabel() {
        tvNightTime.setText(String.format("%02d:00 – %02d:00", nightStartHour(), nightEndHour()));
    }

    /** 选夜间生效时段：先弹开始时间，确认后再弹结束时间（整点即可，忽略分） */
    private void pickNightTime() {
        android.app.TimePickerDialog start = new android.app.TimePickerDialog(this,
                (d, sh, sm) -> {
                    sp.edit().putInt(SafeWalkPrefKey.nightTorchStartHour, sh).commit();
                    SafeWalkConfig.exportConfig(this);
                    pickNightEndTime();
                }, nightStartHour(), 0, true);
        start.setTitle("夜间生效 · 开始时间");
        start.show();
    }

    private void pickNightEndTime() {
        android.app.TimePickerDialog end = new android.app.TimePickerDialog(this,
                (d, eh, em) -> {
                    sp.edit().putInt(SafeWalkPrefKey.nightTorchEndHour, eh).commit();
                    SafeWalkConfig.exportConfig(this);
                    updateNightTimeLabel();
                }, nightEndHour(), 0, true);
        end.setTitle("夜间生效 · 结束时间");
        end.show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 离开页面停止定位监听，省电；下次打开且开关还开着会重新启动
        stopAutoLocate();
    }
}
