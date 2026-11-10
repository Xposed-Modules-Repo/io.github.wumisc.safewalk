package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 控制中心磁贴的状态判定回归测试。
 *
 * 判错两个方向都有代价：
 * ① 总开关关着却显示「可点」→ 用户点了没反应，以为磁贴是坏的；
 * ② 开关开着却显示「关」→ 用户反复点，每次都被当成「开启」，永远关不掉。
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
                WalkTileStatePolicy.resolve(false, false));
        // 总开关关着时，行走模式残留为开也必须置灰：点了也不会生效
        assertEquals(WalkTileStatePolicy.UNAVAILABLE,
                WalkTileStatePolicy.resolve(false, true));
    }

    @Test
    public void walkOnIsActive() {
        assertEquals(WalkTileStatePolicy.ACTIVE,
                WalkTileStatePolicy.resolve(true, true));
    }

    @Test
    public void walkOffIsInactive() {
        assertEquals(WalkTileStatePolicy.INACTIVE,
                WalkTileStatePolicy.resolve(true, false));
    }

    @Test
    public void subtitleExplainsWhatWillHappen() {
        assertTrue(WalkTileStatePolicy.subtitle(false, false, false).contains("总开关"));
        assertTrue(WalkTileStatePolicy.subtitle(true, false, false).contains("点击开启"));
        assertTrue(WalkTileStatePolicy.subtitle(true, true, true).contains("已开启"));
        // 配置开着但服务没活：不能只说「已开启」，否则用户对着黑屏无从下手
        assertTrue(WalkTileStatePolicy.subtitle(true, true, false).contains("预览未运行"));
    }
}
