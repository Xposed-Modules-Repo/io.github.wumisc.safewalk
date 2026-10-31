package io.github.wumisc.safewalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * SafeWalk 配置界面。
 * 配置写入 safewalk_xsp（MODE_WORLD_READABLE），LSPosed hook 通过 XSharedPreferences 读取。
 */
public final class MainActivity extends Activity {

    // ========== 磁贴状态 ==========
    private TextView tileEnableStatus, tileBatteryStatus, tileIdStatus, tileSitStatus;
    private TextView tileSocialStatus, tileNightTorchStatus, tileRearCamStatus;

    // ========== 开关状态 ==========
    private boolean swEnable, swBattery, swId, swSit, swSocial, swNightTorch, swRearCam;
    private String travelMode = "求职";

    // ========== 页面导航 ==========
    private View pageSettings, pageAbout;
    private LinearLayout tabSettings, tabAbout;
    private TextView tabSettingsText, tabAboutText;
    private ImageView tabSettingsIcon, tabAboutIcon;
    private boolean onPage = false;


    // ========== 滑动手势 ==========
    private float downX, downY;

    private static final int COLOR_ON = 0xFF2D7A3E;
    private static final int COLOR_OFF = 0xFF8B7D6B;


    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        if (!hasAllPermissions()) { requestNeededPermissions(); return; }
        initUI();
    }

    // ========== 权限 ==========

    private boolean hasAllPermissions() {
        for (String p : new String[]{
                android.Manifest.permission.ACTIVITY_RECOGNITION,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.ACCESS_FINE_LOCATION}) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void requestNeededPermissions() {
        try {
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String p : new String[]{
                    android.Manifest.permission.ACTIVITY_RECOGNITION,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.ACCESS_FINE_LOCATION}) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    need.add(p);
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 100);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] res) {
        super.onRequestPermissionsResult(req, p, res);
        if (req == 100) {
            if (hasAllPermissions()) initUI();
            else new AlertDialog.Builder(this)
                    .setTitle("权限未授予")
                    .setMessage("需要身体活动和通知权限才能工作。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(i); finish();
                    })
                    .setNegativeButton("退出", (d, w) -> finish())
                    .show();
        }
    }

    // ========== 初始化 ==========

    private void initUI() {
        bindViews();
        loadConfig();
        checkFirstLaunch();
    }

    private SharedPreferences xsp() {
        return getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
    }

    private void bindViews() {
        // 磁贴状态文本
        tileEnableStatus = findViewById(R.id.tile_enable_status);
        tileBatteryStatus = findViewById(R.id.tile_auto_status);
        tileIdStatus = findViewById(R.id.tile_lookdown_status);
        tileSitStatus = findViewById(R.id.tile_night_status);
        tileSocialStatus = findViewById(R.id.tile_safety_status);
        tileNightTorchStatus = findViewById(R.id.tile_night_torch_status);
        tileRearCamStatus = findViewById(R.id.tile_rear_cam_status);

        // 磁贴点击
        findViewById(R.id.tile_enable).setOnClickListener(v -> toggleEnable());
        findViewById(R.id.tile_auto).setOnClickListener(v -> toggleBattery());
        findViewById(R.id.tile_lookdown).setOnClickListener(v -> toggleId());
        findViewById(R.id.tile_night).setOnClickListener(v -> toggleSit());
        findViewById(R.id.tile_safety).setOnClickListener(v -> toggleSocial());
        findViewById(R.id.tile_night_torch).setOnClickListener(v -> toggleNightTorch());
        // 后置看路：短按开关，长按进设置页
        findViewById(R.id.tile_rear_cam).setOnClickListener(v -> toggleRearCam());
        // 后置看路：长按进设置页
        View tileRearCam = findViewById(R.id.tile_rear_cam);
        tileRearCam.setLongClickable(true);
        tileRearCam.setOnLongClickListener(v -> {
            Toast.makeText(this, "长按触发，打开设置页", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(this, RearCamSettingsActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return true;
        });
        findViewById(R.id.tile_emergency).setOnClickListener(v ->
                startActivity(new Intent(this, EmergencyCardActivity.class)));

        // 保存按钮
        findViewById(R.id.btn_save).setOnClickListener(v -> saveConfig());

        // 底部导航
        pageSettings = findViewById(R.id.page_settings);
        pageAbout = findViewById(R.id.page_about);
        tabSettings = findViewById(R.id.tab_settings);
        tabAbout = findViewById(R.id.tab_about);
        tabSettingsText = findViewById(R.id.tab_settings_label);
        tabAboutText = findViewById(R.id.tab_about_label);
        tabSettingsIcon = findViewById(R.id.tab_settings_icon);
        tabAboutIcon = findViewById(R.id.tab_about_icon);
        tabSettings.setOnClickListener(v -> switchTab(true));
        tabAbout.setOnClickListener(v -> switchTab(false));

        // 关于页
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_about_subtitle)).setText("安全出行 · LSPosed 模块 v" + ver);
        } catch (Throwable ignored) {}
        // 出门踩坑指南
        findViewById(R.id.btn_tips).setOnClickListener(v ->
                startActivity(new Intent(this, TipsActivity.class)));
        // GitHub 项目地址
        findViewById(R.id.btn_github).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk"));
            startActivity(intent);
        });
        // 致敬名单
        findViewById(R.id.btn_credits).setOnClickListener(v ->
                startActivity(new Intent(this, AcknowledgementsActivity.class)));
        // 支持捐赠（直接打开二维码页面）
        findViewById(R.id.btn_donate).setOnClickListener(v ->
                startActivity(new Intent(this, DonateQrActivity.class)));
        findViewById(R.id.btn_save_wallpaper).setOnClickListener(v -> saveWallpaper());
        findViewById(R.id.btn_lab).setOnLongClickListener(v -> {
            startActivity(new Intent(this, LabActivity.class));
            return true;
        });
    }

    // ========== 配置读写 ==========

    private void loadConfig() {
        SharedPreferences sp = xsp();
        swEnable = sp.getBoolean(SafeWalkPrefKey.masterEnable, true);
        swBattery = sp.getBoolean(SafeWalkPrefKey.enableBatteryWarning, true);
        swId = sp.getBoolean(SafeWalkPrefKey.enableIdReminder, true);
        swSit = sp.getBoolean(SafeWalkPrefKey.enableSitHealthRemind, true);
        swSocial = sp.getBoolean(SafeWalkPrefKey.enableSocialTipCard, true);
        swNightTorch = sp.getBoolean(SafeWalkPrefKey.enableNightTorch, true);
        swRearCam = sp.getBoolean(SafeWalkPrefKey.enableRearCamera, false);
        travelMode = sp.getString(SafeWalkPrefKey.travelMode, "求职");
        refreshTileStatus();
    }

    private void saveConfig() {
        SharedPreferences.Editor e = xsp().edit();
        e.putBoolean(SafeWalkPrefKey.masterEnable, swEnable);
        e.putBoolean(SafeWalkPrefKey.enableBatteryWarning, swBattery);
        e.putBoolean(SafeWalkPrefKey.enableIdReminder, swId);
        e.putBoolean(SafeWalkPrefKey.enableSitHealthRemind, swSit);
        e.putBoolean(SafeWalkPrefKey.enableSocialTipCard, swSocial);
        e.putBoolean(SafeWalkPrefKey.enableNightTorch, swNightTorch);
        e.putBoolean(SafeWalkPrefKey.enableRearCamera, swRearCam);
        e.putString(SafeWalkPrefKey.travelMode, travelMode);
        e.commit();
        // 导出JSON文件给Hook端读
        SafeWalkConfig.exportConfig(this);
    }

    private void refreshTileStatus() {
        setStatus(tileEnableStatus, swEnable);
        setStatus(tileBatteryStatus, swBattery);
        setStatus(tileIdStatus, swId);
        setStatus(tileSitStatus, swSit);
        setStatus(tileSocialStatus, swSocial);
        setStatus(tileNightTorchStatus, swNightTorch);
        setStatus(tileRearCamStatus, swRearCam);
    }

    private void setStatus(TextView tv, boolean on) {
        tv.setText(on ? "已开启" : "已关闭");
        tv.setTextColor(on ? COLOR_ON : COLOR_OFF);
    }

    // ========== 磁贴开关（语义化方法） ==========

    private void toggleEnable()    { swEnable = !swEnable;    saveConfig(); refreshTileStatus(); }
    private void toggleBattery()   { swBattery = !swBattery;  saveConfig(); refreshTileStatus(); }
    private void toggleId()        { swId = !swId;            saveConfig(); refreshTileStatus(); }
    private void toggleSit()       { swSit = !swSit;          saveConfig(); refreshTileStatus(); }
    private void toggleSocial()    { swSocial = !swSocial;    saveConfig(); refreshTileStatus(); }
    private void toggleNightTorch(){ swNightTorch = !swNightTorch; saveConfig(); refreshTileStatus(); }
    private void toggleRearCam()   { swRearCam = !swRearCam;  saveConfig(); refreshTileStatus(); }

    // ========== 首次启动引导 ==========

    private void checkFirstLaunch() {
        SharedPreferences sp = getSharedPreferences("safewalk", MODE_PRIVATE);
        if (sp.getBoolean("activated", false)) return;
        sp.edit().putBoolean("activated", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("⚙️ 模块激活引导")
                .setMessage("本模块需要两步激活：\n\n"
                        + "① LSPosed 管理器 → 模块 → 勾选 SafeWalk → 作用域选 SystemUI\n"
                        + "② 重启手机或 SystemUI\n\n"
                        + "激活后功能自动运行。")
                .setPositiveButton("打开 LSPosed", (d, w) -> openLSPosed())
                .setNegativeButton("知道了", null)
                .show();
    }

    private void openLSPosed() {
        for (String pkg : new String[]{"org.lsposed.manager", "org.lsposed.lspatch"}) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) { startActivity(i); return; }
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "未安装 LSPosed 管理器", Toast.LENGTH_SHORT).show();
    }

    // ========== 左右滑动切页 ==========

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = ev.getX(); downY = ev.getY();
                break;
            case android.view.MotionEvent.ACTION_UP:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 2) {
                    if (onPage) {
                        if (dx > 0) switchTab(true); // 关于页右滑回首页
                    } else if (dx < 0) {
                        switchTab(false); // 首页左滑进关于
                    }
                }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void switchTab(boolean toSettings) {
        onPage = !toSettings;
        pageSettings.setVisibility(toSettings ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(toSettings ? View.GONE : View.VISIBLE);
        int active = getColor(R.color.primary);
        int inactive = getColor(R.color.text_hint);
        tabSettingsText.setTextColor(toSettings ? active : inactive);
        tabAboutText.setTextColor(toSettings ? inactive : active);
        tabSettingsIcon.setColorFilter(toSettings ? active : inactive);
        tabAboutIcon.setColorFilter(toSettings ? inactive : active);
    }

    // ========== 保存壁纸 / 捐赠 ==========

    private void saveWallpaper() {
        try {
            java.io.InputStream is = getResources().openRawResource(R.drawable.bg_about_wallpaper);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "SafeWalk_chuchuan.jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/SafeWalk");
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os);
                os.close();
                Toast.makeText(this, "已保存到 DCIM/SafeWalk/", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDonate() {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_donate_wechat);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this)
                .setTitle("支持捐赠")
                .setMessage("如果觉得好用，请作者喝杯奶茶 🧋")
                .setView(iv)
                .setPositiveButton("好", null)
                .show();
    }
}
