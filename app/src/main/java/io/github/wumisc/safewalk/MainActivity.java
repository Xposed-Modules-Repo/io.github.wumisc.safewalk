package io.github.wumisc.safewalk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
public final class MainActivity extends androidx.activity.ComponentActivity {

    // ========== 磁贴状态 ==========
    private TextView tileEnableStatus, tileSmartStatus, tileWalkStatus;

    // ========== 开关状态（用于汇总显示） ==========
    private boolean swEnable, swBattery, swId, swSit, swSocial, swNightTorch, swRearCam;

    // ========== 页面导航 ==========
    private View pageSettings, pageAbout;
    private androidx.compose.ui.platform.ComposeView composeNav;
    private boolean onPage = false;
    private boolean viewsReady = false;

    // ========== 滑动手势 ==========
    private float downX, downY;
    /** 【手势导航联动模块】本次滑动是否已判定为「水平为主」（避免竖向滚动误触发导航栏跟手） */
    private boolean swipeTracking = false;
    /** 水平滑动判定的最小位移，避免点击/轻微抖动也让导航栏抽动 */
    private static final float SWIPE_SLOP = 24f;

    /** 升级后的「重启系统界面」提示是否正在显示，防止旋转屏等重建时叠两个框 */
    private boolean restartPromptShowing = false;

    // ========== 【5.5.0 隐私】桌面图标隐藏 / 最近任务隐藏 ==========
    private boolean hideLauncherIcon;
    private boolean hideFromRecentTasks;
    private TextView tvHideLauncherState, tvHideRecentState;

    // ========== 【系统 UI 适配 · 首页入口】 ==========
    // 4.3 起取代原来的「HyperOS 版本适配」三芯片。旧版让用户选 HyperOS 3 / 4，
    // 但三个策略函数（前台服务 / 手电 / 悬浮窗）都是运行时降级链，版本号根本没被读过；
    // 真正影响行为的是「哪一家 UI」——它决定自启动页跳哪儿、引导文案怎么写、
    // 用哪张相机包名表。档位从 3 个变成 7 个（自动 + 五家厂商 + 原生），但只占两行：
    // 一行注明，一行选择。
    private TextView tvRomCaption;
    private TextView[] romChips;

    /**
     * 芯片对应的配置值，下标与 {@link #romChips}、布局里的 chip_rom_* 一一对应。
     *
     * 抽成常量而不是在绑定与刷新两处各写一遍：两处漏改一处，就会变成
     * 「点了华为、高亮小米」这种不报错但明显不对的状态。
     *
     * 【5.2.0】加三星后共 9 个芯片，布局从「两行四列」改成「三行三列」。
     * 顺序按页面从上到下、从左到右排列，与 layout 里的摆放严格一致：
     *   自动 / 小米 / 三星
     *   荣耀 / OPPO / vivo
     *   华为 / 魅族 / 原生
     */
    private static final String[] ROM_CHIP_KEYS = {
            RomSettings.OVERRIDE_AUTO,
            RomId.HYPEROS.name(),
            RomId.ONEUI.name(),
            RomId.MAGICOS.name(),
            RomId.COLOROS.name(),
            RomId.ORIGINOS.name(),
            RomId.HARMONYOS.name(),
            RomId.FLYME.name(),
            RomId.AOSP.name()
    };
    /** 当前生效的生态（自动识别或用户手选的结果），用于刷新高亮与文案。 */
    private RomId romId = RomId.AOSP;

    // 状态文字配色（开=绿，关=灰）。取值必须在 onCreate 里从资源读，
    // 原来写死成常量，深色模式下这两个颜色对比度不足。
    private int COLOR_ON;
    private int COLOR_OFF;


    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        // 状态色取自资源：深浅色各有一套值
        COLOR_ON = getColor(R.color.accent_green);
        COLOR_OFF = getColor(R.color.text_hint);
        // App 进程日志出口：与 SystemUI 进程的 Hook 日志写同一套规则，
        // 优先 /storage/emulated/0/Android/Travel safely/，写不进自动降级
        DiagLog.init(getApplicationContext());
        // 【配置防丢】数据被清 / 重装后，先从外部备份恢复行走模式等设置，
        // 必须放在 initUI() 读配置之前，否则首页会先按默认值渲染一遍。
        ConfigBackup.restoreIfNeeded(getApplicationContext());
        // 【诊断】进入首页打印一次环境快照（厂商/型号/SDK/prop/识别结论）。
        // 4.3 起首页不再有「HyperOS 版本适配」UI，这段只为现场排查留证据 —— 用户报
        // 「首页写的是 XX 但我这明明是 YY」时，不用连 logcat 就能对上号。
        HyperOsCompat.logEnvironment();
        // 【到达提醒】证件 / 到场提示按「到达某类场所」触发：进首页就启动定位监控，
        // 这样即使行走模式没开（服务未起）也能正常提醒。start 幂等，不会重复注册。
        PlaceArrivalMonitor.start(getApplicationContext());

