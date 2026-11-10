package io.github.wumisc.safewalk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全屏急救卡（Pixel 车祸检测风格）。
 *
 * 车祸检测在 SystemUI 进程，检测到撞击后广播拉起本页；
 * 这里显示用户预填的急救卡信息 + 60 秒倒计时 + 警报声，
 * 倒计时结束仍未点「我没事」就自动求助：发含位置的短信 + 拨紧急联系人。
 */
public class EmergencyAlertActivity extends Activity {

    private static final int COUNTDOWN_SECONDS = 60;

    private TextView tvCountdown, tvName, tvBlood, tvContact, tvAddress;
    private Button btnOk, btnSosNow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int remain = COUNTDOWN_SECONDS;
    private Ringtone ringtone;
    private boolean resolved = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_emergency_alert);

        // 锁屏 + 熄屏也要能弹出来，这是急救卡的核心诉求
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        bindViews();
        loadCard();
        bindButtons();
        startCountdown();
        startAlarm();
    }

    private void bindViews() {
        tvCountdown = findViewById(R.id.tv_alert_countdown);
        tvName = findViewById(R.id.tv_alert_name);
        tvBlood = findViewById(R.id.tv_alert_blood);
        tvContact = findViewById(R.id.tv_alert_contact);
        tvAddress = findViewById(R.id.tv_alert_address);
        btnOk = findViewById(R.id.btn_alert_ok);
        btnSosNow = findViewById(R.id.btn_alert_sos);
    }

    private void loadCard() {
        SharedPreferences sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        String name = SafePrefs.getString(sp, SafeWalkPrefKey.emerName, "");
        String blood = SafePrefs.getString(sp, SafeWalkPrefKey.emerBlood, "");
        String contactName = SafePrefs.getString(sp, SafeWalkPrefKey.emerContactName, "");
        String address = SafePrefs.getString(sp, SafeWalkPrefKey.emerAddress, "");

        tvName.setText(name.isEmpty() ? "未填写" : name);
        tvBlood.setText(blood.isEmpty() ? "未填写" : blood);
        tvAddress.setText(address.isEmpty() ? "未填写" : address);
        tvContact.setText(contactName.isEmpty() ? "未设置紧急联系人" : contactName);
    }

    private void bindButtons() {
        btnOk.setOnClickListener(v -> resolve(false));
        btnSosNow.setOnClickListener(v -> resolve(true));
    }

    private void startCountdown() {
        tvCountdown.setText(String.valueOf(remain));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (resolved) return;
                remain--;
                if (remain <= 0) {
                    resolve(true); // 超时自动求助
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
            Toast.makeText(this, "已取消，注意安全", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void doSos() {
        SharedPreferences sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);

        // 【本次修复】重复求助去重：撞击检测可能连续触发、full-screen 通知被点开也可能
        // 重建本页（进程被杀后）。没有去重时，一次事故会给联系人发好几条短信、拨好几通
        // 电话——既骚扰对方，也可能产生通信费用。
        long last = SafePrefs.getLong(sp, SafeWalkPrefKey.emerLastSosTs, 0L);
        long now = System.currentTimeMillis();
        if (last > 0L && now - last < SOS_DEDUP_MS) {
            DiagLog.log("[sos] " + (now - last) / 1000 + "s 前已求助过，本次跳过（防重复短信/拨号）");
            Toast.makeText(this, "刚刚已发送过求助，已跳过重复发送", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // 先落盘再执行：即使随后进程被杀，也不会重复求助
        sp.edit().putLong(SafeWalkPrefKey.emerLastSosTs, now).commit();

        String phone = SafePrefs.getString(sp, SafeWalkPrefKey.emerContactPhone, "");
        String name = SafePrefs.getString(sp, SafeWalkPrefKey.emerName, "");
        String contactName = SafePrefs.getString(sp, SafeWalkPrefKey.emerContactName, "");

        // 没设联系人时不要把页面关掉：用户需要看见「为什么没求助」并当场去填
        if (phone == null || phone.trim().isEmpty()) {
            DiagLog.log("[sos] 未设置紧急联系人电话，无法自动求助");
            Toast.makeText(this, "未设置紧急联系人电话，请先到急救卡里填写", Toast.LENGTH_LONG).show();
            tvCountdown.setText("未设置紧急联系人");
            return;
        }

        // 先发短信（含位置），再拨电话，避免拨号界面遮挡短信发送
        sendSosSms(phone, name, contactName);
        callContact(phone);
        finish();
    }

    /** 同一次事故只在首轮求助；窗口内重复触发一律跳过 */
    private static final long SOS_DEDUP_MS = 10 * 60 * 1000L;

    @SuppressLint("MissingPermission")
    private void sendSosSms(String phone, String name, String contactName) {
        if (phone == null || phone.isEmpty()) {
            Toast.makeText(this, "未设置紧急联系人电话，无法自动求助", Toast.LENGTH_LONG).show();
            return;
        }
        String loc = lastKnownLocationText();
        String msg = "【SafeWalk 紧急求助】我可能遭遇了事故，请尽快联系我。"
                + (name.isEmpty() ? "" : "\n姓名：" + name)
                + (contactName.isEmpty() ? "" : "\n紧急联系人：" + contactName)
                + (loc.isEmpty() ? "" : "\n位置：" + loc);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                // 【本次修复】长短信必须分片。求助短信含姓名/联系人/坐标，
                // 中英文混排很容易超过单条 70 字的限制，用 sendTextMessage 发
                // 会被运营商截断——最常见的结果就是「位置」那一段没了，
                // 而位置恰恰是求助短信里最要紧的信息。
                SmsManager sm = SmsManager.getDefault();
                java.util.ArrayList<String> parts = sm.divideMessage(msg);
                if (parts != null && parts.size() > 1) {
                    sm.sendMultipartTextMessage(phone, null, parts, null, null);
                } else {
                    sm.sendTextMessage(phone, null, msg, null, null);
                }
                Toast.makeText(this, "已发送求助短信", Toast.LENGTH_LONG).show();
                return;
            } catch (Throwable t) {
                // 发送失败降级跳短信应用
            }
        }
        // 降级：跳转短信应用，预填内容，用户点一下发送
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
            i.putExtra("sms_body", msg);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法发送短信，请手动联系", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void callContact(String phone) {
        if (phone == null || phone.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + phone)));
                return;
            } catch (Throwable ignored) {
            }
        }
        // 降级：跳转拨号盘（不直接呼出，需要用户点一下拨号键）
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)));
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    private String lastKnownLocationText() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
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
        // singleTask 复用实例时，如果上一轮撞击的倒计时还在跑，说明是同一次事故
        // （或同一次事故的两条拉起通道先后到达），不能再叠一轮倒计时。
        if (!resolved) {
            DiagLog.log("[sos] 倒计时进行中再次收到撞击，忽略重复拉起");
        }
    }

    @Override
    protected void onDestroy() {
        stopAlarm();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
