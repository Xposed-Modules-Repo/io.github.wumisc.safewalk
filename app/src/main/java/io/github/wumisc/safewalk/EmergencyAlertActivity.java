package io.github.wumisc.safewalk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全屏急救卡（v1.1 求助闭环）。
 *
 * 从 EmergencyVault 读完整卡（Activity 面保留住址，U1），红底 ≥6 字段 ≥18sp。
 * 可见即发 ACTION_EMERGENCY_VISIBLE → 覆盖层 removeView + 清字段副本（单一暴露面）。
 * 求助后必给可见回执（showReceipt），不静默失败；FSI 被撤销时 Receiver 已降级通知。
 */
public class EmergencyAlertActivity extends Activity {

    private static final int COUNTDOWN_SECONDS = 60;
    private static final long SOS_DEDUP_MS = 10 * 60 * 1000L;
    private static final String EXTRA_EVENT_ID = "event_id";

    private TextView tvCountdown, tvName, tvBlood, tvAllergy, tvConditions;
    private TextView tvContact, tvAge, tvAddress, tvReceipt;
    private Button btnOk, btnSos, btnSosSystem, btnAed, btnQr, btnDial120;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int remain = COUNTDOWN_SECONDS;
    private Ringtone ringtone;
    private boolean resolved = false;
    private boolean visibleNotified = false;
    private EmergencyCard card = new EmergencyCard();
    private String eventId = "";
    private Bitmap qrBitmap;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_emergency_alert);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        EmergencyDiag.install();
        EmergencyVault.init(this);
        EmergencyVault.migrateIfNeeded(this);
        card = EmergencyVault.readCard();
        eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);
        if (eventId == null || eventId.isEmpty()) eventId = "local";

        bindViews();
        loadCard();
        bindButtons();
        startCountdown();
        startAlarm();
        notifyVisible();
    }

    private void bindViews() {
        tvCountdown = findViewById(R.id.tv_alert_countdown);
        tvName = findViewById(R.id.tv_alert_name);
        tvBlood = findViewById(R.id.tv_alert_blood);
        tvAllergy = findViewById(R.id.tv_alert_allergy);
        tvConditions = findViewById(R.id.tv_alert_conditions);
        tvContact = findViewById(R.id.tv_alert_contact);
        tvAge = findViewById(R.id.tv_alert_age);
        tvAddress = findViewById(R.id.tv_alert_address);
        tvReceipt = findViewById(R.id.tv_alert_receipt);
        btnOk = findViewById(R.id.btn_alert_ok);
        btnSos = findViewById(R.id.btn_alert_sos);
        btnSosSystem = findViewById(R.id.btn_alert_sos_system);
        btnAed = findViewById(R.id.btn_alert_aed);
        btnQr = findViewById(R.id.btn_alert_qr);
        btnDial120 = findViewById(R.id.btn_alert_dial_120);
    }

    /** 从 Vault 读完整卡：Activity 面显示住址（U1），关键字段 ≥18sp */
    private void loadCard() {
        tvName.setText(value(card.getName(), "未填写"));
        String blood = (card.getBloodType().getLabel() + " " + card.getRh().getLabel()).trim();
        tvBlood.setText("血型：" + value(blood, "未填写"));
        tvAllergy.setText("过敏：" + value(card.getAllergy(), "无记录")
                + (card.getAllergySevere() ? "（★严重）" : ""));
        tvConditions.setText("基础疾病：" + value(card.getConditions(), "无记录"));
        EmergencyContact p = card.primaryContact();
        String contact = (p.getName() + " " + p.getRelation() + " " + p.getPhone()).trim();
        tvContact.setText("紧急联系人：" + value(contact, "未设置"));
        tvAge.setText("年龄：" + (card.getAge() > 0 ? card.getAge() + " 岁" : "未填写"));
        tvAddress.setText("住址：" + value(card.getAddress(), "未填写"));
        tvReceipt.setText("求助将在倒计时结束后自动进行，点「我没事」可取消。");
        EmergencyDiag.logCard("alert_read", card);
    }

    private static String value(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }

    private void bindButtons() {
        btnOk.setOnClickListener(v -> resolve(false));
        btnSos.setOnClickListener(v -> resolve(true));
        btnSosSystem.setOnClickListener(v -> {
            boolean ok = SystemSosLauncher.openSystemSos(this);
            showReceipt(new SosAction(eventId, SosType.SYSTEM_SOS, "", false, false, ok,
                    ok ? "已打开系统紧急呼救设置" : "未找到系统紧急呼救入口，请使用下方 120 按钮",
                    System.currentTimeMillis()));
        });
        btnAed.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, AedGuideActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开 AED 引导，请直接拨打 120", Toast.LENGTH_LONG).show();
            }
        });
        btnQr.setOnClickListener(v -> showQr());
        btnDial120.setOnClickListener(v -> {
            boolean ok = SystemSosLauncher.dialEmergency(this, "120");
            showReceipt(new SosAction(eventId, SosType.FALLBACK_120, "120", false, ok, false,
                    ok ? "已打开 120 拨号盘，请按拨号键" : "未找到拨号应用，请手动拨打 120",
                    System.currentTimeMillis()));
        });
    }

    private void showQr() {
        if (!card.getPrivacy().getAllowQr()) {
            Toast.makeText(this, "医疗 QR 为明文，请先在急救卡编辑页开启「允许生成医疗 QR」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap bmp = MedicalQr.encode(card);
        if (bmp == null) {
            Toast.makeText(this, "QR 生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (qrBitmap != null && !qrBitmap.isRecycled()) qrBitmap.recycle();
        qrBitmap = bmp;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this)
                .setTitle("医疗急救卡 QR（明文）")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 可见后一次性发 ACK：覆盖层 removeView + 清字段副本，保证单一暴露面 */
    private void notifyVisible() {
        if (visibleNotified) return;
        visibleNotified = true;
        try {
            Intent i = new Intent(SafeWalkPrefKey.ACTION_EMERGENCY_VISIBLE);
            i.setPackage("com.android.systemui");
            i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            i.putExtra(SafeWalkPrefKey.EXTRA_DISPLAY_TOKEN, "");
            i.putExtra(EXTRA_EVENT_ID, eventId);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
        EmergencyDiag.event(EmergencyDiag.P_OVERLAY, "activity_visible event=" + eventId);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) notifyVisible();
    }

    private void startCountdown() {
        tvCountdown.setText(String.valueOf(remain));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (resolved) return;
                remain--;
                if (remain <= 0) {
                    resolve(true);
                    return;
                }
                tvCountdown.setText(String.valueOf(remain));
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void startAlarm() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            ringtone = RingtoneManager.getRingtone(this, uri);
            if (ringtone != null) ringtone.play();
        } catch (Throwable ignored) {
        }
    }

    private void stopAlarm() {
        if (ringtone != null) {
            try { ringtone.stop(); } catch (Throwable ignored) {}
            ringtone = null;
        }
    }

    /** 结束倒计时。sos=true 表示要执行求助 */
    private void resolve(boolean sos) {
        if (resolved) return;
        resolved = true;
        stopAlarm();
        handler.removeCallbacksAndMessages(null);
        if (sos) {
            doSos();
        } else {
            sendHide();
            Toast.makeText(this, "已取消，注意安全", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void sendHide() {
        try {
            Intent i = new Intent(SafeWalkPrefKey.ACTION_EMERGENCY_HIDE);
            i.setPackage("com.android.systemui");
            i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN);
            i.putExtra(EXTRA_EVENT_ID, eventId);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private void doSos() {
        // 10 分钟去重：先落盘标记再执行，进程被杀也不重发
        long last = EmergencyVault.lastSosTs();
        long now = System.currentTimeMillis();
        if (last > 0L && now - last < SOS_DEDUP_MS) {
            EmergencyDiag.event(EmergencyDiag.P_SOS, "dedup_skip gap=" + (now - last) + "ms");
            showReceipt(new SosAction(eventId, SosType.SMS, "", false, false, false,
                    "刚刚已发送过求助，已跳过重复发送", now));
            sendHide();
            finish();
            return;
        }
        EmergencyVault.markSos(new SosAction(eventId, SosType.SMS, "", false, false, false, "", now));

        EmergencyContact p = card.primaryContact();
        String phone = p.getPhone();
        if (phone == null || phone.trim().isEmpty()) {
            // 没设联系人：不关页面，给出可见原因（修正"点了没反应"）
            EmergencyDiag.event(EmergencyDiag.P_SOS, "no_contact");
            tvCountdown.setText("未设置紧急联系人");
            showReceipt(new SosAction(eventId, SosType.SMS, "", false, false, false,
                    "未设置紧急联系人电话，请到急救卡补齐；也可直接点「拨打 120」", now));
            return;
        }

        boolean sms = sendSosSms(phone, p.getName());
        boolean dial = SystemSosLauncher.dialEmergency(this, phone);
        showReceipt(new SosAction(eventId, SosType.SMS, phone, sms, dial, false,
                (sms ? "求助短信已发送" : "短信未发出，已尝试跳转短信应用") + "；" +
                    (dial ? "拨号盘已打开（请按拨号键）" : "未能打开拨号盘，请手动拨打"),
                now));
        sendHide();
        finish();
    }

    /** 可见回执：任何结果都写在屏幕上，绝不静默失败 */
    private void showReceipt(SosAction a) {
        String text = a.getReceipt();
        tvReceipt.setText(text == null || text.isEmpty() ? "求助已触发，请保持电话畅通" : text);
        EmergencyDiag.event(EmergencyDiag.P_SOS,
                "receipt type=" + a.getType() + " sms=" + a.getSmsSent() +
                    " dial=" + a.getDialPrefilled() + " len=" + (text == null ? 0 : text.length()));
    }

    @SuppressLint("MissingPermission")
    private boolean sendSosSms(String phone, String contactName) {
        if (phone == null || phone.isEmpty()) return false;
        String loc = card.getPrivacy().getAllowSmsLocation() ? lastKnownLocationText() : "";
        String msg = "【SafeWalk 紧急求助】我可能遭遇了事故，请尽快联系我。"
                + (card.getName().isEmpty() ? "" : "\n姓名：" + card.getName())
                + (contactName.isEmpty() ? "" : "\n紧急联系人：" + contactName)
                + (loc.isEmpty() ? "" : "\n位置：" + loc);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                SmsManager sm = SmsManager.getDefault();
                java.util.ArrayList<String> parts = sm.divideMessage(msg);
                if (parts != null && parts.size() > 1) {
                    sm.sendMultipartTextMessage(phone, null, parts, null, null);
                } else {
                    sm.sendTextMessage(phone, null, msg, null, null);
                }
                return true;
            } catch (Throwable ignored) {
            }
        }
        // 降级：跳短信应用预填，用户点发送
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
            i.putExtra("sms_body", msg);
            startActivity(i);
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private String lastKnownLocationText() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return "";
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc == null) return "";
            return String.format(java.util.Locale.US, "%.5f, %.5f",
                    loc.getLatitude(), loc.getLongitude());
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!resolved) {
            EmergencyDiag.event(EmergencyDiag.P_SOS, "new_intent_during_countdown_ignored");
        }
    }

    @Override
    protected void onDestroy() {
        stopAlarm();
        handler.removeCallbacksAndMessages(null);
        if (qrBitmap != null && !qrBitmap.isRecycled()) {
            qrBitmap.recycle();
            qrBitmap = null;
        }
        super.onDestroy();
    }
}
