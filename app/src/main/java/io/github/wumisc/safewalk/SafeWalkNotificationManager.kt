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

        // 通知冷却：60秒内不重复弹
        private const val NOTIFY_COOLDOWN_MS = 60_000L
        private var lastNotifyTs = 0L
    }

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

    /** 检查冷却，返回true表示可以弹 */
    private fun checkCooldown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTs < NOTIFY_COOLDOWN_MS) return false
        lastNotifyTs = now
        return true
    }

    private fun baseBuilder(title: String, msg: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // 超级岛
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            // HyperOS 超级岛扩展
            .apply {
                val islandBundle = Bundle().apply {
                    putString("miui.focus.template", "notification")
                }
                extras.putBundle("miui.focus.param", islandBundle)
            }
    }

    private fun notify(id: Int, b: NotificationCompat.Builder) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }

    fun batteryWarning() {
        if (!checkCooldown()) return
        notify(ID_BATTERY, baseBuilder("SafeWalk 电量预警", "快没电了，赶紧找个充电宝或就近充电。"))
    }

    fun idReminder() {
        if (!checkCooldown()) return
        notify(ID_IDCARD, baseBuilder("SafeWalk 证件提醒", "身份证、护照等重要证件一定要贴身放好。"))
    }

    fun sitRemind() {
        if (!checkCooldown()) return
        notify(ID_SIT, baseBuilder("SafeWalk 久坐提醒", "已坐了2小时了，该起来运动运动了。"))
    }

    fun socialTip(text: String) {
        if (!checkCooldown()) return
        notify(ID_SOCIAL, baseBuilder("SafeWalk 社交提示", text))
    }

    /**
     * 夜间手电通知：超级岛带两个按钮
     * 1. 开手电筒 → 广播给 LSP Hook 控制闪光灯
     * 2. 打开SafeWalk → 打开配置页
     */
    fun flashlightSuggest() {
        if (!checkCooldown()) return

        // 按钮1：开/关手电筒
        val torchIntent = Intent(SafeWalkLSPHook.ACTION_TORCH_TOGGLE)
        val torchPi = PendingIntent.getBroadcast(ctx, 1, torchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 按钮2：打开SafeWalk
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(ctx, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val b = baseBuilder("SafeWalk 夜间照明", "天黑了，点按钮开手电筒照亮前路")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_play, "手电筒", torchPi)
            .addAction(android.R.drawable.ic_menu_view, "打开SafeWalk", openPi)
            .setTimeoutAfter(120_000)

        notify(ID_TORCH, b)
    }
}
