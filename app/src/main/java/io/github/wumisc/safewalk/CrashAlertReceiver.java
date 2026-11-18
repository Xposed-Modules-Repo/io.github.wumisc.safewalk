package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.UUID;

/**
 * 撞击广播接收器（App 进程，v1.1 改造）。
 *
 * 严格鉴权 → buildDisplayFields 最小载荷 → publish 生成一次性 token →
 * 发 ACTION_EMERGENCY_SHOW（**只带 token，不带字段**）→ FSI 可用性检测 → 拉起 Activity。
 *
 * 医疗字段永不进 Intent extra / 日志 / config；只通过 DisplayProvider 的 Binder take 返回。
 */
public class CrashAlertReceiver extends BroadcastReceiver {

    private static final String CHANNEL_CRASH = "safewalk_crash";
    private static final String CHANNEL_CRASH_FALLBACK = "safewalk_crash_fallback";
    private static final int NOTIF_CRASH = 9001;
    private static final int NOTIF_CRASH_FALLBACK = 9002;

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !CrashDetector.ACTION_CRASH_DETECTED.equals(i.getAction())) return;

        // 严格鉴权：令牌 + API34+ uid/包名；senderIsTrusted=false 时直接拒绝
        if (!ReceiverCompat.isStrictCommand(this,
                i.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN),
                SafeWalkPrefKey.BROADCAST_TOKEN)) {
            EmergencyDiag.event(EmergencyDiag.P_SEC_REJECT, "crash_receiver_rejected");
            Log.w("SafeWalk", "[crash] 撞击广播鉴权失败（发送方不可信或令牌不匹配），忽略");
            return;
        }
        EmergencyDiag.event(EmergencyDiag.P_CRASH, "receiver_accepted");

        // v1.0 §1.2 不可达边界降级：撞击瞬间预发一条 SMS 回执（若已授权 SEND_SMS），
        // 保证关机/没电/SystemUI 崩溃时至少有一条"出设备"的证据链。
        // 不写 emerLastSosTs，不影响 Activity 倒计时后的正式求助去重。
        sendCrashReceiptSms(c);

        String eventId = i.getStringExtra(CrashDetector.EXTRA_CRASH_EVENT_ID);
        if (eventId == null || eventId.isEmpty()) eventId = UUID.randomUUID().toString();

        // 从 Vault 取最小显示载荷（隐私开关在撞击场景一律忽略：U2 裁决）
        EmergencyVault.init(c);
        EmergencyVault.migrateIfNeeded(c);
        DisplayFields fields = EmergencyVault.buildDisplayFields();
        String token = DisplayPayloadStore.publish(fields, eventId);
        EmergencyDiag.event(EmergencyDiag.P_DISPLAY,
                "published event=" + eventId + " token=" + EmergencyDiag.tokenHash4(token));

        // SHOW 广播只带一次性 token：Hook 拿 token 自己去 DisplayProvider.take
        try {
            Intent show = new Intent(SafeWalkPrefKey.ACTION_EMERGENCY_SHOW);
            show.setPackage("com.android.systemui");
            show.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            show.putExtra(SafeWalkPrefKey.EXTRA_DISPLAY_TOKEN, token);
            show.putExtra("event_id", eventId);
            c.sendBroadcast(show);
        } catch (Throwable t) {
            EmergencyDiag.event(EmergencyDiag.P_DISPLAY,
                    "show_broadcast_failed: " + t.getClass().getSimpleName());
        }

        // FSI 可用性检测：Android 14+ 用户可撤销；撤销时降级普通高优先级通知并留痕
        boolean canFsi = canUseFullScreenIntent(c);
        if (canFsi) {
            showFullScreenNotification(c);
        } else {
            EmergencyDiag.event(EmergencyDiag.P_SOS, "[fallback] fsi_revoked → heads_up");
            showHeadsUpFallback(c);
        }

        // 拉起完整急救卡（singleTask 保证两条通道只有一个实例、一轮倒计时）
        try {
            Intent alert = new Intent(c, EmergencyAlertActivity.class);
            alert.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            alert.putExtra("event_id", eventId);
            alert.putExtra("fsi_revoked", !canFsi);
            c.startActivity(alert);
        } catch (Throwable t) {
            Log.e("SafeWalk", "[crash] 直接拉起急救卡失败，交由通知兜底", t);
        }
    }

    /** 撞击瞬间预发的 SMS 回执：只发一句确认安全请求，不含医疗字段值 */
    private void sendCrashReceiptSms(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 23 &&
                    c.checkSelfPermission(Manifest.permission.SEND_SMS)
                            != PackageManager.PERMISSION_GRANTED) return;
            EmergencyContact p = EmergencyVault.readCard().primaryContact();
            String phone = p.getPhone();
            if (phone == null || phone.trim().isEmpty()) return;
            SmsManager sm = SmsManager.getDefault();
            sm.sendTextMessage(phone, null,
                    "【SafeWalk】检测到疑似撞击，请尽快联系我确认安全。（若误报请忽略）",
                    null, null);
            EmergencyDiag.event(EmergencyDiag.P_SOS, "[fallback] crash_receipt_sms sent=true");
        } catch (Throwable t) {
            EmergencyDiag.event(EmergencyDiag.P_SOS,
                    "[fallback] crash_receipt_sms failed=" + t.getClass().getSimpleName());
        }
    }

    private boolean canUseFullScreenIntent(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** full-screen intent 通知：App 在后台/锁屏时由系统弹出急救卡 */
    private void showFullScreenNotification(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            createChannel(nm, CHANNEL_CRASH, "车祸预警", "检测到疑似撞击时的全屏急救卡");
            PendingIntent pi = PendingIntent.getActivity(
                    c, 0, new Intent(c, EmergencyAlertActivity.class),
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

    /** FSI 被撤销时的降级：普通高优先级通知（不静默失败） */
    private void showHeadsUpFallback(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            createChannel(nm, CHANNEL_CRASH_FALLBACK, "车祸预警（降级）", "全屏权限被撤销时的高优先级提醒");
            PendingIntent pi = PendingIntent.getActivity(
                    c, 1, new Intent(c, EmergencyAlertActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CHANNEL_CRASH_FALLBACK)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("疑似检测到撞击")
                    .setContentText("点按查看急救卡（全屏权限已关闭）")
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_CRASH_FALLBACK, n);
        } catch (Throwable t) {
            Log.e("SafeWalk", "[crash] 降级通知失败", t);
        }
    }

    private void createChannel(NotificationManager nm, String id, String name, String desc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(desc);
            nm.createNotificationChannel(ch);
        }
    }
}
