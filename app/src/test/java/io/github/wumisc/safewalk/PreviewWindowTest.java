package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 预览窗口参数测试。
 *
 * 存在的理由：上一版把全屏窗口高度写成 `MATCH_PARENT - 状态栏高度`。
 * MATCH_PARENT 是 -1，减完是 -73 这样的负数。WindowManager 不会为此抛异常，
 * 它只是把窗口算成 0 高 —— 于是「全屏预览」点了毫无反应，
 * 而所有单元测试、Lint 都是绿的，因为没人检查过窗口参数本身。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PreviewWindowTest {

    /** 只记录 addView / updateViewLayout 传进来的布局参数 */
    private static class Recorder implements InvocationHandler {
        WindowManager.LayoutParams params;
        View view;

        /**
         * 【增强对比度容器化后】addView 挂的是 FrameLayout 容器（alpha 恒 1），
         * 透明度只作用在容器内第 0 个子层（相机画面 TextureView），
         * 第 1 个子层 HUD 文字层 alpha 也恒 1。测试断言必须针对画面层，
         * 而不是容器本身 —— 否则会把「容器 alpha=1」误判成透明度没生效。
         */
        View renderLayer() {
            if (view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                if (g.getChildCount() > 0) return g.getChildAt(0);
            }
            return view;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "addView":
                case "updateViewLayout":
                    view = (View) args[0];
                    params = (WindowManager.LayoutParams) args[1];
                    return null;
                case "removeView":
                case "removeViewImmediate":
                    view = null;
                    return null;
                default:
                    return null;
            }
        }
    }

    private Recorder recorder;
    private RearCameraOverlay overlay;

    private void build() {
        Context ctx = ApplicationProvider.getApplicationContext();
        recorder = new Recorder();
        WindowManager wm = (WindowManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{WindowManager.class},
                recorder);
        overlay = new RearCameraOverlay(ctx, null, null, wm);
    }

    @Test
    public void fullscreen_windowSizeIsPositive() {
        build();
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR);

        assertNotNull("窗口根本没被 addView", recorder.params);
        // 全屏必须是真实屏幕像素尺寸（正数），不能用 MATCH_PARENT(-1)：
        // 相机搬到 App 进程后，MATCH_PARENT 悬浮窗在部分 ROM 上布局出 0×0，
        // openCamera 拿到 0 尺寸直接放弃 → 全屏黑屏、小窗正常
        assertTrue("全屏宽度必须为正数，实际是 " + recorder.params.width,
                recorder.params.width > 0);
        assertTrue("全屏高度必须为正数，实际是 " + recorder.params.height,
                recorder.params.height > 0);
        // 全屏覆盖整个屏幕（含状态栏），从左上角 0,0 开始
        assertEquals(Gravity.TOP | Gravity.START, recorder.params.gravity);
    }

    @Test
    public void opacity_isAppliedToView() {
        build();
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 80, RearCameraOverlay.CORNER_BR);
        assertEquals(0.80f, recorder.renderLayer().getAlpha(), 0.01f);
        // 容器（含 HUD 文字层）alpha 必须恒为 1：增强对比度要求文字永不被调淡
        assertEquals(1.0f, recorder.view.getAlpha(), 0.001f);

        // 越界值要夹到 10~100，不能出现全透明或全不透明
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 0, RearCameraOverlay.CORNER_BR);
        assertEquals(0.10f, recorder.renderLayer().getAlpha(), 0.01f);
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 999, RearCameraOverlay.CORNER_BR);
        assertEquals(0.99f, recorder.renderLayer().getAlpha(), 0.001f);
    }

    @Test
    public void fullOpacity_isCappedAt099_andWindowStaysTranslucent() {
        build();
        // 拉满 100%：视图 alpha 必须封顶 0.99。
        // 真机实测 alpha=1.0 的全屏悬浮窗出不来相机画面（系统把它判成不透明
        // layer，与 TRANSLUCENT 声明冲突），半透明正常，所以上限压到 0.99
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 100, RearCameraOverlay.CORNER_BR);
        assertEquals(0.99f, recorder.renderLayer().getAlpha(), 0.001f);
        assertEquals(android.graphics.PixelFormat.TRANSLUCENT, recorder.params.format);

        // 半透明保持原值，窗口格式不随不透明度切换
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR);
        assertEquals(0.50f, recorder.renderLayer().getAlpha(), 0.01f);
        assertEquals(android.graphics.PixelFormat.TRANSLUCENT, recorder.params.format);
    }

    @Test
    public void smallWindow_hasPositiveSizeAndCorner() {
        build();
        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_TL);

        assertTrue("小窗宽高必须为正数", recorder.params.width > 0 && recorder.params.height > 0);
        assertEquals("左上角要贴左上",
                Gravity.TOP | Gravity.START, recorder.params.gravity);
        // 小窗不能铺满屏幕，否则和全屏模式没区别
        assertTrue("小窗高度不该和全屏一样",
                recorder.params.height != ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Test
    public void contrastLevel_isAcceptedAndTogglesHudVisibility() {
        build();
        // 【4.9.0 语义变更】HUD 可见性由「有没有文案」决定，不再由对比度开关决定。
        // 这组断言锁住三件事：
        //  1) 没文案 → 即使对比度开着也不显示（默认状态，画面上一个字都没有）；
        //  2) 有文案 + 对比度开 → 可见；
        //  3) 有文案 + 对比度关 → 仍然可见，但退化成纯白字（level=0，无描边无衬底）。
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                true, false, 85, null, 13, 150, 12);
        assertNotNull(recorder.params);
        assertTrue("窗口根必须是 FrameLayout 容器", recorder.view instanceof ViewGroup);
        ViewGroup root = (ViewGroup) recorder.view;
        assertTrue("容器应含画面层 + HUD 文字层", root.getChildCount() >= 2);
        assertEquals("没有文案时 HUD 层不应显示（哪怕对比度开着）",
                View.GONE, root.getChildAt(1).getVisibility());
        assertEquals("容器 alpha 恒为 1（文字层永不被调淡）",
                1.0f, root.getAlpha(), 0.001f);

        // 有文案 + 对比度开：HUD 可见
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                true, false, 85, "路况预览", 13, 150, 12);
        ViewGroup root2 = (ViewGroup) recorder.view;
        assertEquals("有文案且对比度开启时 HUD 层应可见",
                View.VISIBLE, root2.getChildAt(1).getVisibility());
        assertEquals("画面层透明度不受 HUD 影响",
                0.50f, root2.getChildAt(0).getAlpha(), 0.01f);

        // 有文案 + 对比度关：仍显示，只是不加修饰
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                false, false, 85, "路况预览", 13, 150, 12);
        ViewGroup root3 = (ViewGroup) recorder.view;
        assertEquals("文案开关与对比度解耦：关掉对比度文字仍在",
                View.VISIBLE, root3.getChildAt(1).getVisibility());
    }

    @Test
    @Config(sdk = 34, qualifiers = "w411dp-h891dp")
    public void smallWindowSizeAndMargin_followUserSettings() {
        build();
        // 小窗尺寸按 150:260 比例换算，边距按设置值下发。
        // 指定一块够高的屏幕（891dp），让「横屏高度上限」不参与，这条只测用户设置是否生效。
        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 180, 30);
        assertNotNull(recorder.params);
        Context ctx = ApplicationProvider.getApplicationContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int expectW = Math.round(180 * density);
        int expectH = Math.round((180 * RearCameraOverlay.SMALL_ASPECT) * density);
        assertEquals("宽度应等于用户设置值", expectW, recorder.params.width);
        assertEquals("高度按固定宽高比换算", expectH, recorder.params.height);
        assertEquals("边距应等于用户设置值", Math.round(30 * density), recorder.params.x);
    }

    /**
     * 【5.3.0 横屏】横过来之后屏幕高度腰斩，小窗不能顶出屏幕。
     *
     * 411dp 高的横屏上，180dp 宽换算出来 312dp 高，已经占了屏高四分之三，
     * 再叠加边距就顶到状态栏外面。所以只压高度、不动宽度（用户调的是宽度），
     * 上限取屏高 62% —— 画面是等比铺满，压矮只会多裁上下，不变形。
     */
    @Test
    @Config(sdk = 34, qualifiers = "w891dp-h411dp")
    public void smallWindowHeightIsCappedOnLandscapeScreens() {
        build();
        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 180, 30);
        assertNotNull(recorder.params);
        Context ctx = ApplicationProvider.getApplicationContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int uncapped = Math.round((180 * RearCameraOverlay.SMALL_ASPECT) * density);
        assertEquals("宽度不受高度上限影响", Math.round(180 * density), recorder.params.width);
        assertEquals("高度应压到屏高的 62%", (int) (screenH * 0.62f), recorder.params.height);
        assertTrue("横屏下确实压矮了", recorder.params.height < uncapped);
    }

    /**
     * 描边只作用于小窗。
     *
     * 全屏描边会紧贴屏幕边缘画一圈白线，看上去像「画面没铺满」，
     * 用户会当成故障 —— 所以这条既是功能，也是防回归。
     */
    @Test
    public void borderOnlyAppliesToSmallWindow() {
        build();
        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 150, 12, 0, 2, false);
        assertNotNull("小窗 + 2dp 描边应设置前景 drawable", recorder.view.getForeground());

        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 150, 12, 0, 2, false);
        assertNull("全屏不应描边", recorder.view.getForeground());

        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 150, 12, 0, 0, false);
        assertNull("描边为 0 时不应有前景", recorder.view.getForeground());
    }

    /**
     * 取景范围 / 描边 / 低光增强越界时不能把窗口搞崩。
     *
     * 这三个值来自 SeekBar 与配置文件（用户在旧版本可能存过任意值），
     * 越界必须在 Style 构造时夹住，而不是带着 500% 去算矩阵。
     */
    @Test
    public void framingAndBorder_areClampedAndWindowStillMounts() {
        build();
        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 150, 12, 500, 99, true);
        assertNotNull("越界参数也必须能正常挂窗", recorder.params);
        assertTrue("窗口宽高仍为正数", recorder.params.width > 0 && recorder.params.height > 0);

        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR,
                false, false, 50, null, 13, 150, 12, -80, -5, true);
        assertNotNull("负值同样不能崩", recorder.params);
        assertNull("负描边按 0 处理，不应有前景", recorder.view.getForeground());
    }
}
