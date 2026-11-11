package io.github.wumisc.safewalk

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * 无界面自启中转页。
 *
 * 【为什么需要它】
 * WalkPreviewService 是 `camera` 类型前台服务。Android 14+ 规定：CAMERA 属于
 * 「使用期间」权限，应用在后台时不持有该权限，从后台 `startForegroundService`
 * 一个 camera FGS 会直接抛 SecurityException；Android 15 起 `BOOT_COMPLETED`
 * 接收器也被明令禁止启动 camera / dataSync 等类型的 FGS，抛
 * ForegroundServiceStartNotAllowedException。
 *
 * 官方给出的稳定豁免只有几种，其中最适合本模块的是：
 *   「启动 FGS 的那一刻，本应用有一个可见的 Activity」。
 *
 * 所以链路由 SystemUI 里的 Hook 发起：
 *   Hook(SystemUI，系统进程) --startActivity--> 本 Activity(透明)
 *       --startForegroundService--> WalkPreviewService
 * 本页存在的那几百毫秒里应用处于可见状态，服务启动合法；随后自己 finish()，
 * 用户几乎无感。这就是「重启后不用再手动拨行走模式开关」的实现方式。
 *
 * 【安全】
 * 只接受系统进程（SystemUI 的 Hook）拉起；同时校验广播令牌，
 * 兼容极少数给 SystemUI 分配了独立 uid 的 ROM，挡掉第三方应用静默开相机。
 */
class AutoStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 进程冷启动时日志出口还没初始化，先补一下，保证这次自启有记录可查
        try {
            DiagLog.init(applicationContext)
        } catch (_: Throwable) {
        }

        // 【配置防丢】自启时主界面可能还没开过：先从外部备份恢复配置，
        // 避免数据被清/重装后开机按默认值（行走模式=关）判断。
        try {
            ConfigBackup.restoreIfNeeded(applicationContext)
        } catch (_: Throwable) {
        }

        // `launchedFromUid` 是 API 34 才有的属性。minSdk 33 上直接读会抛 NoSuchMethodError
        // （被下面 catch 吞掉后 fromUid=-1，虽然不至于崩，但日志里会变成「uid=-1 拒绝」，
        // 排查时极易误判成「被第三方拉起」）。这里显式按版本取值。
        val fromUid = if (Build.VERSION.SDK_INT >= 34) {
            try {
                launchedFromUid
            } catch (_: Throwable) {
                -1
            }
        } else {
            -1
        }
        if (!isTrustedLauncher(fromUid)) {
            DiagLog.log("[walk] AutoStart 被不可信 uid=$fromUid 拉起，拒绝"
                    + (if (fromUid < 0) "（本机 SDK=${Build.VERSION.SDK_INT}，取不到启动方 uid，仅按令牌判定）" else ""))
            finish()
            return
        }

        // 【防误启】Hook 的配置快照有 60 秒缓存：用户刚在 App 里关掉行走模式，
        // 看门狗可能还拿着「开」的旧值把本页拉起来。这里以 App 自己的 SP 为准
        // 再判一次，避免服务被拉起来空转、前台通知明明挂着却没有画面。
        val sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)
        val desired = KeepAlivePolicy.desired(
            SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true),
            SafePrefs.getBool(sp, SafeWalkPrefKey.enableRearCamera, false)
        )
        if (!desired) {
            DiagLog.log("[walk] AutoStart：配置已关闭行走模式，拒绝代启（防止看门狗缓存滞后误启）")
            finish()
            return
        }

        // 只有「曾被系统杀掉后由看门狗/静态兜底拉起」才带这个标记，
        // 开机首次自启不带 —— 否则每次开机都弹一条「已自动恢复」。
        val fromRevive = try {
            intent?.getBooleanExtra(KeepAlivePolicy.EXTRA_FROM_REVIVE, false) ?: false
        } catch (_: Throwable) {
            false
        }
        try {
            WalkPreviewService.start(this, fromRevive)
            DiagLog.log("[walk] AutoStart：已请求启动预览服务（fromUid=$fromUid, fromRevive=$fromRevive）")
        } catch (t: Throwable) {
            DiagLog.log("[walk] AutoStart 启动服务失败: ${t.message}")
        }

        // 稍等再退出：给服务把 startForeground() 提上去留出时间，
        // 避免「Activity 已销毁 → 进程被认为可回收」与 FGS 提升之间的竞态。
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 400L)
    }

    /**
     * 可信来源：
     * ① 系统 uid（android.uid.system = 1000），AOSP 上 SystemUI 即此 uid；
     * ② 任何系统段 uid（< FIRST_APPLICATION_UID），兼容个别 ROM 的 SystemUI 变体；
     * ③ 携带正确广播令牌——兼容给 SystemUI 分配了应用段 uid 的 ROM，
     *    令牌与本模块其它跨进程通道保持一致。
     */
    private fun isTrustedLauncher(uid: Int): Boolean {
        if (uid == Process.SYSTEM_UID) return true
        if (uid in 1 until Process.FIRST_APPLICATION_UID) return true
        val token = try {
            intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
        } catch (_: Throwable) {
            null
        }
        return token != null && token == SafeWalkPrefKey.BROADCAST_TOKEN
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
