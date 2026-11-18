package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Proxy;

/**
 * 急救卡迁移核心单测：幂等 / 读回一致 / 失败不回退明文。
 *
 * 这里注入两个普通 SharedPreferences 验证迁移算法本身，不依赖 AndroidKeyStore；
 * 加密存储不可用时 EmergencyVault 走内存态并提示"本次未落盘"，绝不写明文副本。
 */
@RunWith(RobolectricTestRunner.class)
public class EmergencyVaultTest {

    private SharedPreferences legacy;
    private SharedPreferences enc;

    @Before
    public void setUp() {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        legacy = ctx.getSharedPreferences("test_legacy_xsp", android.content.Context.MODE_PRIVATE);
        enc = ctx.getSharedPreferences("test_enc_vault", android.content.Context.MODE_PRIVATE);
        legacy.edit().clear().commit();
        enc.edit().clear().commit();
    }

    private void seedLegacy() {
        legacy.edit()
                .putString(SafeWalkPrefKey.emerName, "张三")
                .putString(SafeWalkPrefKey.emerBlood, "A+")
                .putString(SafeWalkPrefKey.emerAddress, "北京市朝阳区1号")
                .putString(SafeWalkPrefKey.emerContactName, "李四")
                .putString(SafeWalkPrefKey.emerContactPhone, "13800000000")
                .putString(SafeWalkPrefKey.emerAllergy, "青霉素过敏")
                .putString(SafeWalkPrefKey.emerCondition, "高血压")
                .putLong(SafeWalkPrefKey.emerLastSosTs, 123456L)
                .commit();
    }

    @Test
    public void migrate_isIdempotent_andClearsLegacyKeys() {
        seedLegacy();
        assertTrue(EmergencyVault.migrateIfNeeded(legacy, enc));
        assertTrue(EmergencyVault.migrateIfNeeded(legacy, enc)); // 第二次必须无副作用

        assertTrue(enc.getBoolean(SafeWalkPrefKey.emerVaultMigrated, false));
        assertFalse("旧 key emerName 必须已删除", legacy.contains(SafeWalkPrefKey.emerName));
        assertFalse("旧 key emerBlood 必须已删除", legacy.contains(SafeWalkPrefKey.emerBlood));
        assertFalse("旧 key emerAddress 必须已删除", legacy.contains(SafeWalkPrefKey.emerAddress));
        assertFalse("旧 key emerAllergy 必须已删除", legacy.contains(SafeWalkPrefKey.emerAllergy));
        assertFalse("旧 key emerCondition 必须已删除", legacy.contains(SafeWalkPrefKey.emerCondition));
        assertFalse("旧 key emerContactName 必须已删除", legacy.contains(SafeWalkPrefKey.emerContactName));
        assertFalse("旧 key emerContactPhone 必须已删除", legacy.contains(SafeWalkPrefKey.emerContactPhone));
        assertEquals("求助时间戳一并迁入", 123456L,
                enc.getLong(SafeWalkPrefKey.emerLastSosTs, 0L));
    }

    @Test
    public void migrate_readBackEqualsWrittenValue() {
        seedLegacy();
        assertTrue(EmergencyVault.migrateIfNeeded(legacy, enc));
        String json = enc.getString("card_v1", null);
        assertNotNull("加密槽位必须写入完整卡 JSON", json);
        EmergencyCard card = EmergencyCard.fromJson(json);
        assertEquals("张三", card.getName());
        assertEquals(BloodType.A, card.getBloodType());
        assertEquals(RhFactor.POSITIVE, card.getRh());
        assertEquals("北京市朝阳区1号", card.getAddress());
        assertEquals("青霉素过敏", card.getAllergy());
        assertEquals("高血压", card.getConditions());
        assertEquals(1, card.getContacts().size());
        assertEquals("李四", card.getContacts().get(0).getName());
        assertTrue(card.getContacts().get(0).getPrimary());
    }

    @Test
    public void migrate_writeFailure_keepsLegacyPlaintext() {
        seedLegacy();
        SharedPreferences failing = failingTarget();
        assertFalse("写入失败时迁移必须返回 false", EmergencyVault.migrateIfNeeded(legacy, failing));
        assertEquals("旧明文必须保留，下次重试", "张三",
                legacy.getString(SafeWalkPrefKey.emerName, ""));
        assertEquals("写入失败不能留下迁移标记", false,
                failing.getBoolean(SafeWalkPrefKey.emerVaultMigrated, false));
    }

    @Test
    public void migrate_emptyLegacy_marksDoneWithoutFabricatingCard() {
        assertTrue(EmergencyVault.migrateIfNeeded(legacy, enc));
        assertTrue(enc.getBoolean(SafeWalkPrefKey.emerVaultMigrated, false));
        assertEquals(null, enc.getString("card_v1", null));
    }

    @Test
    public void displayFields_isWhitelistSubset_noAddressOrInsurance() {
        seedLegacy();
        EmergencyVault.migrateIfNeeded(legacy, enc);
        EmergencyCard full = EmergencyCard.fromJson(enc.getString("card_v1", null));
        DisplayFields d = EmergencyVault.buildDisplayFields(full);
        assertEquals("张三", d.getName());
        assertEquals("A型", d.getBloodType());
        assertEquals("青霉素过敏", d.getAllergy());
        // U1：住址只在 Activity 完整卡面，不进覆盖层载荷（类型层面就没有该字段）
        String json = d.toJson();
        assertFalse(json.contains("北京市朝阳区1号"));
    }

    /** commit() 永远失败的 SharedPreferences，用于验证"失败不删旧明文" */
    private static SharedPreferences failingTarget() {
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "edit":
                            return Proxy.newProxyInstance(
                                    SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class},
                                    (p, m, a) -> {
                                        if ("commit".equals(m.getName())) return false;
                                        if (m.getReturnType() == SharedPreferences.Editor.class) return p;
                                        return null;
                                    });
                        case "getBoolean": return false;
                        case "getLong": return 0L;
                        case "getString": return null;
                        case "getAll": return new java.util.HashMap<String, Object>();
                        default: return null;
                    }
                });
    }
}
