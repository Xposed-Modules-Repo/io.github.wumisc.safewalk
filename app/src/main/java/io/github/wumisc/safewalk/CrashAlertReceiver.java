package io.github.wumisc.safewalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 撞击广播接收器（App 进程）。
 *
 * 车祸检测在 SystemUI 进程，检测到撞击后发广播过来；这里收到广播后
 * 拉起全屏急救卡。双保险：
 * ① 直接 startActivity（App 在前台/最近任务时立即弹出）；
 * ② full-screen intent 通知（App 在后台/锁屏时由系统弹出，最可靠）。
 *
 * 令牌校验挡掉伪造广播——否则任意应用发一条广播就能在你手机上
 * 弹全屏急救卡并开始倒计时。
 */
public class CrashAlertReceiver extends BroadcastReceiver {

    private static final String CHANNEL_CRASH = "safewalk_crash";
    private static final int NOTIF_CRASH = 9001;

    @Override
    public void onReceive(Context c, Intent i) {
        // 双重校验：API 34+ 先看发送方 uid（必须是系统进程或本应用），再验令牌。
        // 这里必须拦住伪造广播——一条假的 CRASH_DETECTED 会让急救卡走完倒计时，
        // 真的给紧急联系人发短信、真的拨号，代价远不止「弹个卡片」。
        //
        // 【本次修复】改用 isStrictCommand：宽松版在「API34+ 且发送方身份确定不可信」
        // 时只要令牌匹配仍会放行，而令牌是硬编码字符串、反编译即可获得——
        // 等于任何第三方 App 都能伪造撞击、触发真实短信与拨号。
        if (!ReceiverCompat.isStrictCommand(this,
                i == null ? null : i.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN),
                SafeWalkPrefKey.BROADCAST_TOKEN)) {
            Log.w("SafeWalk", "[crash] 撞击广播鉴权失败（发送方不可信或令牌不匹配），忽略");
            return;
        }
        Log.w("SafeWalk", "[crash] 收到撞击广播，拉起急救卡");

        showFullScreenNotification(c);

        try {
            Intent alert = new Intent(c, EmergencyAlertActivity.class);
            alert.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(alert);
        } catch (Throwable t) {
            Log.e("SafeWalk", "[crash] 直接拉起急救卡失败，交由通知兜底", t);
        }
    }

    /** full-screen intent 通知：App 在后台/锁屏时也能由系统弹出急救卡 */
    private void showFullScreenNotification(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_CRASH, "车祸预警", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("检测到疑似撞击时的全屏急救卡");
                nm.createNotificationChannel(ch);
            }

            Intent fullScreen = new Intent(c, EmergencyAlertActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    c, 0, fullScreen,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(c, CHANNEL_CRASH)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("疑似检测到撞击")
                    .setContentText("点按查看急救卡")
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_CRASH, n);
        } catch (Throwable t) {
            Log.e("SafeWalk", "[crash] 全屏通知失败", t);
        }
    }
}
