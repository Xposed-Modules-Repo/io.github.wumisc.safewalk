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
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("switchTab", boolean.class);
            m.setAccessible(true);
            m.invoke(a, true);
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

    @Test
    public void lightPages() {
        shot(LabActivity.class, "light_lab");
        shot(TipsActivity.class, "light_tips");
        shot(AcknowledgementsActivity.class, "light_ack");
        shot(EmergencyCardActivity.class, "light_emergency");
        shot(SmartReminderActivity.class, "light_smart");
        shot(WalkModeActivity.class, "light_walk");
        shot(DonateQrActivity.class, "light_donate");
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    public void darkPages() {
        shot(LabActivity.class, "dark_lab");
        shot(TipsActivity.class, "dark_tips");
        shot(AcknowledgementsActivity.class, "dark_ack");
        shot(EmergencyCardActivity.class, "dark_emergency");
        shot(SmartReminderActivity.class, "dark_smart");
        shot(WalkModeActivity.class, "dark_walk");
        shot(DonateQrActivity.class, "dark_donate");
    }
}
