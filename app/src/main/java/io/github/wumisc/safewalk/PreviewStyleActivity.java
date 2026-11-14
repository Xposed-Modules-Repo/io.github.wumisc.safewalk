package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

/**
 * 预览小窗自定义（行走模式 → 三级页）。
 *
 * 【为什么要有这一页】
 * 早期版本把「长什么样」和「开不开」塞在同一屏里，结果是两类完全不同频率的操作
 * 挤在一起：开关一年动一次，而「小窗挡不挡导航栏」一天可能调三次。
 * 拆出来之后，这一页只回答一个问题：画面应该长什么样。
 *
 * 【为什么文字默认关】
 * 原来画面上那行「路况预览中 · 增强对比度」是调试遗留物 —— 它把实现细节
 * （用了描边）当成了用户信息，而用户本来就知道自己在看路况。
 * 这一版把「文字」与「对比度」解耦：文字是内容开关，对比度只是画法。
 *
 * 所有改动即时写入 SharedPreferences 并导出 JSON，退出不丢失、无需保存按钮。
 */
public final class PreviewStyleActivity extends Activity {

    private SharedPreferences sp;

    private SwitchCompat swHud, swContrast, swLowLight;
    /**
     * 【5.1.0】智能方案：开启后白天/夜间由系统深色模式 + 夜间时段自动判定，
     * 下面三个 chip 只作为「手动覆盖」的入口。
     */
    private SwitchCompat swPresetAuto, swScreenEnhance;
    private TextView tvNightStart, tvNightEnd;
    private SeekBar seekScreenContrast, seekScreenShadow, seekScreenSat;
    private TextView tvScreenContrast, tvScreenShadow, tvScreenSat;
    private EditText etHudText;
    private SeekBar seekWidth, seekMargin, seekOpacity, seekHudSize, seekContrastLevel, seekFraming;
    private TextView tvWidth, tvMargin, tvOpacity, tvHudSize, tvContrastLevel, tvFraming;
    private TextView tvStyleSummary;
    /**
     * 展示形态与位置只在这里**读取**，不再提供修改入口。
     *
     * 4.9.0 这页重复做了「显示方式 / 小窗位置」两个控件，与行走模式页完全一样，
     * 改一处忘另一处就会出现「上一页显示小窗、这一页显示全屏」的自相矛盾。
     * 5.0.0 起形态归上一页管，这里只用来决定哪些参数有意义（全屏时尺寸/边距置灰）。
     */
    private TextView[] borderChips, presetChips;
    /** 一键方案标识：day / night / plain */
    private static final String[] PRESET_KEYS = {"day", "night", "plain"};

    /**
     * 【5.3.0 画面方向】三档：自动 / 固定竖屏 / 翻转 180°。
     *
     * 下标与 {@link #rotChips}、布局里的 chip_rot_* 一一对应。
     * 抽成常量而不是在两处各写一遍：漏改一处就会出现「点了自动、高亮翻转」
     * 这种不报错但明显不对的状态（4.9.0 的描边 chip 踩过同一个坑）。
     */
    private static final String[] ROT_MODES = {
            PreviewOrientationPolicy.MODE_AUTO,
            PreviewOrientationPolicy.MODE_FIXED,
            PreviewOrientationPolicy.MODE_FLIP
    };
    private TextView[] rotChips;

    private String rearMode = RearCameraOverlay.MODE_FULLSCREEN;
    private String rearCorner = RearCameraOverlay.CORNER_BR;

    /** 载入配置期间屏蔽 listener，避免「读一次值触发一次写盘」 */
    private boolean binding = true;

