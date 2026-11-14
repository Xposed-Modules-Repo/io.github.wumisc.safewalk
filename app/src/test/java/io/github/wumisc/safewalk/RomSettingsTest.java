package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * 首页「系统 UI 适配」选择器的判定测试（4.3）。
 *
 * 这一层取代了原来的 HyperOS 3/4 三芯片，最容易犯的错不是功能写错，而是
 * **旧配置把用户锁死**：老版本存过 `hyperOsMode=hos3`，如果新代码把无法识别的
 * override 原样保留，界面就会出现「六个芯片一个都不亮」，而用户点哪儿都改不掉。
 * 所以下面第一组测试专门打这个点。
 */
public class RomSettingsTest {

    // ---------- normalize：非法值一律收回 auto ----------

    @Test
    public void nullAndBlankBecomeAuto() {
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize(null));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize(""));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("   "));
    }

    @Test
    public void autoIsCaseInsensitive() {
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("auto"));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("AUTO"));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize(" Auto "));
    }

    @Test
    public void legacyHyperOsModesFallBackToAuto() {
        // 4.0~4.2 存在过的两档，现在一个都不该留下 —— 留着就等于把老用户
        // 锁在一个既没有 UI 可改、也不影响任何行为的模式里。
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("hos3"));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("hos4"));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("HOS4"));
    }

    @Test
    public void garbageFallsBackToAuto() {
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("coloros15"));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("xiaomi"));
        assertEquals(RomSettings.OVERRIDE_AUTO, RomSettings.normalize("123"));
    }

    @Test
    public void everyRomIsSelectable() {
        for (RomId id : RomId.values()) {
            assertEquals("手选的这几家必须都能存下来：" + id,
                    id.name(), RomSettings.normalize(id.name()));
            assertEquals("小写也要认：" + id,
                    id.name(), RomSettings.normalize(id.name().toLowerCase()));
        }
    }

    // ---------- overrideId / resolve：手动优先 ----------

    @Test
    public void autoHasNoOverrideId() {
        assertNull(RomSettings.overrideId("auto"));
        assertNull(RomSettings.overrideId(null));
    }

    @Test
    public void manualOverrideWins() {
        assertEquals(RomId.COLOROS, RomSettings.resolve("COLOROS", RomId.HYPEROS));
        assertEquals(RomId.AOSP, RomSettings.resolve("AOSP", RomId.MAGICOS));
    }

    @Test
    public void autoFallsThroughToDetected() {
        assertEquals(RomId.ORIGINOS, RomSettings.resolve("auto", RomId.ORIGINOS));
        assertEquals(RomId.MAGICOS, RomSettings.resolve(null, RomId.MAGICOS));
        // 旧值同样不能压住自动识别
        assertEquals(RomId.MAGICOS, RomSettings.resolve("hos3", RomId.MAGICOS));
    }

    // ---------- caption：只有一行，必须说清楚「自动 / 手动」 ----------

    @Test
    public void captionDistinguishesAutoFromManual() {
        String auto = RomSettings.caption(RomId.HYPEROS, "auto");
        assertTrue("自动时必须写明自动识别：" + auto, auto.contains("自动识别"));
        assertTrue("自动时不能出现手动字样：" + auto, !auto.contains("手动"));

        String manual = RomSettings.caption(RomId.COLOROS, "COLOROS");
        assertTrue("手动时必须写明手动指定：" + manual, manual.contains("手动指定"));
        assertTrue("必须写明只影响指路，否则会被当成「兼容模式」：" + manual,
                manual.contains("仅影响指路"));
    }

    @Test
    public void captionUsesTheEffectiveNameOnly() {
        // 五家文案不能串台：选了 ColorOS 却显示 HyperOS 是最刺眼的 bug。
        // 这里断言的是芯片短名 —— 恰好也锁住「caption 必须用 shortName」这条约定，
        // 改回 displayName 会让 AOSP 那条变长并在真机被截断。
        for (RomId id : RomId.values()) {
            for (String raw : new String[]{"auto", id.name()}) {
                String c = RomSettings.caption(id, raw);
                assertTrue("文案里应出现 " + id.getShortName() + "：" + c,
                        c.contains(id.getShortName()));
                for (RomId other : RomId.values()) {
                    if (other == id) continue;
                    assertFalse("文案串台了（" + id + " 里出现了 " + other + "）：" + c,
                            c.contains(other.getShortName()));
                }
            }
        }
    }

    @Test
    public void unrecognizedVendorDoesNotClaimStockAndroid() {
        // 三星/摩托这类未收录厂商在判定上落到 AOSP，但对用户说「本机 UI：原生」是错的
        String c = RomSettings.caption(RomId.AOSP, "auto", true);
        assertTrue("必须承认没认出来：" + c, c.contains("未识别"));
        assertTrue("必须给出可操作提示：" + c, c.contains("手动指定"));
        assertFalse("不能断言用户用的是原生：" + c, c.contains("本机 UI：原生"));

        // 手动选了「原生」就按用户说的来，不能再写未识别
        String manual = RomSettings.caption(RomId.AOSP, "AOSP", true);
        assertTrue(manual, manual.contains("手动指定"));
        assertFalse("用户自己选了，就不要再说未识别：" + manual, manual.contains("未识别"));
    }

    @Test
    public void captionFitsInOneLineOn360dp() {
        /*
         * 首页那一行是 maxLines=1 + ellipsize=end，装不下就被截成「…手动指定（仅影…」，
         * 用户看到的是一句断掉的话。360dp 机型、卡片左右各 14dp padding 后可用约 332dp，
         * 字号 12.5sp —— 折算成 26.5 个「全角宽度」。
         *
         * 这里用估算而不是真测量：中文/全角按 1.0，ASCII 按 0.55（Roboto 平均值）。
         * 估算偏保守即可，目的是拦住「文案又变长了」这种回归，不追求像素级准确。
         */
        String[] raws = {"auto", RomId.HYPEROS.name(), RomId.AOSP.name()};
        for (RomId id : RomId.values()) {
            for (String raw : raws) {
                for (boolean unrecognized : new boolean[]{false, true}) {
                    String c = RomSettings.caption(id, raw, unrecognized);
                    double em = widthInEm(c);
                    assertTrue("这一行会被截断（约 " + String.format("%.1f", em)
                                    + " em > 26.5）：" + c,
                            em <= 26.5);
                }
            }
        }
    }

    /** 粗略估算字符串宽度（单位：个全角字符）。 */
    private static double widthInEm(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            w += (ch < 128) ? 0.55 : 1.0;
        }
        return w;
    }

    @Test
    public void captionIsOneLine() {
        // 布局里 maxLines=1 + ellipsize=end，换行符会被吃掉一半，等于白写
        for (RomId id : RomId.values()) {
            assertFalse("注明行不能含换行：" + id, RomSettings.caption(id, id.name()).contains("\n"));
            assertFalse("注明行不能含换行：" + id, RomSettings.caption(id, "auto").contains("\n"));
        }
    }

    // ---------- explain：日志要能还原「用户的意图」 ----------

    @Test
    public void explainMentionsIgnoredDetectionWhenManual() {
        String s = RomSettings.explain(RomId.COLOROS, "COLOROS", RomId.HYPEROS);
        assertTrue(s, s.contains("手动指定"));
        assertTrue("必须写出被忽略的自动结果，否则排查时看不出「为什么跟 Build 不符」：" + s,
                s.contains("HyperOS"));
        assertTrue(s, s.contains("已忽略"));
    }

    @Test
    public void explainOnAutoDoesNotMentionOverride() {
        String s = RomSettings.explain(RomId.MAGICOS, "auto", RomId.MAGICOS);
        assertTrue(s, s.contains("自动识别"));
        assertFalse(s, s.contains("忽略"));
    }

    // ---------- 短名：只进那一行注明，必须短且单行 ----------

    @Test
    public void shortNamesStayReadable() {
        // 4.3 起芯片上写的是**厂商短名**（小米/荣耀/OPPO/vivo/华为/原生），
        // shortName 只出现在那一行注明里，所以这里不再按「芯片格宽」卡 8 个字符，
        // 而是卡「一行注明里给得起多少」：9 个 ASCII 字符（HarmonyOS）已经是最长的了。
        for (RomId id : RomId.values()) {
            String n = id.getShortName();
            assertNotNull("短名不能为空：" + id, n);
            assertFalse("短名不能为空：" + id, n.trim().isEmpty());
            assertFalse("短名不能带换行：" + id, n.contains("\n"));
            assertTrue("短名太长会把注明行挤到省略号：" + id + " → " + n,
                    n.length() <= 9);
        }
    }

    @Test
    public void shortNameAndDisplayNameAgreeForVendors() {
        /*
         * 短名必须是 displayName 的前缀：两者描述的是同一家，不能各说各话
         * （例如 displayName 写「HarmonyOS / EMUI」、短名写「鸿蒙」，用户就得多想一步）。
         *
         * 例外是「displayName 用英文、芯片用中文」的三家：AOSP / FLYME / ONEUI。
         * 首页芯片三行三列，中文短名在视觉上比英文更整齐（「原生」「魅族」「三星」
         * 与「小米」「荣耀」同宽），而 displayName 要出现在「本机 UI：X」这句
         * 注明里，用 Flyme / One UI 比用「魅族」「三星」更准确（那是 UI 名，不是厂商名）。
         */
        java.util.Set<RomId> chineseChip = java.util.EnumSet.of(
                RomId.AOSP, RomId.FLYME, RomId.ONEUI);
        for (RomId id : RomId.values()) {
            if (chineseChip.contains(id)) {
                String expect;
                if (id == RomId.AOSP) expect = "原生";
                else if (id == RomId.FLYME) expect = "魅族";
                else expect = "三星";
                assertEquals("中文短名与预期不符：" + id, expect, id.getShortName());
            } else {
                assertTrue(id + " 的短名应是 displayName 的前缀：" + id.getShortName()
                                + " vs " + id.getDisplayName(),
                        id.getDisplayName().startsWith(id.getShortName()));
            }
        }
    }

    @Test
    public void thereAreExactlySixVendorsPlusStock() {
        // 选择器是「自动 + 七厂商 + 原生」九格（5.2.0 起三行三列）；多一个少一个都会让布局错位
        assertEquals(8, RomId.values().length);
        long vendors = Arrays.stream(RomId.values()).filter(id -> id != RomId.AOSP).count();
        assertEquals("除原生外必须正好七家厂商", 7, vendors);
    }
}
