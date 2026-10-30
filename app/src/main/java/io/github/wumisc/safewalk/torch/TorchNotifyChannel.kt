package io.github.wumisc.safewalk.torch

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * 创建手电筒通知通道（适配Android O+）
 */
fun createTorchNotifyChannel(context: Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            TORCH_CHANNEL_ID,
            "SafeWalk夜间手电",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "夜间出行手电筒悬浮提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
