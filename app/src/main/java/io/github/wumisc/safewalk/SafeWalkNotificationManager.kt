package io.github.wumisc.safewalk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 通知管理器：全部走超级岛（PRIORITY_HIGH heads-up）。
 */
class SafeWalkNotificationManager(private val ctx: Context) {

    companion object {
        const val CHANNEL_ID = "SAFEWALK_REMIND"
        const val ID_BATTERY = 1001
        const val ID_IDCARD = 1002
        const val ID_SIT = 1003
        const val ID_SOCIAL = 1004
        const val ID_TORCH = 1005
    }

    init { createChannel() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "SafeWalk出行提醒", NotificationManager.IMPORTANCE_HIGH
            )
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
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
    }

    private fun notify(id: Int, b: NotificationCompat.Builder) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }

    fun batteryWarning() = notify(ID_BATTERY, baseBuilder("SafeWalk 电量预警", "快没电了，赶紧找个充电宝或就近充电。"))

    fun idReminder() = notify(ID_IDCARD, baseBuilder("SafeWalk 证件提醒", "身份证、护照等重要证件一定要贴身放好。"))

    fun sitRemind() = notify(ID_SIT, baseBuilder("SafeWalk 久坐提醒", "已坐了2小时了，该起来运动运动了。"))

    fun socialTip(text: String) = notify(ID_SOCIAL, baseBuilder("SafeWalk 社交提示", text))

    /** 夜间手电通知：超级岛带按钮，点按钮直接开手电筒 */
    fun flashlightSuggest() {
        val intent = android.content.Intent(SafeWalkLSPHook.ACTION_TORCH_TOGGLE)
        val pi = android.app.PendingIntent.getBroadcast(ctx, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        val b = baseBuilder("SafeWalk 夜间照明", "天黑了，点按钮开手电筒照亮前路")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_play, "开手电筒", pi)
            .setTimeoutAfter(120_000)

        notify(ID_TORCH, b)
    }
}
