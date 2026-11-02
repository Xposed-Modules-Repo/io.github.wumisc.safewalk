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
 * SafeWalk 配置界面（一级页面）。
 *
 * 磁贴只保留入口：总开关 / 智能提醒 / 行走模式 / 急救卡，外加两个预留空位。
 * 具体的 5 项提醒开关收进 {@link SmartReminderActivity}，
 * 行走模式等行走参数收进 {@link WalkModeActivity}。
 */
public final class MainActivity extends Activity {

    // ========== 磁贴状态 ==========
    private TextView tileEnableStatus, tileSmartStatus, tileWalkStatus;

    // ========== 开关状态（用于汇总显示） ==========
    private boolean swEnable, swBattery, swId, swSit, swSocial, swNightTorch, swRearCam;

    // ========== 页面导航 ==========
    private View pageSettings, pageAbout;
    private LinearLayout tabSettings, tabAbout;
    private TextView tabSettingsText, tabAboutText;
    private ImageView tabSettingsIcon, tabAboutIcon;
    private boolean onPage = false;
    private boolean viewsReady = false;

    // ========== 滑动手势 ==========
    private float downX, downY;

    private static final int COLOR_ON = 0xFF2D7A3E;
    private static final int COLOR_OFF = 0xFF8B7D6B;


    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        // App 进程日志出口：与 SystemUI 进程的 Hook 日志写同一套规则，
        // 优先 /storage/emulated/0/Android/Travel safely/，写不进自动降级
        DiagLog.init(getApplicationContext());

