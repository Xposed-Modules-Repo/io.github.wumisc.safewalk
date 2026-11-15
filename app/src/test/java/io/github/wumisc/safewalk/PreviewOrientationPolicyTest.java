package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 预览方向策略单测。
 *
 * 这些断言全是纯算术，不需要真机、不需要 Robolectric。之所以值得写这么多，
 * 是因为「画面方向」这类 bug 在单元测试里**完全看不出来**：代码不抛异常、
 * Lint 全绿、编译通过，只有把手机横过来才会发现路是躺着的。
 * 把「源图四个角映射到窗口哪里」写成断言，等于把真机那一眼固化下来。
 */
public class PreviewOrientationPolicyTest {

    /** 按 3×3 矩阵算一个点的去向（列向量 [x,y,1]） */
    private static double[] map(float[] m, double x, double y) {
        return new double[]{
                m[0] * x + m[1] * y + m[2],
                m[3] * x + m[4] * y + m[5]
        };
    }

    /**
     * 把「源图上的一个点」按新语义送进矩阵。
     *
     * 【5.3.3】`setTransform` 收的矩阵作用在**窗口坐标**上：系统绘制前已经
     * 把源画面非等比拉伸铺满了整个 View，矩阵只是在它之上再叠一层。
     * 所以要把源坐标换算成「它落在窗口的哪个像素」，再喂进去。
     * 换算式就是系统那一步拉伸：(sx * viewW/srcW, sy * viewH/srcH)。
     */
    private static double[] mapSrc(float[] m, double sx, double sy,
                                   int srcW, int srcH, int viewW, int viewH) {
        return map(m, sx * viewW / (double) srcW, sy * viewH / (double) srcH);
    }

    private static void assertPoint(String why, double ex, double ey, double[] got, double tol) {
        assertEquals(why + " x", ex, got[0], tol);
        assertEquals(why + " y", ey, got[1], tol);
    }

    // ==================== 旋转角 ====================

    @Test
    public void backCameraRotatesWithDisplay() {
        // 后摄横装（90°）：竖握要转 90°，横过来（屏幕 90°）就不用转
        assertEquals(90, PreviewOrientationPolicy.rotationDegrees(90, 0, false));
        assertEquals(0, PreviewOrientationPolicy.rotationDegrees(90, 90, false));
        assertEquals(270, PreviewOrientationPolicy.rotationDegrees(90, 180, false));
        assertEquals(180, PreviewOrientationPolicy.rotationDegrees(90, 270, false));
    }

    @Test
    public void sensor270IsHandled() {
        // 少数机型（部分索尼 / 老 Nexus）传感器是 270°，别把它当成 90 处理
        assertEquals(270, PreviewOrientationPolicy.rotationDegrees(270, 0, false));
        assertEquals(180, PreviewOrientationPolicy.rotationDegrees(270, 90, false));
        assertEquals(0, PreviewOrientationPolicy.rotationDegrees(270, 270, false));
    }

    @Test
    public void frontCameraUsesAddition() {
        // 前摄是镜像安装，方向关系与后摄相反
        assertEquals(90, PreviewOrientationPolicy.rotationDegrees(90, 0, true));
        assertEquals(180, PreviewOrientationPolicy.rotationDegrees(90, 90, true));
    }

    @Test
    public void dirtyAnglesAreRoundedToQuarterTurns() {
        // 传感器上报 89 / 91 这种脏数据时，不能算出「半格」导致画面歪一点
        assertEquals(90, PreviewOrientationPolicy.rotationDegrees(91, 0, false));
        assertEquals(90, PreviewOrientationPolicy.rotationDegrees(89, 0, false));
        assertEquals(0, PreviewOrientationPolicy.rotationDegrees(135, 135, false));
    }

    @Test
    public void displayRotationConstantsMapCorrectly() {
        assertEquals(0, PreviewOrientationPolicy.displayDegrees(0));
        assertEquals(90, PreviewOrientationPolicy.displayDegrees(1));
        assertEquals(180, PreviewOrientationPolicy.displayDegrees(2));
        assertEquals(270, PreviewOrientationPolicy.displayDegrees(3));
        // 非法值不能崩，按 0 处理
        assertEquals(0, PreviewOrientationPolicy.displayDegrees(-1));
        assertEquals(0, PreviewOrientationPolicy.displayDegrees(99));
    }

