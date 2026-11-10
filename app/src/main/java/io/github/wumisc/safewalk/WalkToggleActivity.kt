package io.github.wumisc.safewalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 控制中心磁贴的「开」中转页（透明、不进最近任务、无动画）。
 *
 * 【为什么磁贴不能自己启动服务】
 * [WalkPreviewService] 是 `camera` 类型前台服务。QS 面板展开时本应用在后台，
 * Android 14+ 从后台启动 camera FGS 会直接抛异常（CAMERA 属于 while-in-use 权限）。
 * 官方稳定豁免是「启动 FGS 的那一刻应用有可见 Activity」——本页存在的几百毫秒
 * 就是那个窗口，服务提起前台后自己 finish，用户只看到小窗出现。
 *
 * 【权限不齐时怎么办】
 * 不写配置、不启动服务，直接把用户送到权限引导页并说明缺哪一项。
 * 如果把 `enableRearCamera` 先写成 true、服务却没起来，磁贴会显示「已开启」
 * 而屏幕上什么都没有 —— 这是最难排查的一类"假开"。
 */
class WalkToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 进程可能刚被磁贴冷启动：日志出口、配置备份都要先就位
        try {
            DiagLog.init(applicationContext)
        } catch (_: Throwable) {
        }
        try {
            ConfigBackup.restoreIfNeeded(applicationContext)
        } catch (_: Throwable) {
        }

        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
        val masterOn = SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)
        if (!masterOn) {
            toast("总开关已关闭，请先在安全出行里打开")
            openThenFinish(MainActivity::class.java)
            return
        }

        val camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        if (!camOk || !overlayOk) {
            val missing = when {
                !camOk && !overlayOk -> "相机与悬浮窗"
                !camOk -> "相机"
                else -> "悬浮窗"
            }
            toast("还缺「$missing」权限，先完成设置再点磁贴")
            DiagLog.log("[tile] 权限不足（相机=$camOk 悬浮窗=$overlayOk），跳权限引导页")
            openThenFinish(PermissionOnboardingActivity::class.java)
            return
        }

        // 配置先落盘再启动：服务在 onCreate 里就要读到 enableRearCamera=true
        sp.edit().putBoolean(SafeWalkPrefKey.enableRearCamera, true).commit()
        SafeWalkConfig.exportConfig(this)
        DiagLog.log("[tile] 中转页：已写开关=开，启动预览服务")
        // 配置已是「开」，磁贴立刻切成激活态，不必等用户下次拉开面板
        WalkTileService.refresh(this)

        try {
            WalkPreviewService.start(this)
        } catch (t: Throwable) {
            DiagLog.log("[tile] 中转页启动预览服务失败: ${t.javaClass.simpleName} ${t.message}")
            toast("启动失败，请从应用内开启")
        }

        // 等服务把 startForeground() 提上去再退出，避免「Activity 已销毁 → 进程可回收」
        // 与 FGS 提升之间的竞态（与 AutoStartActivity 同款处理）
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            WalkTileService.refresh(this)
        }, 450L)
    }

    private fun openThenFinish(cls: Class<*>) {
        try {
            startActivity(
                Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (t: Throwable) {
            DiagLog.log("[tile] 跳转 ${cls.simpleName} 失败: ${t.message}")
        }
        finish()
    }

    private fun toast(text: String) {
        try {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        try {
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {
        }
    }
}
