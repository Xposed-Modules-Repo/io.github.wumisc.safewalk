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
 * 行走模式二级菜单（2.10 起为纯手动模式）。
 *
 * 语义极简：开关打开 = 身后路况常显，关闭 = 收起。不再有「自动检测步行」、
 * 步态判定阈值之类的东西——那套自动开关在真机上反复失效，已经整体移除。
 *
 * 所有改动即时写入并导出 JSON，Hook 端 2 秒轮询内生效。
 */
public final class WalkModeActivity extends Activity {

    private SharedPreferences sp;

    private SwitchCompat swRearCam, swAuto;
    private TextView heroSummary, tvAutoDesc, tvCornerTitle, tvOpacity;
    private TextView tvCamPermHint, tvPreviewStage;

    private SeekBar seekOpacity;
    private SeekBar seekGaitSteps, seekGaitStop;
    private TextView tvGaitSteps, tvGaitStop;
    private TextView[] modeChips, posChips;

    // 【新增对比度 UI 模块】增强对比度独立开关（整卡可点）
    private SwitchCompat swPreviewContrast;
    // HyperOS 版本适配入口已按需求收敛到首页 MainActivity（两处共用
    // SafeWalkPrefKey.hyperOsMode）。本页不再持有其控件与状态，避免重复入口。

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

