package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 控制中心磁贴的状态判定回归测试。
 *
 * 磁贴管的是「自动跟随步行」（rearCamAuto），不是行走模式总开关（enableRearCamera）。
 * 判错两个方向都有代价：
 * ① 只开了常显、自动跟随没开，磁贴却显示激活 → 用户站着不动也一直挂着小窗，
 *    和「自动跟随」四个字完全矛盾；
 * ② 自动跟随开着却显示「关」→ 用户反复点，每次都被当成「开启」，永远关不掉。
 * 另外状态值必须与 `Tile.STATE_*` 数值一致（0/1/2），否则磁贴外观会错乱。
 */
public class WalkTileStatePolicyTest {

    @Test
    public void stateValuesMatchSystemConstants() {
        // Tile.STATE_UNAVAILABLE=0 / STATE_INACTIVE=1 / STATE_ACTIVE=2（android.service.quicksettings.Tile）
        assertEquals(0, WalkTileStatePolicy.UNAVAILABLE);
        assertEquals(1, WalkTileStatePolicy.INACTIVE);
        assertEquals(2, WalkTileStatePolicy.ACTIVE);
    }

    @Test
    public void masterOffAlwaysUnavailable() {
        assertEquals(WalkTileStatePolicy.UNAVAILABLE,
                WalkTileStatePolicy.resolve(false, false, false));
        // 总开关关着时，残留的自动跟随也必须置灰：点了也不会生效
        assertEquals(WalkTileStatePolicy.UNAVAILABLE,
                WalkTileStatePolicy.resolve(false, true, true));
    }

    @Test
    public void autoFollowOnIsActive() {
        assertEquals(WalkTileStatePolicy.ACTIVE,
                WalkTileStatePolicy.resolve(true, true, true));
    }

    @Test
    public void previewOnButManualIsNotActive() {
        // 只在 App 里开了「路况预览」的常显档：磁贴必须是灰的，
        // 因为自动跟随并没有开
        assertEquals(WalkTileStatePolicy.INACTIVE,
                WalkTileStatePolicy.resolve(true, true, false));
    }

    @Test
    public void everythingOffIsInactive() {
        assertEquals(WalkTileStatePolicy.INACTIVE,
                WalkTileStatePolicy.resolve(true, false, false));
    }

    @Test
    public void subtitleExplainsWhatWillHappen() {
        assertTrue(WalkTileStatePolicy.subtitle(false, true, true, true).contains("总开关"));
        assertTrue(WalkTileStatePolicy.subtitle(true, false, false, false).contains("点击开启"));
        assertTrue(WalkTileStatePolicy.subtitle(true, true, false, true).contains("点击开启"));
        assertTrue(WalkTileStatePolicy.subtitle(true, true, true, true).contains("走起来"));
        // 自动跟随开着但服务没活：不能只说「已开启」，否则用户对着黑屏无从下手
        assertTrue(WalkTileStatePolicy.subtitle(true, true, true, false).contains("预览未运行"));
    }
}
