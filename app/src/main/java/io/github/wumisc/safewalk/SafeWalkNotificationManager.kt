package io.github.wumisc.safewalk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * 通知管理器：全部走超级岛（PRIORITY_HIGH heads-up）。
 * 带快捷按钮：手电筒、打开SafeWalk。
 * 带通知冷却，防重复弹窗。
 */
class SafeWalkNotificationManager(private val ctx: Context) {

    companion object {
        const val CHANNEL_ID = "SAFEWALK_REMIND"
        const val ID_BATTERY = 1001
        const val ID_IDCARD = 1002
        const val ID_SIT = 1003
        const val ID_SOCIAL = 1004
        const val ID_TORCH = 1005
        /** 预览失败提示（不是提醒，是故障告知） */
        const val ID_PREVIEW = 1006

        /** 本模块包名，用于构造跨进程 PendingIntent */
        private const val APP_PKG = "io.github.wumisc.safewalk"
        /** SystemUI 包名：手电广播必须显式投递，否则隐式广播收不到 */
        private const val SYSTEMUI_PKG = "com.android.systemui"

        private const val NOTIFY_COOLDOWN_MS = 60_000L
    }

    // 通知冷却：按通道分别计时。
    // ① 原先是一个全局时间戳，一次出门同时满足证件/社交/夜间提醒时只有第一条能弹；
    // ② 必须放实例字段而不是 companion：静态表会在进程内永久累积，
    //    Hook 重新初始化后仍带着旧时间戳，导致提醒长时间不出现。
    private val lastNotifyTs = HashMap<Int, Long>()

    init { createChannel() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "SafeWalk出行提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "出行安全悬浮提醒，快捷按钮：手电筒、打开SafeWalk"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    /** 检查冷却，按通知 id 独立判定 */
    private fun checkCooldown(id: Int): Boolean {
        val now = System.currentTimeMillis()
        val last = lastNotifyTs[id] ?: 0L
        if (now - last < NOTIFY_COOLDOWN_MS) return false
        lastNotifyTs[id] = now
        return true
    }

    /**
     * 「打开 SafeWalk」的 PendingIntent。
     *
     * 必须用 setClassName 显式指定包名+类名：
     * 这里 ctx 是 SystemUI 的 context，若写 Intent(ctx, MainActivity::class.java)，
     * 会拼成 ComponentName("com.android.systemui", "...MainActivity")，点击必然打不开。
     */
    private fun openAppPi(): PendingIntent {
        val i = Intent().apply {
            setClassName(APP_PKG, "$APP_PKG.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun baseBuilder(title: String, msg: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            // 点通知主体直接进 App，此前所有通知都没设 contentIntent，点了没反应
            .setContentIntent(openAppPi())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // 超级岛
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            // 声音交给 channel 管。原来这里又加了一次 DEFAULT_SOUND，
            // 等于「channel 响一声 + 通知再响一声」，部分 ROM 上真的会连响两次。
            // HyperOS 超级岛扩展
            .setExtras(
                Bundle().apply {
                    putBundle("miui.focus.param", Bundle().apply {
                        putString("miui.focus.template", "notification")
                    })
                }
            )
    }

    private fun notify(id: Int, b: NotificationCompat.Builder) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }

    fun batteryWarning() {
        if (!checkCooldown(ID_BATTERY)) return
        notify(ID_BATTERY, baseBuilder("SafeWalk 电量预警", "快没电了，赶紧找个充电宝或就近充电。"))
    }

    fun idReminder() {
        if (!checkCooldown(ID_IDCARD)) return
        notify(ID_IDCARD, baseBuilder("SafeWalk 证件提醒", "身份证、护照等重要证件一定要贴身放好。"))
    }

    fun sitRemind() {
        if (!checkCooldown(ID_SIT)) return
        notify(ID_SIT, baseBuilder("SafeWalk 久坐提醒", "已坐了2小时了，该起来运动运动了。"))
    }

    fun socialTip(text: String) {
        if (!checkCooldown(ID_SOCIAL)) return
        notify(ID_SOCIAL, baseBuilder("SafeWalk 社交提示", text))
    }

    /**
     * 后视画面出不来。
     *
     * 这个提示存在的意义：以前相机打不开只会写一行日志，用户看到的是
     * 「点预览没反应」或者一个半透明黑框，最后只能得出「这功能是假的」。
     * 现在至少能说清楚是权限问题还是相机没回画面。
     */
    fun previewFailed(reason: String) {
        if (!checkCooldown(ID_PREVIEW)) return
        notify(
            ID_PREVIEW,
            baseBuilder(
                "SafeWalk 后视画面打不开",
                "原因：$reason。\n\n相机在本应用内打开：请确认已授予「相机」权限，且没有其他应用（相机、扫码、视频通话）正占用后置摄像头。"
            )
        )
    }

    /**
     * 夜间手电通知：超级岛带两个按钮
     * 1. 开手电筒 → 投给应用自己的 TorchActionReceiver（它负责让预览先让路再点灯）
     * 2. 打开SafeWalk → 打开配置页
     */
    fun flashlightSuggest() {
        if (!checkCooldown(ID_TORCH)) return

        // 按钮1：打开手电筒。夜间提醒的语义就是「太黑了，帮你把灯打开」，
        // 所以发明确的 ON 指令（不是 toggle）。toggle 有一个致命问题：
        // 它靠接收端自己记的翻转状态，和手电真实状态（TorchCallback 同步的）
        // 会脱节——用户先用手电再点按钮，就会出现「点开变关」。
        //
        // 落点是**透明 Activity**，不是广播。
        // 夜间应用进程常被 ROM 回收，跨进程广播能不能把它拉起来全看后台策略，
        // HyperOS 上大概率直接丢弃——这就是「点了没反应、日志里连一行都没有」。
        // 而点击通知是用户操作，PendingIntent.getActivity 一定能拉起进程，
        // 只有这样才能保证 setTorchMode 真的被执行。
        val torchIntent = Intent(io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_ON).apply {
            setClassName(APP_PKG, "$APP_PKG.TorchActionActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
        }
        val torchPi = PendingIntent.getActivity(ctx, 1, torchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 按钮2：打开SafeWalk
        val openPi = openAppPi()

        val b = baseBuilder("SafeWalk 夜间照明", "天黑了，点按钮开手电筒照亮前路")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_play, "手电筒", torchPi)
            .addAction(android.R.drawable.ic_menu_view, "打开SafeWalk", openPi)
            .setTimeoutAfter(120_000)

        notify(ID_TORCH, b)
    }
}