        DiagLog.init(getApplicationContext());
        refreshAll();
        // 系统版本识别的环境快照仍打印（属于调试日志要求，与 UI 入口无关）；
        // HyperOS 版本适配的展示与切换已统一收敛到首页 MainActivity。
        HyperOsCompat.logEnvironment();
        DiagLog.log("[walk] 打开行走模式页 rearCam=" + swRearCam.isChecked()
                + " mode=" + rearMode
                + " opacity=" + (seekOpacity.getProgress() + 10)
                + " contrast=" + swPreviewContrast.isChecked()
                + " master=" + SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)
                + " | 日志=" + DiagLog.status());
    }

    private void bindViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        heroSummary = findViewById(R.id.hero_walk_summary);
        tvCornerTitle = findViewById(R.id.tv_corner_title);
        tvOpacity = findViewById(R.id.tv_opacity);

        // 这一行回答「预览现在归谁管」：相机和悬浮窗都在本应用进程，
        // 缺了权限就把原因摆在脸上，不让用户对着黑框猜功能是不是假的。
        tvCamPermHint = findViewById(R.id.tv_cam_perm_hint);
        tvCamPermHint.setOnClickListener(v -> showCameraPermissionHelp());

        tvPreviewStage = findViewById(R.id.tv_preview_stage);

        swRearCam = findViewById(R.id.sw_rear_cam);
        swAuto = findViewById(R.id.sw_auto);
        tvAutoDesc = findViewById(R.id.tv_auto_desc);
        // 【新增对比度 UI 模块】
        swPreviewContrast = findViewById(R.id.sw_preview_contrast);
        findViewById(R.id.card_preview_contrast).setOnClickListener(v -> swPreviewContrast.toggle());
        // HyperOS 版本适配卡片已从本页布局删除，此处不再绑定其控件
        // （否则 findViewById 返回 null，点击时直接 NPE）。
        seekOpacity = findViewById(R.id.seek_opacity);
        seekGaitSteps = findViewById(R.id.seek_gait_steps);
        seekGaitStop = findViewById(R.id.seek_gait_stop);
        tvGaitSteps = findViewById(R.id.tv_gait_steps);
        tvGaitStop = findViewById(R.id.tv_gait_stop);

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
        // 整卡可点切自动开关；自动开关关 = 手动常显（2.10 的默认行为）
        findViewById(R.id.card_auto).setOnClickListener(v -> swAuto.toggle());

        modeChips[0].setOnClickListener(v -> selectMode(RearCameraOverlay.MODE_FULLSCREEN));
        modeChips[1].setOnClickListener(v -> selectMode(RearCameraOverlay.MODE_SMALLWINDOW));

        for (int i = 0; i < POS_KEYS.length; i++) {
            final String key = POS_KEYS[i];
            posChips[i].setOnClickListener(v -> selectCorner(key));
        }

        // 重启系统界面：整卡可点，右侧按钮只是视觉提示（点按钮会冒泡到卡片）
        View restartCard = findViewById(R.id.card_restart_systemui);
        restartCard.setOnClickListener(v -> confirmRestart());
    }

    private void loadConfig() {
        // 统一走 SafePrefs：类型对就用原生读取，历史上类型变过（int↔float）也不会 CCE
        swRearCam.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false));
        // 自动跟随步行默认关：默认行为是 2.10 的手动常显，用户主动打开才进入自动
        swAuto.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false));
        // 【新增对比度 UI 模块】增强对比度默认关，不影响老用户既有观感
        swPreviewContrast.setChecked(SafePrefs.getBool(sp, SafeWalkPrefKey.enablePreviewContrast, false));
        // HyperOS 适配模式由首页 MainActivity 单独读写，本页不再加载该配置。

        rearMode = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamMode, RearCameraOverlay.MODE_FULLSCREEN);
        if (rearMode == null) rearMode = RearCameraOverlay.MODE_FULLSCREEN;
        rearCorner = SafePrefs.getString(sp, SafeWalkPrefKey.rearCamCorner, "bottom_right");
        if (rearCorner == null) rearCorner = "bottom_right";

        int opacity = SafePrefs.getInt(sp, SafeWalkPrefKey.rearCamOpacity, 50);
        opacity = Math.max(10, Math.min(100, opacity));
        seekOpacity.setProgress(opacity - 10);

        updateOpacityLabel(opacity);

        // 确认步数 2~10（滑条 0~8），默认 5；停下延迟 5~30 秒（滑条 0~25），默认 10
        int steps = SafePrefs.getInt(sp, SafeWalkPrefKey.gaitConfirmSteps, 5);
        steps = Math.max(2, Math.min(10, steps));
        seekGaitSteps.setProgress(steps - 2);
        tvGaitSteps.setText("连续走 " + steps + " 步才弹出预览");

        int stopSec = SafePrefs.getInt(sp, SafeWalkPrefKey.gaitStopSec, 10);
        stopSec = Math.max(5, Math.min(30, stopSec));
        seekGaitStop.setProgress(stopSec - 5);
        tvGaitStop.setText("停下后 " + stopSec + " 秒收起预览");
    }

    private void bindListeners() {
        swRearCam.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[walk] 行走模式开关 → " + (c ? "开" : "关"));
            if (c) {
                // 开关打开：先走权限检查，两样都齐了就启动预览服务，路况常显
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
            DiagLog.log("[walk] 自动跟随步行 → " + (c ? "开（走动显示/停下收起）" : "关（常显）"));
            refreshDependencies();
            // 自动开关切换要立刻反映到运行中的预览服务（启动/停止步态监听），
            // 否则服务要等到下次重建才知道 rearCamAuto 变了。手动常显本身不受影响。
            if (swRearCam.isChecked()) {
                WalkPreviewService.send(WalkModeActivity.this, WalkPreviewService.ACTION_SHOW, null);
            }
        });

        // 【新增对比度 UI 模块】开关只切 HUD 文字层，不动透明度、不动相机采集。
        swPreviewContrast.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enablePreviewContrast, c).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.log("[contrast] 增强对比度 → " + (c ? "开（文字描边/阴影/衬底）" : "关")
                    + " | 透明度滑块保持原有取值不变");
            // 立即刷新运行中的悬浮窗：doShow 会带上新的 contrast 值，
            // show() 检测到 Style 变化会走 updateViewLayout 切 HUD 可见性。
            previewIfEnabled();
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

        seekGaitSteps.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvGaitSteps.setText("连续走 " + (progress + 2) + " 步才弹出预览");
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.gaitConfirmSteps, sb.getProgress() + 2).commit();
                SafeWalkConfig.exportConfig(WalkModeActivity.this);
                // 新步数阈值要立刻作用于运行中的步态监听
                if (swRearCam.isChecked()) WalkPreviewService.send(WalkModeActivity.this, WalkPreviewService.ACTION_SHOW, null);
            }
        });

        seekGaitStop.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvGaitStop.setText("停下后 " + (progress + 5) + " 秒收起预览");
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(SafeWalkPrefKey.gaitStopSec, sb.getProgress() + 5).commit();
                SafeWalkConfig.exportConfig(WalkModeActivity.this);
                if (swRearCam.isChecked()) WalkPreviewService.send(WalkModeActivity.this, WalkPreviewService.ACTION_SHOW, null);
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
        // 总开关关着时只记录偏好、不弹预览，否则用户在这里打开开关，
        // 预览窗口闪一下就被 Hook 收掉，体感完全是「这功能坏了」
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
        // HyperOS 版本适配卡片已移到首页，本页不再刷新其 UI。
    }

    // ========== HyperOS 版本适配 ==========
    // 展示与切换统一在首页 MainActivity；本页仅保留一条环境快照调试日志
    // （HyperOsCompat.logEnvironment() 于 onCreate 调用），不再持有任何控件/状态。

    private void refreshDependencies() {
        boolean rearOn = swRearCam.isChecked();
        boolean auto = swAuto.isChecked();
        boolean small = RearCameraOverlay.MODE_SMALLWINDOW.equals(rearMode);
        boolean master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true);

        if (!master) {
            // 总开关关闭时这里的开关打开也没用
            heroSummary.setText("总开关已关闭 · 行走模式不生效");
        } else if (rearOn && auto) {
            heroSummary.setText("已开启 · 自动（走动显示 / 停下收起）");
        } else if (rearOn) {
            heroSummary.setText("已开启 · 路况常显");
        } else {
            heroSummary.setText("已关闭");
        }

        // 自动开关在行走模式关着时变灰但仍可点：点了会走这里重算，副标题
        // 保持「已关闭」，用户能看出前置条件；彻底禁用反而像「点了没反应」
        boolean autoUsable = rearOn;
        swAuto.setEnabled(autoUsable);
        swAuto.setAlpha(autoUsable ? 1f : 0.45f);
        if (tvAutoDesc != null) {
            tvAutoDesc.setText(auto
                    ? "走起来自动显示，停下自动收起"
                    : "关闭时路况常显，直到手动关闭开关");
        }

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
                        + "行走模式开关打开后，画面会持续显示，直到你手动关闭开关。")
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
        // 透明度滑块可能是在拖动中被切走的，onStopTrackingTouch 不一定触发。
        // 只 exportConfig 兜不住：JSON 是从 SP 读出来的，SP 里还是旧值。
        if (!binding && seekOpacity != null) {
            sp.edit()
                    .putInt(SafeWalkPrefKey.rearCamOpacity, seekOpacity.getProgress() + 10)
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
