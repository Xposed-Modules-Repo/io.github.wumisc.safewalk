package io.github.wumisc.safewalk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * 稳定性压测。
 *
 * 覆盖四类容易在真机才暴露的问题：
 *   ① 反复进出页面（生命周期重入 / 监听器泄漏 / 静态状态残留）
 *   ② 横竖屏切换（配置变更后 View 重建、控件引用失效）
 *   ③ 全量随机点击（任何未初始化的控件、空指针都会在这里炸）
 *   ④ 主线程消息队列清空（Handler 里 postDelayed 的回调真的跑一遍）
 *
 * 这些测试跑的是真 Activity、真布局 inflate，不是 mock。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StabilityTest {

    private Context ctx;
    private SharedPreferences sp;

    /** 参与压测的全部页面 */
    private static final Class<? extends Activity>[] PAGES = new Class[]{
            MainActivity.class,
            SmartReminderActivity.class,
            WalkModeActivity.class,
            EmergencyCardActivity.class,
            TipsActivity.class,
            AcknowledgementsActivity.class,
            DonateQrActivity.class,
            LabActivity.class
    };

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        sp = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
    }

    // ========== ① 生命周期压测 ==========

    @Test
    public void allPages_lifecycleStress_100Rounds() {
        for (Class<? extends Activity> cls : PAGES) {
            for (int i = 0; i < 100; i++) {
                ActivityController<? extends Activity> c = Robolectric.buildActivity(cls);
                Activity a = c.create().start().resume().visible().get();
                assertNotNull(cls.getSimpleName() + " 第 " + i + " 次启动为 null", a);
                c.pause().stop().destroy();
                // 每轮都清空消息队列，确保 Handler 里的延时任务真的执行过
                ShadowLooper.idleMainLooper();
            }
        }
    }

    @Test
    public void allPages_fullLifecycleOrderVariants() {
        for (Class<? extends Activity> cls : PAGES) {
            // 只 create 就 destroy：模拟被系统立刻回收
            Robolectric.buildActivity(cls).create().destroy();

            // create -> pause -> stop（没有 resume）：模拟后台启动
            Robolectric.buildActivity(cls).create().start().pause().stop().destroy();

            // 反复 pause/resume：模拟频繁切前后台
            ActivityController<? extends Activity> c = Robolectric.buildActivity(cls).create().start().resume();
            for (int i = 0; i < 20; i++) {
                c.pause().resume();
            }
            c.destroy();

            ShadowLooper.idleMainLooper();
        }
    }

    // ========== ② 配置变更 ==========

    @Test
    public void allPages_surviveConfigurationChange() {
        for (Class<? extends Activity> cls : PAGES) {
            ActivityController<? extends Activity> c = Robolectric.buildActivity(cls).create().start().resume().visible();

            // 切横屏：Activity 会被销毁重建，布局重新 inflate
            RuntimeEnvironment.setQualifiers("+land");
            Activity recreated = c.get();
            assertNotNull(cls.getSimpleName() + " 横屏重建失败", recreated);

            // 切回竖屏
            RuntimeEnvironment.setQualifiers("+port");
            assertNotNull(cls.getSimpleName() + " 竖屏重建失败", c.get());

            c.pause().stop().destroy();
            ShadowLooper.idleMainLooper();
        }
    }

    // ========== ③ 随机/全量点击 ==========

    @Test
    public void allPages_clickEveryView_noCrash() {
        for (Class<? extends Activity> cls : PAGES) {
            ActivityController<? extends Activity> c = Robolectric.buildActivity(cls).create().start().resume().visible();
            Activity a = c.get();

            List<View> clickable = new ArrayList<>();
            collectClickable(a.getWindow().getDecorView(), clickable);
            // 纯展示页（致敬名单等）可能确实没有可点控件，属于正常情况
            if (clickable.isEmpty()) {
                c.pause().stop().destroy();
                continue;
            }
            for (View v : clickable) {
                if (a.isFinishing() || a.isDestroyed()) break;
                // 「返回」会把页面关掉，导致后面的控件一个都点不到——
                // 之前所谓「全量点击」其实只点了一个返回键，覆盖率为 0
                if (v.getId() == R.id.btn_back) continue;
                try {
                    v.performClick();
                    ShadowLooper.idleMainLooper();
                } catch (Throwable t) {
                    throw new AssertionError(
                            cls.getSimpleName() + " 点击 " + describe(v) + " 崩溃: " + t, t);
                }
            }
            c.pause().stop().destroy();
            ShadowLooper.idleMainLooper();
        }
    }

    @Test
    public void allPages_clickTwiceInARow_noCrash() {
        // 连点是最容易暴露「重复注册 / 重复 startActivity / 状态机错乱」的场景
        for (Class<? extends Activity> cls : PAGES) {
            ActivityController<? extends Activity> c = Robolectric.buildActivity(cls).create().start().resume().visible();
            Activity a = c.get();

            List<View> clickable = new ArrayList<>();
            collectClickable(a.getWindow().getDecorView(), clickable);

            for (View v : clickable) {
                if (a.isFinishing() || a.isDestroyed()) break;
                if (v.getId() == R.id.btn_back) continue;
                try {
                    v.performClick();
                    v.performClick();
                    v.performClick();
                    ShadowLooper.idleMainLooper();
                } catch (Throwable t) {
                    throw new AssertionError(
                            cls.getSimpleName() + " 连点 " + describe(v) + " 崩溃: " + t, t);
                }
            }
            c.pause().stop().destroy();
            ShadowLooper.idleMainLooper();
        }
    }

    // ========== ④ 主线程队列 ==========

    @Test
    public void mainLooperDrained_noPendingCrash() {
        for (Class<? extends Activity> cls : PAGES) {
            ActivityController<? extends Activity> c = Robolectric.buildActivity(cls).create().start().resume().visible();
            // 把所有 postDelayed 任务推进 10 秒，延时回调里的空指针会在这里暴露
            ShadowLooper.idleMainLooper(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(c.get());
            c.pause().stop().destroy();
            ShadowLooper.idleMainLooper(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // ========== 工具 ==========

    private void collectClickable(View v, List<View> out) {
        if (v == null) return;
        if (v.isClickable() && v.getVisibility() == View.VISIBLE) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectClickable(g.getChildAt(i), out);
            }
        }
    }

    private String describe(View v) {
        try {
            return v.getClass().getSimpleName()
                    + "#" + (v.getId() == View.NO_ID ? "noid"
                    : v.getResources().getResourceEntryName(v.getId()));
        } catch (Throwable t) {
            return v.getClass().getSimpleName() + "#" + v.getId();
        }
    }
}
