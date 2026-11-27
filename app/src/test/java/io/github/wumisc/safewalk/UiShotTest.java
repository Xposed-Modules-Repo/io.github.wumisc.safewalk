package io.github.wumisc.safewalk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 临时性的「截图」测试：把 Activity 真实渲染成 PNG，用来看版面是否成立。
 * 不参与发布，验证完即删。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xhdpi")
public class UiShotTest {

    // 输出到系统临时目录：每台机器的截图落在自己本地，不写死绝对路径
    private static final String OUT = System.getProperty("java.io.tmpdir") + "/safewalk-ui-shots";

    private void shot(Class<? extends Activity> cls, String name) {
        try {
            new File(OUT).mkdirs();
            ActivityController<? extends Activity> c = Robolectric.buildActivity(cls).setup();
            Activity a = c.get();
            View root = a.getWindow().getDecorView();
            int w = 1080, h = 2340;
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            root.draw(canvas);
            File f = new File(OUT, name + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            System.out.println("SHOT " + f.getAbsolutePath() + " " + f.length() + "B");
        } catch (Throwable t) {
            System.out.println("SHOT-FAIL " + name + " -> " + t);
            t.printStackTrace();
        }
    }

    /** 关于页：切到第二个 tab 再截（Compose 在 Robolectric 下画不出来，保留备用） */
    @SuppressWarnings("unused")
    private void shotAbout(String name) {
        try {
            new File(OUT).mkdirs();
            ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
            MainActivity a = c.get();
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("switchTab", int.class);
            m.setAccessible(true);
            m.invoke(a, 2);
            View root = a.getWindow().getDecorView();
            int w = 1080, h = 2340;
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bmp));
            File f = new File(OUT, name + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            System.out.println("SHOT " + f.getAbsolutePath() + " " + f.length() + "B");
        } catch (Throwable t) {
            System.out.println("SHOT-FAIL " + name + " -> " + t);
        }
    }

    /**
     * 三个主页面（首页 / 功能 / 关于）：不走 MainActivity，直接充布局再手动切显隐。
     * MainActivity 在 Robolectric 里会在 Compose 初始化阶段报配置解析错，这里只要版面。
     */
    @Test
    public void mainPagesByLayout() {
        for (int idx = 0; idx <= 2; idx++) {
            try {
                new File(OUT).mkdirs();
                ActivityController<android.app.Activity> c =
                        Robolectric.buildActivity(android.app.Activity.class).setup();
                android.app.Activity a = c.get();
                // 不 attach 到窗口：ComposeView 一旦上窗就要 ViewTreeLifecycleOwner，
                // Robolectric 里给不了。这里只要静态版面，所以悬空充布局再手动画。
                a.setTheme(R.style.Theme_Safewalk);
                View root = a.getLayoutInflater().inflate(R.layout.activity_main, null);
                View nav = root.findViewById(R.id.compose_nav);
                if (nav != null) nav.setVisibility(View.GONE);
                int[] pages = {R.id.page_settings, R.id.page_features, R.id.page_about};
                for (int i = 0; i < pages.length; i++) {
                    root.findViewById(pages[i]).setVisibility(i == idx ? View.VISIBLE : View.GONE);
                }
                int w = 1080, h = 2340;
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, w, h);
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(bmp));
                File f = new File(OUT, "layout_page" + idx + ".png");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                System.out.println("SHOT " + f.getAbsolutePath() + " " + f.length() + "B");
            } catch (Throwable t) {
                System.out.println("SHOT-FAIL layout_page" + idx + " -> " + t);
            }
        }
    }

    /** 三个主页面（首页 / 功能 / 关于）：改版后靠它看排版是否还成立。 */
    @Test
    public void mainPages() {
        for (int idx = 0; idx <= 2; idx++) {
            try {
                new File(OUT).mkdirs();
                ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
                MainActivity a = c.get();
                java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("switchTab", int.class);
                m.setAccessible(true);
                m.invoke(a, idx);
                View root = a.getWindow().getDecorView();
                int w = 1080, h = 2340;
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, w, h);
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(bmp));
                File f = new File(OUT, "main_page" + idx + ".png");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                System.out.println("SHOT " + f.getAbsolutePath() + " " + f.length() + "B");
            } catch (Throwable t) {
                System.out.println("SHOT-FAIL main_page" + idx + " -> " + t);
            }
        }
    }

    /** 公益列表一条：用真实文案渲染，核对「栏目 → 姓名，性别，情形 → 时间 · 地点」这个层级。 */
    @Test
    public void kidRow() {
        try {
            new File(OUT).mkdirs();
            ActivityController<android.app.Activity> c =
                    Robolectric.buildActivity(android.app.Activity.class).setup();
            android.app.Activity a = c.get();
            a.setTheme(R.style.Theme_Safewalk);
            View root = a.getLayoutInflater().inflate(R.layout.item_kid, null);
            ((android.widget.TextView) root.findViewById(R.id.tv_kid_tag)).setText("家寻宝贝");
            ((android.widget.TextView) root.findViewById(R.id.tv_kid_name)).setText("牛乾坤，男，遗弃");
            ((android.widget.TextView) root.findViewById(R.id.tv_kid_info))
                    .setText("1995-08-16 走失 · 河北省,邯郸市,丛台区,三陵乡陈窑村");
            int w = 1080;
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = root.getMeasuredHeight();
            root.layout(0, 0, w, h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bmp));
            File f = new File(OUT, "kid_row.png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            System.out.println("SHOT " + f.getAbsolutePath() + " " + f.length() + "B h=" + h);
        } catch (Throwable t) {
            System.out.println("SHOT-FAIL kid_row -> " + t);
        }
    }

    @Test
    public void lightPages() {
        shot(ThanksActivity.class, "light_thanks");
        shot(AcknowledgementsActivity.class, "light_ack");
        shot(EmergencyCardActivity.class, "light_emergency");
        shot(SmartReminderActivity.class, "light_smart");
        shot(WalkModeActivity.class, "light_walk");
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    public void darkPages() {
        shot(ThanksActivity.class, "dark_thanks");
        shot(AcknowledgementsActivity.class, "dark_ack");
        shot(EmergencyCardActivity.class, "dark_emergency");
        shot(SmartReminderActivity.class, "dark_smart");
        shot(WalkModeActivity.class, "dark_walk");
    }
}
