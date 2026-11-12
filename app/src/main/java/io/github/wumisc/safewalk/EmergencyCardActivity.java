package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

    private EditText etName, etBlood, etPhone, etAddress, etContact, etAllergy, etCondition;
    private TextView tvPermStatus;
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
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // MODE_WORLD_READABLE 在 Android 7+ 会直接抛 SecurityException，改用私有模式
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);

        etName = findViewById(R.id.et_name);
        etBlood = findViewById(R.id.et_blood);
        etPhone = findViewById(R.id.et_phone);
        etAddress = findViewById(R.id.et_address);
        etContact = findViewById(R.id.et_contact);
        etAllergy = findViewById(R.id.et_allergy);
        etCondition = findViewById(R.id.et_condition);
        tvPermStatus = findViewById(R.id.tv_perm_status);

        etName.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerName, ""));
        etBlood.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerBlood, ""));
        etPhone.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerContactPhone, ""));
        etAddress.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerAddress, ""));
        etContact.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerContactName, ""));
        etAllergy.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerAllergy, ""));
        etCondition.setText(SafePrefs.getString(sp, SafeWalkPrefKey.emerCondition, ""));

        Button btnSave = findViewById(R.id.btn_save_emergency);
        btnSave.setOnClickListener(v -> saveCard());

        Button btnCopy = findViewById(R.id.btn_copy_emergency);
        btnCopy.setOnClickListener(v -> copyCard());

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

    // ========== 保存 / 复制 ==========

    /**
     * 保存急救卡。
     *
     * 两处关键改动：
     * ① {@code apply()} → {@code commit()}：急救信息不能等异步落盘，点完立刻退出
     *    也必须写进去；且后面的 exportConfig 要读的是已落盘的值。
     * ② 加最小校验：姓名和联系人电话都空时拒绝保存（空卡对救援毫无意义）；
     *    电话填了但有效数字太少时提示检查。
     */
    private void saveCard() {
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (name.isEmpty() && phone.isEmpty()) {
            Toast.makeText(this, "姓名和紧急联系人电话至少填一项，否则急救卡没有意义",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!phone.isEmpty() && phone.replaceAll("[^0-9]", "").length() < 5) {
            Toast.makeText(this, "紧急联系人电话看起来不完整，请检查后再保存",
                    Toast.LENGTH_LONG).show();
            return;
        }

        boolean ok = sp.edit()
                .putString(SafeWalkPrefKey.emerName, name)
                .putString(SafeWalkPrefKey.emerBlood, etBlood.getText().toString().trim())
                .putString(SafeWalkPrefKey.emerContactPhone, phone)
                .putString(SafeWalkPrefKey.emerAddress, etAddress.getText().toString().trim())
                .putString(SafeWalkPrefKey.emerContactName, etContact.getText().toString().trim())
                .putString(SafeWalkPrefKey.emerAllergy, etAllergy.getText().toString().trim())
                .putString(SafeWalkPrefKey.emerCondition, etCondition.getText().toString().trim())
                .commit();
        SafeWalkConfig.exportConfig(this);

        if (ok) {
            Toast.makeText(this, "急救卡已保存", Toast.LENGTH_SHORT).show();
            DiagLog.log("[emergency] 急救卡已保存（commit）");
        } else {
            Toast.makeText(this, "保存失败：磁盘写入被拒绝", Toast.LENGTH_LONG).show();
        }
    }

    /** 复制成一段可直接粘贴到系统「紧急信息」的文本（锁屏可见的那一份） */
    private void copyCard() {
        StringBuilder sb = new StringBuilder("【医疗急救信息】\n");
        appendField(sb, "姓名", etName.getText().toString());
        appendField(sb, "血型", etBlood.getText().toString());
        appendField(sb, "过敏史", etAllergy.getText().toString());
        appendField(sb, "慢性病/用药", etCondition.getText().toString());
        appendField(sb, "住址", etAddress.getText().toString());
        String contact = (etContact.getText().toString().trim() + " "
                + etPhone.getText().toString().trim()).trim();
        appendField(sb, "紧急联系人", contact);

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException("剪贴板不可用");
            cm.setPrimaryClip(ClipData.newPlainText("SafeWalk 急救信息", sb.toString()));
            Toast.makeText(this, "已复制，去系统「紧急信息」里粘贴", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append(label).append('：').append(value.trim()).append('\n');
    }

    /** 车祸预警所需权限的逐项状态：避免「以为在监测，其实没授权」 */
    private void updatePermStatus() {
        if (tvPermStatus == null) return;
        int total = CRASH_PERMS.length;
        int granted = 0;
        StringBuilder missing = new StringBuilder();
        for (String p : CRASH_PERMS) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
                granted++;
            } else {
                if (missing.length() > 0) missing.append('、');
                missing.append(permLabel(p));
            }
        }
        if (granted == total) {
            tvPermStatus.setText("权限 " + granted + "/" + total + " · 全部就绪");
            tvPermStatus.setTextColor(getColor(R.color.accent_green));
        } else {
            tvPermStatus.setText("权限 " + granted + "/" + total + " · 缺少：" + missing);
            tvPermStatus.setTextColor(getColor(R.color.accent_warn));
        }
    }

    private static String permLabel(String p) {
        switch (p) {
            case Manifest.permission.CALL_PHONE: return "打电话";
            case Manifest.permission.SEND_SMS: return "发短信";
            default: return "定位";
        }
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
        updatePermStatus();
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
