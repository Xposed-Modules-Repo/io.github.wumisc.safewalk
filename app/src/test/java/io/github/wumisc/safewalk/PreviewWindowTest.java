package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        // 【对比度强度进度条】六参 show：开关开 + 强度 85%，窗口仍正常挂载，
        // 容器含画面层 + HUD 文字层，且 HUD 层可见。
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                true, false, 85);
        assertNotNull(recorder.params);
        assertTrue("窗口根必须是 FrameLayout 容器", recorder.view instanceof ViewGroup);
        ViewGroup root = (ViewGroup) recorder.view;
        assertTrue("容器应含画面层 + HUD 文字层", root.getChildCount() >= 2);
        assertEquals("增强对比度开启时 HUD 层应可见",
                View.VISIBLE, root.getChildAt(1).getVisibility());
        assertEquals("容器 alpha 恒为 1（文字层永不被调淡）",
                1.0f, root.getAlpha(), 0.001f);

        // 关掉总闸：HUD 层置 GONE，透明度仍只作用于画面层
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                false, false, 85);
        ViewGroup root2 = (ViewGroup) recorder.view;
        assertEquals("增强对比度关闭时 HUD 层应隐藏",
                View.GONE, root2.getChildAt(1).getVisibility());
        assertEquals("画面层透明度不受对比度开关影响",
                0.50f, root2.getChildAt(0).getAlpha(), 0.01f);
    }
}
