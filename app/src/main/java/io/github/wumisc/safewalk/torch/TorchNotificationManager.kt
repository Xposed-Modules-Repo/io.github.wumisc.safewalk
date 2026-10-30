package io.github.wumisc.safewalk.torch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.wumisc.safewalk.MainActivity

/**
 * 手电筒通知管理器：
 * - 使用 CameraManager.TorchCallback 全局监听闪光灯状态（兼容HyperOS）
 * - 通知按钮动态切换「打开手电 / 关闭手电」
 * - 通知60秒自动消失，闪光灯保持开启
 */
class TorchNotificationManager(private val context: Context) {
    @Volatile
    internal var torchIsOn = false
        private set

    private var torchCallback: CameraManager.TorchCallback? = null
    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "SafeWalk-Torch"
    }

    /**
     * 开始监听手电筒状态（Camera2原生回调，HyperOS兼容）
     */
    fun startMonitor() {
        try {
            Log.i(TAG, "开始监听手电状态")
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    Log.i(TAG, "手电状态变化: $enabled")
                    torchIsOn = enabled
                    refreshNotification()
                }
            }
            torchCallback = callback
            camManager.registerTorchCallback(callback, null)
            Log.i(TAG, "TorchCallback 注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "TorchCallback 注册失败", e)
        }
    }

    /**
     * 停止监听，释放资源
     */
    fun stopMonitor() {
        torchCallback?.let {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camManager.unregisterTorchCallback(it)
            torchCallback = null
        }
    }

    /**
     * 刷新手电通知，根据当前状态动态切换按钮文字
     */
    fun refreshNotification() {
        Log.i(TAG, "刷新通知，手电状态: $torchIsOn")

        // 点击通知主体 → 打开SafeWalk主页
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 按钮：发广播，系统进程里的receiver接收处理（系统进程有权限访问摄像头）
        val turnOnIntent = Intent("io.github.wumisc.safewalk.OPEN_TORCH").apply {
            setPackage(context.packageName)
        }
        val turnOnPi = PendingIntent.getBroadcast(
            context, 100, turnOnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val turnOffIntent = Intent("io.github.wumisc.safewalk.CLOSE_TORCH").apply {
            setPackage(context.packageName)
        }
        val turnOffPi = PendingIntent.getBroadcast(
            context, 200, turnOffIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, TORCH_CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppPi)
            .setTimeoutAfter(60 * 1000L)

        if (torchIsOn) {
            builder
                .setContentTitle("手电筒已开启")
                .setContentText("已长时间静置，记得及时关闭手电节约电量")
                .addAction(
                    NotificationCompat.Action.Builder(
                        context.applicationInfo.icon,
                        "关闭手电",
                        turnOffPi
                    ).build()
                )
        } else {
            builder
                .setContentTitle("夜间手电筒")
                .setContentText("夜间出行，点击按钮打开手电照明")
                .addAction(
                    NotificationCompat.Action.Builder(
                        context.applicationInfo.icon,
                        "打开手电",
                        turnOnPi
                    ).build()
                )
        }
        nm.notify(TORCH_NOTIFY_ID, builder.build())
    }

    /**
     * 手动移除通知
     */
    fun dismissNotification() {
        nm.cancel(TORCH_NOTIFY_ID)
    }
}
