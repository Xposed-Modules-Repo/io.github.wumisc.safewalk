package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 【5.7.1】竖条窗「折中填充」按源画面宽高比计算的回归用例。
 *
 * <p><b>背景（5.7.0 真机问题）</b>：小窗竖条 488×845、取景=0% 时，代码把缩放写死 2.0f。
 * 该常数是按 16:9 横画幅定出来的；隐藏超广角 id=3 是 4:3（3264×2448），
 * 沿用 2.0 等于在 4:3 画面上横向再裁一半，超广角的宽视野被吃掉，
 * 用户因此反馈「超广角方案疑似出了问题 / 像没开」。
 *
 * <p><b>测法</b>：A 组用反射调真实 {@code RearCameraOverlay.Companion.compromiseZoomFor}
 * 锁真值表；B 组是源码契约（applyFramingZoom 必须用新函数、并保留 2.0 兜底与 5.4.0 的
 * 固定常量 {@code portraitCompromiseZoom}），零环境依赖，保证误改会被拦下。
 */
public class UltrawideFramingZoom571Test {

    private static final String SOURCE_REL =
            "src/main/java/io/github/wumisc/safewalk/RearCameraOverlay.kt";

    private static Method fn;
    private static Object companion;
    private static Throwable reflectErr;

    static {
        try {
            Class<?> compCls = Class.forName("io.github.wumisc.safewalk.RearCameraOverlay$Companion");
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeCls.getMethod("allocateInstance", Class.class);
            companion = allocateInstance.invoke(unsafe, compCls);
            fn = compCls.getDeclaredMethod("compromiseZoomFor", int.class, int.class, int.class, int.class);
            fn.setAccessible(true);
        } catch (Throwable t) {
            reflectErr = t;
        }
    }

    private static String src() throws Exception {
        Path[] candidates = {
                Paths.get("app/" + SOURCE_REL),
                Paths.get(SOURCE_REL),
                Paths.get("../app/" + SOURCE_REL),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("找不到 RearCameraOverlay.kt");
    }

    private static float zoom(int bw, int bh, int sw, int sh) throws Exception {
        return (Float) fn.invoke(companion, bw, bh, sw, sh);
    }

    /** A1：16:9 源必须落在 5.4.0 的 2.0 附近（老画面不变）。 */
    @Test
    public void sixteenNineStaysNearLegacyTwoPointZero() throws Exception {
        Assume.assumeNoException(reflectErr);
        // 小窗 488×845 + 960×540（16:9）
        float z = zoom(488, 845, 960, 540);
        assertEquals("16:9 折中须约 2.04（与 5.4.0 的 2.0 等价）", 2.04f, z, 0.06f);
    }

    /** A2：4:3 超广角必须明显小于 2.0（少裁广角），且仍大于 1.5（不至于回到大留白）。 */
    @Test
    public void fourThreeKeepsMoreHorizontalField() throws Exception {
        Assume.assumeNoException(reflectErr);
        float z = zoom(488, 845, 960, 720);      // 4:3
        assertEquals("4:3 折中须约 1.65", 1.65f, z, 0.05f);
        assertTrue("4:3 必须比 5.4.0 的固定 2.0 裁得少（更广）", z < 1.75f);
        assertTrue("4:3 仍需明显放大以收窄黑边", z > 1.5f);
    }

    /** A3：横条窗 1:1、源尺寸缺失 1:1、源比窗口更瘦时也不裁。 */
    @Test
    public void guardsReturnOneToOne() throws Exception {
        Assume.assumeNoException(reflectErr);
        assertEquals("横条窗须 1:1", 1f, zoom(845, 488, 960, 540), 0.0001f);
        assertEquals("源尺寸缺失须 1:1", 1f, zoom(488, 845, 0, 0), 0.0001f);
        assertEquals("正方形窗口须 1:1", 1f, zoom(500, 500, 960, 540), 0.0001f);
        // 极瘦源（1:2）在 488×845 里铺满只需 0.87 倍 → 不放大
        assertEquals("铺满不需放大时须 1:1", 1f, zoom(488, 845, 480, 960), 0.0001f);
    }

    /** B2 补充（5.7.2）：真超广角必须走「完整取景 1:1」，不许再被折中放大裁窄。 */
    @Test
    public void realUltrawideIsNotCompromiseCropped() throws Exception {
        String code = src();
        assertTrue("超广角分支须存在", code.contains("lastPickRealUltrawide && style.cameraLens == CameraLensPolicy.MODE_ULTRAWIDE"));
        assertTrue("超广角分支须明确 1:1", code.contains("超广角完整取景"));
        // 该分支必须在折中（portraitCompromiseZoom / compromiseZoomFor）之前返回
        int uw = code.indexOf("超广角完整取景");
        int comp = code.indexOf("compromiseZoomFor(boxW, boxH, previewSrcW, previewSrcH)");
        assertTrue("超广角 1:1 必须早于折中分支", uw > 0 && comp > 0 && uw < comp);
    }

    /** B2：超广角自愈必须存在，且「进程内只试一次」（真没广角的机型不能每次显示都闪）。 */
    @Test
    public void ultrawideSelfHealRunsOnce() throws Exception {
        String code = src();
        assertTrue("须在首帧后调用自愈", code.contains("maybeSelfHealUltrawide()"));
        assertTrue("自愈次数须有一次性守卫", code.contains("ultrawideHealAttempted"));
        assertTrue("自愈须校验真的选到广角", code.contains("lastPickRealUltrawide"));
        assertTrue("自愈须只在选超广角时触发",
                code.contains("style.cameraLens != CameraLensPolicy.MODE_ULTRAWIDE"));
        assertTrue("自愈须落最终结论，不静默", code.contains("重试后仍未拿到超广角"));
    }

    /** B1：applyFramingZoom 必须走新函数，并保留固定 2.0 作为源尺寸未知时的兜底。 */
    @Test
    public void applyFramingUsesAspectAwareZoom() throws Exception {
        String code = src();
        assertTrue("须调用 compromiseZoomFor", code.contains("compromiseZoomFor(boxW, boxH, previewSrcW, previewSrcH)"));
        assertTrue("须保留 5.4.0 固定常量作为兜底", code.contains("return 2.0f"));
        assertTrue("须保留竖条门控", code.contains("!landscapeWindow && boxH > boxW"));
        assertTrue("不得把折中叠加到 f>0 主动取景分支之外", code.contains("val k = f / 200f"));
    }
}