        // 界面先建起来，权限再异步申请。
        // 之前缺权限直接 return，用户看到的是一整页空白磁贴，像是「App 坏了」。
        initUI();
        // 把 SystemUI 进程（Hook）里攒的日志要过来，和本进程的写进同一个 diag.log：
        // 步态判定、手电、车祸检测都跑在那个进程，只看 App 侧日志等于没看
        requestHookLog();
        // 延后弹：引导框和系统权限框同时出现会叠在一起，用户只会全点掉
        getWindow().getDecorView().postDelayed(this::maybeGuideLogDir, 1500);
        if (!hasAllPermissions()) requestNeededPermissions();
    }

    /**
     * 向 SystemUI 里的 Hook 索要最近日志。
     *
     * 一次性动作：Hook 回传后会由 [LogBridgeReceiver] 追加到本应用的 diag.log。
     * Hook 没激活（LSPosed 没勾作用域）时不会有任何回包，这本身也是一条有效信息。
     */
    private void requestHookLog() {
        try {
            Intent i = new Intent(DiagLog.ACTION_REQUEST_LOG);
            i.setPackage("com.android.systemui");
            i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            sendBroadcast(i);
            DiagLog.log("[ui] 已请求系统界面日志；本应用日志目录=" + DiagLog.status());
        } catch (Throwable t) {
            DiagLog.log("[ui] 请求系统界面日志失败: " + t.getMessage());
        }
    }

    /**
     * 引导授予「所有文件访问」权限。
     *
     * Android 11 起，没有这个权限就写不进 /storage/emulated/0/Android/Travel safely/，
     * 日志只会落到应用专属目录 —— 用户按这个路径去找，永远是空的。
     * 只在第一次提示，拒绝之后可以在「功能实验区」里重新打开。
     */
    private void maybeGuideLogDir() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return;
        if (DiagLog.hasAllFilesAccess(this)) return;
        SharedPreferences g = getSharedPreferences("safewalk", MODE_PRIVATE);
        if (g.getBoolean("log_dir_guided", false)) return;
        g.edit().putBoolean("log_dir_guided", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("让日志落到你能找到的地方")
                .setMessage("你要求的日志路径是：\n/storage/emulated/0/Android/Travel safely/\n\n"
                        + "Android 11 起，应用不能直接在 Android/ 下建目录，"
                        + "必须授予「所有文件访问」权限才能写进去。\n\n"
                        + "不授权也能用，只是日志会落到应用专属目录（文件管理器多半进不去），"
                        + "排查问题时只能靠 adb logcat -s SafeWalk。")
                .setPositiveButton("去授权", (d, w) -> openAllFilesAccessSettings())
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void openAllFilesAccessSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                Toast.makeText(this, "跳转失败，请到系统设置里手动开启「所有文件访问」", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ========== 权限 ==========

    /**
     * 按系统版本组装需要的运行时权限。
     * POST_NOTIFICATIONS 是 API 33 才引入的，在 Android 11/12 上申请它会一直返回 DENIED，
     * 导致首页永远进不去（低级但致命的死循环）。
     *
     * 注意这里**不含 CAMERA**：首页和智能提醒页都不需要相机，
     * 行走模式的相机权限改到 WalkModeActivity 打开开关时按需申请。
     * 所以「打开软件弹相机授权」这个行为已经没有了。
     */
    private String[] neededPermissions() {
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(android.Manifest.permission.ACTIVITY_RECOGNITION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            list.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    private boolean hasAllPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void requestNeededPermissions() {
        try {
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String p : neededPermissions()) {
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
            if (hasAllPermissions()) {
                loadConfig();
            } else new AlertDialog.Builder(this)
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
        viewsReady = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从智能提醒/行走模式二级页返回时，重新同步磁贴状态
        if (viewsReady) {
            // 用户可能刚去系统设置授予了「所有文件访问」，这里重新探一次，
            // 能写用户目录就立刻切过去（否则日志还在那个找不到的降级目录里）
            DiagLog.init(getApplicationContext());
            loadConfig();
            // 引导弹窗放到权限都拿到之后，避免和系统权限框叠在一起
            if (hasAllPermissions()) checkFirstLaunch();
        }
    }

    private SharedPreferences xsp() {
        return getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
    }

    private void bindViews() {
        // 磁贴状态文本
        tileEnableStatus = findViewById(R.id.tile_enable_status);
        tileSmartStatus = findViewById(R.id.tile_smart_status);
        tileWalkStatus = findViewById(R.id.tile_walk_status);

        // 磁贴入口
        findViewById(R.id.tile_enable).setOnClickListener(v -> toggleEnable());
        findViewById(R.id.tile_smart).setOnClickListener(v ->
                startActivity(new Intent(this, SmartReminderActivity.class)));
        findViewById(R.id.tile_walk).setOnClickListener(v ->
                startActivity(new Intent(this, WalkModeActivity.class)));
        findViewById(R.id.tile_emergency).setOnClickListener(v ->
                startActivity(new Intent(this, EmergencyCardActivity.class)));

        // 保存按钮
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            saveConfig();
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        });

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
        swEnable = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true);
        swBattery = SafePrefs.getBool(sp, SafeWalkPrefKey.enableBatteryWarning, true);
        swId = SafePrefs.getBool(sp, SafeWalkPrefKey.enableIdReminder, true);
        swSit = SafePrefs.getBool(sp, SafeWalkPrefKey.enableSitHealthRemind, true);
        swSocial = SafePrefs.getBool(sp, SafeWalkPrefKey.enableSocialTipCard, true);
        swNightTorch = SafePrefs.getBool(sp, SafeWalkPrefKey.enableNightTorch, true);
        swRearCam = SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false);
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
        e.commit();
        // 导出JSON文件给Hook端读
        SafeWalkConfig.exportConfig(this);
    }

    private void refreshTileStatus() {
        // 光写「已开启/已关闭」不够：整张卡片可点，用户根本不知道这里能点，
        // 也不知道点下去会发生什么。把动作直接写在状态后面。
        tileEnableStatus.setText(swEnable ? "已开启 · 点击关闭" : "已关闭 · 点击开启");
        tileEnableStatus.setTextColor(swEnable ? COLOR_ON : COLOR_OFF);

        int on = 0;
        if (swBattery) on++;
        if (swId) on++;
        if (swSit) on++;
        if (swSocial) on++;
        if (swNightTorch) on++;

        /*
         * 这里必须显示「真实状态」，不能因为总开关关闭就把二级页的开关状态
         * 覆盖成「总开关已关闭」。
         *
         * 原来的写法是：总开关一关，智能提醒/行走模式两个磁贴统统写「总开关已关闭」，
         * 而点进去看，五项提醒明明全是打开的 —— 外面说全没开、里面说全开，
         * 用户只会得出「这软件坏了」。总开关关闭的含义是「不生效」，不是「开关被关掉了」。
         */
        String smart;
        if (on == 5) smart = "5 项全部开启";
        else if (on == 0) smart = "5 项全部关闭";
        else smart = "已开 " + on + "/5 项";
        if (!swEnable) smart = "总开关未开（已设 " + on + "/5）";
        tileSmartStatus.setText(smart);
        tileSmartStatus.setTextColor(swEnable && on > 0 ? COLOR_ON : COLOR_OFF);

        String walk = swRearCam ? "行走模式已开启" : "行走模式已关闭";
        if (!swEnable) walk = "总开关未开（" + (swRearCam ? "已开启" : "未开启") + "）";
        tileWalkStatus.setText(walk);
        tileWalkStatus.setTextColor(swEnable && swRearCam ? COLOR_ON : COLOR_OFF);
    }

    // ========== 总开关 ==========

    private void toggleEnable() {
        swEnable = !swEnable;
        saveConfig();
        refreshTileStatus();
        // 整张卡片可点，之前没有任何反馈：点一下就把总开关关了，
        // 用户只看到三个磁贴齐刷刷变成「总开关已关闭」，根本不知道是自己点出来的
        Toast.makeText(this,
                swEnable ? "总开关已开启，提醒恢复生效" : "总开关已关闭，所有提醒暂停",
                Toast.LENGTH_SHORT).show();
        DiagLog.log("[ui] 总开关 → " + (swEnable ? "开" : "关"));
    }

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

    // ========== 保存壁纸 ==========

    private void saveWallpaper() {
        android.net.Uri uri = null;
        try {
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.bg_about_wallpaper);
            if (bmp == null) throw new IllegalStateException("壁纸解码失败");

            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "SafeWalk_chuchuan.jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/SafeWalk");
            // Android 10+ 必须先标记 pending，写完再置 0；
            // 否则相册可能在文件还没写完时就扫到一张半截图
            cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);

            uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("相册拒绝了写入请求");

            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                // openOutputStream 在某些 ROM 上会返回 null，之前直接 os.close() 就 NPE 了
                if (os == null) throw new java.io.IOException("无法打开输出流");
                if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)) {
                    throw new java.io.IOException("图片编码失败");
                }
            }

            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);

            Toast.makeText(this, "已保存到 DCIM/SafeWalk/", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // 写失败要删掉占位记录，否则相册里会留下一条打不开的空条目
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
            }
            Toast.makeText(this, "保存失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
