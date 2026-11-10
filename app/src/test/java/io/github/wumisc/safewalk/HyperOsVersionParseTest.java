package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * HyperOS 版本号解析与「向前兼容」的防回归测试。
 *
 * 这组测试回答的是用户那句「那会不会影响 HyperOS 4 啊？」背后的真问题：
 * **系统一年一代往前跑，这个 App 会不会跟着每年坏一次？**
 *
 * 锁住三件事：
 *
 * 1. **识别要认得未来的版本**。旧正则写死 `[34]`，遇到 `HyperOS 5.0` 什么都匹配不到，
 *    只能掉进 SDK 兜底——结果看着没错，但日志会写成「未提取到 3/4」，排查时误导人。
 *    现在 `3~6` 都能读出来，`>=4` 统一并进 HOS4 分支（策略同构）。
 *
 * 2. **识别不能把老 prop 误读成新版本**。`V816`（MIUI 14）里明明有 8，`V35` 里有 3，
 *    一旦被读成 HOS3/HOS4，文案就会给用户完全错误的结论。靠 `(?![0-9])` 与 `[3-6]`
 *    的组合挡住，这两条各有一例。
 *
 * 3. **识别错了也伤不到功能**。三个功能策略必须与 ROM 版本无关（另见
 *    {@link HyperOsCompatStrategyTest}），文案里也不能写死「HyperOS 4」而把未来的
 *    HyperOS 5/6 排除在外。
 */
public class HyperOsVersionParseTest {

    // ---------------------------------------------------------------- 能认出 3~6

    @Test
    public void readsHyperOsMajorFromPropName() {
        assertEquals("HyperOS 3.0 要读出 3", 3, HyperOsCompat.majorFrom("HyperOS 3.0"));
        assertEquals("HyperOS 4.0.1.0 要读出 4", 4, HyperOsCompat.majorFrom("HyperOS 4.0.1.0"));
        assertEquals("HyperOS3（无空格）也要读出 3", 3, HyperOsCompat.majorFrom("HyperOS3"));
    }

    @Test
    public void readsFutureHyperOsVersions() {
        // 这是本次新增能力：HyperOS 5 / 6 出现时不需要改正则、不需要改枚举
        assertEquals("HyperOS 5.0.1.0 要读出 5，不能退化成 SDK 兜底",
                5, HyperOsCompat.majorFrom("HyperOS 5.0.1.0"));
        assertEquals("HyperOS 6 要读出 6", 6, HyperOsCompat.majorFrom("HyperOS 6"));
    }

    @Test
    public void readsOsAndVStyleProps() {
        assertEquals("OS4.0.1.0.UNBCNXM 是 HOS4 的常见 incremental",
                4, HyperOsCompat.majorFrom("OS4.0.1.0.UNBCNXM"));
        assertEquals("纯版本号 3.0.1 也要能读", 3, HyperOsCompat.majorFrom("3.0.1"));
        assertEquals("纯版本号 4.0 也要能读", 4, HyperOsCompat.majorFrom("4.0"));
    }

    @Test
    public void scansTheWholePoolBeforeGivingUp() {
        // 第一个参数读不出，第二个能读出 —— 必须继续往后扫，不能第一条失败就放弃
        assertEquals(4, HyperOsCompat.majorFrom("", "UKQ1.231003.002", "HyperOS 4.0"));
    }

    // ---------------------------------------------------------------- 不能误读

    @Test
    public void doesNotMisreadMiuiLegacyProps() {
        // V816 = MIUI 14，里面的 8 一旦被读成版本号就是灾难
        assertEquals("V816 不能被读成版本号", -1, HyperOsCompat.majorFrom("V816"));
        // V35 看起来像 HyperOS 3，实际是 MIUI 的内部版本名
        assertEquals("V35 不能被读成 3", -1, HyperOsCompat.majorFrom("V35"));
        // MIUI 9 (V9)：[3-6] 之外，必须放过
        assertEquals("V9 不能被读成新版本", -1, HyperOsCompat.majorFrom("V9"));
        // MIUI 12 (V12)：多位数，(?![0-9]) 要挡住
        assertEquals("V12 不能被读成 1 或 2", -1, HyperOsCompat.majorFrom("V12"));
    }

    @Test
    public void doesNotMisreadBuildDisplayOrAospFields() {
        assertEquals("AOSP build id 不能被读成版本号", -1,
                HyperOsCompat.majorFrom("UKQ1.231003.002"));
        assertEquals("Android RELEASE 15 不能被读成 1 或 5", -1,
                HyperOsCompat.majorFrom("15"));
        assertEquals("空串要安全返回 -1", -1, HyperOsCompat.majorFrom(""));
    }

    // ---------------------------------------------------------------- 文案要覆盖未来

    @Test
    public void hos4CopyCoversFutureVersions() {
        String note = HyperOsCompat.supportNote(HyperOsCompat.Os.HOS4);
        String desc = HyperOsCompat.describe(HyperOsCompat.Os.HOS4, HyperOsCompat.MODE_AUTO);
        // 仍然要能看出是 HOS4 这一代（既有测试依赖）
        assertTrue("要提到 HyperOS 4：" + note, note.contains("HyperOS 4"));
        // 但不能把 HyperOS 5/6 排除在外
        assertTrue("不能写死 HyperOS 4 一个版本，要写「及更新版本」：" + note,
                note.contains("及更新版本"));
        assertTrue("摘要同理：" + desc, desc.contains("及更新版本"));
        // 老规矩：不许出现无法验证的承诺
        assertFalse(note.contains("完美"));
        assertFalse(note.contains("100%"));
    }

    @Test
    public void futureVersionFallsIntoHos4Branch() {
        // major>=4 一律并进 HOS4：HyperOS 5 绝不能因为"枚举里没有"就退化成 OTHER
        // （OTHER 是最保守策略，会把 camera FGS 的语义也一并降掉）
        assertTrue(HyperOsCompat.majorFrom("HyperOS 5.0") >= 4);
        assertTrue(HyperOsCompat.majorFrom("HyperOS 6.1.2") >= 4);
    }
}