    @Test
    public void modeOverrides() {
        assertEquals(90, PreviewOrientationPolicy.applyMode(
                90, PreviewOrientationPolicy.MODE_AUTO));
        // 固定竖屏：忽略屏幕方向，永远 0°
        assertEquals(0, PreviewOrientationPolicy.applyMode(
                90, PreviewOrientationPolicy.MODE_FIXED));
        // 翻转：在自动结果上再转半圈
        assertEquals(270, PreviewOrientationPolicy.applyMode(
                90, PreviewOrientationPolicy.MODE_FLIP));
        // 脏值按自动处理
        assertEquals(90, PreviewOrientationPolicy.applyMode(90, "whatever"));
        assertEquals(90, PreviewOrientationPolicy.applyMode(90, null));
        assertEquals(PreviewOrientationPolicy.MODE_AUTO,
                PreviewOrientationPolicy.normalizeMode("AUTO"));
    }

    // ==================== 选预览尺寸 ====================

    @Test
    public void picksClosestAspectWithin1080p() {
        int[][] sizes = {
                {3840, 2160}, {1920, 1080}, {1280, 720}, {640, 480}, {320, 240}
        };
        // 竖屏小窗 150×260：转 90° 后要 1.73 左右的源比例 → 16:9 最接近
        int[] p = PreviewOrientationPolicy.pickPreviewSize(sizes, 150, 260, 90);
        assertNotNull(p);
        assertEquals("应选 1080p 而不是 4K（小窗用不上，白烧电）", 1920, p[0]);
        assertEquals(1080, p[1]);
    }

    @Test
    public void picksByLandscapeAspectWhenNotRotated() {
        int[][] sizes = {{1920, 1080}, {1280, 720}, {640, 480}};
        // 横屏全屏 2400×1080（2.22）：16:9 仍是最接近的一档
        int[] p = PreviewOrientationPolicy.pickPreviewSize(sizes, 2400, 1080, 0);
        assertNotNull(p);
        assertEquals(1920, p[0]);
    }

    @Test
    public void ignoresTooSmallSizes() {
        int[][] sizes = {{320, 240}, {176, 144}};
        assertNull("全是不足 640×480 的档位时宁可不选，让调用方兜底", 
                PreviewOrientationPolicy.pickPreviewSize(sizes, 150, 260, 90));
    }

    @Test
    public void fallsBackToOversizeWhenNothingElseExists() {
        int[][] sizes = {{3840, 2160}};
        int[] p = PreviewOrientationPolicy.pickPreviewSize(sizes, 150, 260, 90);
        assertNotNull("只有 4K 一档时也得用，总比没有画面强", p);
        assertEquals(3840, p[0]);
    }

    @Test
    public void handlesEmptyInput() {
        assertNull(PreviewOrientationPolicy.pickPreviewSize(null, 150, 260, 90));
        assertNull(PreviewOrientationPolicy.pickPreviewSize(new int[0][], 150, 260, 90));
        assertNull(PreviewOrientationPolicy.pickPreviewSize(
                new int[][]{{1920, 1080}}, 0, 0, 90));
    }

    // ==================== 变换矩阵 ====================

    @Test
    public void centerAlwaysMapsToWindowCenter() {
        // 无论转多少度，源图中心都必须落在窗口中心 —— 这是「不跑偏」的底线
        int[][] cases = {{0, 400, 300}, {90, 400, 300}, {180, 400, 300}, {270, 400, 300}};
        for (int[] c : cases) {
            float[] m = PreviewOrientationPolicy.transform(300, 400, c[1], c[2], c[0], 0);
            double[] got = mapSrc(m, c[1] / 2.0, c[2] / 2.0, c[1], c[2], 300, 400);
            assertPoint("rot=" + c[0] + " 中心", 150, 200, got, 0.5);
        }
    }

    @Test
    public void rotate90MapsTopLeftToTopRight() {
        // 源 400×300 转 90° 到 300×400 窗口：cover 恰好为 1，四个角应当严丝合缝
        float[] m = PreviewOrientationPolicy.transform(300, 400, 400, 300, 90, 0);
        assertPoint("源左上 → 屏幕右上", 300, 0, mapSrc(m, 0, 0, 400, 300, 300, 400), 0.5);
        assertPoint("源右上 → 屏幕右下", 300, 400, mapSrc(m, 400, 0, 400, 300, 300, 400), 0.5);
        assertPoint("源左下 → 屏幕左上", 0, 0, mapSrc(m, 0, 300, 400, 300, 300, 400), 0.5);
    }

