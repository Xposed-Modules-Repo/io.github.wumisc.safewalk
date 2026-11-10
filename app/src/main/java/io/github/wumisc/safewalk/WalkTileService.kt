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
 * 磁贴管的是**自动跟随步行**：点亮 = 走起来才弹小窗，再点 = 关掉自动跟随。
 * 所以点亮时两个配置都要写成 true（没有总开关就没有画面），熄灭时两个都写 false ——
 * 只把 auto 写成 false 会留下「总开关还是开、档位是常显」的状态，下次开机
 * `AutoStartActivity` 一启动服务就会常显小窗，等于关不干净。
 *
 * 【为什么关不需要中转页，开需要】
 * 关：写配置 + 发 STOP 给服务即可，不启动任何组件。
 * 开：如果服务已在跑（原本是常显档），只改配置 + 发 SHOW，不用中转页；
 * 如果服务没跑，`WalkPreviewService` 是 `camera` 类型前台服务，QS 面板展开时
 * 应用在后台，Android 14+ 直接 `startForegroundService` 会抛
 * ForegroundServiceStartNotAllowedException。官方稳定豁免是「启动瞬间应用有
 * 可见 Activity」，所以这一次交给 [WalkToggleActivity] 这个透明中转页去起服务。
 *
 * 【状态为什么不看 isAlive()】
 * 见 [WalkTileStatePolicy]：自动跟随开着就显示激活，否则点完到服务起来的两秒内
 * 磁贴还是灰的，像点了没反应。服务没活只在副标题里提示。
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
        val master = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)
        val walk = SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)
        val auto = SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)

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

    /** 关：自动跟随 + 路况预览一起关，服务停止。全程不需要可见界面。 */
    private fun turnOff() {
        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
        sp.edit()
            .putBoolean(SafeWalkPrefKey.enableRearCamera, false)
            .putBoolean(SafeWalkPrefKey.rearCamAuto, false)
            .commit()
        SafeWalkConfig.exportConfig(this)
        WalkPreviewService.send(this, WalkPreviewService.ACTION_STOP, null)
        DiagLog.log("[tile] 磁贴 → 关闭自动跟随步行（同时停掉预览服务）")
        toast("已关闭自动跟随步行")
    }

    /**
     * 开：优先走「服务已在跑」的轻量路径，只切档位、不闪中转页。
     * 服务没跑才需要可见 Activity 去拉起 camera 前台服务。
     */
    private fun turnOn(walkOn: Boolean) {
        if (walkOn && WalkPreviewService.isAlive()) {
            val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
            sp.edit().putBoolean(SafeWalkPrefKey.rearCamAuto, true).commit()
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

    private fun mainIntent(): Intent =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

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
        val auto = SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)
        tile.state = WalkTileStatePolicy.resolve(master, walk, auto)
        tile.label = getString(R.string.tile_walk_label)
        tile.subtitle = WalkTileStatePolicy.subtitle(
            master, walk, auto, WalkPreviewService.isAlive()
        )
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
