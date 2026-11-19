package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 急救卡导出 / 导入回归测试。
 *
 * 存在的理由：换签名后用户必须卸载重装，而急救卡在 EncryptedSharedPreferences 里，
 * 卸载即删。导出/导入是这条路上唯一的救命绳 —— 它一旦「悄悄少存一个字段」，
 * 用户重装后看到的是一张**看起来正常但缺了过敏史**的卡，比打不开更危险。
 *
 * 因此这里逐字段比对，而不是只断言"能解析出来"：
 * 姓名 / 血型 / Rh / 年龄 / 性别 / 住址 / 过敏(含严重标记) / 疾病 / 用药 /
 * 联系人数量与内容 / 医保 / 器官捐献 / 隐私开关。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EmergencyCardBackupTest {

    private static EmergencyCard fullCard() {
        List<EmergencyContact> contacts = new ArrayList<>();
        contacts.add(new EmergencyContact("李四", "13800000000", "配偶", true));
        contacts.add(new EmergencyContact("王五", "13900000000", "儿子", false));
        contacts.add(new EmergencyContact("赵六", "13700000000", "邻居", false));
        PrivacyFlags privacy = new PrivacyFlags(true, false, true, true);
        return new EmergencyCard(
                "self",
                "张三",
                68,
                Gender.MALE,
                BloodType.AB,
                RhFactor.NEGATIVE,
                "北京市朝阳区某路 1 号 2 单元 303",
                "青霉素过敏、海鲜过敏",
                true,
                "高血压、2 型糖尿病",
                "二甲双胍 0.5g 每日两次",
                contacts,
                "BJ-123456789",
                true,
                privacy,
                1_763_500_000_000L
        );
    }

    // ==================== 往返一致 ====================

    @Test
    public void exportImport_roundTripKeepsEveryField() {
        EmergencyCard source = fullCard();
        String json = EmergencyCardBackup.exportJson(source, 1_763_512_345_678L);
        EmergencyCard back = EmergencyCardBackup.importJson(json);

        assertNotNull("导出串必须能被自己解析回来（否则备份等于没做）", back);
        assertEquals("id", source.getId(), back.getId());
        assertEquals("姓名", source.getName(), back.getName());
        assertEquals("年龄", source.getAge(), back.getAge());
        assertEquals("性别", source.getGender(), back.getGender());
        assertEquals("血型", source.getBloodType(), back.getBloodType());
        assertEquals("Rh", source.getRh(), back.getRh());
        assertEquals("住址", source.getAddress(), back.getAddress());
        assertEquals("过敏史", source.getAllergy(), back.getAllergy());
        assertEquals("过敏严重标记", source.getAllergySevere(), back.getAllergySevere());
        assertEquals("基础疾病", source.getConditions(), back.getConditions());
        assertEquals("当前用药", source.getMedications(), back.getMedications());
        assertEquals("医保号", source.getInsuranceId(), back.getInsuranceId());
        assertEquals("器官捐献", source.getOrganDonation(), back.getOrganDonation());
        assertEquals("卡片更新时间", source.getUpdatedAt(), back.getUpdatedAt());

        assertEquals("联系人数量", source.getContacts().size(), back.getContacts().size());
        for (int i = 0; i < source.getContacts().size(); i++) {
            EmergencyContact a = source.getContacts().get(i);
            EmergencyContact b = back.getContacts().get(i);
            assertEquals("第 " + (i + 1) + " 位联系人姓名", a.getName(), b.getName());
            assertEquals("第 " + (i + 1) + " 位联系人电话", a.getPhone(), b.getPhone());
            assertEquals("第 " + (i + 1) + " 位联系人关系", a.getRelation(), b.getRelation());
            assertEquals("第 " + (i + 1) + " 位联系人主联系人标记",
                    a.getPrimary(), b.getPrimary());
        }

        PrivacyFlags sp = source.getPrivacy();
        PrivacyFlags bp = back.getPrivacy();
        assertEquals("锁屏显示血型", sp.getShowBloodOnLock(), bp.getShowBloodOnLock());
        assertEquals("锁屏显示住址", sp.getShowAddressOnLock(), bp.getShowAddressOnLock());
        assertEquals("短信带定位", sp.getAllowSmsLocation(), bp.getAllowSmsLocation());
        assertEquals("允许生成 QR", sp.getAllowQr(), bp.getAllowQr());
    }

    @Test
    public void emptyCard_roundTripDoesNotCrash() {
        // 空卡是合法状态（用户点了保存但没填），导出/导入都不能崩，也不能变成 null
        String json = EmergencyCardBackup.exportJson(new EmergencyCard(), 1_000L);
        EmergencyCard back = EmergencyCardBackup.importJson(json);
        assertNotNull("空卡往返必须成功", back);
        assertEquals("", back.getName());
        assertEquals(0, back.getContacts().size());
        assertEquals(BloodType.UNKNOWN, back.getBloodType());
        assertEquals(RhFactor.UNKNOWN, back.getRh());
    }

    // ==================== 顶部结构 ====================

    @Test
    public void exportedJson_carriesSchemaVersionAndTimestamp() {
        String json = EmergencyCardBackup.exportJson(fullCard(), 1_763_512_345_678L);
        // 去掉空白再比键值：导出串带缩进，冒号后是否有空格属于实现细节，不该被钉死
        String compact = json.replaceAll("\\s", "");
        assertTrue("必须带 schemaVersion，否则将来无法做格式迁移", compact.contains("schemaVersion"));
        assertTrue("schemaVersion 必须是当前版本 " + EmergencyCardBackup.SCHEMA_VERSION,
                compact.contains("\"schemaVersion\":" + EmergencyCardBackup.SCHEMA_VERSION));
        assertTrue("必须记录导出时刻", compact.contains("exportedAt"));
        assertTrue("导出时刻取值必须准确", compact.contains("1763512345678"));
        assertTrue("卡片必须挂在 card 字段下", compact.contains("\"card\":{"));
    }

    /**
     * 导出文件里绝不能出现 SOS 时间戳等设备状态。
     *
     * `emerLastSosTs` 记的是"这台设备上次求助的时间"。跟着卡片搬到新手机，
     * 只会造成"刚装好就提示近期求助过"的误解，而且它属于隐私状态，不该外带。
     */
    @Test
    public void exportedJson_hasNoSosTimestampOrDeviceState() {
        String json = EmergencyCardBackup.exportJson(fullCard(), 1_763_512_345_678L);
        assertFalse("不得包含 SOS 时间戳键", json.contains("emerLastSosTs"));
        assertFalse("不得包含 SOS 时间戳键（短名）", json.contains("lastSosTs"));
        assertFalse("不得包含 SOS 时间戳键（驼峰变体）", json.contains("lastSOS"));
        assertFalse("不得包含加密/偏好键名", json.contains("card_v1"));
        assertFalse("不得包含加密偏好文件名", json.contains("safewalk_emer_secure"));
    }

    // ==================== 非法输入一律返回 null，绝不抛异常 ====================

    @Test
    public void importJson_rejectsBrokenInput() {
        assertNull("null 输入", EmergencyCardBackup.importJson(null));
        assertNull("空串", EmergencyCardBackup.importJson(""));
        assertNull("全空白", EmergencyCardBackup.importJson("   \n\t  "));
        assertNull("坏 JSON", EmergencyCardBackup.importJson("{\"schemaVersion\":1,\"card\":"));
        assertNull("不是 JSON", EmergencyCardBackup.importJson("这是从微信里随便存的一段文字"));
        assertNull("JSON 数组", EmergencyCardBackup.importJson("[1,2,3]"));
        assertNull("纯数字", EmergencyCardBackup.importJson("12345"));
    }

    @Test
    public void importJson_rejectsUnsupportedSchemaVersion() {
        // 直接构造未来版本，不依赖导出串的空白格式
        assertNull("不认识的版本必须拒绝，绝不猜测字段含义", EmergencyCardBackup.importJson(
                "{\"schemaVersion\":999,\"exportedAt\":1000,\"card\":{\"name\":\"张三\"}}"));
        assertNull("版本 0 同样不认识", EmergencyCardBackup.importJson(
                "{\"schemaVersion\":0,\"card\":{\"name\":\"张三\"}}"));
    }

    @Test
    public void importJson_rejectsMissingStructure() {
        assertNull("缺 schemaVersion", EmergencyCardBackup.importJson(
                "{\"card\":{\"name\":\"张三\"}}"));
        assertNull("缺 card 对象", EmergencyCardBackup.importJson(
                "{\"schemaVersion\":1,\"exportedAt\":1000}"));
        assertNull("card 不是对象", EmergencyCardBackup.importJson(
                "{\"schemaVersion\":1,\"card\":\"张三\"}"));
        assertNull("card 是空对象（结构被截断/手改坏）", EmergencyCardBackup.importJson(
                "{\"schemaVersion\":1,\"card\":{}}"));
    }

    // ==================== 文件名 ====================

    @Test
    public void suggestFileName_containsDateAndJsonSuffix() {
        // 2026-11-19 12:00:00 UTC+8
        String name = EmergencyCardBackup.suggestFileName(1_763_524_800_000L);
        assertTrue("必须是 json 文件，文件选择器才认得", name.endsWith(".json"));
        assertTrue("必须带 safewalk 前缀，用户一眼知道是谁导的", name.startsWith("safewalk-"));
        assertTrue("文件名里不能有冒号/空格（部分 ROM 的文件选择器会出问题）",
                name.indexOf(':') < 0 && name.indexOf(' ') < 0);
        assertTrue("必须带 8 位数字日期", name.matches("^safewalk-emergency-card-\\d{8}\\.json$"));
    }
}
