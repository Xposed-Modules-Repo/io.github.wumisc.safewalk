package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

public class EmergencyCardActivity extends Activity {

    private EditText etName, etBlood, etPhone, etAddress, etContact;
    private SharedPreferences sp;

    // 车祸预警
    private SwitchCompat swCrashDetection;
    private TextView chipCrashLow, chipCrashMid, chipCrashHigh;
    private TextView tvCrashHint;
    private int crashSensitivity = 1;
    private boolean binding = true;

    private static final int REQ_CRASH_PERMS = 3001;
    private static final String[] CRASH_PERMS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_emergency);
        // MODE_WORLD_READABLE 在 Android 7+ 会直接抛 SecurityException，改用私有模式
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);

        etName = findViewById(R.id.et_name);
        etBlood = findViewById(R.id.et_blood);
        etPhone = findViewById(R.id.et_phone);
        etAddress = findViewById(R.id.et_address);
        etContact = findViewById(R.id.et_contact);

        etName.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerName, ""));
        etBlood.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerBlood, ""));
        etPhone.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerContactPhone, ""));
        etAddress.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerAddress, ""));
        etContact.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerContactName, ""));

        Button btnSave = findViewById(R.id.btn_save_emergency);
        btnSave.setOnClickListener(v -> {
            sp.edit()
                    .putString(SafeWalkPrefKey.emerName, etName.getText().toString())
                    .putString(SafeWalkPrefKey.emerBlood, etBlood.getText().toString())
                    .putString(SafeWalkPrefKey.emerContactPhone, etPhone.getText().toString())
                    .putString(SafeWalkPrefKey.emerAddress, etAddress.getText().toString())
                    .putString(SafeWalkPrefKey.emerContactName, etContact.getText().toString())
                    .apply();
            // 同步导出 JSON，保证 Hook 端能读到最新配置
            SafeWalkConfig.exportConfig(this);
            Toast.makeText(this, "急救卡已保存", Toast.LENGTH_SHORT).show();
        });

        Button btnSos = findViewById(R.id.btn_goto_sos);
        btnSos.setOnClickListener(v -> openSosSettings());

        // ===== 车祸预警 =====
        swCrashDetection = findViewById(R.id.sw_crash_detection);
        chipCrashLow = findViewById(R.id.chip_crash_low);
        chipCrashMid = findViewById(R.id.chip_crash_mid);
        chipCrashHigh = findViewById(R.id.chip_crash_high);
        tvCrashHint = findViewById(R.id.tv_crash_hint);

        swCrashDetection.setChecked(
                SafePrefs.getBool(sp, SafeWalkPrefKey.enableCrashDetection, false));
        crashSensitivity = Math.max(0, Math.min(2,
                SafePrefs.getInt(sp, SafeWalkPrefKey.crashSensitivity, 1)));

        swCrashDetection.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enableCrashDetection, c).commit();
            SafeWalkConfig.exportConfig(this);
            if (c) {
                requestCrashPermsIfNeeded();
            }
            updateCrashHint();
        });

        chipCrashLow.setOnClickListener(v -> selectSensitivity(0));
        chipCrashMid.setOnClickListener(v -> selectSensitivity(1));
        chipCrashHigh.setOnClickListener(v -> selectSensitivity(2));

        binding = false;
        refreshCrashChips();
        updateCrashHint();
    }

    // ========== 车祸预警：灵敏度 ==========

    private void selectSensitivity(int level) {
        crashSensitivity = level;
        sp.edit().putInt(SafeWalkPrefKey.crashSensitivity, level).commit();
        SafeWalkConfig.exportConfig(this);
        refreshCrashChips();
    }

    private void refreshCrashChips() {
        TextView[] chips = {chipCrashLow, chipCrashMid, chipCrashHigh};
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == crashSensitivity;
            chips[i].setBackgroundResource(
                    on ? R.drawable.bg_choice_chip_on : R.drawable.bg_choice_chip);
            chips[i].setTextColor(getColor(on ? R.color.card_bg : R.color.text_body));
        }
    }

    // ========== 车祸预警：权限 ==========

    private void requestCrashPermsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : CRASH_PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_CRASH_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CRASH_PERMS) return;
        updateCrashHint();
    }

    private void updateCrashHint() {
        boolean on = swCrashDetection.isChecked();
        if (!on) {
            tvCrashHint.setText("关闭时不监测撞击。开启后请保持应用在系统里不被清理。");
            return;
        }
        // 总开关关着时，Hook 里 updateCrashDetection() 直接不启动监听，
        // 界面还说「已就绪」就是在骗人
        if (!SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true)) {
            tvCrashHint.setText("总开关已关闭，车祸预警不会触发。请先在首页打开总开关。");
            tvCrashHint.setOnClickListener(null);
            return;
        }
        boolean allGranted = Build.VERSION.SDK_INT < 23;
        if (Build.VERSION.SDK_INT >= 23) {
            allGranted = true;
            for (String p : CRASH_PERMS) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
        }
        final boolean granted = allGranted;
        tvCrashHint.setText(granted
                ? "已就绪：检测到撞击会自动拨打电话、发含位置的求助短信。"
                : "已开启，但部分权限未授权：求助时将跳转系统拨号/短信，需你手动点一下发送。点此补授权。");
        tvCrashHint.setOnClickListener(v -> {
            if (!granted) requestCrashPermsIfNeeded();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 总开关可能在首页被改过，回来看一眼结论还成不成立
        if (!binding) updateCrashHint();
    }

    private void openSosSettings() {
        // 多厂商路径兜底
        String[] paths = {
            "com.android.settings/.Settings$EmergencySettingsActivity",
            "com.miui.securitycenter/.emergency.EmergencyActivity",
            "com.huawei.emergency/.MainActivity",
            "com.coloros.safecenter/.permission.startup.EmergencyActivity",
            "com.iqoo.call/.emergency.EmergencyActivity"
        };
        for (String path : paths) {
            try {
                Intent i = new Intent();
                i.setComponent(android.content.ComponentName.unflattenFromString(path));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {}
        }
        // 兜底：跳系统设置主页
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            Toast.makeText(this, "请在设置中搜索「紧急呼救」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }
}
