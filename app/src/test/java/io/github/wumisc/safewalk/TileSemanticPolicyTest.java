package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 【磁贴语义】步数源决定磁贴是「自动跟随」还是「路况预览开关」。
 *
 * 守住三件事：
 * 1. 有步数 → 自动跟随（行为与 LSPosed 版一致，不能因为改造把老用户体验变差）；
 * 2. 无步数 → 手动预览 + 强制关自动跟随（否则留一个永远不动的假开关）；
 * 3. 判据是**现算**的：权限补授后必须立刻回到自动跟随，不能钉死在手动档。
 */
public class TileSemanticPolicyTest {

    private static final int UNAVAILABLE = 0;
    private static final int INACTIVE = 1;
    private static final int ACTIVE = 2;

    @Test
    public void stepOk_keepsAutoFollowTile() {
        assertEquals(TileSemanticPolicy.AUTO_FOLLOW,
                TileSemanticPolicy.mode(StepSensorPolicy.OK));
        assertFalse(TileSemanticPolicy.isManual(StepSensorPolicy.OK));
        assertFalse("有步数时不能去动用户的自动跟随开关",
                TileSemanticPolicy.shouldForceAutoOff(StepSensorPolicy.OK));
    }

    @Test
    public void noSensor_degradesToManualPreview() {
        assertEquals(TileSemanticPolicy.MANUAL_PREVIEW,
                TileSemanticPolicy.mode(StepSensorPolicy.NO_SENSOR));
        assertTrue(TileSemanticPolicy.shouldForceAutoOff(StepSensorPolicy.NO_SENSOR));
    }

    @Test
    public void noPermission_degradesToManualPreview() {
        assertEquals(TileSemanticPolicy.MANUAL_PREVIEW,
                TileSemanticPolicy.mode(StepSensorPolicy.NO_PERMISSION));
        assertTrue(TileSemanticPolicy.shouldForceAutoOff(StepSensorPolicy.NO_PERMISSION));
    }

    @Test
    public void judgementIsLive_soPermissionGrantRestoresAutoFollow() {
        // 先无权限（手动档），用户去授权后必须立刻回到自动档 —— 这正是「后面又有步数了」的场景
        assertEquals(TileSemanticPolicy.MANUAL_PREVIEW,
                TileSemanticPolicy.mode(StepSensorPolicy.NO_PERMISSION));
        assertEquals(TileSemanticPolicy.AUTO_FOLLOW,
                TileSemanticPolicy.mode(StepSensorPolicy.OK));
    }

    @Test
    public void manualState_followsPreviewMasterSwitch() {
        assertEquals("总开关关着一律置灰", UNAVAILABLE,
                TileSemanticPolicy.manualState(false, true));
        assertEquals("预览开着就亮", ACTIVE, TileSemanticPolicy.manualState(true, true));
        assertEquals("预览关着就灰", INACTIVE, TileSemanticPolicy.manualState(true, false));
    }

    @Test
    public void manualSubtitle_tellsWhatTapDoes() {
        assertEquals("总开关已关闭",
                TileSemanticPolicy.manualSubtitle(false, true, true));
        assertEquals("点击显示路况",
                TileSemanticPolicy.manualSubtitle(true, false, false));
        assertEquals("常显中 · 点击关闭",
                TileSemanticPolicy.manualSubtitle(true, true, true));
    }
}
