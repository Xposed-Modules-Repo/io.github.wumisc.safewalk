package io.github.wumisc.safewalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 安全出行配置界面。国风+Beyond风格，磁贴开关。
 * 配置写入 safewalk_xsp，LSPosed hook 通过 XSharedPreferences 读取。
 */
public final class MainActivity extends Activity {

    private TextView tileEnableStatus, tileBatteryStatus, tileIdStatus, tileSitStatus, tileSocialStatus;
    private boolean swEnable, swBattery, swId, swSit, swSocial;
    private String travelMode = "求职";

    private View pageSettings, pageAbout;
    private LinearLayout tabSettings, tabAbout;
    private TextView tabSettingsText, tabAboutText;
    private ImageView tabSettingsIcon, tabAboutIcon;

    private static final int COLOR_ON = 0xFF2D7A3E;
    private static final int COLOR_OFF = 0xFF8B7D6B;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        if (!hasAllPermissions()) {
            requestNeededPermissions();
            return;
        }
        initUI();
    }

    private boolean hasAllPermissions() {
        for (String p : new String[]{
                android.Manifest.permission.ACTIVITY_RECOGNITION,
                android.Manifest.permission.POST_NOTIFICATIONS}) {
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
                    android.Manifest.permission.POST_NOTIFICATIONS}) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    need.add(p);
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 100);
        } catch (Throwable ignored) {}
    }

    private void initUI() {
        bindViews();
        loadConfig();
        setupGuideTabs();
        checkFirstLaunch();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] res) {
        super.onRequestPermissionsResult(req, p, res);
        if (req == 100) {
            if (hasAllPermissions()) initUI();
            else {
                new AlertDialog.Builder(this)
                        .setTitle("权限未授予")
                        .setMessage("需要身体活动和通知权限才能工作。")
                        .setPositiveButton("去设置", (d, w) -> {
                            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            i.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                            finish();
                        })
                        .setNegativeButton("退出", (d, w) -> finish())
                        .show();
            }
        }
    }

    private SharedPreferences xsp() {
        return getSharedPreferences("safewalk_xsp", MODE_WORLD_READABLE);
    }

    private void bindViews() {
        tileEnableStatus = findViewById(R.id.tile_enable_status);
        tileBatteryStatus = findViewById(R.id.tile_auto_status);
        tileIdStatus = findViewById(R.id.tile_lookdown_status);
        tileSitStatus = findViewById(R.id.tile_night_status);
        tileSocialStatus = findViewById(R.id.tile_safety_status);

        findViewById(R.id.tile_enable).setOnClickListener(v -> toggleTile(0));
        findViewById(R.id.tile_auto).setOnClickListener(v -> toggleTile(1));
        findViewById(R.id.tile_lookdown).setOnClickListener(v -> toggleTile(2));
        findViewById(R.id.tile_night).setOnClickListener(v -> toggleTile(3));
        findViewById(R.id.tile_safety).setOnClickListener(v -> toggleTile(4));
        findViewById(R.id.tile_emergency).setOnClickListener(v ->
                startActivity(new Intent(this, EmergencyCardActivity.class)));

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

        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_about_subtitle)).setText("安全出行 · LSPosed 模块 v" + ver);
        } catch (Throwable ignored) {}
        findViewById(R.id.btn_donate).setOnClickListener(v -> showDonate());
        findViewById(R.id.btn_save_wallpaper).setOnClickListener(v -> saveWallpaper());
        findViewById(R.id.btn_lab).setOnLongClickListener(v -> {
            startActivity(new Intent(this, LabActivity.class));
            return true;
        });

        // 浮窗壁纸：长按隐藏，记住状态；在关于页长按标题恢复
        final android.view.View wp = findViewById(R.id.floating_wallpaper);
        android.content.SharedPreferences ui = getSharedPreferences("safewalk_ui", MODE_PRIVATE);
        wp.setVisibility(ui.getBoolean("wp_hidden", false) ? View.GONE : View.VISIBLE);
        wp.setOnLongClickListener(v -> {
            wp.setVisibility(View.GONE);
            ui.edit().putBoolean("wp_hidden", true).apply();
            Toast.makeText(this, "壁纸已隐藏", Toast.LENGTH_SHORT).show();
            return true;
        });
        // 关于页长按版本号恢复壁纸
        ((TextView) findViewById(R.id.tv_about_subtitle)).setOnLongClickListener(v -> {
            if (wp.getVisibility() != View.VISIBLE) {
                wp.setVisibility(View.VISIBLE);
                ui.edit().putBoolean("wp_hidden", false).apply();
                Toast.makeText(this, "壁纸已显示", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void loadConfig() {
        SharedPreferences sp = xsp();
        swEnable = sp.getBoolean("masterEnable", true);
        swBattery = sp.getBoolean("enableBatteryWarning", true);
        swId = sp.getBoolean("enableIdReminder", true);
        swSit = sp.getBoolean("enableSitHealthRemind", true);
        swSocial = sp.getBoolean("enableSocialTipCard", true);
        travelMode = sp.getString("travelMode", "求职");
        refreshTileStatus();
    }

    private void toggleTile(int which) {
        switch (which) {
            case 0: swEnable = !swEnable; break;
            case 1: swBattery = !swBattery; break;
            case 2: swId = !swId; break;
            case 3: swSit = !swSit; break;
            case 4: swSocial = !swSocial; break;
        }
        saveConfig();
        refreshTileStatus();
    }

    private void saveConfig() {
        SharedPreferences.Editor e = xsp().edit();
        e.putBoolean("masterEnable", swEnable);
        e.putBoolean("enableBatteryWarning", swBattery);
        e.putBoolean("enableIdReminder", swId);
        e.putBoolean("enableSitHealthRemind", swSit);
        e.putBoolean("enableSocialTipCard", swSocial);
        e.putString("travelMode", travelMode);
        e.commit();
    }

    private void refreshTileStatus() {
        setStatus(tileEnableStatus, swEnable);
        setStatus(tileBatteryStatus, swBattery);
        setStatus(tileIdStatus, swId);
        setStatus(tileSitStatus, swSit);
        setStatus(tileSocialStatus, swSocial);
    }

    private void setStatus(TextView tv, boolean on) {
        tv.setText(on ? "已开启" : "已关闭");
        tv.setTextColor(on ? COLOR_ON : COLOR_OFF);
    }

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

    private int curGuideTab = 0;
    private void setupGuideTabs() {
        int[] tabIds = {R.id.tab_preview, R.id.tab_job, R.id.tab_date, R.id.tab_party, R.id.tab_travel};
        int[] panelIds = {R.id.panel_preview, R.id.panel_job, R.id.panel_date, R.id.panel_party, R.id.panel_travel};
        String[] modes = {"", "求职", "约会", "聚会", "旅游"};
        for (int i = 0; i < tabIds.length; i++) {
            final int idx = i;
            findViewById(tabIds[i]).setOnClickListener(v -> {
                curGuideTab = idx;
                for (int j = 0; j < panelIds.length; j++) {
                    findViewById(panelIds[j]).setVisibility(j == idx ? View.VISIBLE : View.GONE);
                    ((TextView) findViewById(tabIds[j])).setTextColor(j == idx ? 0xFF1B3A5C : 0xFF8B7D6B);
                    ((TextView) findViewById(tabIds[j])).setTypeface(null, j == idx ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                }
                travelMode = modes[idx];
                saveConfig();
            });
        }
        // 滑动切标签由 dispatchTouchEvent 统一处理
        findViewById(R.id.btn_copy_all).setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            String[] titles = {"【求职】", "【约会】", "【聚会】", "【旅游】"};
            int[] contentIds = {R.id.panel_job, R.id.panel_date, R.id.panel_party, R.id.panel_travel};
            for (int i = 0; i < titles.length; i++) {
                sb.append(titles[i]).append("\n");
                sb.append(((TextView) findViewById(contentIds[i])).getText()).append("\n\n");
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("踩坑指南", sb.toString()));
            Toast.makeText(this, "已复制全部", Toast.LENGTH_SHORT).show();
        });
    }

    private boolean onPage = false; // 当前是否在关于页
    private float downX, downY;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = ev.getX(); downY = ev.getY();
                break;
            case android.view.MotionEvent.ACTION_UP:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                // 水平滑且幅度够
                if (Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 2) {
                    if (onPage) {
                        // 在关于页：左右滑切踩坑指南标签
                        guideSwipe(dx > 0 ? -1 : 1);
                    } else {
                        // 在首页：左滑进关于，右滑不动
                        if (dx < 0) switchTab(false);
                    }
                } else if (Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 2 && onPage) {
                    // 在关于页向右滑回首页
                    if (dx > 0 && curGuideTab == 0) switchTab(true);
                }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void guideSwipe(int dir) {
        int[] tabIds = {R.id.tab_preview, R.id.tab_job, R.id.tab_date, R.id.tab_party, R.id.tab_travel};
        int[] panelIds = {R.id.panel_preview, R.id.panel_job, R.id.panel_date, R.id.panel_party, R.id.panel_travel};
        String[] modes = {"", "求职", "约会", "聚会", "旅游"};
        int next = curGuideTab + dir;
        if (next < 0) { switchTab(true); return; } // 预览页继续右滑回首页
        if (next >= tabIds.length) return;
        curGuideTab = next;
        for (int j = 0; j < panelIds.length; j++) {
            findViewById(panelIds[j]).setVisibility(j == curGuideTab ? View.VISIBLE : View.GONE);
            ((TextView) findViewById(tabIds[j])).setTextColor(j == curGuideTab ? 0xFF1B3A5C : 0xFF8B7D6B);
            ((TextView) findViewById(tabIds[j])).setTypeface(null, j == curGuideTab ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        travelMode = modes[curGuideTab];
        saveConfig();
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