        // 界面先建起来，权限再异步申请。
        // 之前缺权限直接 return，用户看到的是一整页空白磁贴，像是「App 坏了」。
        initUI();
        // 【5.5.0 隐私】校准桌面图标 alias：偏好与系统实际状态可能不一致（用户曾在系统设置里
        // 手动改过组件启用状态），这里以偏好为准恢复，避免「隐藏后图标还在 / 恢复后图标还是没了」。
        calibrateLauncherIcon();
        // 把 SystemUI 进程（Hook）里攒的日志要过来，和本进程的写进同一个 diag.log：
        // 步态判定、手电、车祸检测都跑在那个进程，只看 App 侧日志等于没看
        requestHookLog();

        // 【模块4 初始化批量权限申请】首次安装 → 集中引导页一次性申请全部权限
        // （运行时权限 + 悬浮窗 + 相机/闪光灯 + 后台/电池优化 + 自启动 + 无障碍 + ROOT 探测），
        // 并附每项用途说明。为避免与下面零散的权限框、日志目录框叠在一起，
        // 首次启动只走引导页，把旧的分散申请整个让位给它。
        final boolean firstRun = PermissionOnboardingActivity.shouldShow(this);
        if (firstRun) {
            DiagLog.init(getApplicationContext());
            DiagLog.log("[PERM] 首次启动，拉起批量权限引导页（旧分散权限申请本次跳过）");
            // 延后到首页渲染完成后再跳，避免和 initUI 抢窗口、出现白屏闪一下
            getWindow().getDecorView().postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                try {
                    PermissionOnboardingActivity.start(this);
                } catch (Throwable t) {
                    // 引导页万一拉不起来，不能让用户卡在「什么权限都没申请」的状态，
                    // 回退到旧的分散申请，保证至少关键运行时权限会被请求。
                    DiagLog.log("[PERM:SYSTEM] 引导页启动失败，回退分散申请: " + t.getMessage());
                    if (!hasAllPermissions()) requestNeededPermissions();
                }
            }, 900);
        } else {
            // 非首次：沿用原来的零散补漏逻辑（用户后来关了某个权限时兜底），
            // 延后弹是避免引导框和系统权限框同时出现会叠在一起，用户只会全点掉。
            getWindow().getDecorView().postDelayed(this::maybeGuideLogDir, 1500);
            if (!hasAllPermissions()) requestNeededPermissions();
        }
    }

    /**
     * 检测到「装的比跑的新」时，提示重启系统界面（5.3.2）。
     *
     * 为什么必须有这一步：模块代码是在 SystemUI 进程启动时注入的，覆盖安装只会
     * 换掉磁盘上的 APK，那个进程里仍然是旧类。表现就是「更新日志写了修 A，
     * 装上以后 A 一点没变」——真机上已经发生过两次（5.3.0 的方向代码、
     * 5.3.1 的姿态代码，日志里连一条新增诊断都没有）。
     *
     * 首次安装与每次覆盖升级都要提示：ackedVersionCode 为 0 即从未确认过（首次安装），
     * 此时 SystemUI 里还没有本模块的代码，不重启等于所有功能都不生效 ——
     * 这是用户明确要求「首次打开必须通知」的那一步，不能省。
     */
    private void maybePromptRestartForUpdate() {
        if (isFinishing() || isDestroyed()) return;
        final int installed = BuildConfig.VERSION_CODE;
        final long installStamp = InstallFingerprint.stamp(this);
        final long apkSize = InstallFingerprint.apkSize(this);
        final SharedPreferences sp;
        final int acked;
        final long handled;
        final long prompted;
        final long promptedSize;
        final boolean replacedPending;
        try {
            sp = getSharedPreferences(RestartReminderPolicy.SP_ONEOFF, MODE_PRIVATE);
            acked = sp.getInt(RestartReminderPolicy.KEY_ACK_VERSION, 0);
            handled = sp.getLong(RestartReminderPolicy.KEY_INSTALL_STAMP, 0L);
            prompted = sp.getLong(RestartReminderPolicy.KEY_PROMPTED_STAMP, 0L);
            promptedSize = sp.getLong(RestartReminderPolicy.KEY_PROMPTED_SIZE, 0L);
            replacedPending = sp.getBoolean(RestartReminderPolicy.KEY_REPLACED_PENDING, false);
        } catch (Throwable t) {
            return;
        }
        // acked == 0 表示首次安装（从未确认过）——必须同样提示：
        // 刚装完 SystemUI 里没有本模块代码，不重启就点开关「毫无反应」。
        // 以前这里为了「不重复打扰」直接 return，把首次安装这条路漏掉了。
        //
        // installStampChanged 补上「同版本覆盖安装」：versionCode 没变、只是重装了一遍
        // （换签名重刷 / adb install -r / 手动再装一次同一包），SystemUI 里仍是旧代码，
        // 只看 versionCode 会判定「已确认过」而一声不吭。
        // replacedPending 是第三条独立证据：由 MY_PACKAGE_REPLACED 广播写入
        // （见 KeepAliveReceiver），即便某些 ROM 的 lastUpdateTime 不刷新，覆盖安装也照样弹。
        //
        // apkChanged 是第四条、也是最硬的一条：只比 APK 字节数。同版本重装一个内容
        // 不同的包（本项目的常态：版本号不动、只改代码再打一次），时间戳可能被 ROM
        // 复用，但大小换了就一定判定为「新安装事件」。
        final boolean apkChanged = InstallFingerprint.changed(apkSize,
                sp.getLong(RestartReminderPolicy.KEY_INSTALL_SIZE, 0L));
        final boolean need = RestartReminderPolicy.shouldPrompt(installed, acked)
                || RestartReminderPolicy.installStampChanged(installStamp, handled)
                || apkChanged
                || replacedPending;
        if (!need) return;
        // 同一次安装事件只弹一次：点「稍后再说」后回前台不再反复骚扰。
        // 但覆盖安装标记优先：那次安装如果没提示过（stamp 被 ROM 复用了同一个），
        // 也必须补弹一次，否则标记会永远挂着、永远不提示。
        if (!replacedPending && RestartReminderPolicy.alreadyPrompted(installStamp, prompted)
                && !InstallFingerprint.changed(apkSize, promptedSize)) {
            return;
        }
        final boolean firstInstall = RestartReminderPolicy.isFirstInstall(acked);
        if (restartPromptShowing) return;
        restartPromptShowing = true;
        try {
            sp.edit().putLong(RestartReminderPolicy.KEY_PROMPTED_STAMP, installStamp)
                    .putLong(RestartReminderPolicy.KEY_PROMPTED_SIZE, apkSize)
                    .putBoolean(RestartReminderPolicy.KEY_REPLACED_PENDING, false)
                    .apply();
        } catch (Throwable ignored) {
        }
        DiagLog.log("[PERM] 首页检测到需重启：" + RestartReminderPolicy.describe(installed, acked)
                + " 安装时间戳=" + installStamp + " apk=" + apkSize + "B 换包=" + apkChanged);

        try {
            new AlertDialog.Builder(this)
                    .setTitle(RestartReminderPolicy.title(firstInstall))
                    .setMessage(RestartReminderPolicy.bodyNeutral(firstInstall))
                    .setPositiveButton(RestartReminderPolicy.actionText(),
                            (d, w) -> restartSystemUiFromMain())
                    .setNegativeButton(RestartReminderPolicy.laterText(), null)
                    .setOnDismissListener(d -> restartPromptShowing = false)
                    .show();
        } catch (Throwable t) {
            restartPromptShowing = false;
            DiagLog.log("[PERM:SYSTEM] 重启提示弹窗失败: " + t.getMessage());
        }
    }

    /**
     * 当前这次安装的时间戳（毫秒），作「新安装事件」的指纹。
     *
     * 同版本覆盖安装时 versionCode 不变，只有这个值会刷新；取不到时返回 0，
     * 判定退回 versionCode 那一条，不会误判成「永远需要提示」。
     * 具体取值见 [InstallFingerprint]：lastUpdateTime 与 APK 落盘时间取较大者，
     * 避开部分 ROM 覆盖安装不刷新 lastUpdateTime 的坑。
     */
    private long currentInstallStamp() {
        return InstallFingerprint.stamp(this);
    }

    /**
     * 首页里的「重启系统界面」。
     *
     * 成功后记下 versionCode 与本次安装时间戳 —— 下次再启动时
     * [maybePromptRestartForUpdate] 就不会再问，直到下一次安装（升级或重装）发生。
     */
    private void restartSystemUiFromMain() {
        DiagLog.log("[PERM] 首页：用户点击「重启系统界面」v" + BuildConfig.VERSION_CODE);
        final long installStamp = currentInstallStamp();
        final long apkSize = InstallFingerprint.apkSize(this);
        SystemUiRestarter.restart(this, (ok, viaRoot) -> {
            if (isFinishing() || isDestroyed()) return;
            if (ok) {
                try {
                    getSharedPreferences(RestartReminderPolicy.SP_ONEOFF, MODE_PRIVATE)
                            .edit()
                            .putInt(RestartReminderPolicy.KEY_ACK_VERSION, BuildConfig.VERSION_CODE)
                            .putLong(RestartReminderPolicy.KEY_INSTALL_STAMP, installStamp)
                            .putLong(RestartReminderPolicy.KEY_INSTALL_SIZE, apkSize)
                            .commit();
                } catch (Throwable ignored) {
                }
                Toast.makeText(this, RestartReminderPolicy.successText(viaRoot),
                        Toast.LENGTH_LONG).show();
            } else {
                DiagLog.log("[PERM] 首页重启失败：两条通道都没通");
                new AlertDialog.Builder(this)
                        .setTitle("重启失败")
                        .setMessage(RestartReminderPolicy.failureText())
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
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
     * 只在第一次提示，拒绝之后可在关于页「权限与无障碍」引导页重新开启。
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
        // 【首次安装也提示】重启系统界面这条必须每次进首页都判一次：
        // 首次装完 SystemUI 里没有本模块代码，不重启等于所有开关都点不动；
        // 覆盖升级同理会继续跑旧类。用户确认重启后写入 ackedVersionCode，
        // 之后 shouldPrompt 自然返回 false，不会反复打扰。
        // 放在 onResume 而不是只在 onCreate：从首次引导页返回时也要能弹出来。
        getWindow().getDecorView().postDelayed(this::maybePromptRestartForUpdate, 1500);
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

        // 【BUG 修复】手动「保存」按钮已删除：所有配置改动即时落盘（见 saveConfig()），
        // 首页 / 智能提醒页 / 行走模式页的每个开关都是改完立即 commit + exportConfig，
        // 不再需要用户额外点一次保存。

        // 【5.6.13】本机 UI 手动选择芯片卡已按需求移除（首页不再展示）。
        // 后台自动识别保留：loadConfig() 仍算 romId 供设置页跳转/引导文案使用，
        // 跨机型适配能力一字未减，只是不再让用户手选。tvRomCaption/romChips 为 null，
        // refreshRomUi() 开头判空直接返回。

        // 底部导航
        pageSettings = findViewById(R.id.page_settings);
        pageAbout = findViewById(R.id.page_about);
        // 液态玻璃导航栏（Compose）：首页=0，关于=1。点 tab 切页面。
        // 注意 onPage=false 表示当前停在**首页**，所以初始值必须是 0；
        // 原来写成 onPage ? 0 : 1，一进来高亮就落在「关于」上。
        composeNav = findViewById(R.id.compose_nav);
        MainNav.attach(composeNav, onPage ? 1 : 0, idx -> {
            switchTab(idx == 0);
            return kotlin.Unit.INSTANCE;
        });

        // 关于页
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_about_subtitle)).setText("安全出行 · LSPosed 模块 v" + ver);
        } catch (Throwable ignored) {}
        // 权限与无障碍入口已上移到「智能提醒」页顶部（用户要求放在最靠前），
        // 关于页不再重复。绑定逻辑见 SmartReminderActivity。
        // GitHub 项目地址
        findViewById(R.id.btn_github).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk"));
            startActivity(intent);
        });
        // 功能建议：写一句想法，直接跳 GitHub 新建 issue（带机型与版本，省掉来回问）
        findViewById(R.id.btn_feedback).setOnClickListener(v ->
                startActivity(new Intent(this, FeedbackActivity.class)));
        // 致敬名单（开源项目）
        findViewById(R.id.btn_credits).setOnClickListener(v ->
                startActivity(new Intent(this, AcknowledgementsActivity.class)));
        // 感谢名单（参与测试 / 反馈的用户，头像 + 名字）
        findViewById(R.id.btn_thanks).setOnClickListener(v ->
                startActivity(new Intent(this, ThanksActivity.class)));
        // 支持捐赠（直接打开二维码页面）
        findViewById(R.id.btn_donate).setOnClickListener(v ->
                startActivity(new Intent(this, DonateQrActivity.class)));

        // 【5.6.13】功能实验区已整体删除：二级界面、入口、长按跳转及
        // 配套的一次性测试指令通道一并移除，普通用户界面不再出现实验入口。

        // 诊断日志磁贴：点一下开/关切换
        android.view.View tileLog = findViewById(R.id.btn_toggle_log);
        android.widget.TextView tvLog = findViewById(R.id.tv_toggle_log);
        Runnable refreshLogTile = () -> {
            boolean on = SafePrefs.getBool(xsp(), SafeWalkPrefKey.enableDiagLog, true);
            tvLog.setText(on ? "🪵 诊断日志：开" : "🪵 诊断日志：关（省电）");
        };
        refreshLogTile.run();
        tileLog.setOnClickListener(v -> {
            boolean nowOn = !SafePrefs.getBool(xsp(), SafeWalkPrefKey.enableDiagLog, true);
            xsp().edit().putBoolean(SafeWalkPrefKey.enableDiagLog, nowOn).commit();
            SafeWalkConfig.exportConfig(this);
            DiagLog.enabled = nowOn;
            DiagLog.log("[diag] 诊断日志 → " + nowOn);
            refreshLogTile.run();
            android.widget.Toast.makeText(this, nowOn ? "诊断日志已开" : "诊断日志已关",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        // 【5.5.0 隐私】两项本地隐私开关（5.6.13 起布局移入「关于」页，ID 不变，接线不变）
        tvHideLauncherState = findViewById(R.id.tv_hide_launcher_state);
        tvHideRecentState = findViewById(R.id.tv_hide_recent_state);
        findViewById(R.id.btn_hide_launcher).setOnClickListener(v -> onToggleHideLauncher());
        findViewById(R.id.btn_hide_recent).setOnClickListener(v -> onToggleHideRecent());

        // 【5.6.13 公益】寻找宝贝回家卡片：异步加载最新一条，点卡片打开官方登记页。
        setupMissingKidsCard();
    }

    /** 公益卡片：加载/渲染/点击。任何失败都降级为占位文案，不影响首页。 */
    private void setupMissingKidsCard() {
        android.view.View card = findViewById(R.id.card_missing_kids);
        if (card == null) return;
        MissingKidsRepository.load(this, kids -> {
            if (isFinishing() || isDestroyed()) return kotlin.Unit.INSTANCE;
            for (int i = 0; i < Math.min(kids.size(), MissingKidsRepository.SHOW_COUNT); i++) {
                bindKid(i, kids.get(i));
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /** 绑定第 idx（0 起）条公益卡：姓名/副标题/照片/点击跳官方登记页。 */
    private void bindKid(int idx, MissingKidsRepository.Kid kid) {
        int nameId, infoId, photoId, cardId;
        if (idx == 0) {
            nameId = R.id.tv_kid_name_1; infoId = R.id.tv_kid_info_1;
            photoId = R.id.iv_kid_photo_1; cardId = R.id.kid_card_1;
        } else {
            nameId = R.id.tv_kid_name_2; infoId = R.id.tv_kid_info_2;
            photoId = R.id.iv_kid_photo_2; cardId = R.id.kid_card_2;
        }
        final android.widget.TextView tvName = findViewById(nameId);
        final android.widget.TextView tvInfo = findViewById(infoId);
        final android.widget.ImageView ivPhoto = findViewById(photoId);
        final android.view.View kidCard = findViewById(cardId);

        tvName.setText(kid.getName());
        tvInfo.setText(MissingKidsRepository.subtitle(kid));
        kidCard.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(kid.getUrl())));
            } catch (Throwable t) {
                DiagLog.log("[kids] 打开登记页失败: " + t.getMessage());
            }
        });
        if (kid.getPhoto() != null && !kid.getPhoto().isEmpty()) {
            MissingKidsRepository.loadPhoto(this, kid.getPhoto(), bmp -> {
                if (bmp != null && !isFinishing() && !isDestroyed()) ivPhoto.setImageBitmap(bmp);
                return kotlin.Unit.INSTANCE;
            });
        }
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
        // 【5.5.0 隐私】两项都是纯 App 本地设置，不进白名单、不导出 JSON
        hideLauncherIcon = SafePrefs.getBool(sp, SafeWalkPrefKey.hideLauncherIcon, false);
        hideFromRecentTasks = SafePrefs.getBool(sp, SafeWalkPrefKey.hideFromRecentTasks, false);
        refreshPrivacyUi();
        applyRecentTasks();
        // 【系统 UI 适配】生效值 = 手动指定优先，否则自动识别。统一走 RomSettings，
        // 不再各处直接调 RomDetector.detectFromBuild()，否则手选之后这页和引导页
        // 会得出两个不同的结论。
        romId = RomSettings.effective(this);
        refreshTileStatus();
        refreshRomUi();
    }

    /**
     * 【BUG 修复 · 后端实时保存】配置落盘的唯一入口。
     *
     * 手动「保存」按钮已删除，本方法由**每一处配置变更**立即调用：
     * 用 commit()（同步写盘，避免刚改完就被杀进程丢配置）+ exportConfig()
     * 把配置导出给 SystemUI 进程的 Hook 读取。二级页（智能提醒 / 行走模式）
     * 同样是每个开关改完即 commit + exportConfig，全 App 不存在「待保存」状态。
     */
    private void saveConfig() {
        SharedPreferences.Editor e = xsp().edit();
        e.putBoolean(SafeWalkPrefKey.masterEnable, swEnable);
        e.putBoolean(SafeWalkPrefKey.enableBatteryWarning, swBattery);
        e.putBoolean(SafeWalkPrefKey.enableIdReminder, swId);
        e.putBoolean(SafeWalkPrefKey.enableSitHealthRemind, swSit);
        e.putBoolean(SafeWalkPrefKey.enableSocialTipCard, swSocial);
        e.putBoolean(SafeWalkPrefKey.enableNightTorch, swNightTorch);
        e.commit();
        // 导出 JSON 文件给 Hook 端读（同步完成，2s 轮询内生效）
        SafeWalkConfig.exportConfig(this);
        DiagLog.log("[ui] 配置实时落盘（commit + exportConfig），无需手动保存");
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

        boolean walkAuto = SafePrefs.getBool(xsp(), SafeWalkPrefKey.rearCamAuto, false);
        String walk;
        if (!swRearCam) walk = "行走模式已关闭";
        else walk = walkAuto ? "已开启 · 走动时自动显示" : "已开启 · 路况常显";
        if (!swEnable) walk = "总开关未开（" + (swRearCam ? "已开启" : "未开启") + "）";
        tileWalkStatus.setText(walk);
        tileWalkStatus.setTextColor(swEnable && swRearCam ? COLOR_ON : COLOR_OFF);
    }

    // ========== 【系统 UI 适配 · 首页入口】 ==========

    /**
     * 用户点了某个芯片：即时落盘（全 App 无「保存」按钮），再刷新高亮与注明行。
     *
     * 刻意**不**导出配置：这一项只影响本机 App 内部「把用户送到哪个设置页」，
     * SystemUI 侧的 Hook 不需要知道，导出只会多一个两边可能不一致的状态。
     */
    private void selectRom(String key) {
        RomSettings.save(this, key);
        romId = RomSettings.effective(this);
        DiagLog.log("[ROM] 首页切换系统 UI → " + RomSettings.normalize(key)
                + "｜" + RomSettings.explain(romId, key));
        try {
            DiagLog.log("[ROM] " + RomRegistry.describe(romId)
                    + "｜自启动去处：" + RomRegistry.routeHint(romId, TargetKind.AUTOSTART));
        } catch (Throwable t) {
            // 适配表坏了不能让「点一下芯片」变成崩溃（RomRegistry 是 object，
            // spec 写错会在首次访问抛 ExceptionInInitializerError，那是 Error）。
            DiagLog.log("[ROM] 适配表不可用，仅记录选择：" + t.getClass().getSimpleName());
        }
        refreshRomUi();
    }

    /** 刷新芯片高亮 + 那一行注明。 */
    private void refreshRomUi() {
        if (tvRomCaption == null || romChips == null) return;
        String raw = RomSettings.overrideOf(this);
        String normalized = RomSettings.normalize(raw);
        for (int i = 0; i < ROM_CHIP_KEYS.length && i < romChips.length; i++) {
            paintChip(romChips[i], ROM_CHIP_KEYS[i].equals(normalized));
        }
        if (romId == null) romId = RomSettings.effective(this);
        // 第三个参数只在「自动识别 + 落到 AOSP」时才起作用：三星/摩托这类未收录厂商
        // 会显示「未识别系统 UI · 按原生处理」，而不是错误地宣称「本机 UI：原生」。
        tvRomCaption.setText(RomSettings.caption(romId, normalized, RomDetector.isUnrecognizedOnBuild()));
    }

    private void paintChip(TextView chip, boolean on) {
        chip.setBackgroundResource(on ? R.drawable.bg_choice_chip_on : R.drawable.bg_choice_chip);
        chip.setTextColor(getColor(on ? R.color.card_bg : R.color.text_body));
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

    // ========== 【5.5.0 隐私】桌面图标隐藏 / 最近任务隐藏 ==========

    /**
     * 校准 alias：以 SharedPreferences 偏好为准，把「带 LAUNCHER / 无 LAUNCHER」两个别名
     * 设到应处的状态。只在状态确实不同时才调用系统 API（[LauncherIconPolicy.needsChange]），
     * 既满足「重复调用无副作用」，又避免每次进首页都触发桌面图标重建。
     *
     * 整个过程包 try/catch：部分 ROM 对 setComponentEnabledSetting 有限制，
     * 失败不能让首页打不开 —— 而首页打不开恰恰是最严重的「隐藏后再也打不开」。
     */
    private void calibrateLauncherIcon() {
        applyLauncherIcon(SafePrefs.getBool(xsp(), SafeWalkPrefKey.hideLauncherIcon, false));
    }

    private void applyLauncherIcon(boolean hide) {
        try {
            String pkg = getPackageName();
            ComponentName normal = new ComponentName(pkg, pkg + "." + LauncherIconPolicy.ALIAS_NORMAL);
            ComponentName hidden = new ComponentName(pkg, pkg + "." + LauncherIconPolicy.ALIAS_HIDDEN);
            PackageManager pm = getPackageManager();
            boolean normalShouldEnable = LauncherIconPolicy.normalAliasEnabled(hide);
            boolean hiddenShouldEnable = LauncherIconPolicy.hiddenAliasEnabled(hide);
            // 先启用目标、再禁用另一端，且 DONT_KILL_APP：
            // 任何一步抛错都不会让 App 进程被系统重启，桌面图标最多闪一下。
            if (LauncherIconPolicy.needsChange(
                    pm.getComponentEnabledSetting(hidden), hiddenShouldEnable)) {
                pm.setComponentEnabledSetting(hidden,
                        LauncherIconPolicy.componentState(hiddenShouldEnable),
                        PackageManager.DONT_KILL_APP);
            }
            if (LauncherIconPolicy.needsChange(
                    pm.getComponentEnabledSetting(normal), normalShouldEnable)) {
                pm.setComponentEnabledSetting(normal,
                        LauncherIconPolicy.componentState(normalShouldEnable),
                        PackageManager.DONT_KILL_APP);
            }
            DiagLog.log("[privacy] 桌面图标 alias 已对齐：hide=" + hide
                    + " | normal=" + normalShouldEnable + " hidden=" + hiddenShouldEnable);
        } catch (Throwable t) {
            DiagLog.log("[privacy] 桌面图标 alias 切换失败: " + t.getMessage());
        }
    }

    /** 隐藏前必须弹确认框，把系统级 / 磁贴 / adb 三条恢复路径一次讲清（功能 1 的 ①）。 */
    private void onToggleHideLauncher() {
        if (!hideLauncherIcon) {
            new AlertDialog.Builder(this)
                    .setTitle("隐藏桌面图标")
                    .setMessage(LauncherIconPolicy.recoveryMessage(getPackageName()))
                    .setPositiveButton("确认隐藏", (d, w) -> setHideLauncher(true))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            // 恢复是变安全的操作，无需二次确认
            setHideLauncher(false);
        }
    }

    private void setHideLauncher(boolean hide) {
        hideLauncherIcon = hide;
        xsp().edit().putBoolean(SafeWalkPrefKey.hideLauncherIcon, hide).commit();
        applyLauncherIcon(hide);
        refreshPrivacyUi();
        // 磁贴是隐藏态下的恢复入口，状态变了让它下一次展开就对齐
        WalkTileService.refresh(this);
        Toast.makeText(this, hide ? "桌面图标已隐藏" : "桌面图标已恢复",
                Toast.LENGTH_SHORT).show();
        DiagLog.log("[privacy] 桌面图标隐藏 → " + hide);
    }

    private void onToggleHideRecent() {
        hideFromRecentTasks = !hideFromRecentTasks;
        xsp().edit().putBoolean(SafeWalkPrefKey.hideFromRecentTasks, hideFromRecentTasks).commit();
        applyRecentTasks();
        refreshPrivacyUi();
        Toast.makeText(this,
                hideFromRecentTasks ? "已不在最近任务中显示" : "已恢复显示在最近任务",
                Toast.LENGTH_SHORT).show();
        DiagLog.log("[privacy] 最近任务隐藏 → " + hideFromRecentTasks);
    }

    /**
     * 运行时把本 App 的任务从「最近任务 / 概览」里抹掉。
     *
     * 用 API 21+ 的 public API ActivityManager.getAppTasks() → AppTask.setExcludeFromRecents()，
     * 不走反射；静态 android:excludeFromRecents 无法运行时切换，所以不能只靠 Manifest。
     * 另在 Activity 上写一条 TaskDescription，给用户一个可见的隐私提示。
     * 全部 try/catch：失败只是「隐私开关这一项没生效」，绝不能连累首页打开。
     */
    private void applyRecentTasks() {
        try {
            setTaskDescription(new ActivityManager.TaskDescription(
                    RecentTaskPolicy.taskDescriptionLabel("安全出行", hideFromRecentTasks)));
        } catch (Throwable ignored) {
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                task.setExcludeFromRecents(hideFromRecentTasks);
            }
            DiagLog.log("[privacy] 最近任务排除 → " + hideFromRecentTasks);
        } catch (Throwable t) {
            DiagLog.log("[privacy] 最近任务设置失败: " + t.getMessage());
        }
    }

    private void refreshPrivacyUi() {
        if (tvHideLauncherState == null || tvHideRecentState == null) return;
        tvHideLauncherState.setText(hideLauncherIcon ? "已隐藏" : "已显示");
        tvHideLauncherState.setTextColor(hideLauncherIcon ? COLOR_ON : COLOR_OFF);
        tvHideRecentState.setText(hideFromRecentTasks ? "已隐藏" : "已显示");
        tvHideRecentState.setTextColor(hideFromRecentTasks ? COLOR_ON : COLOR_OFF);
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
                swipeTracking = false;
                // 【BUG 修复】记录导航栏基准 tab；只有真正水平滑动才会扰动特效，
                // 单纯点按钮 / 竖向滚动不会让液态玻璃滑块乱跳。
                MainNav.beginDrag();
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                // 【手势导航联动 · BUG 修复】把左右滑动进度实时推给
                // LiquidTabsController，直接驱动 dampedDragAnimation（滑块 / 高亮 /
                // 折射一起跟手），不再只是平移整个导航栏容器。
                // 只在水平位移明显大于竖直时才联动，避免和页面竖向滚动打架。
                float mx = ev.getX() - downX;
                float my = ev.getY() - downY;
                if (!swipeTracking && Math.abs(mx) > SWIPE_SLOP
                        && Math.abs(mx) > Math.abs(my) * 1.5f) {
                    swipeTracking = true;
                }
                if (swipeTracking) {
                    // 以屏宽为分母归一化到 [-1,1]：负=左滑趋向「关于」，正=右滑趋向「首页」
                    int w = getResources().getDisplayMetrics().widthPixels;
                    MainNav.updateDragProgress(w > 0 ? mx / w : 0f);
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                boolean switched = false;
                if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                        && Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 2) {
                    if (onPage) {
                        if (dx > 0) { switchTab(true); logGesture("右滑", "关于→首页"); switched = true; }
                    } else if (dx < 0) {
                        switchTab(false); logGesture("左滑", "首页→关于"); switched = true;
                    }
                }
                if (!switched) {
                    // 未切页：释放按压态、滑块弹回基准 tab。
                    // 真切页时 switchTab → MainNav.select 已直接吸附到落点，
                    // 这里再调会「先弹回原位再滑到新页」，产生一次可见抖动。
                    MainNav.endDrag();
                }
                swipeTracking = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 【手势导航联动】分类调试日志：手势方向、切页结果、导航栏目标高亮 */
    private void logGesture(String dir, String fromTo) {
        DiagLog.log("[GESTURE] " + dir + " 切页 " + fromTo
                + " | 底部导航栏已联动高亮 index=" + (onPage ? 1 : 0));
    }

    private void switchTab(boolean toSettings) {
        // toSettings=true  → 首页（page_settings，四宫格磁贴），导航栏 index 0
        // toSettings=false → 关于（page_about），     导航栏 index 1
        onPage = !toSettings;
        pageSettings.setVisibility(toSettings ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(toSettings ? View.GONE : View.VISIBLE);
        // 高亮必须在这里同步：滑动切页（dispatchTouchEvent）不经过 Compose 的
        // onTabSelected，只写界面显隐的话导航栏会一直停在上一个 tab 上。
        MainNav.select(toSettings ? 0 : 1);
    }
}
