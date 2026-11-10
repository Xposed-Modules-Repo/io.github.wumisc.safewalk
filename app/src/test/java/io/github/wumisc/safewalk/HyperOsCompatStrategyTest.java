package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * HyperOS 适配策略的防回归测试。
 *
 * 这里锁的是「4.0 之前踩过的两个坑」，不是具体实现细节：
 *
 * 1. **不能把前台服务类型降成不带相机语义的 `plain`**。3.3~3.5 的代码里写着
 *    「HOS4 → plain」，但那等于关掉后台相机（Android 11 起 CAMERA 属于
 *    while-in-use 权限），小窗会彻底拍不到画面 —— 这叫阉割，不叫适配。
 *    现在必须是 camera 优先的降级链。
 *
 * 2. **HOS3 与 HOS4 必须走同一条链**。旧版给人「两个版本两套代码」的错觉，
 *    实际上只有 HyperOS 大版本号不同、系统限制来源是 Android 15/16；
 *    一旦分成两套，HOS3 就会变成没人跑过、没人维护的死分支。
 *
 * 另外锁住「描述文案里的策略名必须是用户能看懂的中文/事实」，
 * 避免 `releasePreviewFirst`、`strict` 这类内部代号又漏到界面上。
 */
public class HyperOsCompatStrategyTest {

    private static final HyperOsCompat.Os HOS3 = HyperOsCompat.Os.HOS3;
    private static final HyperOsCompat.Os HOS4 = HyperOsCompat.Os.HOS4;
    private static final HyperOsCompat.Os OTHER = HyperOsCompat.Os.OTHER;

    @Test
    public void foregroundServiceChainStartsWithCamera() {
        for (HyperOsCompat.Os os : new HyperOsCompat.Os[]{HOS3, HOS4, OTHER}) {
            String s = HyperOsCompat.foregroundServiceStrategy(os);
            assertTrue("所有 ROM 都必须 camera 优先：" + os + " -> " + s, s.startsWith("camera"));
            // 不能出现「一开始就用 plain」那种把相机能力去掉的写法
            assertFalse("不允许把 plain 当作首选类型：" + s, s.startsWith("plain"));
            // 降级必须存在，否则 HOS4 上被拒就彻底起不来服务
            assertTrue("降级链要包含 specialUse：" + s, s.contains("specialUse"));
        }
    }

    @Test
    public void hos3AndHos4ShareTheSameChain() {
        assertEquals("HyperOS 3 与 4 必须共用同一条运行时链路，禁止分裂成两套代码",
                HyperOsCompat.foregroundServiceStrategy(HOS3),
                HyperOsCompat.foregroundServiceStrategy(HOS4));
    }

    @Test
    public void torchIsHandledInsidePreviewSession() {
        // 3.2 起手电统一开在预览会话内（FLASH_MODE_TORCH），
        // 不存在「先释放预览再点灯」这一步，旧的 releasePreviewFirst 会误导维护者。
        for (HyperOsCompat.Os os : new HyperOsCompat.Os[]{HOS3, HOS4, OTHER}) {
            String s = HyperOsCompat.torchOpenStrategy(os);
            assertEquals("inSession", s);
            assertFalse(s.contains("releasePreviewFirst"));
        }
    }

    @Test
    public void overlayPermissionIsVerifiedBeforeEveryShow() {
        for (HyperOsCompat.Os os : new HyperOsCompat.Os[]{HOS3, HOS4, OTHER}) {
            assertEquals("verifyBeforeShow", HyperOsCompat.overlayPermissionStrategy(os));
        }
    }

    @Test
    public void describeIsHumanReadable() {
        String d3 = HyperOsCompat.describe(HOS3, HyperOsCompat.MODE_HOS3);
        String d4 = HyperOsCompat.describe(HOS4, HyperOsCompat.MODE_AUTO);
        assertTrue("要写清是哪个版本：" + d3, d3.contains("HyperOS 3"));
        assertTrue("要写清是哪个版本：" + d4, d4.contains("HyperOS 4"));
        // 内部代号不该出现在给用户看的摘要里
        for (String d : new String[]{d3, d4}) {
            assertFalse("摘要里不能出现内部代号 plain：" + d, d.contains("plain"));
            assertFalse("摘要里不能出现内部代号 strict：" + d, d.contains("strict"));
            assertFalse("摘要里不能出现内部代号 releasePreviewFirst：" + d,
                    d.contains("releasePreviewFirst"));
        }
    }

    @Test
    public void supportNoteAnswersWhetherHos3Works() {
        String n3 = HyperOsCompat.supportNote(HOS3);
        String n4 = HyperOsCompat.supportNote(HOS4);
        assertTrue("要明确回答 HOS3 是否可用：" + n3, n3.contains("HyperOS 3"));
        assertTrue(n3.contains("标准路径"));
        assertTrue(n4.contains("降级"));
        // 不许出现「完美支持 / 100%」这类无法验证的承诺
        for (String n : new String[]{n3, n4}) {
            assertFalse(n.contains("完美"));
            assertFalse(n.contains("100%"));
        }
    }
}
