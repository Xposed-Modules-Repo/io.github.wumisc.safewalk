package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「认不出这是哪一家」的判定（4.3 新增）。
 *
 * 这组测试存在的理由：首页那行注明会把结论直接写给用户看。如果三星 One UI 被写成
 * 「本机 UI：原生」，用户第一眼就会发现不对劲，然后开始怀疑整页数据 —— 一句错的文案
 * 比不写更糟。所以「真原生」和「没认出来」必须在判定层就分开，而不是靠 UI 猜。
 */
public class RomDetectorTest {

    @Test
    public void fiveVendorsAreRecognized() {
        assertFalse(RomDetector.isUnrecognized("Xiaomi", "Redmi", "marble"));
        assertFalse(RomDetector.isUnrecognized("HONOR", "HONOR", "ELP-NX9"));
        assertFalse(RomDetector.isUnrecognized("OPPO", "OPPO", "CPH2451"));
        assertFalse(RomDetector.isUnrecognized("vivo", "iQOO", "V2227A"));
        assertFalse(RomDetector.isUnrecognized("HUAWEI", "HUAWEI", "ALN-AL80"));
    }

    @Test
    public void huaweiAndHonorDoNotCollide() {
        /*
         * 荣耀老机型（MagicUI 时期）的 MANUFACTURER 仍是 HUAWEI、BRAND 是 HONOR，
         * 两个关键字会同时命中。判定顺序保证它落在 MAGICOS（那正是 MagicUI 的档案），
         * 而纯华为机（brand 也是 HUAWEI）才落 HARMONYOS。
         * 这一条不锁死，未来往 TOKENS 里加关键字就可能把大量荣耀用户指到华为的设置页。
         */
        assertEquals(RomId.MAGICOS, RomDetector.detect("HUAWEI", "HONOR", "ELP-NX9"));
        assertEquals(RomId.HARMONYOS, RomDetector.detect("HUAWEI", "HUAWEI", "ALN-AL80"));
        assertEquals(RomId.HARMONYOS, RomDetector.detect("HUAWEI", "HUAWEI", "HarmonyOS"));
    }

    @Test
    public void stockAndroidIsRecognizedAsStock() {
        // Pixel / LineageOS 判定结果也是 AOSP，但那是「本来就是原生」，不算「没认出来」
        assertFalse(RomDetector.isUnrecognized("Google", "google", "cheetah"));
        assertFalse(RomDetector.isUnrecognized("Google", "Pixel 7", "panther"));
        assertFalse(RomDetector.isUnrecognized("LineageOS", "lineage", "lemonade"));
        assertFalse(RomDetector.isUnrecognized("AOSP", "aosp", "generic"));
    }

    @Test
    public void otherVendorsAreUnrecognized() {
        // 这两家都真实存在、都不在本项目的适配范围内 —— 也是「不能被写成原生」的典型
        assertTrue(RomDetector.isUnrecognized("motorola", "moto", "eagle"));
        assertTrue(RomDetector.isUnrecognized("Sony", "sony", "XQ-DQ72"));
        // 一加归 ColorOS，必须被认出来
        assertFalse(RomDetector.isUnrecognized("OnePlus", "OnePlus", "CPH2581"));
        // 【5.2.0】三星已单独建档（One UI），不能再算「未识别」——
        // 否则 S24 Ultra 用户看到的仍是「本机 UI：原生」这句明确错误的话
        assertFalse(RomDetector.isUnrecognized("samsung", "samsung", "SM-S9180"));
    }

    @Test
    public void emptyInputIsUnrecognized() {
        assertTrue(RomDetector.isUnrecognized(null, null, null));
        assertTrue(RomDetector.isUnrecognized("", "", ""));
    }

    @Test
    public void unrecognizedImpliesAospFallback() {
        // 「没认出来」必须落在 AOSP 档案上，否则 UI 会显示「未识别」却用着别家的表
        assertEquals(RomId.AOSP, RomDetector.detect("motorola", "moto", "eagle"));
        assertEquals(RomId.AOSP, RomDetector.detect("Sony", "sony", "XQ-DQ72"));
        assertEquals(RomId.AOSP, RomDetector.detect(null, null, null));
    }

    @Test
    public void samsungNoLongerFallsBackToAosp() {
        // 5.2.0 的反向锁：三星必须命中 ONEUI 而不是兜底到 AOSP
        assertEquals(RomId.ONEUI, RomDetector.detect("samsung", "samsung", "SM-S9180"));
    }

    @Test
    public void recognizedVendorIsNeverUnrecognized() {
        // 反向锁死：命中任一厂商关键字就绝不可能是「未识别」，防止以后加关键字时写岔
        String[][] cases = {
                {"Xiaomi", "POCO", "poco"},
                {"HONOR", "HONOR", "magicos"},
                {"realme", "realme", "RMX"},
                {"vivo", "vivo", "originos"},
                {"HUAWEI", "HUAWEI", "emui"},
                {"SAMSUNG", "samsung", "e3q"}
        };
        for (String[] c : cases) {
            assertFalse("不该判成未识别：" + String.join("/", c),
                    RomDetector.isUnrecognized(c[0], c[1], c[2]));
        }
    }
}
