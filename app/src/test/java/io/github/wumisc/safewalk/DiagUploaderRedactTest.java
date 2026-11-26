package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [DiagUploader.redact] 上报前本地脱敏的纯函数契约。
 *
 * 上报内容会离开设备，手机号 / MAC / IMEI / 坐标一旦漏进日志正文就属于隐私泄露；
 * 这些正则兜底必须被钉死，后续改动脱敏规则时本测试会直接拦下回归。
 */
public class DiagUploaderRedactTest {

    private String redact(String in) {
        return DiagUploader.redact(in);
    }

    @Test
    public void redact_masksPhoneNumber() {
        String out = redact("联系电话 13812345678 请回拨");
        assertFalse("手机号必须被抹除", out.contains("13812345678"));
        assertTrue(out.contains("***(手机号)"));
    }

    @Test
    public void redact_keepsPhoneLikeMillisecondTimestamp() {
        // 13 位毫秒时间戳里含形似手机号的数字段，不得被手机号正则从中段抹掉
        String out = redact("readyStamp=1795659272919 版本直取");
        assertTrue("毫秒时间戳必须保留", out.contains("1795659272919"));
        assertFalse("长数字串中段不应误判为手机号", out.contains("***(手机号)"));
    }

    @Test
    public void redact_masksMacAddress() {
        String out = redact("BSSID aa:bb:cc:dd:ee:ff 信号弱");
        assertFalse(out.contains("aa:bb:cc:dd:ee:ff"));
        assertTrue(out.contains("***(MAC)"));
    }

    @Test
    public void redact_masksImei() {
        String out = redact("imei 351234567890123 机身");
        assertFalse(out.contains("351234567890123"));
        assertTrue(out.contains("***(IMEI)"));
    }

    @Test
    public void redact_masksCoordinates() {
        String out = redact("lat=30.123456 lng=120.654321 到场");
        assertFalse(out.contains("30.123456"));
        assertFalse(out.contains("120.654321"));
        assertTrue(out.contains("***(坐标)"));
    }

    @Test
    public void redact_keepsOrdinaryDiagnosticText() {        String text = "📊 状态=SLEEP 窗口步数=0/50 来源=加速度计兜底 姿态=TILTED";
        String out = redact(text);
        assertTrue("普通诊断文本不得被破坏", out.contains("SLEEP"));
        assertTrue(out.contains("0/50"));
    }

    @Test
    public void redact_emptyInput_isEmpty() {
        assertTrue(redact("").isEmpty());
    }
}
