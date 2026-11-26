package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 【无障碍 · HUD】播报策略契约。
 *
 * 背景：小窗的 HUD 与步数徽章是两个 TextView，视觉用户一眼看全，读屏用户默认一个字都听不到；
 * 若每个视图各念一句，又会被拆成「路况预览中」「今日 12 步」两条碎片。
 * 这里锁死两件事：合成一句人话、别把走路当成播报节拍器。
 */
@RunWith(JUnit4.class)
public class HudA11yPolicyTest {

    @Test
    public void compose_mergesHudAndStepsIntoOneSentence() {
        assertEquals("路况预览中，今日 128 步",
                HudA11yPolicy.compose("路况预览中", "今日 128 步"));
    }

    @Test
    public void compose_handlesBlankAndNull() {
        assertEquals("今日 12 步", HudA11yPolicy.compose(null, "今日 12 步"));
        assertEquals("路况预览中", HudA11yPolicy.compose("路况预览中  ", "  "));
        assertEquals("", HudA11yPolicy.compose(null, null));
    }

    @Test
    public void announce_firstTimeAlways() {
        assertTrue(HudA11yPolicy.shouldAnnounce(null, "路况预览中", 1000L, 0L, 0L));
    }

    @Test
    public void announce_sameTextNeverRepeats() {
        assertFalse(HudA11yPolicy.shouldAnnounce("今日 12 步", "今日 12 步", 5000L, 1000L, 40L));
    }

    @Test
    public void announce_throttlesWithinThreeSeconds() {
        // 步数变了但 1 秒内：不播，避免走路时读屏被刷
        assertFalse(HudA11yPolicy.shouldAnnounce("今日 12 步", "今日 13 步", 2000L, 1000L, 1L));
        // 过了 3 秒：播
        assertTrue(HudA11yPolicy.shouldAnnounce("今日 12 步", "今日 20 步", 4000L, 1000L, 8L));
    }

    @Test
    public void announce_milestoneBreaksThrottle() {
        // 3 秒未到，但累计走了 50 步 → 允许插播一次
        assertTrue(HudA11yPolicy.shouldAnnounce("今日 12 步", "今日 62 步", 2000L, 1000L, 50L));
    }

    @Test
    public void announce_emptyTextIsSilent() {
        assertFalse(HudA11yPolicy.shouldAnnounce(null, "", 10_000L, 0L, 0L));
    }

    @Test
    public void announce_clockRollbackStillAllowed() {
        assertTrue(HudA11yPolicy.shouldAnnounce("a", "b", 500L, 1000L, 0L));
    }
}
