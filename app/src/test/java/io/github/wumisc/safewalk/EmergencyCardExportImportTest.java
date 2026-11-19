package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 【5.5.0 急救卡导出/导入】补充验收测试（QA 独立编写）。
 *
 * <p>工程师已覆盖「往返逐字段一致 / 空卡 / 坏输入 / schemaVersion / 文件名」。
 * 本文件补的是<b>真正落盘那一步</b>与<b>安全边界</b>，因为换签名重装是「只有一次机会」的操作：
 * <ol>
 *   <li><b>真文件往返</b>：经真实 FileOutputStream 写 UTF-8 → 读回 → 解析。
 *       只测字符串在内存里往返，测不出编码问题（中文姓名/过敏史一旦变问号，
 *       用户重装后看到的是一张「血型对、过敏史是乱码」的卡）。</li>
 *   <li><b>再导出稳定</b>：export → import → export 必须逐字节一致，否则用户来回备份会漂移。</li>
 *   <li><b>未知字段容忍 / 结构性破坏拒绝</b>：把「宽容到哪、严格到哪」的边界钉死。</li>
 *   <li><b>BOM 边界</b>：Activity 会先剥 BOM 再解析，本文件把这条契约写明，
 *       避免以后有人删掉剥 BOM 那行而无人发现。</li>
 *   <li><b>隐私</b>：导入确认框摘要不得泄露住址/病史；导出导入路径不得把卡片明文写进日志。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EmergencyCardExportImportTest {

    private static EmergencyCard fullCard() {
        List<EmergencyContact> contacts = new ArrayList<>();
        contacts.add(new EmergencyContact("李四", "13800000000", "配偶", true));
        contacts.add(new EmergencyContact("王五", "13900000000", "儿子", false));
        return new EmergencyCard(
                "self", "张三", 68, Gender.MALE, BloodType.AB, RhFactor.NEGATIVE,
                "北京市朝阳区某路 1 号 2 单元 303",
                "青霉素过敏、海鲜过敏", true,
                "高血压、2 型糖尿病", "二甲双胍 0.5g 每日两次",
                contacts, "BJ-123456789", true,
                new PrivacyFlags(true, false, true, true),
                1_763_500_000_000L);
    }

    // ==================== 真文件往返（UTF-8） ====================

    @Test
    public void realFileRoundTrip_keepsChineseAndEveryField() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        File f = new File(ctx.getCacheDir(), "qa-emergency-roundtrip.json");
        EmergencyCard source = fullCard();
        String json = EmergencyCardBackup.exportJson(source, 1_763_512_345_678L);

        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);

        EmergencyCard back = EmergencyCardBackup.importJson(text);
        assertNotNull("落盘再读回的备份必须能解析", back);
        assertEquals("中文姓名必须原样保留", "张三", back.getName());
        assertEquals("中文住址必须原样保留", source.getAddress(), back.getAddress());
        assertEquals("中文过敏史必须原样保留", source.getAllergy(), back.getAllergy());
        assertEquals("血型", source.getBloodType(), back.getBloodType());
        assertEquals("Rh", source.getRh(), back.getRh());
        assertEquals("用药", source.getMedications(), back.getMedications());
        assertEquals("联系人数量", 2, back.getContacts().size());
        assertEquals("联系人姓名", "李四", back.getContacts().get(0).getName());
        assertEquals("主联系人标记", true, back.getContacts().get(0).getPrimary());
        assertEquals("医保号", source.getInsuranceId(), back.getInsuranceId());
        assertEquals("器官捐献", source.getOrganDonation(), back.getOrganDonation());
        assertEquals("锁屏显示血型", true, back.getPrivacy().getShowBloodOnLock());
        assertEquals("允许 QR", true, back.getPrivacy().getAllowQr());
        assertTrue("临时文件应清理，避免明文留在缓存目录", f.delete());
    }

    @Test
    public void reExportAfterImport_isByteIdentical() {
        // 用户「导出 → 导入 → 再导出」不该产生漂移（否则两份备份肉眼比对会误判丢字段）
        EmergencyCard source = fullCard();
        long now = 1_763_512_345_678L;
        String first = EmergencyCardBackup.exportJson(source, now);
        EmergencyCard back = EmergencyCardBackup.importJson(first);
        assertNotNull(back);
        String second = EmergencyCardBackup.exportJson(back, now);
        assertEquals("两次导出必须逐字节一致", first, second);
    }

    // ==================== 宽容度边界 ====================

    @Test
    public void unknownExtraKeysInsideCard_areTolerated() {
        // 未来版本加了字段、或用户手工加了备注：旧版本导入不能因此报「文件无法识别」
        String json = "{\"schemaVersion\":1,\"exportedAt\":1000,\"card\":{"
                + "\"name\":\"张三\",\"futureField\":\"whatever\",\"nested\":{\"a\":1}}}";
        EmergencyCard back = EmergencyCardBackup.importJson(json);
        assertNotNull("未知字段必须被忽略而不是拒绝整份备份", back);
        assertEquals("已知字段仍要读出", "张三", back.getName());
    }

    @Test
    public void cardWithOnlyUnknownKeys_importsAsEmptyCard() {
        // 结构性破坏（缺 schemaVersion / 缺 card / card={} ）一律拒绝（既有用例已覆盖）；
        // 但「card 有未知键」属于可解析，宽容成默认值而不是崩掉 —— 这里把这个边界钉住，
        // 避免以后有人把 length()==0 的宽松判断改成「必须含 name」而误杀旧备份。
        EmergencyCard back = EmergencyCardBackup.importJson(
                "{\"schemaVersion\":1,\"card\":{\"unknown\":1}}");
        assertNotNull(back);
        assertEquals("", back.getName());
        assertEquals(BloodType.UNKNOWN, back.getBloodType());
    }

    @Test
    public void bomPrefixedText_isTolerated_evenWithoutActivityStrip() {
        // 实测：org.json 的 JSONTokener 会跳过开头的 BOM，所以直接喂带 BOM 的串也能解析。
        // Activity 侧仍会先剥 BOM（见 readText），两层保险；这条用例把「BOM 不该是拒绝理由」钉住，
        // 防止以后换 JSON 实现后用户用记事本存的备份突然打不开。
        String json = EmergencyCardBackup.exportJson(fullCard(), 1_000L);
        assertNotNull("带 BOM 的备份也必须能导入（BOM 不是拒绝理由）",
                EmergencyCardBackup.importJson("\uFEFF" + json));
        assertNotNull("不带 BOM 的原文当然要能导入",
                EmergencyCardBackup.importJson(json));
    }

    @Test
    public void schemaVersionConstantIsOne() {
        assertEquals("schemaVersion 必须与导出/导入判定一致", 1, EmergencyCardBackup.SCHEMA_VERSION);
    }

    // ==================== 隐私 ====================

    @Test
    public void describe_doesNotLeakAddressOrMedicalDetail() {
        // 导入确认框只给「是不是我那张卡」的最小信息；住址/过敏/用药绝不上屏，更不进日志
        String d = EmergencyCardBackup.describe(fullCard());
        assertTrue("要能看出姓名", d.contains("张三"));
        assertTrue("要能看出血型", d.contains("AB") && d.contains("Rh"));
        assertTrue("要能看出联系人数", d.contains("2"));
        assertFalse("不得显示住址", d.contains("朝阳区"));
        assertFalse("不得显示过敏史", d.contains("青霉素"));
        assertFalse("不得显示用药", d.contains("二甲双胍"));
        assertFalse("不得显示医保号", d.contains("BJ-123456789"));
    }

    @Test
    public void exportedJson_isPlaintextByDesign_butHasNoDeviceState() {
        // 明文是「卸载重装可恢复」的代价（UI 有风险提示，见 UI 用例）；但设备状态一律不得外带
        String json = EmergencyCardBackup.exportJson(fullCard(), 1_763_512_345_678L);
        assertTrue("导出内容是明文医疗信息，这一点必须被 UI 明示", json.contains("青霉素"));
        assertFalse("不得包含 SOS 时间戳", json.toLowerCase(Locale.US).contains("sos"));
        assertFalse("不得包含加密偏好文件名", json.contains("safewalk_emer_secure"));
    }

    // ==================== 文件名 ====================

    @Test
    public void suggestFileName_usesLocalDateAndIsFilesystemSafe() {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.NOVEMBER, 19, 0, 5, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long ts = cal.getTimeInMillis();

        String name = EmergencyCardBackup.suggestFileName(ts);
        String expected = "safewalk-emergency-card-"
                + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ts))
                + ".json";
        assertEquals("文件名必须与本地日期一致", expected, name);
        assertTrue("文件名必须匹配固定格式", name.matches("^safewalk-emergency-card-\\d{8}\\.json$"));
        assertFalse("不得含路径分隔符", name.contains("/") || name.contains("\\"));
    }
}
