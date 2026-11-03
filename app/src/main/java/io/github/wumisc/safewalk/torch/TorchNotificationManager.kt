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
    /** 注册回调会立刻回传一次当前状态，首帧不弹通知，否则 SystemUI 一启动就弹「夜间手电」 */
    private var stateInitialized = false
    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "SafeWalk-Torch"
        /** 本模块包名：跳转主页时用 */
        private const val APP_PKG = "io.github.wumisc.safewalk"
        /** Hook 宿主包名：手电按钮的广播要投给它 */
        private const val SYSTEMUI_PKG = "com.android.systemui"
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
                    if (!stateInitialized) {
                        stateInitialized = true
                        return
                    }
                    // 关闭时顺手撤掉提醒；开启时交给静置计时器决定是否提醒
                    if (!enabled) dismissNotification()
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
        // context 是 SystemUI 的，Intent(context, MainActivity) 会拼成
        // ComponentName("com.android.systemui", "...MainActivity")，点击必然打不开
        val openAppIntent = Intent().apply {
            setClassName("io.github.wumisc.safewalk", "io.github.wumisc.safewalk.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 按钮：落点是**应用进程里的透明 Activity**。
        //
        // 这条通知来自静置提醒，夜间应用进程很可能已经被回收。
        // 广播能不能把进程拉起来取决于厂商后台策略，而点击通知是用户操作，
        // getActivity 一定会拉起进程——这是唯一有保证的方式。
        // 同时也解决了「预览占着摄像头」：Activity 在前台时收预览、点灯都合法。
        val turnOnIntent = Intent(io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_ON).apply {
            setClassName(APP_PKG, "$APP_PKG.TorchActionActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                io.github.wumisc.safewalk.SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN,
                io.github.wumisc.safewalk.SafeWalkPrefKey.BROADCAST_TOKEN
            )
        }
        val turnOnPi = PendingIntent.getActivity(
            context, 100, turnOnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val turnOffIntent = Intent(io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_OFF).apply {
            setClassName(APP_PKG, "$APP_PKG.TorchActionActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                io.github.wumisc.safewalk.SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN,
                io.github.wumisc.safewalk.SafeWalkPrefKey.BROADCAST_TOKEN
            )
        }
        val turnOffPi = PendingIntent.getActivity(
            context, 200, turnOffIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, TORCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
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
                        android.R.drawable.ic_menu_close_clear_cancel,
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
                        android.R.drawable.ic_menu_view,
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
