package io.github.wumisc.safewalk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.os.Build;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedBridge;

public class CameraOverlay implements SurfaceHolder.Callback {
    private final Context ctx;
    private WindowManager wm;
    private FrameLayout rootView;
    private SurfaceView surfaceView;
    private Camera camera;
    private boolean showing = false;

    public CameraOverlay(Context c){
        ctx = c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void showOverlay(){
        if(showing) return;
        showing = true;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }else{
            lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        }
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.BOTTOM;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = 600;

        rootView = new FrameLayout(ctx);
        surfaceView = new SurfaceView(ctx);
        surfaceView.getHolder().addCallback(this);
        rootView.addView(surfaceView);
        wm.addView(rootView, lp);
        XposedBridge.log("[SafeWalk] overlay show");
    }

    public void hideOverlay(){
        if(!showing) return;
        showing = false;
        releaseCamera();
        if(rootView != null){
            wm.removeView(rootView);
            rootView = null;
        }
        XposedBridge.log("[SafeWalk] overlay hide");
    }

    private void openCamera(SurfaceHolder holder){
        try{
            camera = Camera.open(0);
            camera.setPreviewDisplay(holder);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewSize(640,480);
            camera.setParameters(params);
            camera.startPreview();
        }catch (Exception e){
            DiagLogger.getInstance(ctx).log("camera open err:"+e.getMessage());
        }
    }
    private void releaseCamera(){
        if(camera != null){
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera(holder);
    }
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }
}
