package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

/**
 * 行走模式二级菜单。
 *
 * 原首页「行走模式」磁贴收进这里，同时把手电静置时长之外的行走相关参数
 * （形态、位置、透明度、预览、步行判定阈值）统一在一页管理。
 *
 * 所有改动即时写入并导出 JSON，Hook 端 2 秒轮询内生效。
 */
public final class WalkModeActivity extends Activity {

    private SharedPreferences sp;

    private SwitchCompat swRearCam, swAuto;
    private TextView heroSummary, tvAutoDesc, tvCornerTitle, tvOpacity, tvSteps, tvStatic;
    private TextView tvCamPermHint, tvPreviewStage;
    private SeekBar seekOpacity, seekSteps, seekStatic;
    private TextView[] modeChips, posChips;

    private String rearMode = "fullscreen";
    private String rearCorner = "bottom_right";

    private boolean binding = true;

    private static final String[] POS_KEYS = {"bottom_right", "bottom_left", "top_right", "top_left"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_walk_mode);
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);

        bindViews();
        loadConfig();
        bindListeners();
        binding = false;

        refreshAll();
        DiagLog.init(getApplicationContext());
        DiagLog.log("[walk] 打开行走模式页 rearCam=" + swRearCam.isChecked()
                + " auto=" + swAuto.isChecked()
                + " mode=" + rearMode
                + " opacity=" + (seekOpacity.getProgress() + 10)
                + " master=" + SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)
                + " 日志=" + DiagLog.status());
    }

    private void bindViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        heroSummary = findViewById(R.id.hero_walk_summary);
        tvAutoDesc = findViewById(R.id.tv_auto_desc);
        tvCornerTitle = findViewById(R.id.tv_corner_title);
        tvOpacity = findViewById(R.id.tv_opacity);
        tvSteps = findViewById(R.id.tv_steps);
        tvStatic = findViewById(R.id.tv_static);

        // 这一行回答「预览现在归谁管」：相机和悬浮窗都在本应用进程，
        // 缺了权限就把原因摆在脸上，不让用户对着黑框猜功能是不是假的。
        tvCamPermHint = findViewById(R.id.tv_cam_perm_hint);
        tvCamPermHint.setOnClickListener(v -> showCameraPermissionHelp());

        tvPreviewStage = findViewById(R.id.tv_preview_stage);

        swRearCam = findViewById(R.id.sw_rear_cam);
        swAuto = findViewById(R.id.sw_auto);

        seekOpacity = findViewById(R.id.seek_opacity);
        seekSteps = findViewById(R.id.seek_steps);
        seekStatic = findViewById(R.id.seek_static);

        modeChips = new TextView[]{
                findViewById(R.id.chip_mode_full),
                findViewById(R.id.chip_mode_small)
        };
        posChips = new TextView[]{
                findViewById(R.id.chip_pos_br),
                findViewById(R.id.chip_pos_bl),
                findViewById(R.id.chip_pos_tr),
                findViewById(R.id.chip_pos_tl)
        };

        findViewById(R.id.card_rear_cam).setOnClickListener(v -> swRearCam.toggle());
        // 总开关关闭时整行禁用，父容器点击也要拦住，否则能绕过置灰状态。
        // 但拦住之后必须给一句说明——「点了完全没反应」和功能坏了在用户眼里是一回事。
        findViewById(R.id.card_auto).setOnClickListener(v -> {
            if (swAuto.isEnabled()) swAuto.toggle();
            else Toast.makeText(this, "先打开上方的行走模式开关", Toast.LENGTH_SHORT).show();
        });

        modeChips[0].setOnClickListener(v -> selectMode(RearCameraOverlay.MODE_FULLSCREEN));
        modeChips[1].setOnClickListener(v -> selectMode(RearCameraOverlay.MODE_SMALLWINDOW));

        for (int i = 0; i < POS_KEYS.length; i++) {
            final String key = POS_KEYS[i];
            posChips[i].setOnClickListener(v -> selectCorner(key));
        }

        findViewById(R.id.btn_preview_full).setOnClickListener(
                v -> sendPreview("open_full"));
        findViewById(R.id.btn_preview_small).setOnClickListener(
                v -> sendPreview("open_small"));
        findViewById(R.id.btn_preview_close).setOnClickListener(
                v -> sendPreview("close"));

        // 重启系统界面：整卡可点，右侧按钮只是视觉提示（点按钮会冒泡到卡片）
        View restartCard = findViewById(R.id.card_restart_systemui);
        restartCard.setOnClickListener(v -> confirmRestart());
    }

    private void loadConfig() {
        // 统一走 SafePrefs：类型对就用原生读取，历史上类型变过（int↔float）也不会 CCE
        swRearCam.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false));
        swAuto.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, true));

        rearMode = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamMode, RearCameraOverlay.MODE_FULLSCREEN);
        if (rearMode == null) rearMode = RearCameraOverlay.MODE_FULLSCREEN;
        rearCorner = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamCorner, "bottom_right");
        if (rearCorner == null) rearCorner = "bottom_right";

        int opacity = SafePrefs.getInt(sp, SafeWalkPrefKey.rearCamOpacity, 50);
        opacity = Math.max(10, Math.min(100, opacity));
        seekOpacity.setProgress(opacity - 10);

        int steps = SafePrefs.getInt(sp, SafeWalkPrefKey.stepThreshold, 50);
        steps = Math.max(20, Math.min(200, steps));
        seekSteps.setProgress(steps - 20);

        int staticSec = SafePrefs.getInt(sp, SafeWalkPrefKey.staticResetSec, 20);
        staticSec = Math.max(5, Math.min(60, staticSec));
        seekStatic.setProgress(staticSec - 5);

        updateOpacityLabel(opacity);
        updateStepsLabel(steps);
        updateStaticLabel(staticSec);
    }

    private void bindListeners() {
        swRearCam.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[walk] 行走模式开关 → " + (c ? "开" : "关"));
            if (c) {
                // 开关打开 ≠ 立刻弹相机：先走权限检查，缺什么补什么，
                // 两样都齐了才启动预览服务
                ensurePermissionsThenStart();
            } else {
                WalkPreviewService.send(this, WalkPreviewService.ACTION_STOP, null);
                tvPreviewStage.setVisibility(View.GONE);
            }
            refreshDependencies();
        });

        swAuto.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.rearCamAuto, c).commit();
            SafeWalkConfig.exportConfig(this);
            refreshDependencies();
        });

        seekOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateOpacityLabel(progress + 10);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // 透明度会立即刷新到悬浮窗，拖动结束才写盘，避免频繁 IO
                sp.edit().putInt(SafeWalkPrefKey.rearCamOpacity, sb.getProgress() + 10).commit();
                SafeWalkConfig.exportConfig(WalkModeActivity.this);
                previewIfEnabled();
            }
        });

        seekSteps.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateStepsLabel(progress + 20);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.stepThreshold, sb.getProgress() + 20).commit();
                SafeWalkConfig.exportConfig(WalkModeActivity.this);
            }
        });

        seekStatic.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateStaticLabel(progress + 5);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.staticResetSec, sb.getProgress() + 5).commit();
                SafeWalkConfig.exportConfig(WalkModeActivity.this);
            }
        });
    }

    // ========== 选择项 ==========

    private void selectMode(String mode) {
        // 开关没开时不再"点了没反应"，直接说明为什么
        if (!swRearCam.isChecked()) {
            Toast.makeText(this, "先打开上方的行走模式开关", Toast.LENGTH_SHORT).show();
            return;
        }
        rearMode = mode;
        sp.edit().putString(SafeWalkPrefKey.rearCamMode, mode).commit();
        SafeWalkConfig.exportConfig(this);
        refreshAll();
        previewIfEnabled();
    }

    private void selectCorner(String corner) {
        if (!swRearCam.isChecked()) {
            Toast.makeText(this, "先打开上方的行走模式开关", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode)) {
            Toast.makeText(this, "切到「角落小窗」后才能选位置", Toast.LENGTH_SHORT).show();
            return;
        }
        rearCorner = corner;
        sp.edit().putString(SafeWalkPrefKey.rearCamCorner, corner).commit();
        SafeWalkConfig.exportConfig(this);
        refreshAll();
        previewIfEnabled();
    }

    /** 参数改动后静默拉一次预览，让用户当场看到形态/位置/透明度的变化 */
    private void previewIfEnabled() {
        if (!swRearCam.isChecked()) return;
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)) return;
        startPreviewService(RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode)
                ? "smallwindow" : "fullscreen");
    }

    /**
     * 显示/刷新预览：直接指挥 App 进程里的预览服务。
     *
     * 预览已不在 SystemUI 进程：相机和悬浮窗都归 [WalkPreviewService]，
     * 吃本应用自己申请的权限。服务没起来时 startService 会把它拉起来，
     * 两条指令按顺序进 onStartCommand，先 goForeground 再显示。
     */
    private void startPreviewService(String mode) {
        DiagLog.log("[walk] 请求预览 mode=" + mode
                + " camPerm=" + (checkSelfPermission(android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED)
                + " overlay=" + android.provider.Settings.canDrawOverlays(this));
        WalkPreviewService.start(this);
        WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, mode);
    }

    // ========== 权限申请 ==========

    private static final int REQ_CAMERA = 2001;

    /** 相机权限 + 悬浮窗权限都齐了才启动预览服务，缺哪样补哪样 */
    private void ensurePermissionsThenStart() {
        // 总开关关着时只记录偏好、不弹预览。
        // 否则用户在这里打开开关，预览窗口闪一下就被 Hook 收掉
        //（masterEnabled=false 时 updateOverlay 立刻发 hide），
        // 体感完全是「这功能坏了」，而且两份逻辑还互相打架。
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)) {
            Toast.makeText(this, "总开关已关闭：设置已记录，但不会生效", Toast.LENGTH_LONG).show();
            return;
        }
        boolean camOk = checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean overlayOk = android.provider.Settings.canDrawOverlays(this);

        if (!camOk) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (!overlayOk) {
            askOverlayPermission();
            return;
        }
        startPreviewService(RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode)
                ? "smallwindow" : "fullscreen");
    }

    private void askOverlayPermission() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("需要「显示在其他应用上层」权限")
                .setMessage("身后路况要叠在屏幕上显示，必须开启悬浮窗权限。\n\n"
                        + "点「去开启」后在列表里找到「安全出行」并允许。")
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        Toast.makeText(this, "跳转失败，请到系统设置里手动开启", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DiagLog.log("[walk] 相机权限已授予");
            updateCameraPermissionHint(swRearCam.isChecked());
            if (android.provider.Settings.canDrawOverlays(this)) {
                startPreviewService(RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode)
                        ? "smallwindow" : "fullscreen");
            } else {
                askOverlayPermission();
            }
        } else {
            DiagLog.log("[walk] 相机权限被拒绝");
            Toast.makeText(this,
                    "没有相机权限，预览开不起来。稍后点「预览相机」那一行可以重新授权",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ========== 预览诊断行 ==========

    /** 服务端预览链路分步诊断（窗口挂载/相机打开/收到画面…），广播推过来 */
    private static final String ACTION_PREVIEW_STAGE = "io.github.wumisc.safewalk.PREVIEW_STAGE";

    private android.content.BroadcastReceiver previewStageReceiver;

    /** 手动预览按钮：直接指挥本应用的预览服务 */
    private void sendPreview(String action) {
        if (!swRearCam.isChecked()) {
            Toast.makeText(this, "请先打开行走模式开关", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)) {
            Toast.makeText(this, "总开关已关闭，预览不会生效。请先在首页打开总开关", Toast.LENGTH_LONG).show();
            return;
        }
        boolean camOk = checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean overlayOk = android.provider.Settings.canDrawOverlays(this);
        if (!camOk || !overlayOk) {
            Toast.makeText(this,
                    camOk ? "先开启悬浮窗权限（点上方红色提示行）" : "先授权相机（点上方红色提示行）",
                    Toast.LENGTH_LONG).show();
            updateCameraPermissionHint(true);
            return;
        }
        String mode = "open_full".equals(action) ? "fullscreen"
                : ("open_small".equals(action) ? "smallwindow" : null);
        if (mode == null) {
            WalkPreviewService.send(this, WalkPreviewService.ACTION_HIDE, null);
            Toast.makeText(this, "已关闭预览", Toast.LENGTH_SHORT).show();
        } else {
            startPreviewService(mode);
            Toast.makeText(this, "预览已调出", Toast.LENGTH_SHORT).show();
        }
    }

    // ========== 重启系统界面 ==========

    private void confirmRestart() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("重启系统界面？")
                .setMessage("状态栏、导航栏会消失 2~5 秒后自动恢复，正在进行的操作不会中断，已保存的设置也不会丢。\n\n"
                        + "适合这几种情况：刚在 LSPosed 里勾上作用域、改了开关一直不生效、悬浮窗卡住不消失。")
                .setPositiveButton("重启", (d, w) -> doRestart())
                .setNegativeButton("取消", null)
                .show();
    }

    private void doRestart() {
        Toast.makeText(this, "正在重启系统界面…", Toast.LENGTH_SHORT).show();
        SystemUiRestarter.restart(this, (ok, viaRoot) -> {
            if (isFinishing() || isDestroyed()) return;
            if (ok) {
                Toast.makeText(this,
                        viaRoot ? "已通过 root 重启系统界面" : "系统界面正在重启",
                        Toast.LENGTH_SHORT).show();
            } else {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("没能重启")
                        .setMessage("没收到模块响应，也没拿到 root 权限。\n\n请检查：\n"
                                + "① LSPosed 里已启用 SafeWalk\n"
                                + "② 作用域包含「系统界面」\n\n"
                                + "或者直接手动重启手机。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    // ========== 显示刷新 ==========

    private void refreshAll() {
        refreshDependencies();
        refreshModeChips();
        refreshPosChips();
    }

    private void refreshDependencies() {
        boolean rearOn = swRearCam.isChecked();
        boolean auto = swAuto.isChecked();
        boolean small = RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode);
        boolean master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true);

        if (!master) {
            // 这里的开关打开也没用，Hook 端第一行就 return 了
            // （之前不说，用户会以为「开了没反应是坏了」）
            heroSummary.setText("总开关已关闭 · 行走模式不生效");
        } else {
            heroSummary.setText(rearOn
                    ? (auto ? "行走模式已开启 · 自动" : "行走模式已开启 · 常显")
                    : "行走模式已关闭");
        }

        tvAutoDesc.setText(auto ? "走起来自动开，停下自动收" : "关闭后常显，需手动关闭");

        setEnabledState(swAuto, rearOn);
        // 形态/位置 chip 保持可点（只是变淡），否则用户点上去毫无反馈，
        // 和「点了没反应」是同一种观感问题
        setDimmed(modeChips[0], rearOn);
        setDimmed(modeChips[1], rearOn);
        for (TextView chip : posChips) setDimmed(chip, rearOn && small);
        setEnabledState(seekOpacity, rearOn);

        tvCornerTitle.setAlpha(small ? 1f : 0.4f);

        updateCameraPermissionHint(rearOn);
    }

    /**
     * 刷新「预览相机」那行状态。
     *
     * 只在行走模式开关打开时显示：开关关着的时候这行没有意义。
     * 相机现在就是本应用自己的了：缺 CAMERA 缺悬浮窗都要说在明面上。
     */
    private void updateCameraPermissionHint(boolean rearOn) {
        if (tvCamPermHint == null) return;
        if (!rearOn) {
            tvCamPermHint.setVisibility(View.GONE);
            return;
        }
        tvCamPermHint.setVisibility(View.VISIBLE);
        boolean camOk = checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean overlayOk = android.provider.Settings.canDrawOverlays(this);
        if (!camOk) {
            tvCamPermHint.setText("预览相机：未授权，点此授权 ›");
            tvCamPermHint.setTextColor(getColor(R.color.seal_red));
        } else if (!overlayOk) {
            tvCamPermHint.setText("悬浮窗权限未开启，点此去开启 ›");
            tvCamPermHint.setTextColor(getColor(R.color.seal_red));
        } else {
            tvCamPermHint.setText("预览相机：已授权");
            tvCamPermHint.setTextColor(getColor(R.color.text_muted));
        }
    }

    /** 点权限状态行：缺什么就补什么，都齐了就弹原理说明 */
    private void showCameraPermissionHelp() {
        boolean camOk = checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean overlayOk = android.provider.Settings.canDrawOverlays(this);
        if (!camOk) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (!overlayOk) {
            askOverlayPermission();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("预览是怎么看到路的")
                .setMessage("画面由本应用自己采集：申请相机权限后，App 打开后置摄像头，"
                        + "把画面叠在屏幕上（全屏半透明或角落小窗）。\n\n"
                        + "「自动检测步行」开着时，走起来自动亮出画面、停下自动收起——"
                        + "判定由系统界面里的模块完成，它不再碰相机。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void refreshModeChips() {
        boolean full = RearCameraOverlay.MODE_FULLSCREEN.equals(rearMode);
        paintChip(modeChips[0], full);
        paintChip(modeChips[1], !full);
    }

    private void refreshPosChips() {
        for (int i = 0; i < POS_KEYS.length; i++) {
            paintChip(posChips[i], POS_KEYS[i].equals(rearCorner));
        }
    }

    private void paintChip(TextView chip, boolean on) {
        chip.setBackgroundResource(on ? R.drawable.bg_choice_chip_on : R.drawable.bg_choice_chip);
        chip.setTextColor(getColor(on ? R.color.card_bg : R.color.text_body));
    }

    private void setEnabledState(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.4f);
        v.setClickable(enabled);
    }

    /** 变淡但仍可点：点下去会给出「为什么现在用不了」的提示，而不是彻底没反应 */
    private void setDimmed(View v, boolean usable) {
        v.setEnabled(true);
        v.setClickable(true);
        v.setAlpha(usable ? 1f : 0.45f);
    }

    private void updateOpacityLabel(int value) {
        tvOpacity.setText("画面透明度 " + value + "%");
    }

    private void updateStepsLabel(int value) {
        tvSteps.setText("5 分钟内累计 " + value + " 步触发判定");
    }

    private void updateStaticLabel(int value) {
        tvStatic.setText("静置 " + value + " 秒判定为停下");
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 诊断行只在页面活着的时候收：用户就是在这个页面上点预览的，
        // 服务执行也就在那几百毫秒里，页面在就够用了，不必常驻
        if (previewStageReceiver == null) {
            previewStageReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (!SafeWalkPrefKey.BROADCAST_TOKEN.equals(
                            i.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN))) return;
                    String step = i.getStringExtra("step");
                    if (step == null || tvPreviewStage == null) return;
                    tvPreviewStage.setVisibility(View.VISIBLE);
                    tvPreviewStage.setText("预览诊断： " + step);
                }
            };
            // 诊断由本应用自己的服务进程发来，同进程；这里仍走 exported 注册
            // 并校验令牌，避免将来别的应用伪造诊断内容刷屏
            ReceiverCompat.registerExported(this, previewStageReceiver,
                    new android.content.IntentFilter(ACTION_PREVIEW_STAGE));
        }
    }

    @Override
    protected void onDestroy() {
        if (previewStageReceiver != null) {
            try { unregisterReceiver(previewStageReceiver); } catch (Throwable ignored) {}
            previewStageReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 权限可能在页面之外被改动（adb 授权、系统更新重置权限），
        // 回到页面重新读一次，不然这行状态会一直停在打开页面那一刻的结论
        if (!binding) refreshDependencies();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 三个滑块可能是在拖动中被切走的，onStopTrackingTouch 不一定触发。
        // 只 exportConfig 兜不住：JSON 是从 SP 读出来的，SP 里还是旧值。
        if (!binding && seekOpacity != null) {
            sp.edit()
                    .putInt(SafeWalkPrefKey.rearCamOpacity, seekOpacity.getProgress() + 10)
                    .putInt(SafeWalkPrefKey.stepThreshold, seekSteps.getProgress() + 20)
                    .putInt(SafeWalkPrefKey.staticResetSec, seekStatic.getProgress() + 5)
                    .commit();
        }
        SafeWalkConfig.exportConfig(this);
    }

    /** 只关心进度与松手两个回调，省掉重复样板 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}
    }
}
