package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * tokenHash4 回归（5.6.10 修复：toHexString 不补零导致哈希偶尔只有 3 位）。
 *
 * <p>旧实现 {@code Integer.toHexString(h and 0xFFFF)} 在高四位为 0 时输出不足 4 位，
 * 随机 token 约 1/16 概率命中——DisplaySecurityTest 因此偶发失败。
 * 这里用确定性搜索构造命中样本，锁死「任何非空 token 恒为 4 位」。
 */
public class TokenHash4Test {

    private static int hash16(String token) {
        int h = 0;
        for (char c : token.toCharArray()) h = 31 * h + c;
        return h & 0xFFFF;
    }

    @Test
    public void emptyAndNull_areFourDashes() {
        assertEquals("----", EmergencyDiag.tokenHash4(null));
        assertEquals("----", EmergencyDiag.tokenHash4(""));
    }

    @Test
    public void highNibbleZero_stillExactlyFourChars() {
        // 确定性构造高四位为 0 的 token（旧实现在这里输出 3 位）
        String found = null;
        for (int i = 0; i < 200_000; i++) {
            String t = "evt-pad-" + i;
            if ((hash16(t) & 0xF000) == 0) { found = t; break; }
        }
        assertTrue("20 万个样本内必然能构造出高四位为 0 的 token", found != null);
        String hash = EmergencyDiag.tokenHash4(found);
        assertEquals("高四位为 0 时仍必须恰好 4 位（补零）", 4, hash.length());
        assertTrue("必须以 0 开头", hash.startsWith("0"));
    }

    @Test
    public void alwaysFourHexChars_andNeverEqualsToken() {
        for (int i = 0; i < 5_000; i++) {
            String t = "token-" + i + "-" + (i * 31);
            String hash = EmergencyDiag.tokenHash4(t);
            assertEquals("任意 token 恒为 4 位", 4, hash.length());
            assertTrue("必须是十六进制小写", hash.matches("[0-9a-f]{4}"));
            assertFalse("哈希不得等于完整 token", hash.equals(t));
        }
    }

    @Test
    public void stableAcrossCalls() {
        String t = "evt-stable";
        assertEquals(EmergencyDiag.tokenHash4(t), EmergencyDiag.tokenHash4(t));
    }
}
