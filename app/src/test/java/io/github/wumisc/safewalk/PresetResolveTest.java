package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 智能方案判定回归测试。
 *
 * 用户提的两件事，这里一条用例对应一件：
 * ① 「不能按照深色模式跟随来启用夜间或者白天吗」→ 深色模式必须能单独决定；
 * ② 「没开深色模式的用户，可以增加一个时间选择」→ 时段必须能单独决定，且跨零点；
 * ③ 另外锁一条容易被忽略的：**白天开着深色模式时不能加曝光**，
 *    否则 +2EV 会把白天的路面直接曝成一片白，比不增强还糟。
 */
public class PresetResolveTest {

    // ---------- 夜间时段判定 ----------

    @Test
    public void nightWindowAcrossMidnight() {
        // 22:00~6:00 是默认值，覆盖跨零点
        assertTrue(PresetResolve.isNightHour(22, 22, 6));
        assertTrue(PresetResolve.isNightHour(23, 22, 6));
        assertTrue(PresetResolve.isNightHour(0, 22, 6));
        assertTrue(PresetResolve.isNightHour(5, 22, 6));
    }

    @Test
    public void dayHoursAreNotNight() {
        assertFalse(PresetResolve.isNightHour(6, 22, 6));
        assertFalse(PresetResolve.isNightHour(12, 22, 6));
        assertFalse(PresetResolve.isNightHour(21, 22, 6));
    }

    @Test
    public void sameStartAndEndMeansDisabled() {
        // 起点=终点 表示「不想按时间猜」，比要求用户拖成相反数自然
        for (int h = 0; h < 24; h++) {
            assertFalse("start==end 时任何小时都不算夜间", PresetResolve.isNightHour(h, 10, 10));
        }
    }

    @Test
    public void nonWrappingWindowWorks() {
        // 白天窗口写法（6:00~18:00）也要算得对，虽然 UI 不直接提供
        assertTrue(PresetResolve.isNightHour(9, 6, 18));
        assertFalse(PresetResolve.isNightHour(19, 6, 18));
        assertFalse(PresetResolve.isNightHour(23, 6, 18));
    }

    // ---------- 方案判定 ----------

    @Test
    public void darkModeAtNightGivesNightWithLowLight() {
        PresetResolve.Params p = PresetResolve.resolveParams(true, 23, 22, 6);
        assertEquals(PresetResolve.NIGHT, p.kind);
        assertTrue("真夜间必须开低光增强", p.lowLight);
        assertTrue("夜间必须开画面增强", p.screenEnhance);
    }

    /** 白天 + 深色模式：保留夜间视觉风格，但**不许**加曝光，否则白天直接过曝 */
    @Test
    public void darkModeDuringDayDropsExposure() {
        PresetResolve.Params p = PresetResolve.resolveParams(true, 13, 22, 6);
        assertEquals(PresetResolve.NIGHT, p.kind);
        assertFalse("白天开深色模式不能加曝光", p.lowLight);
        assertTrue("视觉增强可以保留", p.screenEnhance);
    }

    @Test
    public void lightModeAtNightStillNight() {
        // 系统常年浅色的用户，靠时段兜底
        PresetResolve.Params p = PresetResolve.resolveParams(false, 23, 22, 6);
        assertEquals(PresetResolve.NIGHT, p.kind);
        assertTrue(p.lowLight);
    }

    @Test
    public void lightModeDuringDayIsDay() {
        PresetResolve.Params p = PresetResolve.resolveParams(false, 13, 22, 6);
        assertEquals(PresetResolve.DAY, p.kind);
        assertFalse(p.lowLight);
        assertFalse("白天不做算法增强，省电也省算力", p.screenEnhance);
    }

    // ---------- 参数表 ----------

    @Test
    public void nightPresetIsTheStrongest() {
        PresetResolve.Params night = PresetResolve.params(PresetResolve.NIGHT);
        PresetResolve.Params day = PresetResolve.params(PresetResolve.DAY);
        PresetResolve.Params plain = PresetResolve.params(PresetResolve.PLAIN);

        assertTrue("夜间取景要比白天收得更近", night.framing > day.framing);
        assertTrue("夜间描边要比白天粗", night.borderDp > day.borderDp);
        assertTrue("夜间暗部要提得比白天多", night.screenShadowLift > day.screenShadowLift);
        assertTrue("夜间要降饱和去噪点", night.screenSaturation < 50);
        assertTrue("极简什么都不加", !plain.screenEnhance && !plain.lowLight && plain.borderDp == 0);
        assertEquals("极简不描边", 0, plain.borderDp);
    }

    @Test
    public void unknownPresetFallsBackToDay() {
        // 老版本配置里可能留着已删掉的方案名，绝不能因为读不懂就崩
        assertEquals(PresetResolve.DAY, PresetResolve.params("something-old").kind);
        assertEquals(PresetResolve.DAY, PresetResolve.params(null).kind);
    }

    @Test
    public void reasonIsHumanReadable() {
        assertTrue(PresetResolve.reason(true, 23, 22, 6).contains("夜间"));
        assertTrue(PresetResolve.reason(false, 13, 22, 6).contains("白天"));
    }
}
