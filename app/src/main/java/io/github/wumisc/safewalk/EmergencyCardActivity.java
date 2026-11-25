package io.github.wumisc.safewalk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 医疗急救卡编辑页（v1.1 扩展）。
 *
 * 字段：姓名/年龄/性别/血型/Rh/住址/过敏(严重标记)/基础疾病/用药/医保/器官捐献/
 * ≥3 紧急联系人/隐私开关/医疗 QR，全部写入 EmergencyVault（加密）。
 * 保留"复制到系统紧急信息"卡片：Q3 联动不替代自建。
 */
public class EmergencyCardActivity extends Activity {

    private static final String[] BLOOD_LABELS = {"A型", "B型", "AB型", "O型", "其他", "未知"};
    private static final String[] RH_LABELS = {"Rh+", "Rh-", "Rh未知"};
    private static final String[] GENDER_LABELS = {"未知", "男", "女", "其他"};

    private EditText etName, etAge, etAddress, etAllergy, etCondition, etMedications, etInsurance;
    private EditText[][] contactRows = new EditText[3][3]; // {name, relation, phone}
    private Spinner spBlood, spRh, spGender;
    private SwitchCompat swAllergySevere, swOrganDonation;
    private SwitchCompat swShowBloodLock, swShowAddressLock, swAllowSmsLocation, swAllowQr;
    private TextView tvVaultStatus;
    private ImageView ivQr;
    private Bitmap qrBitmap;
    private SharedPreferences sp;

    // 车祸预警
    private SwitchCompat swCrashDetection;
    private TextView chipCrashLow, chipCrashMid, chipCrashHigh;
    private TextView tvCrashHint, tvPermStatus;
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

