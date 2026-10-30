package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class EmergencyCardActivity extends Activity {

    private EditText etName, etBlood, etPhone, etAddress, etContact;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_emergency);
        // 急救卡数据存到 safewalk_xsp（MODE_WORLD_READABLE），与其他配置统一
        sp = getSharedPreferences("safewalk_xsp", MODE_WORLD_READABLE);

        etName = findViewById(R.id.et_name);
        etBlood = findViewById(R.id.et_blood);
        etPhone = findViewById(R.id.et_phone);
        etAddress = findViewById(R.id.et_address);
        etContact = findViewById(R.id.et_contact);

        etName.setText(sp.getString(SafeWalkPrefKey.emerName, ""));
        etBlood.setText(sp.getString(SafeWalkPrefKey.emerBlood, ""));
        etPhone.setText(sp.getString(SafeWalkPrefKey.emerContactPhone, ""));
        etAddress.setText(sp.getString(SafeWalkPrefKey.emerAddress, ""));
        etContact.setText(sp.getString(SafeWalkPrefKey.emerContactName, ""));

        Button btnSave = findViewById(R.id.btn_save_emergency);
        btnSave.setOnClickListener(v -> {
            sp.edit()
                    .putString(SafeWalkPrefKey.emerName, etName.getText().toString())
                    .putString(SafeWalkPrefKey.emerBlood, etBlood.getText().toString())
                    .putString(SafeWalkPrefKey.emerContactPhone, etPhone.getText().toString())
                    .putString(SafeWalkPrefKey.emerAddress, etAddress.getText().toString())
                    .putString(SafeWalkPrefKey.emerContactName, etContact.getText().toString())
                    .apply();
            Toast.makeText(this, "急救卡已保存", Toast.LENGTH_SHORT).show();
        });

        Button btnSos = findViewById(R.id.btn_goto_sos);
        btnSos.setOnClickListener(v -> openSosSettings());
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
