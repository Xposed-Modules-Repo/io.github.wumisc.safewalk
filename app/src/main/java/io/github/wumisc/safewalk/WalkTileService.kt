package io.github.wumisc.safewalk

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 控制中心磁贴：一键开关「自动跟随步行」。
 *
 * 【它到底切什么】
 * 行走模式有两个独立配置：
 *  - `enableRearCamera`：路况预览总开关（有画面 / 没画面）
 *  - `rearCamAuto`：自动跟随步行（走起来自动显示、停下收起；关 = 常显）
 * 磁贴管的是**自动跟随步行**，和 App 里那个 swAuto 开关同一个语义：
 * 点亮 = 开自动跟随并拉起预览（两个配置都要写成 true，没有总开关就没有画面）；
 * 熄灭 = 只关自动跟随（rearCamAuto=false），总开关不动、服务不停，
 * 画面转为常显继续留在屏幕上，跟 App 内关掉「自动跟随步行」的表现完全一致。
 *
 * 【为什么关不需要中转页，开需要】
 * 关：写配置 + 发 SHOW 给服务即可（服务据此停掉步态监听、画面转常显），不启动任何组件。
 * 开：如果服务已在跑（原本是常显档），只改配置 + 发 SHOW，不用中转页；
 * 如果服务没跑，`WalkPreviewService` 是 `camera` 类型前台服务，QS 面板展开时
 * 应用在后台，Android 14+ 直接 `startForegroundService` 会抛
 * ForegroundServiceStartNotAllowedException。官方稳定豁免是「启动瞬间应用有
 * 可见 Activity」，所以这一次交给 [WalkToggleActivity] 这个透明中转页去起服务。
 *
 * 【状态为什么不看 isAlive()】
 * 见 [WalkTileStatePolicy]：自动跟随开着就显示激活，否则点完到服务起来的两秒内
 * 磁贴还是灰的，像点了没反应。服务没活只在副标题里提示。
 *
 * 【磁贴不承担「恢复入口」职责】
 * 它只负责「常显 ↔ 自动跟随」这一个纯切换，任何开关状态下点下去都是切换语义。
 * 桌面图标隐藏后的恢复入口走**通知**（行走模式的常驻通知点击即回首页）与
 * **LSPosed 模块**（INFO 入口，不依赖桌面图标），磁贴不再为此让位 ——
 * 否则「隐藏图标的用户发现磁贴不切换档位了」，两种语义互相污染。
 */
class WalkTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        DiagLog.init(applicationContext)
        DiagLog.log("[tile] 磁贴已添加到控制中心")
        refreshTile()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        DiagLog.log("[tile] 磁贴已从控制中心移除")
    }

    override fun onStartListening() {
        super.onStartListening()
        // 面板每次展开都会回调：App 内改过开关后，这里能自动对齐
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        DiagLog.init(applicationContext)
        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)

        // 磁贴只做「常显 ↔ 自动跟随」的纯切换，不再兼任「桌面图标隐藏后的恢复入口」：
        // 恢复入口走通知栏常驻通知与 LSPosed 模块，磁贴不因隐藏态改变行为。
        val master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)
        val walk = SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)
        val auto = SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)

        // 【步数源不可用 → 磁贴改语义】自动跟随没有步数就永远弹不出来，
        // 与其留一个点了没反应的假开关，不如让它变成「路况预览」总开关。
        if (TileSemanticPolicy.isManual(StepSourceProbe.issue(this))) {
            onManualClick(sp, master, walk)
            return
        }

        when (WalkTileStatePolicy.resolve(master, walk, auto)) {
            WalkTileStatePolicy.UNAVAILABLE -> {
                // 置灰状态通常点不动，但个别 ROM 仍会回调：给一句明确原因，别让人以为坏了
                toast("总开关已关闭，请先在安全出行里打开")
                startActivityAndCollapseCompat(mainIntent())
            }

            WalkTileStatePolicy.ACTIVE -> turnOff()

            else -> turnOn(walk)
        }
        refreshTile()
    }

    /**
     * 【手动档】步数源不可用时的点击：把路况预览当纯开关用（开 / 关画面）。
     *
     * 和自动档的区别只有一处：这里写的不是 `rearCamAuto`，而是 `enableRearCamera` 本身；
     * `rearCamAuto` 会被强制写成 false，避免留着「自动跟随开」的配置让服务空等步态。
     */
    private fun onManualClick(sp: android.content.SharedPreferences, master: Boolean, walk: Boolean) {
        if (!master) {
            toast("总开关已关闭，请先在安全出行里打开")
            startActivityAndCollapseCompat(mainIntent())
            refreshTile()
            return
        }
        if (walk) {
            sp.edit()
                .putBoolean(SafeWalkPrefKey.enableRearCamera, false)
                .putBoolean(SafeWalkPrefKey.rearCamAuto, false)
                .commit()
            SafeWalkConfig.exportConfig(this)
            WalkPreviewService.send(this, WalkPreviewService.ACTION_STOP, null)
            DiagLog.log("[tile] 手动档 → 关闭路况预览")
            toast("已关闭路况预览")
        } else if (WalkPreviewService.isAlive()) {
            sp.edit()
                .putBoolean(SafeWalkPrefKey.enableRearCamera, true)
                .putBoolean(SafeWalkPrefKey.rearCamAuto, false)
                .commit()
            SafeWalkConfig.exportConfig(this)
            WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, null)
            DiagLog.log("[tile] 手动档 → 服务已在跑，打开路况预览（常显）")
            toast("已打开路况预览")
        } else {
            DiagLog.log("[tile] 手动档 → 经中转页启动预览服务（常显）")
            val launch = {
                startActivityAndCollapseCompat(
                    Intent(this, WalkToggleActivity::class.java)
                        .putExtra(WalkToggleActivity.EXTRA_MANUAL_PREVIEW, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            }
            val km = getSystemService(KeyguardManager::class.java)
            if (km != null && km.isKeyguardLocked) {
                unlockAndRun { launch() }
            } else {
                launch()
            }
        }
        refreshTile()
    }

    /** 关：只关自动跟随（rearCamAuto=false），画面转常显、服务继续跑；不动总开关。全程不需要可见界面。 */
    private fun turnOff() {
        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
        sp.edit()
            .putBoolean(SafeWalkPrefKey.rearCamAuto, false)
            .commit()
        SafeWalkConfig.exportConfig(this)
        WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, null)
        DiagLog.log("[tile] 磁贴 → 关闭自动跟随（画面转常显，服务继续运行）")
        toast("已关闭自动跟随（画面保持常显）")
    }

    /**
     * 开：开自动跟随并拉起预览，两个配置都写成 true（没有总开关就没有画面）。
     * 优先走「服务已在跑」的轻量路径，只切档位、不闪中转页；
     * 服务没跑才需要可见 Activity 去拉起 camera 前台服务。
     */
    private fun turnOn(walkOn: Boolean) {
        if (walkOn && WalkPreviewService.isAlive()) {
            val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
            sp.edit()
                // 防历史残留：服务在跑但 enableRearCamera 可能被别处写过 false
                .putBoolean(SafeWalkPrefKey.enableRearCamera, true)
                .putBoolean(SafeWalkPrefKey.rearCamAuto, true)
                .commit()
            SafeWalkConfig.exportConfig(this)
            WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, null)
            DiagLog.log("[tile] 磁贴 → 服务已在跑，切到自动跟随档（走起来显示/停下收起）")
            toast("已开启自动跟随：走起来才显示")
            return
        }
        DiagLog.log("[tile] 磁贴 → 请求开启自动跟随（经透明中转页启动相机前台服务）")
        val launch = {
            startActivityAndCollapseCompat(
                Intent(this, WalkToggleActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
        // 锁屏上也能展开控制中心：直接起 Activity 会被锁屏挡住，先请用户解锁
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            unlockAndRun { launch() }
        } else {
            launch()
        }
    }

    private fun mainIntent(): Intent {
        // 最近任务也隐藏时，新任务从一开始就不进概览，避免恢复入口反而留下痕迹
        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
        val hideRecent = SafePrefs.getBool(sp, SafeWalkPrefKey.hideFromRecentTasks, false)
        return Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    or RecentTaskPolicy.launchFlags(hideRecent))
    }

    /**
     * Android 14 起 `startActivityAndCollapse(Intent)` 被废弃，必须用 PendingIntent 版本
     * （旧版在 14 上会把面板收起但**不启动** Activity）。minSdk 33，所以两条分支都要留。
     */
    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile: Tile = qsTile ?: return
        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
        val master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)
        val walk = SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)
        var auto = SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)
        val issue = StepSourceProbe.issue(this)
        val manual = TileSemanticPolicy.isManual(issue)

        // 【强制关自动跟随】步数不可用时 auto 留在配置里毫无意义：服务会一直等步态，
        // 界面上的开关却还亮着。这里只清一次配置，不打扰用户（改变的是无效状态）。
        if (manual && auto) {
            sp.edit().putBoolean(SafeWalkPrefKey.rearCamAuto, false).commit()
            SafeWalkConfig.exportConfig(this)
            DiagLog.log("[tile] 步数源不可用（$issue）→ 强制关闭自动跟随")
            auto = false
        }

        if (manual) {
            tile.state = TileSemanticPolicy.manualState(master, walk)
            tile.label = getString(R.string.tile_preview_label)
            tile.subtitle = TileSemanticPolicy.manualSubtitle(
                master, walk, WalkPreviewService.isAlive()
            )
        } else {
            // 【有步数时预览必须开】自动跟随的点亮前提是「有画面」：
            // 只写 rearCamAuto=true 而 enableRearCamera=false，服务起来也没画面，
            // 用户看到的就是「开关亮着、屏幕空的」。这里补一次，和磁贴点亮时写的两个 key 一致。
            if (master && auto && !walk) {
                sp.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, true).commit()
                SafeWalkConfig.exportConfig(this)
            }
            tile.state = WalkTileStatePolicy.resolve(master, walk, auto)
            tile.label = getString(R.string.tile_walk_label)
            // 副标题统一由纯策略给出：磁贴不因「图标已隐藏」改变文案或行为（恢复入口走通知/LSPosed）
            tile.subtitle = WalkTileStatePolicy.subtitle(
                master, walk, auto, WalkPreviewService.isAlive()
            )
        }
        tile.updateTile()
    }

    private fun toast(text: String) {
        try {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    companion object {

        /**
         * 主动请求系统刷新磁贴外观。
         *
         * 面板收起时 `onStartListening()` 不会被调用，App 内改了开关、或预览服务
         * 启停，磁贴就会停在旧状态。这里在状态真正变化的地方调一下，
         * 用户下次拉开面板看到的就是对的（系统会尽快回调 onStartListening）。
         */
        @JvmStatic
        fun refresh(ctx: Context) {
            try {
                requestListeningState(
                    ctx, ComponentName(ctx, WalkTileService::class.java)
                )
            } catch (_: Throwable) {
                // 用户没添加磁贴时可能抛，忽略即可
            }
        }
    }
}