        EmergencyDiag.install();
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE);
        // 幂等迁移：老用户 7 个明文字段一次性进加密卡，旧 key 校验读回一致后才删除
        EmergencyVault.init(this);
        EmergencyVault.migrateIfNeeded(this);

        bindViews();
        setupSpinners();
        fillFromVault();

        Button btnSave = findViewById(R.id.btn_save_emergency);
        btnSave.setOnClickListener(v -> saveCard());
        Button btnCopy = findViewById(R.id.btn_copy_emergency);
        btnCopy.setOnClickListener(v -> copyCard());
        // 【5.5.0】换签名后旧包必须卸载重装，加密卡会随之消失 —— 给用户一条
        // 「先导出到文件、重装后再导入」的备份通道（SAF，不要存储权限）。
        Button btnExport = findViewById(R.id.btn_export_emergency);
        btnExport.setOnClickListener(v -> exportCard());
        Button btnImport = findViewById(R.id.btn_import_emergency);
        btnImport.setOnClickListener(v -> pickImportFile());
        Button btnQr = findViewById(R.id.btn_gen_qr);
        btnQr.setOnClickListener(v -> generateQr());
        Button btnSos = findViewById(R.id.btn_goto_sos);
        btnSos.setOnClickListener(v -> openSosSettings());

        setupCrashDetection();
        binding = false;
        refreshCrashChips();
        updateCrashHint();
    }

    private void bindViews() {
        etName = findViewById(R.id.et_name);
        etAge = findViewById(R.id.et_age);
        etAddress = findViewById(R.id.et_address);
        etAllergy = findViewById(R.id.et_allergy);
        etCondition = findViewById(R.id.et_condition);
        etMedications = findViewById(R.id.et_medications);
        etInsurance = findViewById(R.id.et_insurance);
        contactRows[0] = new EditText[]{findViewById(R.id.et_contact1_name),
                findViewById(R.id.et_contact1_relation), findViewById(R.id.et_contact1_phone)};
        contactRows[1] = new EditText[]{findViewById(R.id.et_contact2_name),
                findViewById(R.id.et_contact2_relation), findViewById(R.id.et_contact2_phone)};
        contactRows[2] = new EditText[]{findViewById(R.id.et_contact3_name),
                findViewById(R.id.et_contact3_relation), findViewById(R.id.et_contact3_phone)};
        spBlood = findViewById(R.id.sp_blood);
        spRh = findViewById(R.id.sp_rh);
        spGender = findViewById(R.id.sp_gender);
        swAllergySevere = findViewById(R.id.sw_allergy_severe);
        swOrganDonation = findViewById(R.id.sw_organ_donation);
        swShowBloodLock = findViewById(R.id.sw_show_blood_lock);
        swShowAddressLock = findViewById(R.id.sw_show_address_lock);
        swAllowSmsLocation = findViewById(R.id.sw_allow_sms_location);
        swAllowQr = findViewById(R.id.sw_allow_qr);
        tvVaultStatus = findViewById(R.id.tv_vault_status);
        ivQr = findViewById(R.id.iv_qr);
    }

    private void setupSpinners() {
        ArrayAdapter<String> blood = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, BLOOD_LABELS);
        ArrayAdapter<String> rh = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, RH_LABELS);
        ArrayAdapter<String> gender = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, GENDER_LABELS);
        spBlood.setAdapter(blood);
        spRh.setAdapter(rh);
        spGender.setAdapter(gender);
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(value)) return i;
        }
        return arr.length - 1;
    }

    /** 从加密 Vault 回填全部字段 */
    private void fillFromVault() {
        EmergencyCard card = EmergencyVault.readCard();
        etName.setText(card.getName());
        etAge.setText(card.getAge() > 0 ? String.valueOf(card.getAge()) : "");
        etAddress.setText(card.getAddress());
        etAllergy.setText(card.getAllergy());
        etCondition.setText(card.getConditions());
        etMedications.setText(card.getMedications());
        etInsurance.setText(card.getInsuranceId());
        spBlood.setSelection(indexOf(BLOOD_LABELS, card.getBloodType().getLabel()));
        spRh.setSelection(indexOf(RH_LABELS, card.getRh().getLabel()));
        spGender.setSelection(indexOf(GENDER_LABELS, card.getGender().getLabel()));
        swAllergySevere.setChecked(card.getAllergySevere());
        swOrganDonation.setChecked(card.getOrganDonation());
        PrivacyFlags p = card.getPrivacy();
        swShowBloodLock.setChecked(p.getShowBloodOnLock());
        swShowAddressLock.setChecked(p.getShowAddressOnLock());
        swAllowSmsLocation.setChecked(p.getAllowSmsLocation());
        swAllowQr.setChecked(p.getAllowQr());

        List<EmergencyContact> contacts = card.getContacts();
        for (int i = 0; i < contactRows.length; i++) {
            if (i < contacts.size()) {
                EmergencyContact c = contacts.get(i);
                contactRows[i][0].setText(c.getName());
                contactRows[i][1].setText(c.getRelation());
                contactRows[i][2].setText(c.getPhone());
            } else {
                for (EditText e : contactRows[i]) e.setText("");
            }
        }
        tvVaultStatus.setText(EmergencyVault.isEncrypted()
                ? "已加密落盘（AndroidKeyStore AES256-GCM）· 仅本机可读"
                : "⚠ 本次未落盘：设备加密不可用，关闭页面后数据丢失，请检查锁屏密码设置");
        EmergencyDiag.logCard("read", card);
    }

    /** 从 UI 组装完整卡 */
    private EmergencyCard buildCardFromUi() {
        String ageText = etAge.getText().toString().trim();
        int age = 0;
        try {
            if (!ageText.isEmpty()) age = Integer.parseInt(ageText);
        } catch (NumberFormatException ignored) {
        }
        List<EmergencyContact> contacts = new ArrayList<>();
        for (int i = 0; i < contactRows.length; i++) {
            String n = text(contactRows[i][0]);
            String rel = text(contactRows[i][1]);
            String phone = text(contactRows[i][2]);
            if (n.isEmpty() && phone.isEmpty() && rel.isEmpty()) continue;
            contacts.add(new EmergencyContact(n, phone, rel, i == 0));
        }
        PrivacyFlags privacy = new PrivacyFlags(
                swShowBloodLock.isChecked(),
                swShowAddressLock.isChecked(),
                swAllowSmsLocation.isChecked(),
                swAllowQr.isChecked()
        );
        return new EmergencyCard(
                "self",
                text(etName),
                age,
                genderAt(spGender.getSelectedItemPosition()),
                BloodType.values()[spBlood.getSelectedItemPosition()],
                RhFactor.values()[spRh.getSelectedItemPosition()],
                text(etAddress),
                text(etAllergy),
                swAllergySevere.isChecked(),
                text(etCondition),
                text(etMedications),
                contacts,
                text(etInsurance),
                swOrganDonation.isChecked(),
                privacy,
                System.currentTimeMillis()
        );
    }

    private static String text(EditText e) {
        return e == null ? "" : e.getText().toString().trim();
    }

    /** GENDER_LABELS 的顺序是 未知/男/女/其他，与 Gender 枚举定义顺序不同，必须显式映射 */
    private static Gender genderAt(int pos) {
        switch (pos) {
            case 1: return Gender.MALE;
            case 2: return Gender.FEMALE;
            case 3: return Gender.OTHER;
            default: return Gender.UNKNOWN;
        }
    }

    /**
     * 保存急救卡：写加密 Vault（不再写明文 SharedPreferences）。
     * exportConfig 仍调用，但它只导出白名单开关，医疗字段不会进配置文件。
     */
    private void saveCard() {
        EmergencyCard card = buildCardFromUi();
        String firstPhone = card.getContacts().isEmpty() ? "" : card.getContacts().get(0).getPhone();
        if (card.getName().isEmpty() && firstPhone.isEmpty()) {
            Toast.makeText(this, "姓名和紧急联系人电话至少填一项，否则急救卡没有意义",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!firstPhone.isEmpty() && firstPhone.replaceAll("[^0-9]", "").length() < 5) {
            Toast.makeText(this, "紧急联系人电话看起来不完整，请检查后再保存",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = EmergencyVault.saveCard(card);
        SafeWalkConfig.exportConfig(this);
        if (ok) {
            Toast.makeText(this, "急救卡已加密保存", Toast.LENGTH_SHORT).show();
            EmergencyDiag.logCard("saved", card);
            tvVaultStatus.setText("已加密落盘（AndroidKeyStore AES256-GCM）· 仅本机可读");
        } else {
            Toast.makeText(this, "⚠ 本次未落盘：设备加密不可用，仅保留在当前页面",
                    Toast.LENGTH_LONG).show();
            tvVaultStatus.setText("⚠ 本次未落盘：设备加密不可用，关闭页面后数据丢失");
        }
    }

    /** 复制成一段可直接粘贴到系统「紧急信息」的文本（保留 Q3 联动入口） */
    private void copyCard() {
        EmergencyCard card = buildCardFromUi();
        StringBuilder sb = new StringBuilder("【医疗急救信息】\n");
        appendField(sb, "姓名", card.getName());
        appendField(sb, "年龄", card.getAge() > 0 ? String.valueOf(card.getAge()) : "");
        appendField(sb, "血型", card.getBloodType().getLabel() + " " + card.getRh().getLabel());
        appendField(sb, "过敏史", card.getAllergy() + (card.getAllergySevere() ? "（严重）" : ""));
        appendField(sb, "基础疾病", card.getConditions());
        appendField(sb, "当前用药", card.getMedications());
        appendField(sb, "住址", card.getAddress());
        for (EmergencyContact c : card.getContacts()) {
            String s = (c.getName() + " " + c.getRelation() + " " + c.getPhone()).trim();
            appendField(sb, "紧急联系人", s);
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException("剪贴板不可用");
            cm.setPrimaryClip(ClipData.newPlainText("SafeWalk 急救信息", sb.toString()));
            Toast.makeText(this, "已复制，去系统「紧急信息」里粘贴", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 医疗 QR：明文风险必须显式开启 + 二次确认，且只在点击时生成一次 */
    private void generateQr() {
        if (!swAllowQr.isChecked()) {
            Toast.makeText(this, "请先打开「允许生成医疗 QR」（二维码为明文，扫码即可读全部信息）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("生成明文医疗 QR？")
                .setMessage("二维码包含完整急救信息，任何扫码的人都能读到。请确认只在需要时展示。")
                .setPositiveButton("确认生成", (d, w) -> {
                    EmergencyCard card = buildCardFromUi();
                    Bitmap bmp = MedicalQr.encode(card);
                    if (bmp == null) {
                        Toast.makeText(this, "生成失败，请检查存储空间", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (qrBitmap != null && !qrBitmap.isRecycled()) qrBitmap.recycle();
                    qrBitmap = bmp;
                    ivQr.setImageBitmap(bmp);
                    ivQr.setVisibility(View.VISIBLE);
                    // 生成按钮在页面底部，二维码在按钮下方；不滚动的话用户
                    // 点完什么都看不到，会以为没生成。滚到二维码并给一句提示。
                    ivQr.post(() -> {
                        try {
                            ScrollView sv = findViewById(R.id.sv_emergency);
                            if (sv != null) {
                                sv.smoothScrollTo(0, Math.max(0, ivQr.getTop() - 24));
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                    Toast.makeText(this, "医疗 QR 已生成（明文，请谨慎展示）",
                            Toast.LENGTH_SHORT).show();
                    EmergencyDiag.event(EmergencyDiag.P_EMERGENCY, "qr_generated");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append(label).append('：').append(value.trim()).append('\n');
    }

    // ========== 导出 / 导入（换签名重装后的数据恢复通道） ==========

    private static final int REQ_EXPORT_CARD = 4001;
    private static final int REQ_IMPORT_CARD = 4002;
    /**
     * 导入文件大小上限。
     *
     * 正常急救卡 JSON 不到 4KB。用户从文件选择器挑的可能是任意文件（照片、APK、
     * 几十兆的日志），先按体量一刀切掉，避免把整个文件读进内存。
     */
    private static final int MAX_IMPORT_BYTES = 256 * 1024;

    /**
     * 导出：先明示风险，再让用户用系统文件选择器决定落在哪。
     *
     * 这里**不**直接写到公共目录：SAF 让用户自己选位置，既不需要
     * READ/WRITE_EXTERNAL_STORAGE，也避免 App 悄悄把明文医疗信息铺在磁盘上。
     */
    private void exportCard() {
        EmergencyCard card = EmergencyVault.readCard();
        if (card.getName().trim().isEmpty() && card.getContacts().isEmpty()) {
            Toast.makeText(this, "还没有保存过急救卡，请先填写并点「保存急救卡」再导出",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("导出急救卡？")
                .setMessage("导出文件包含血型、过敏史、住址、联系人等敏感医疗信息，"
                        + "且为**明文**（未加密）。\n\n"
                        + "请保存到只有你自己能访问的位置，不要发到群聊、网盘或公共目录；"
                        + "换签名重装完成后建议删除该文件。")
                .setPositiveButton("继续导出", (d, w) -> launchCreateDocument())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 拉起系统「新建文件」选择器（ACTION_CREATE_DOCUMENT），默认文件名带日期 */
    private void launchCreateDocument() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,
                    EmergencyCardBackup.suggestFileName(System.currentTimeMillis()));
            startActivityForResult(i, REQ_EXPORT_CARD);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件选择器，无法导出", Toast.LENGTH_LONG).show();
        }
    }

    /** 导入：先让用户挑文件（ACTION_OPEN_DOCUMENT），解析成功再确认覆盖 */
    private void pickImportFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            startActivityForResult(i, REQ_IMPORT_CARD);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件选择器，无法导入", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQ_EXPORT_CARD) {
            writeExportFile(uri);
        } else if (requestCode == REQ_IMPORT_CARD) {
            readImportFile(uri);
        }
    }

    /** 把当前**已保存**的急救卡写成 JSON 文件（未保存的编辑不会混进备份） */
    private void writeExportFile(Uri uri) {
        String json = EmergencyCardBackup.exportJson(EmergencyVault.readCard());
        java.io.OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(uri, "wt");
            if (os == null) throw new IllegalStateException("所选位置不可写");
            os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            Toast.makeText(this, "急救卡已导出，请妥善保管该文件", Toast.LENGTH_LONG).show();
            EmergencyDiag.event(EmergencyDiag.P_EMERGENCY, "card_exported");
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            closeQuietly(os);
        }
    }

    /** 读入用户选的文件 → 解析 → 确认 → 覆盖写入加密 Vault */
    private void readImportFile(Uri uri) {
        String text;
        try {
            text = readText(uri);
        } catch (Throwable t) {
            Toast.makeText(this, "读取文件失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        EmergencyCard card = EmergencyCardBackup.importJson(text);
        if (card == null) {
            Toast.makeText(this, "文件无法识别：不是本 App 导出的急救卡备份（或版本不兼容）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("导入这张急救卡？")
                .setMessage(EmergencyCardBackup.describe(card)
                        + "\n\n导入将覆盖当前的急救卡（加密保存在本机）。")
                .setPositiveButton("覆盖导入", (d, w) -> applyImport(card))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyImport(EmergencyCard card) {
        boolean ok = EmergencyVault.saveCard(card);
        fillFromVault();   // 界面立刻显示导入内容，用户能当场核对
        if (ok) {
            Toast.makeText(this, "已导入急救卡，重启 App 后全部生效", Toast.LENGTH_LONG).show();
            EmergencyDiag.event(EmergencyDiag.P_EMERGENCY, "card_imported");
        } else {
            Toast.makeText(this, "⚠ 本次未落盘：设备加密不可用，重启后会丢失",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 读取 URI 全部文本（UTF-8，去掉可能的 BOM），超过 [MAX_IMPORT_BYTES] 直接判非法 */
    private String readText(Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法打开所选文件");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX_IMPORT_BYTES) {
                    throw new IllegalStateException("文件过大，不像是急救卡备份");
                }
                bos.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
        }
        String text = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    // ========== 车祸预警 ==========
    private void setupCrashDetection() {
        swCrashDetection = findViewById(R.id.sw_crash_detection);
        chipCrashLow = findViewById(R.id.chip_crash_low);
        chipCrashMid = findViewById(R.id.chip_crash_mid);
        chipCrashHigh = findViewById(R.id.chip_crash_high);
        tvCrashHint = findViewById(R.id.tv_crash_hint);
        tvPermStatus = findViewById(R.id.tv_perm_status);

        swCrashDetection.setChecked(
                SafePrefs.getBool(sp, SafeWalkPrefKey.enableCrashDetection, false));
        crashSensitivity = Math.max(0, Math.min(2,
                SafePrefs.getInt(sp, SafeWalkPrefKey.crashSensitivity, 1)));

        swCrashDetection.setOnCheckedChangeListener((v, c) -> {
            if (binding) return;
            sp.edit().putBoolean(SafeWalkPrefKey.enableCrashDetection, c).commit();
            SafeWalkConfig.exportConfig(this);
            if (c) requestCrashPermsIfNeeded();
            updateCrashHint();
        });
        chipCrashLow.setOnClickListener(v -> selectSensitivity(0));
        chipCrashMid.setOnClickListener(v -> selectSensitivity(1));
        chipCrashHigh.setOnClickListener(v -> selectSensitivity(2));
    }

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

    private void requestCrashPermsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> missing = new ArrayList<>();
        for (String p : CRASH_PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_CRASH_PERMS);
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
                ? "已就绪：检测到撞击会锁屏强提醒，60秒未响应自动求助。"
                : "已开启，但部分权限未授权：求助时将跳转系统拨号/短信，需你手动点一下发送。点此补授权。");
        tvCrashHint.setOnClickListener(v -> {
            if (!granted) requestCrashPermsIfNeeded();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!binding) updateCrashHint();
    }

    private void openSosSettings() {
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
            } catch (Throwable ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            Toast.makeText(this, "请在设置中搜索「紧急呼救」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (qrBitmap != null && !qrBitmap.isRecycled()) {
            qrBitmap.recycle();
            qrBitmap = null;
        }
        super.onDestroy();
    }
}
