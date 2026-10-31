package io.github.wumisc.safewalk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;

public class GlassCapsuleBar {
    private final Context ctx;
    private WindowManager wm;
    private LinearLayout capsuleRoot;
    private boolean show = false;

    public GlassCapsuleBar(Context c){
        ctx = c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void showBar(){
        if(show) return;
        show = true;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }else{
            lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        }
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;

        capsuleRoot = new LinearLayout(ctx);
        capsuleRoot.setOrientation(LinearLayout.HORIZONTAL);
        capsuleRoot.setPadding(40,12,40,12);
        TextView tv = new TextView(ctx);
        tv.setText("SafeWalk");
        capsuleRoot.addView(tv);
        wm.addView(capsuleRoot, lp);
    }

    public void hideBar(){
        if(!show) return;
        show = false;
        if(capsuleRoot != null){
            wm.removeView(capsuleRoot);
            capsuleRoot = null;
        }
    }
}