    @Test
    public void rotate270MapsTopLeftToBottomLeft() {
        float[] m = PreviewOrientationPolicy.transform(300, 400, 400, 300, 270, 0);
        assertPoint("源左上 → 屏幕左下", 0, 400, mapSrc(m, 0, 0, 400, 300, 300, 400), 0.5);
        assertPoint("源右下 → 屏幕右上", 300, 0, mapSrc(m, 400, 300, 400, 300, 300, 400), 0.5);
    }

    @Test
    public void rotate180FlipsBothAxes() {
        float[] m = PreviewOrientationPolicy.transform(400, 300, 400, 300, 180, 0);
        assertPoint("源左上 → 屏幕右下", 400, 300, mapSrc(m, 0, 0, 400, 300, 400, 300), 0.5);
        assertPoint("源中心不动", 200, 150, mapSrc(m, 200, 150, 400, 300, 400, 300), 0.5);
    }

    @Test
    public void coverFillsWindowWithoutBlackBars() {
        // 4:3 源放进 3:4 窗口（不旋转）：必须放大到铺满，两侧被裁
        float[] m = PreviewOrientationPolicy.transform(300, 400, 400, 300, 0, 0);
        double[] tl = mapSrc(m, 0, 0, 400, 300, 300, 400);
        double[] br = mapSrc(m, 400, 300, 400, 300, 300, 400);
        assertTrue("左边必须被裁到屏幕外", tl[0] <= 0.5);
        assertTrue("右边必须被裁到屏幕外", br[0] >= 299.5);
        assertEquals("上下应刚好铺满", 0, tl[1], 0.5);
        assertEquals(400, br[1], 0.5);
    }

    @Test
    public void framingKeepsLowerHalfOnScreen() {
        // 取景拉满：源图下半部分铺满窗口，中心正好落在屏幕上边缘
        float[] m = PreviewOrientationPolicy.transform(300, 400, 300, 400, 0, 100);
        assertPoint("源中心 → 屏幕上边中点", 150, 0, mapSrc(m, 150, 200, 300, 400, 300, 400), 0.5);
        assertPoint("源底边中点 → 屏幕下边中点", 150, 400, mapSrc(m, 150, 400, 300, 400, 300, 400), 0.5);
    }

    @Test
    public void framingIsAppliedOnScreenAxisAfterRotation() {
        // 横屏（不旋转）+ 取景拉满：仍然只留下半部分，不能变成「留右半」
        float[] m = PreviewOrientationPolicy.transform(400, 200, 400, 200, 0, 100);
        assertPoint("源中心 → 上边", 200, 0, mapSrc(m, 200, 100, 400, 200, 400, 200), 0.5);
    }

    @Test
    public void rotatedBufferStillCoversWholeWindow() {
        // 【5.3.3】真机回归：1920×1080 缓冲 → 488×845 竖窗、转 90°。
        // 修复前 scale 被当成「源像素 → 窗口像素」的绝对比例（≈0.45），
        // setTransform 又在这之上再缩一次，画面只剩窗口四成、贴在左上角。
        // 修复后：源图四角换算到窗口坐标，包围盒必须完整包住 0..488 × 0..845。
        int vw = 488, vh = 845, sw = 1920, sh = 1080;
        float[] m = PreviewOrientationPolicy.transform(vw, vh, sw, sh, 90, 0);
        double[] a = mapSrc(m, 0, 0, sw, sh, vw, vh);
        double[] b = mapSrc(m, sw, 0, sw, sh, vw, vh);
        double[] c = mapSrc(m, 0, sh, sw, sh, vw, vh);
        double[] d = mapSrc(m, sw, sh, sw, sh, vw, vh);
        double minX = Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0]));
        double maxX = Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0]));
        double minY = Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]));
        double maxY = Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1]));
        assertTrue("左边不应留白: minX=" + minX, minX <= 0.5);
        assertTrue("右边不应留白: maxX=" + maxX, maxX >= vw - 0.5);
        assertTrue("上边不应留白: minY=" + minY, minY <= 0.5);
        assertTrue("下边不应留白: maxY=" + maxY, maxY >= vh - 0.5);
    }

    @Test
    public void invalidSizeReturnsIdentity() {
        float[] m = PreviewOrientationPolicy.transform(0, 400, 400, 300, 90, 50);
        assertPoint("宽为 0 时原样返回", 12, 34, map(m, 12, 34), 0.001);
    }
}
