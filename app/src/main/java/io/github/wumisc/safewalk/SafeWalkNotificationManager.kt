package io.github.wumisc.safewalk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 通知管理器：电量、证件、久坐、社交礼仪提醒。
 * 全部本地，不上传网络。
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

    fun showNotify(id: Int, title: String, msg: String) {
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }

    fun batteryWarning() = showNotify(ID_BATTERY, "SafeWalk 电量预警", "快没电了，赶紧找个充电宝或就近充电。")

    fun idReminder() = showNotify(ID_IDCARD, "SafeWalk 证件提醒", "身份证、护照等重要证件一定要贴身放好。")

    fun sitRemind() = showNotify(ID_SIT, "SafeWalk 久坐提醒", "已坐了2小时了，该起来运动运动了。")

    fun socialTip(text: String) = showNotify(ID_SOCIAL, "SafeWalk 社交提示", text)

    fun flashlightSuggest() {
        val intent = android.content.Intent("io.github.wumisc.safewalk.TORCH_TOGGLE").setPackage(ctx.packageName)
        val pi = android.app.PendingIntent.getBroadcast(ctx, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SafeWalk 夜间照明")
            .setContentText("天黑了，点此开启手电筒照亮前路")
            .setStyle(NotificationCompat.BigTextStyle().bigText("天黑了，走路小心，点下方按钮开启手电筒"))
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_play, "开手电筒", pi)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_TORCH, b.build())
    }
}
