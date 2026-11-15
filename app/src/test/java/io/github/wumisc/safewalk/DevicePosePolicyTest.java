package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 重力姿态策略单测。
 *
 * 这些断言守的是真机上最难发现的一类问题：**方向**。
 * 代码不抛异常、lint 全绿、编译通过，只有把手机横过来才知道路是不是躺着的；
 * 而「走路时窗口每秒横竖跳一次」更是任何静态检查都看不出来。
 */
public class DevicePosePolicyTest {

    /** 重力加速度，用于构造指定倾角的样本 */
    private static final float G = 9.8f;

    /** 按「设备逆时针转 theta 度」构造加速度计读数 */
    private static float[] sample(double thetaDeg) {
        double r = Math.toRadians(thetaDeg);
        return new float[]{(float) (G * Math.sin(r)), (float) (G * Math.cos(r)), 0f};
    }

    // ==================== 原始角度 ====================

    @Test
    public void fourCardinalPoses() {
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, DevicePosePolicy.rawDegrees(0f, G));
        assertEquals(DevicePosePolicy.DEG_LANDSCAPE_CCW, DevicePosePolicy.rawDegrees(G, 0f));
        assertEquals(DevicePosePolicy.DEG_UPSIDE_DOWN, DevicePosePolicy.rawDegrees(0f, -G));
        assertEquals(DevicePosePolicy.DEG_LANDSCAPE_CW, DevicePosePolicy.rawDegrees(-G, 0f));
    }

    @Test
    public void tiltedSamplesRoundToNearestQuarter() {
        // 走路时手腕会带 10~20° 的倾斜，不能因此被判成横屏
        float[] a = sample(20);
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, DevicePosePolicy.rawDegrees(a[0], a[1]));
        float[] b = sample(70);
        assertEquals(DevicePosePolicy.DEG_LANDSCAPE_CCW, DevicePosePolicy.rawDegrees(b[0], b[1]));
    }

    @Test
    public void flatDeviceIsDetected() {
        assertTrue("屏幕朝上", DevicePosePolicy.isFlat(0f, 0f, G));
        assertTrue("屏幕朝下", DevicePosePolicy.isFlat(0f, 0f, -G));
        assertFalse("竖着拿", DevicePosePolicy.isFlat(0f, G, 0f));
        assertFalse("斜着拿", DevicePosePolicy.isFlat(0f, 7.0f, 6.9f));
    }

    @Test
    public void angleDistanceWrapsAroundZero() {
        // 0 与 270 其实只差 90°，不绕圈算会得出 270
        assertEquals(90.0, DevicePosePolicy.angleDistance(270.0, 0), 0.001);
        assertEquals(90.0, DevicePosePolicy.angleDistance(0.0, 270), 0.001);
        assertEquals(10.0, DevicePosePolicy.angleDistance(10.0, 0), 0.001);
    }

    // ==================== 跟踪器：迟滞 + 驻留 ====================

    @Test
    public void firstSampleIsAdoptedImmediately() {
        DevicePosePolicy.PoseTracker t = new DevicePosePolicy.PoseTracker();
        float[] a = sample(90);
        assertEquals("首帧就该定下形状，否则会先竖后横闪一下",
                DevicePosePolicy.DEG_LANDSCAPE_CCW, t.update(a[0], a[1], a[2], 0L));
    }

    @Test
    public void wristWobbleDoesNotFlipTheWindow() {
        DevicePosePolicy.PoseTracker t = new DevicePosePolicy.PoseTracker();
        long now = 0L;
        float[] p = sample(0);
        t.update(p[0], p[1], p[2], now);
        // 走到 46°（越过 45° 分界，但在 53° 迟滞带内），连续喂 3 秒
        float[] a = sample(46);
        for (int i = 0; i < 30; i++) {
            now += 100L;
            assertEquals("迟滞带内不许换朝向", DevicePosePolicy.DEG_PORTRAIT,
                    t.update(a[0], a[1], a[2], now));
        }
    }

    @Test
    public void realRotationSwitchesOnlyAfterDwell() {
        DevicePosePolicy.PoseTracker t = new DevicePosePolicy.PoseTracker();
        long now = 0L;
        float[] p = sample(0);
        t.update(p[0], p[1], p[2], now);
        float[] land = sample(70);   // 明确越界
        // 驻留期内仍然是竖屏（候选从 now+100 这一帧开始计时）
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, t.update(land[0], land[1], land[2], now + 100L));
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, t.update(land[0], land[1], land[2], now + 400L));
        // 差一毫秒也不换
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, t.update(land[0], land[1], land[2],
                now + 100L + DevicePosePolicy.DWELL_MS - 1));
        // 满 450ms 才生效
        assertEquals(DevicePosePolicy.DEG_LANDSCAPE_CCW,
                t.update(land[0], land[1], land[2], now + 100L + DevicePosePolicy.DWELL_MS));
    }

    @Test
    public void briefOvershootDoesNotStick() {
        DevicePosePolicy.PoseTracker t = new DevicePosePolicy.PoseTracker();
        long now = 0L;
        float[] p = sample(0);
        t.update(p[0], p[1], p[2], now);
        float[] land = sample(70);
        t.update(land[0], land[1], land[2], now + 100L);
        // 又回到竖屏（转身过冲后回正）：候选被清掉，不该还记着横屏
        float[] back = sample(5);
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, t.update(back[0], back[1], back[2], now + 300L));
        assertEquals("候选清空后要重新计满驻留",
                DevicePosePolicy.DEG_PORTRAIT,
                t.update(land[0], land[1], land[2], now + 500L));
    }

    @Test
    public void flatSampleKeepsLastPose() {
        DevicePosePolicy.PoseTracker t = new DevicePosePolicy.PoseTracker();
        long now = 0L;
        float[] land = sample(90);
        t.update(land[0], land[1], land[2], now);
        // 手机放到桌上：屏幕朝天，ax/ay 只剩噪声，不能据此把窗口转回去
        assertEquals(DevicePosePolicy.DEG_LANDSCAPE_CCW, t.update(0.3f, -0.2f, G, now + 1000L));
    }

    @Test
    public void resetClearsState() {
        DevicePosePolicy.PoseTracker t = new DevicePosePolicy.PoseTracker();
        float[] land = sample(90);
        t.update(land[0], land[1], land[2], 0L);
        t.reset();
        assertEquals(DevicePosePolicy.NO_SAMPLE, t.stable());
        float[] p = sample(0);
        assertEquals(DevicePosePolicy.DEG_PORTRAIT, t.update(p[0], p[1], p[2], 10L));
    }

    // ==================== 窗口形状 ====================

    @Test
    public void windowFollowsPoseExceptWhenLocked() {
        assertFalse(DevicePosePolicy.isLandscapeWindow(0, PreviewOrientationPolicy.MODE_AUTO));
        assertTrue(DevicePosePolicy.isLandscapeWindow(90, PreviewOrientationPolicy.MODE_AUTO));
        assertTrue(DevicePosePolicy.isLandscapeWindow(270, PreviewOrientationPolicy.MODE_AUTO));
        assertFalse("倒置仍是竖条", DevicePosePolicy.isLandscapeWindow(180, PreviewOrientationPolicy.MODE_AUTO));
        assertFalse("锁定竖屏：横着拿也不换形状",
                DevicePosePolicy.isLandscapeWindow(90, PreviewOrientationPolicy.MODE_FIXED));
        assertTrue("翻转档只改画面，不改窗口形状",
                DevicePosePolicy.isLandscapeWindow(90, PreviewOrientationPolicy.MODE_FLIP));
    }

    @Test
    public void windowSizeSwapsSides() {
        int[] portrait = DevicePosePolicy.windowSize(150, 260f / 150f, false);
        assertEquals(150, portrait[0]);
        assertEquals(260, portrait[1]);
        int[] landscape = DevicePosePolicy.windowSize(150, 260f / 150f, true);
        assertEquals(260, landscape[0]);
        assertEquals(150, landscape[1]);
    }

    @Test
    public void windowSizeKeepsSquareUsable() {
        // 正方形比例（未来若开放）也不能算出 0
        int[] s = DevicePosePolicy.windowSize(120, 1f, true);
        assertEquals(120, s[0]);
        assertEquals(120, s[1]);
    }
}
