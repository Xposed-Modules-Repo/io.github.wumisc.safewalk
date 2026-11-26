package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 两条跨进程通道的物理隔离断言（v1.1 隐私契约）。
 *
 * 一旦有人把医疗字段加进 ConfigProvider.Companion.getWHITELIST()，本测试立即失败。
 */
public class WhitelistIsolationTest {

    @Test
    public void whitelist_hasNoMedicalKeys() {
        assertTrue("WHITELIST 与 MEDICAL_KEYS 必须恒不相交",
                java.util.Collections.disjoint(
                        ConfigProvider.Companion.getWHITELIST(), ConfigProvider.MEDICAL_KEYS));
    }

    @Test
    public void whitelist_doesNotContainLegacyMedicalKeys() {
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerName));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerBlood));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerAddress));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerContactPhone));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerAllergy));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerCondition));
    }

    @Test
    public void whitelist_doesNotContainSosOrMigrationFlags() {
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerLastSosTs));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerVaultMigrated));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerQrPayload));
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.emerInsuranceId));
    }

    @Test
    public void whitelist_doesNotContainUploadConsentFlag() {
        // 上报同意标记是纯 App 本地隐私状态，Hook（SystemUI）进程不参与上报，
        // 不得跨进程透传，避免把用户同意状态暴露给另一个进程。
        assertFalse(ConfigProvider.Companion.getWHITELIST().contains(SafeWalkPrefKey.logUploadConsented));
    }

    @Test
    public void displayChannel_isPhysicallySeparatedFromConfigChannel() {
        assertNotEquals("两条通道 authority 必须不同",
                ConfigProvider.AUTHORITY, SafeWalkPrefKey.DISPLAY_AUTHORITY);
        assertFalse(SafeWalkPrefKey.DISPLAY_PATH.equals("/config"));
        assertTrue(SafeWalkPrefKey.DISPLAY_TTL_MS > 0L);
        assertEquals20s();
    }

    private static void assertEquals20s() {
        assertTrue("TTL 必须为 20s", SafeWalkPrefKey.DISPLAY_TTL_MS == 20_000L);
    }

    @Test
    public void displayFields_typeHasNoSensitiveProperties() {
        // 类型层面断言：最小载荷不得暴露医保号/QR/住址/多联系人
        for (java.lang.reflect.Field f : DisplayFields.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertFalse("DisplayFields 不得包含 address", n.contains("address"));
            assertFalse("DisplayFields 不得包含 insurance", n.contains("insurance"));
            assertFalse("DisplayFields 不得包含 qr", n.contains("qr"));
            assertFalse("DisplayFields 不得包含 contacts 列表", n.contains("contacts"));
        }
    }
}