    /** 描边档位：0 / 1 / 2 dp（对应「无 / 细 / 粗」） */
    private static final int[] BORDER_LEVELS = {0, 1, 2};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_preview_style);
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        DiagLog.init(getApplicationContext());

        bindViews();
        loadConfig();
        bindListeners();
        binding = false;
        refreshAll();

        DiagLog.log("[style] 打开小窗自定义 mode=" + rearMode + " corner=" + rearCorner
                + " width=" + readWidth() + "dp margin=" + readMargin() + "dp"
                + " hud=" + swHud.isChecked() + " contrast=" + swContrast.isChecked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从行走模式页改过开关再进来时，控件状态要对齐（本页也可能被系统回收重建）
        binding = true;
        loadConfig();
        binding = false;
        refreshAll();
    }

    private void bindViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvStyleSummary = findViewById(R.id.tv_style_summary);

        presetChips = new TextView[]{
                findViewById(R.id.chip_preset_day),
                findViewById(R.id.chip_preset_night),
                findViewById(R.id.chip_preset_plain)
        };
        borderChips = new TextView[]{
                findViewById(R.id.chip_border_0),
                findViewById(R.id.chip_border_1),
                findViewById(R.id.chip_border_2)
        };
        rotChips = new TextView[]{
                findViewById(R.id.chip_rot_auto),
                findViewById(R.id.chip_rot_fixed),
                findViewById(R.id.chip_rot_flip)
        };

        tvFraming = findViewById(R.id.tv_framing);
        seekFraming = findViewById(R.id.seek_framing);
        swLowLight = findViewById(R.id.sw_low_light);

        // 【5.1.0】智能方案 + 画面增强
        swPresetAuto = findViewById(R.id.sw_preset_auto);
        tvNightStart = findViewById(R.id.tv_night_start);
        tvNightEnd = findViewById(R.id.tv_night_end);
        swScreenEnhance = findViewById(R.id.sw_screen_enhance);
        seekScreenContrast = findViewById(R.id.seek_screen_contrast);
        seekScreenShadow = findViewById(R.id.seek_screen_shadow);
        seekScreenSat = findViewById(R.id.seek_screen_sat);
        tvScreenContrast = findViewById(R.id.tv_screen_contrast);
        tvScreenShadow = findViewById(R.id.tv_screen_shadow);
        tvScreenSat = findViewById(R.id.tv_screen_sat);

        tvWidth = findViewById(R.id.tv_width);
        seekWidth = findViewById(R.id.seek_width);
        tvMargin = findViewById(R.id.tv_margin);
        seekMargin = findViewById(R.id.seek_margin);

        tvOpacity = findViewById(R.id.tv_opacity);
        seekOpacity = findViewById(R.id.seek_opacity);

        swHud = findViewById(R.id.sw_hud);
        etHudText = findViewById(R.id.et_hud_text);
        tvHudSize = findViewById(R.id.tv_hud_size);
        seekHudSize = findViewById(R.id.seek_hud_size);

        swContrast = findViewById(R.id.sw_contrast);
        tvContrastLevel = findViewById(R.id.tv_contrast_level);
        seekContrastLevel = findViewById(R.id.seek_contrast_level);
    }

    // ==================== 读取 ====================

    private void loadConfig() {
        rearMode = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamMode,
                RearCameraOverlay.MODE_FULLSCREEN);
        if (rearMode == null) rearMode = RearCameraOverlay.MODE_FULLSCREEN;
        rearCorner = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamCorner,
                RearCameraOverlay.CORNER_BR);
        if (rearCorner == null) rearCorner = RearCameraOverlay.CORNER_BR;

        int width = readWidth();
        seekWidth.setProgress(width - RearCameraOverlay.SMALL_WIDTH_MIN_DP);
        int margin = readMargin();
        seekMargin.setProgress(margin);

        int opacity = SafePrefs.getInt(sp, SafeWalkPrefKey.rearCamOpacity, 50);
        seekOpacity.setProgress(Math.max(0, Math.min(90, opacity - 10)));

        swHud.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.previewHudEnabled, false));
        String text = SafePrefs.getString(sp, SafeWalkPrefKey.previewHudText, "");
        if (text == null) text = "";
        // 设置里存的是「用户实际输入」，空串代表使用默认文案。这里不要把默认文案
        // 写进输入框：否则用户会以为那是自己填的，清空后又会「变回来」。
        if (!text.equals(etHudText.getText().toString())) etHudText.setText(text);
        int sizeSp = SafePrefs.getInt(sp, SafeWalkPrefKey.previewHudTextSize,
                RearCameraOverlay.HUD_TEXT_SIZE_DEFAULT_SP);
        seekHudSize.setProgress(Math.max(0,
                Math.min(9, sizeSp - RearCameraOverlay.HUD_TEXT_SIZE_MIN_SP)));

        swContrast.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enablePreviewContrast, false));
        int level = SafePrefs.getInt(sp, SafeWalkPrefKey.previewContrastLevel, 50);
        seekContrastLevel.setProgress(Math.max(0, Math.min(100, level)));

        seekFraming.setProgress(readFraming());
        swLowLight.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.previewLowLight, false));

        // 【5.1.0 智能方案】默认开启：用户要的就是「不用管，它自己知道现在是白天还是晚上」。
        // 关掉后芯片恢复成手动三选一，运行时也不再覆盖任何参数。
        swPresetAuto.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.presetAuto, true));
        updateNightWindowLabels();

        // 【5.1.0 画面增强】默认关：这是逐像素的后处理，用户没要求就不该悄悄改变
        // 他看到的画面。三个强度值只在这个开关打开时才有意义。
        swScreenEnhance.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.previewScreenEnhance, false));
        seekScreenContrast.setProgress(clamp100(SafePrefs.getInt(sp,
                SafeWalkPrefKey.previewScreenContrast, RearCameraOverlay.SCREEN_CONTRAST_DEFAULT)));
        seekScreenShadow.setProgress(clamp100(SafePrefs.getInt(sp,
                SafeWalkPrefKey.previewScreenShadowLift, RearCameraOverlay.SCREEN_SHADOW_DEFAULT)));
        seekScreenSat.setProgress(clamp100(SafePrefs.getInt(sp,
                SafeWalkPrefKey.previewScreenSaturation, RearCameraOverlay.SCREEN_SATURATION_DEFAULT)));

        updateWidthLabel();
        updateMarginLabel();
        updateOpacityLabel();
        updateHudSizeLabel();
        updateContrastLabel();
        updateFramingLabel();
        updateScreenLabels();
    }

    private int clamp100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private int readNightHour(String key, int def) {
        int v = SafePrefs.getInt(sp, key, def);
        return ((v % 24) + 24) % 24;
    }

    /** 夜间时段两个按钮上的文字：小时数补零，避免出现 "9:00" 这种长短不一 */
    private void updateNightWindowLabels() {
        tvNightStart.setText(String.format(java.util.Locale.US, "%02d:00",
                readNightHour(SafeWalkPrefKey.nightTorchStartHour, 22)));
        tvNightEnd.setText(String.format(java.util.Locale.US, "%02d:00",
                readNightHour(SafeWalkPrefKey.nightTorchEndHour, 6)));
    }

    private int readFraming() {
        int v = SafePrefs.getInt(sp, SafeWalkPrefKey.previewFramingPct,
                RearCameraOverlay.FRAMING_DEFAULT_PCT);
        return Math.max(RearCameraOverlay.FRAMING_MIN_PCT,
                Math.min(RearCameraOverlay.FRAMING_MAX_PCT, v));
    }

    private int readBorder() {
        int v = SafePrefs.getInt(sp, SafeWalkPrefKey.previewBorderDp,
                RearCameraOverlay.BORDER_DEFAULT_DP);
        return Math.max(RearCameraOverlay.BORDER_MIN_DP,
                Math.min(RearCameraOverlay.BORDER_MAX_DP, v));
    }

    private int readWidth() {
        int v = SafePrefs.getInt(sp, SafeWalkPrefKey.previewSmallWidthDp,
                RearCameraOverlay.SMALL_WIDTH_DEFAULT_DP);
        return Math.max(RearCameraOverlay.SMALL_WIDTH_MIN_DP,
                Math.min(RearCameraOverlay.SMALL_WIDTH_MAX_DP, v));
    }

    private int readMargin() {
        int v = SafePrefs.getInt(sp, SafeWalkPrefKey.previewSmallMarginDp,
                RearCameraOverlay.SMALL_MARGIN_DEFAULT_DP);
        return Math.max(RearCameraOverlay.SMALL_MARGIN_MIN_DP,
                Math.min(RearCameraOverlay.SMALL_MARGIN_MAX_DP, v));
    }

    // ==================== 监听 ====================

    private void bindListeners() {
        for (int i = 0; i < PRESET_KEYS.length; i++) {
            final String kind = PRESET_KEYS[i];
            presetChips[i].setOnClickListener(v -> applyPreset(kind));
        }
        for (int i = 0; i < BORDER_LEVELS.length; i++) {
            final int dp = BORDER_LEVELS[i];
            borderChips[i].setOnClickListener(v -> selectBorder(dp));
        }
        // 【5.3.0】画面方向：三档互斥，点一下立刻生效（预览开着时下一帧就能看到）
        for (int i = 0; i < ROT_MODES.length; i++) {
            final String mode = ROT_MODES[i];
            rotChips[i].setOnClickListener(v -> selectRotation(mode));
        }

        seekFraming.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateFramingLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int pct = sb.getProgress();
                PreviewStyleActivity.this.sp.edit()
                        .putInt(SafeWalkPrefKey.previewFramingPct, pct).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 取景范围 → " + pct + "%");
                takeOverFromAuto("取景范围");
                refreshAll();
                previewIfEnabled();
            }
        });

        swLowLight.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.previewLowLight, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[style] 低光增强 → " + (c ? "开" : "关"));
            takeOverFromAuto("低光增强");
            previewIfEnabled();
        });
        findViewById(R.id.card_low_light).setOnClickListener(v -> swLowLight.toggle());

        seekWidth.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateWidthLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int dp = sb.getProgress() + RearCameraOverlay.SMALL_WIDTH_MIN_DP;
                sp.edit().putInt(SafeWalkPrefKey.previewSmallWidthDp, dp).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 小窗宽度 → " + dp + "dp");
                previewIfEnabled();
            }
        });

        seekMargin.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateMarginLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int dp = sb.getProgress();
                sp.edit().putInt(SafeWalkPrefKey.previewSmallMarginDp, dp).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 小窗边距 → " + dp + "dp");
                previewIfEnabled();
            }
        });

        seekOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateOpacityLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.rearCamOpacity, sb.getProgress() + 10).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                previewIfEnabled();
            }
        });

        swHud.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.previewHudEnabled, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[style] 状态文字 → " + (c ? "开" : "关"));
            refreshHudDependencies();
            previewIfEnabled();
        });
        findViewById(R.id.card_hud).setOnClickListener(v -> swHud.toggle());

        // 文字内容：输入过程中不落盘（否则每敲一个字就重建一次 HUD），
        // 失焦与离开页面时各落一次，兜住「输完直接按返回」的路径。
        etHudText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (binding) return;
                commitHudText(false);
            }
        });
        etHudText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !binding) commitHudText(true);
        });

        seekHudSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateHudSizeLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int sp = hudSizeFromProgress(sb.getProgress());
                PreviewStyleActivity.this.sp.edit()
                        .putInt(SafeWalkPrefKey.previewHudTextSize, sp).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 文字字号 → " + sp + "sp");
                previewIfEnabled();
            }
        });

        swContrast.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enablePreviewContrast, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[style] 文字对比度 → " + (c ? "开" : "关"));
            refreshHudDependencies();
            previewIfEnabled();
        });
        findViewById(R.id.card_contrast).setOnClickListener(v -> swContrast.toggle());

        seekContrastLevel.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateContrastLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int level = sb.getProgress();
                sp.edit().putInt(SafeWalkPrefKey.previewContrastLevel, level).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 文字描边强度 → " + level + "%");
                previewIfEnabled();
            }
        });

        // ==================== 5.1.0 智能方案 ====================

        swPresetAuto.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.presetAuto, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[style] 跟随环境自动切换 → " + (c ? "开" : "关"));
            refreshAll();
            previewIfEnabled();
        });
        findViewById(R.id.card_preset_auto).setOnClickListener(v -> swPresetAuto.toggle());

        tvNightStart.setOnClickListener(v -> pickHour(true));
        tvNightEnd.setOnClickListener(v -> pickHour(false));

        // ==================== 5.1.0 画面增强 ====================

        swScreenEnhance.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.previewScreenEnhance, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[style] 画面增强 → " + (c ? "开" : "关"));
            takeOverFromAuto("画面增强");
            refreshHudDependencies();
            previewIfEnabled();
        });
        findViewById(R.id.card_screen_enhance).setOnClickListener(v -> swScreenEnhance.toggle());

        seekScreenContrast.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateScreenLabels();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.previewScreenContrast, sb.getProgress()).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 画面对比度 → " + sb.getProgress() + "%");
                takeOverFromAuto("画面对比度");
                previewIfEnabled();
            }
        });

        seekScreenShadow.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateScreenLabels();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.previewScreenShadowLift, sb.getProgress()).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 暗部提亮 → " + sb.getProgress() + "%");
                takeOverFromAuto("暗部提亮");
                previewIfEnabled();
            }
        });

        seekScreenSat.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateScreenLabels();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.previewScreenSaturation, sb.getProgress()).commit();
                SafeWalkConfig.exportConfig(PreviewStyleActivity.this);
                DiagLog.log("[style] 饱和度 → " + sb.getProgress() + "%");
                takeOverFromAuto("饱和度");
                previewIfEnabled();
            }
        });
    }

    /**
     * 选夜间时段的起点/终点。
     *
     * 用系统 TimePickerDialog 而不是自己撸两个 SeekBar：小时选择只有 24 个值，
     * 系统控件自带大按钮、无障碍标签和深色适配，比自绘的更省事也更可靠。
     * 只取小时，分钟恒为 0 —— 步行的「天黑」不需要精确到分钟，多一个维度只是多一处要调。
     */
    private void pickHour(boolean isStart) {
        int keyDef = isStart ? 22 : 6;
        String key = isStart ? SafeWalkPrefKey.nightTorchStartHour : SafeWalkPrefKey.nightTorchEndHour;
        int cur = readNightHour(key, keyDef);
        new android.app.TimePickerDialog(this, (view, hour, minute) -> {
            sp.edit().putInt(key, hour).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[style] 夜间时段" + (isStart ? "开始" : "结束") + " → " + hour + ":00");
            updateNightWindowLabels();
            // 时段变了，智能方案此刻选中的是白天还是夜间可能就变了，芯片高亮要跟着刷新
            refreshPresetChips();
            refreshSummary();
            previewIfEnabled();
        }, cur, 0, true).show();
    }

    private void commitHudText(boolean refreshPreview) {
        String raw = etHudText.getText() == null ? "" : etHudText.getText().toString().trim();
        String old = SafePrefs.getString(sp, SafeWalkPrefKey.previewHudText, "");
        if (old == null) old = "";
        if (old.equals(raw)) return;
        sp.edit().putString(SafeWalkPrefKey.previewHudText, raw).commit();
        SafeWalkConfig.exportConfig(this);
        DiagLog.log("[style] 文字内容 → 「" + raw + "」");
        if (refreshPreview) previewIfEnabled();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 兜底：用户可能没失焦就直接返回
        if (!binding) commitHudText(true);
    }

    // ==================== 交互 ====================

    /**
     * 一键方案：把一组常用搭配一次性写进配置。
     *
     * 【5.1.0】参数表搬到 [PresetResolve]，本页和服务端共用同一份 ——
     * 上一版这里写死一套、服务端再读另一套默认值，出现「页面显示已套用夜间、
     * 小窗却按白天渲染」的分裂，根因就是同一件事写了两遍。
     *
     * 点手动方案会**顺手关掉自动跟随**：用户既然亲手选了，就不该被下一次
     * 系统切深色模式又改回去。这比「点了没反应」清楚得多。
     */
    private void applyPreset(String kind) {
        PresetResolve.Params p = PresetResolve.params(kind);
        boolean wasAuto = SafePrefs.getBool(sp, SafeWalkPrefKey.presetAuto, true);
        sp.edit()
                .putInt(SafeWalkPrefKey.previewFramingPct, p.framing)
                .putInt(SafeWalkPrefKey.previewBorderDp, p.borderDp)
                .putBoolean(SafeWalkPrefKey.previewLowLight, p.lowLight)
                .putBoolean(SafeWalkPrefKey.previewHudEnabled, p.hud)
                .putBoolean(SafeWalkPrefKey.previewScreenEnhance, p.screenEnhance)
                .putInt(SafeWalkPrefKey.previewScreenContrast, p.screenContrast)
                .putInt(SafeWalkPrefKey.previewScreenShadowLift, p.screenShadowLift)
                .putInt(SafeWalkPrefKey.previewScreenSaturation, p.screenSaturation)
                .putBoolean(SafeWalkPrefKey.presetAuto, false)
                .commit();
        SafeWalkConfig.exportConfig(this);
        String name = "day".equals(kind) ? "白天" : ("night".equals(kind) ? "夜间" : "极简");
        DiagLog.log("[style] 一键方案「" + name + "」→ 取景=" + p.framing + "% 描边="
                + p.borderDp + "dp 低光=" + p.lowLight + " 文字=" + p.hud
                + " 画面增强=" + p.screenEnhance + "(" + p.screenContrast + "%/"
                + p.screenShadowLift + "%/" + p.screenSaturation + "%)"
                + (wasAuto ? " | 已关闭自动跟随" : ""));

        // 回填控件：binding 期间不写盘，否则每设一个控件就落一次库
        binding = true;
        seekFraming.setProgress(p.framing);
        swLowLight.setChecked(p.lowLight);
        swHud.setChecked(p.hud);
        swScreenEnhance.setChecked(p.screenEnhance);
        seekScreenContrast.setProgress(p.screenContrast);
        seekScreenShadow.setProgress(p.screenShadowLift);
        seekScreenSat.setProgress(p.screenSaturation);
        swPresetAuto.setChecked(false);
        binding = false;

        updateFramingLabel();
        updateScreenLabels();
        refreshAll();
        previewIfEnabled();
        android.widget.Toast.makeText(this,
                "已套用「" + name + "」方案" + (wasAuto ? "，自动跟随已关闭" : ""),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void selectBorder(int dp) {
        sp.edit().putInt(SafeWalkPrefKey.previewBorderDp, dp).commit();
        SafeWalkConfig.exportConfig(this);
        DiagLog.log("[style] 小窗描边 → " + dp + "dp");
        takeOverFromAuto("描边");
        refreshAll();
        previewIfEnabled();
    }

    /**
     * 【5.3.0 画面方向】切档立即生效。
     *
     * 刻意**不**调 [takeOverFromAuto]：方向不属于「白天/夜间」那套方案参数
     * （PresetResolve.Params 里没有它），改方向不该顺手把智能方案关掉。
     * 一个装在车载支架上横放的手机，用户需要的是「固定竖屏」+ 智能方案继续跟随，
     * 而不是二选一。
     */
    private void selectRotation(String mode) {
        String v = PreviewOrientationPolicy.normalizeMode(mode);
        sp.edit().putString(SafeWalkPrefKey.previewRotationMode, v).commit();
        SafeWalkConfig.exportConfig(this);
        DiagLog.log("[style] 画面方向 → " + v);
        refreshAll();
        previewIfEnabled();
    }

    /**
     * 【5.1.0】手动调参 → 自动跟随让位。
     *
     * 这是「自动」最容易变成「碍事」的地方：自动跟随开着时，用户拖对比度到 90%，
     * 下一次显示预览时 doShow 会按智能方案重新算成 45% —— 用户看到的是「拖了没反应」，
     * 而这正是上一版被吐槽的模式（改一个滑块，画面纹丝不动）。
     *
     * 所以规则统一成一句话：**只要用户亲手动了方案里的任何一项，就退出自动**。
     * 和「点方案按钮会关闭自动」是同一个语义，不需要用户额外理解两套规则。
     *
     * @param what 改的是哪一项，只用于日志和提示
     */
    private void takeOverFromAuto(String what) {
        if (binding) return;
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.presetAuto, true)) return;
        sp.edit().putBoolean(SafeWalkPrefKey.presetAuto, false).commit();
        binding = true;
        swPresetAuto.setChecked(false);
        binding = false;
        DiagLog.log("[style] 手动调整「" + what + "」→ 关闭自动跟随，改为手动固定");
        updatePresetHint(matchManualPreset());
        android.widget.Toast.makeText(this,
                "已改为手动：调过「" + what + "」后不再自动跟随",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 参数改动后静默拉一次预览，让用户当场看到变化。
     * 总开关或行走模式开关没开时不拉起 —— 否则调个滑块凭空弹出画面，很吓人。
     */
    private void previewIfEnabled() {
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)) return;
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)) return;
        WalkPreviewService.start(this);
        WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, rearMode);
    }

    // ==================== 刷新 ====================

    private void refreshAll() {
        refreshBorderChips();
        refreshRotationChips();
        refreshPresetChips();
        refreshHudDependencies();
        refreshSummary();
    }

    /** 描边 chip 高亮：与配置里的 dp 值对齐 */
    private void refreshBorderChips() {
        int cur = readBorder();
        for (int i = 0; i < BORDER_LEVELS.length; i++) {
            paintChip(borderChips[i], BORDER_LEVELS[i] == cur);
        }
    }

    /**
     * 【5.3.0】方向 chip 高亮。
     *
     * 直接读配置、按档位匹配，不做「点过哪个记哪个」：配置可能来自
     * 旧版本导出、手动编辑或换机恢复，显示的必须是**真正生效**的那一档。
     */
    private void refreshRotationChips() {
        String cur = PreviewOrientationPolicy.normalizeMode(
                SafePrefs.getString(sp, SafeWalkPrefKey.previewRotationMode,
                        PreviewOrientationPolicy.MODE_AUTO));
        for (int i = 0; i < ROT_MODES.length; i++) {
            paintChip(rotChips[i], ROT_MODES[i].equals(cur));
        }
    }

    /**
     * 方案 chip 高亮。
     *
     * 【5.1.0】两种语义要分开：
     *  · 自动跟随开着 → 高亮「此刻真正生效」的那一个。用户在 21 点打开这个页面
     *    看到「夜间」亮着，就能立刻明白「哦，它已经自己切了」，不用去猜；
     *  · 自动跟随关着 → 按参数匹配，四项全等才亮。用户点完方案又手动拖了一下
     *    取景范围，那个方案就已经不是生效中的状态，继续高亮等于骗人。
     */
    private void refreshPresetChips() {
        String active;
        if (swPresetAuto.isChecked()) {
            active = PresetResolve.resolveParams(
                    isSystemDark(),
                    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                    readNightHour(SafeWalkPrefKey.nightTorchStartHour, 22),
                    readNightHour(SafeWalkPrefKey.nightTorchEndHour, 6)).kind;
        } else {
            active = matchManualPreset();
        }
        for (int i = 0; i < presetChips.length; i++) {
            paintChip(presetChips[i], PRESET_KEYS[i].equals(active));
        }
        updatePresetHint(active);
    }

    /** 当前参数正好等于哪个方案；都不等于返回 null（一个都不亮） */
    private String matchManualPreset() {
        int framing = readFraming();
        int border = readBorder();
        boolean low = SafePrefs.getBool(sp, SafeWalkPrefKey.previewLowLight, false);
        boolean enhance = SafePrefs.getBool(sp, SafeWalkPrefKey.previewScreenEnhance, false);
        // 画面增强的三个强度也必须一起比：只比开关会导致「开关对得上、滑块被拖变了」
        // 时仍然高亮那个方案 —— 用户看到的是一套参数，实际生效的是另一套。
        int contrast = clamp100(SafePrefs.getInt(sp,
                SafeWalkPrefKey.previewScreenContrast, RearCameraOverlay.SCREEN_CONTRAST_DEFAULT));
        int shadow = clamp100(SafePrefs.getInt(sp,
                SafeWalkPrefKey.previewScreenShadowLift, RearCameraOverlay.SCREEN_SHADOW_DEFAULT));
        int sat = clamp100(SafePrefs.getInt(sp,
                SafeWalkPrefKey.previewScreenSaturation, RearCameraOverlay.SCREEN_SATURATION_DEFAULT));
        for (String kind : PRESET_KEYS) {
            PresetResolve.Params p = PresetResolve.params(kind);
            if (p.framing == framing && p.borderDp == border && p.lowLight == low
                    && p.screenEnhance == enhance
                    && p.screenContrast == contrast && p.screenShadowLift == shadow
                    && p.screenSaturation == sat) {
                return kind;
            }
        }
        return null;
    }

    private void updatePresetHint(String active) {
        TextView hint = findViewById(R.id.tv_preset_hint);
        if (hint == null) return;
        if (!swPresetAuto.isChecked()) {
            hint.setText("白天=完整取景+细描边；夜间=低光增强+只看路面+粗描边+画面增强；极简=不描边、不增强。点一下覆盖下方对应参数");
            return;
        }
        boolean dark = isSystemDark();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int s = readNightHour(SafeWalkPrefKey.nightTorchStartHour, 22);
        int e = readNightHour(SafeWalkPrefKey.nightTorchEndHour, 6);
        String kindName = PresetResolve.NIGHT.equals(active) ? "夜间" : "白天";
        hint.setText("自动跟随中，现在按「" + kindName + "」生效 —— "
                + PresetResolve.reason(dark, hour, s, e)
                + "。点下面任一方案会关闭自动跟随，改为手动固定");
    }

    private boolean isSystemDark() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 联动置灰：全屏时尺寸/边距无意义；文字关时字号/内容/对比度无意义 */
    private void refreshHudDependencies() {
        boolean small = RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode);
        setEnabledState(seekWidth, small);
        setEnabledState(seekMargin, small);
        setEnabledState(tvWidth, small);
        setEnabledState(tvMargin, small);
        // 描边只作用于小窗（全屏描边会贴在屏幕边缘，像画面没铺满），全屏时置灰
        for (TextView chip : borderChips) {
            chip.setAlpha(small ? 1f : 0.45f);
        }

        boolean hudOn = swHud.isChecked();
        setEnabledState(etHudText, hudOn);
        setEnabledState(seekHudSize, hudOn);
        setEnabledState(tvHudSize, hudOn);
        // 对比度只影响文字，没有文字时它什么也做不了 —— 直接置灰，
        // 而不是让用户拖半天进度条发现毫无变化。
        setEnabledState(swContrast, hudOn);
        setEnabledState(seekContrastLevel, hudOn && swContrast.isChecked());
        setEnabledState(tvContrastLevel, hudOn && swContrast.isChecked());

        // 【5.1.0】画面增强三个滑块：总开关关着时置灰。
        // 与上面文字那组是两条独立支线 —— 画面清楚和文字清楚互不依赖。
        boolean enhanceOn = swScreenEnhance.isChecked();
        setEnabledState(seekScreenContrast, enhanceOn);
        setEnabledState(seekScreenShadow, enhanceOn);
        setEnabledState(seekScreenSat, enhanceOn);
        setEnabledState(tvScreenContrast, enhanceOn);
        setEnabledState(tvScreenShadow, enhanceOn);
        setEnabledState(tvScreenSat, enhanceOn);

        refreshSummary();
    }

    private void refreshSummary() {
        boolean small = RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode);
        String base;
        if (small) {
            base = "角落小窗 · " + cornerName(rearCorner) + " · " + readWidth() + "dp · 边距 "
                    + readMargin() + "dp · 取景：" + framingWord(readFraming());
        } else {
            base = "全屏滤镜 · 不透明度 "
                    + (SafePrefs.getInt(sp, SafeWalkPrefKey.rearCamOpacity, 50)) + "% · 取景"
                    + "：" + framingWord(readFraming());
        }
        if (small && readBorder() > 0) base += " · 描边 " + readBorder() + "dp";
        if (swLowLight.isChecked()) base += " · 低光增强";
        // 【5.3.0】方向只在非「自动」时写进摘要：自动是常态，天天显示等于噪音；
        // 而一旦用户动过它，就必须在摘要里留痕，否则「我上次是不是改过」只能靠猜。
        String rot = PreviewOrientationPolicy.normalizeMode(
                SafePrefs.getString(sp, SafeWalkPrefKey.previewRotationMode,
                        PreviewOrientationPolicy.MODE_AUTO));
        if (PreviewOrientationPolicy.MODE_FIXED.equals(rot)) {
            base += " · 方向固定竖屏";
        } else if (PreviewOrientationPolicy.MODE_FLIP.equals(rot)) {
            base += " · 方向翻转 180°";
        }
        // 【5.1.0】画面增强在摘要里必须出现，且写明它动的是画面 ——
        // 否则用户会以为「增强」还是指那行文字，改了也没人知道区别在哪。
        if (swScreenEnhance.isChecked()) {
            base += " · 画面增强（对比 " + seekScreenContrast.getProgress()
                    + "% / 暗部 " + seekScreenShadow.getProgress()
                    + "% / 饱和 " + seekScreenSat.getProgress() + "%）";
        }
        if (swHud.isChecked()) {
            String text = etHudText.getText() == null ? "" : etHudText.getText().toString().trim();
            if (text.isEmpty()) text = SafeWalkPrefKey.PREVIEW_HUD_TEXT_DEFAULT;
            base += " · 文字「" + text + "」";
        }
        tvStyleSummary.setText(base + "。改完立即生效。");
    }

    private String cornerName(String key) {
        if (RearCameraOverlay.CORNER_TL.equals(key)) return "左上";
        if (RearCameraOverlay.CORNER_TR.equals(key)) return "右上";
        if (RearCameraOverlay.CORNER_BL.equals(key)) return "左下";
        return "右下";
    }

    private void paintChip(TextView chip, boolean on) {
        chip.setBackgroundResource(on ? R.drawable.bg_choice_chip_on : R.drawable.bg_choice_chip);
        chip.setTextColor(getColor(on ? R.color.card_bg : R.color.text_body));
    }

    private void setEnabledState(View v, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.45f);
    }

    // ==================== 标签 ====================

    private void updateWidthLabel() {
        tvWidth.setText("宽度 · " + (seekWidth.getProgress() + RearCameraOverlay.SMALL_WIDTH_MIN_DP) + "dp");
    }

    private void updateMarginLabel() {
        tvMargin.setText("距屏幕边缘 · " + seekMargin.getProgress() + "dp");
    }

    private void updateOpacityLabel() {
        tvOpacity.setText("画面不透明度 · " + (seekOpacity.getProgress() + 10) + "%");
    }

    private void updateHudSizeLabel() {
        tvHudSize.setText("字号 · " + hudSizeFromProgress(seekHudSize.getProgress()) + "sp");
    }

    private void updateContrastLabel() {
        int p = seekContrastLevel.getProgress();
        String word = p < 30 ? "轻微" : (p < 70 ? "适中" : "强烈");
        tvContrastLevel.setText(p + "% · " + word);
    }

    private void updateFramingLabel() {
        int p = seekFraming.getProgress();
        tvFraming.setText("取景范围 · " + framingWord(p) + "（" + p + "%）");
    }

    /** 【5.1.0】画面增强三个滑块的读值文案。0 和 50 这两个「等于没改」的值单独标注 */
    private void updateScreenLabels() {
        int c = seekScreenContrast.getProgress();
        tvScreenContrast.setText("对比度 · " + c + "%" + (c == 0 ? "（原始）" : ""));
        int sh = seekScreenShadow.getProgress();
        tvScreenShadow.setText("暗部提亮 · " + sh + "%" + (sh == 0 ? "（不提亮）" : ""));
        int st = seekScreenSat.getProgress();
        tvScreenSat.setText("饱和度 · " + st + "%" + (st == 50 ? "（原始）" : ""));
    }

    /**
     * 把 0~100 的连续值说成人话。
     *
     * 分档只是给一个直观印象，实际值仍按百分比下发，不会因为「档」而取整 ——
     * 用户拖到 47% 就该是 47%，不该被吸附成 40%。
     */
    private String framingWord(int pct) {
        if (pct <= 0) return "完整画面";
        if (pct <= 40) return "轻微收窄";
        if (pct <= 75) return "只看路面";
        return "贴近脚下";
    }

    private int hudSizeFromProgress(int progress) {
        // SeekBar max=9，0..9 正好对应 11..20sp，一档一 sp，不会出现拖了没反应的空档
        return RearCameraOverlay.HUD_TEXT_SIZE_MIN_SP + progress;
    }

    /** 让 SeekBar 的两个默认回调不必每次都写空实现 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}
    }
}
