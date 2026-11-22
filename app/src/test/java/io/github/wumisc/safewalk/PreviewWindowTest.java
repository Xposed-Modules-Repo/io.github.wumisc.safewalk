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
         * 透明度只作用在容器内的相机画面层（TextureView），HUD 文字层 alpha 恒 1。
         * 测试断言必须针对画面层，而不是容器本身 —— 否则会把「容器 alpha=1」
         * 误判成透明度没生效。
         *
         * 【5.3.6 旋转层】容器里多了一层 rotLayer（负责 View.rotation），
         * 画面层从「第 0 个子层」变成「第 0 个子层的第 0 个子层」。这里改成
         * 按类型深度优先查找 TextureView，结构再怎么套都不会再错位。
         */
        View renderLayer() {
            View found = findTextureView(view);
            return found != null ? found : view;
        }

        private static View findTextureView(View v) {
            if (v instanceof android.view.TextureView) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View hit = findTextureView(g.getChildAt(i));
                    if (hit != null) return hit;
                }
            }
            return null;
        }

        /**
         * 【5.4.2 Bug B】HUD 已移入 rotLayer 跟随画面旋转，不再是 root 直属子节点，
         * 旧的 root.getChildAt(1) 取不到它。HudTextView 是私有内部类，Java 侧无法
         * 直接引用其类型，故按 TextView 子类深度优先定位（画面层是 TextureView，不会命中）。
         */
        View findHudView(View v) {
            if (v instanceof android.widget.TextView) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View hit = findHudView(g.getChildAt(i));
                    if (hit != null) return hit;
                }
            }
            return null;
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
        // 【5.4.2 Bug B】HUD 已从 root 直属层移入 rotLayer（跟随画面旋转），
        // 定位改走 findHudView 深度查找，结构断言按新层级更新，可见性契约不变。
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                true, false, 85, null, 13, 150, 12);
        assertNotNull(recorder.params);
        assertTrue("窗口根必须是 FrameLayout 容器", recorder.view instanceof ViewGroup);
        ViewGroup root = (ViewGroup) recorder.view;
        // 【5.4.2】root 第 0 个子节点是 rotLayer，rotLayer 内含画面层 + HUD 文字层。
        // 【5.6.5】root 直属 = rotLayer + 奖励小红旗（旗子在后，rotLayer 仍是第 0 个子）
        assertEquals("root 直属 = rotLayer + 小红旗两个子节点", 2, root.getChildCount());
        assertEquals("第 0 个子必须是 rotLayer（旗子后加，不改变旋转层下标）",
                RearCameraOverlay.TAG_STEP_FLAG, root.getChildAt(1).getTag());
        assertTrue("rotLayer 内应含画面层 + HUD 文字层",
                ((ViewGroup) root.getChildAt(0)).getChildCount() >= 2);
        View hud = recorder.findHudView(root);
        assertNotNull("应能在 rotLayer 内定位到 HUD 文字层", hud);
        assertEquals("没有文案时 HUD 层不应显示（哪怕对比度开着）",
                View.GONE, hud.getVisibility());
        assertEquals("容器 alpha 恒为 1（文字层永不被调淡）",
                1.0f, root.getAlpha(), 0.001f);

        // 有文案 + 对比度开：HUD 可见
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                true, false, 85, "路况预览", 13, 150, 12);
        ViewGroup root2 = (ViewGroup) recorder.view;
        View hud2 = recorder.findHudView(root2);
        assertNotNull("应能在 rotLayer 内定位到 HUD 文字层", hud2);
        assertEquals("有文案且对比度开启时 HUD 层应可见",
                View.VISIBLE, hud2.getVisibility());
        assertEquals("画面层透明度不受 HUD 影响",
                0.50f, recorder.renderLayer().getAlpha(), 0.01f);

        // 有文案 + 对比度关：仍显示，只是不加修饰
        overlay.show(RearCameraOverlay.MODE_FULLSCREEN, 50, RearCameraOverlay.CORNER_BR,
                false, false, 85, "路况预览", 13, 150, 12);
        ViewGroup root3 = (ViewGroup) recorder.view;
        View hud3 = recorder.findHudView(root3);
        assertNotNull("应能在 rotLayer 内定位到 HUD 文字层", hud3);
        assertEquals("文案开关与对比度解耦：关掉对比度文字仍在",
                View.VISIBLE, hud3.getVisibility());
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
        // 【5.6.1 横屏必横】横屏下窗口是横条：短边（用户设置值）落在高度上，
        // 宽度是短边 × 宽高比。411dp 屏的 62% 上限高于 180dp 短边，因此这里不该触发压矮。
        int expectShort = Math.round(180 * density);
        int maxH = (int) (screenH * 0.62f);
        assertEquals("横条高 = 用户设置短边", expectShort, recorder.params.height);
        assertEquals("横条宽 = 短边 × 宽高比",
                Math.round((180 * RearCameraOverlay.SMALL_ASPECT) * density), recorder.params.width);
        assertTrue("横条确实是横的", recorder.params.width > recorder.params.height);
        assertTrue("180dp 短边未触到 62% 上限", expectShort < maxH);
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

    /**
     * 【5.3.7】竖条窗口必须**不转** —— 这是「没动路况预览之前」（4.8.0）的行为。
     *
     * 5.3.6 让竖条窗口也转 90°，本想给竖屏一个「正立且完整」的画面，真机上却
     * 变成「画面不完整」。用户的判断很直接：竖屏以前是好的，别动；要改只改横屏。
     *
     * 所以这条测试锁住：竖条窗口下，哪怕方向算出来是 90°，旋转层仍必须与窗口
     * 同尺寸、rotation=0（方向不回退）；画面层在 5.4.0 起按折中方案中心放大 2.0x
     * 收窄上下黑边，不再完全等于 4.8.0 的 scale=1 铺满路径。
     */
    @Test
    public void portraitWindowIsNeverRotated() throws Exception {
        build();
        // 模拟 openCamera 里 resolveOrientation 的结果：竖握 + 传感器 90°
        java.lang.reflect.Field f = RearCameraOverlay.class.getDeclaredField("appliedRotDeg");
        f.setAccessible(true);
        f.setInt(overlay, 90);

        overlay.show(RearCameraOverlay.MODE_SMALLWINDOW, 50, RearCameraOverlay.CORNER_BR);

        ViewGroup root = (ViewGroup) recorder.view;
        assertEquals("窗口根自身不能旋转", 0.0f, root.getRotation(), 0.001f);
        // 【5.4.2 Bug B】HUD 已移入 rotLayer：root 直属只剩 rotLayer 一个子节点，
        // rotLayer 内含画面层 + HUD 两层。下方 rot 仍是 root.getChildAt(0) 即 rotLayer。
        // 【5.6.5】root 直属 = rotLayer + 奖励小红旗（旗子在后，rotLayer 仍是第 0 个子）
        assertEquals("root 直属 = rotLayer + 小红旗两个子节点", 2, root.getChildCount());
        assertEquals("第 0 个子必须是 rotLayer（旗子后加，不改变旋转层下标）",
                RearCameraOverlay.TAG_STEP_FLAG, root.getChildAt(1).getTag());
        assertTrue("rotLayer 内应含画面层 + HUD 文字层",
                ((ViewGroup) root.getChildAt(0)).getChildCount() >= 2);

        View rot = root.getChildAt(0);
        assertEquals("竖条窗口必须不转", 0.0f, rot.getRotation(), 0.001f);
        ViewGroup.LayoutParams lp = rot.getLayoutParams();
        assertEquals("旋转层宽 = 窗口宽", recorder.params.width, lp.width);
        assertEquals("旋转层高 = 窗口高", recorder.params.height, lp.height);
        assertTrue("小窗在竖屏下必须是竖条", recorder.params.height > recorder.params.width);

        // 【5.4.0】竖屏折中填充：横画幅进竖条槽，中心放大 Z=2.0 保留约 50% 视野、
        // 上下留约 17% 窄边（见 portraitCompromiseZoom 几何推导）。不再是 4.8.0 的 scale=1。
        View texture = recorder.renderLayer();
        assertEquals(2.0f, texture.getScaleX(), 0.001f);
        assertEquals(2.0f, texture.getScaleY(), 0.001f);
    }
}
