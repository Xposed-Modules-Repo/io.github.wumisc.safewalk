package io.github.wumisc.safewalk;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;

import java.util.Collections;

/**
 * 后置摄像头路况悬浮窗。
 * form=1：全屏半透明叠加；form=0：角落圆角小窗。
 */
public final class CameraOverlay {

    private static CameraOverlay inst;

    private WindowManager wm;
    private Context ctx;
    private Handler main = new Handler(Looper.getMainLooper());
    private TextureView tv;
    private SurfaceTexture surfaceTexture;
    private CameraManager cm;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private boolean shown;
    private int lastForm = -1, lastAlpha, lastCorner;

    private CameraOverlay(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    public static void show(Context c, int form, int alpha, int corner) {
        if (inst == null) inst = new CameraOverlay(c);
        if (inst.shown && inst.lastForm == form && inst.lastAlpha == alpha && inst.lastCorner == corner) return;
        inst.shown = true;
        inst.lastForm = form;
        inst.lastAlpha = alpha;
        inst.lastCorner = corner;
        inst.apply();
    }

    public static void hide() {
        if (inst != null && inst.shown) {
            inst.shown = false;
            inst.teardown();
        }
    }

    public static boolean isShown() {
        return inst != null && inst.shown;
    }

    private void apply() {
        if (tv == null) {
            tv = new TextureView(ctx);
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surfaceTexture = st;
                    openCamera();
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    closeCamera();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
            tv.setOpaque(false);
            tv.setFocusable(false);
            tv.setClickable(false);
            // 圆角裁剪：小窗 20dp 圆角，全屏不加
            tv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    if (lastForm == 0) {
                        int r = dp(20);
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                    } else {
                        outline.setRect(0, 0, view.getWidth(), view.getHeight());
                    }
                }
            });
            tv.setClipToOutline(true);
        }
        tv.setAlpha(clampAlpha(lastAlpha, lastForm) / 100f);
        tv.invalidateOutline(); // 切换 form 后刷新圆角
        WindowManager.LayoutParams lp = buildParams(lastForm, lastCorner);
        try {
            if (tv.getParent() == null) {
                wm.addView(tv, lp);
            } else {
                wm.updateViewLayout(tv, lp);
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("overlay add: " + t);
        }
    }

    private WindowManager.LayoutParams buildParams(int form, int corner) {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (form == 1) {
            return new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type, flags, PixelFormat.TRANSLUCENT);
        }
        // 角落小窗：固定尺寸 + 圆角
        int w = dp(150), h = dp(260);
        int gravity;
        switch (corner) {
            case 1: gravity = Gravity.TOP | Gravity.START; break;
            case 3: gravity = Gravity.BOTTOM | Gravity.START; break;
            case 4: gravity = Gravity.BOTTOM | Gravity.END; break;
            default: gravity = Gravity.TOP | Gravity.END; break;
        }
        int margin = dp(12);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.x = margin;
        lp.y = margin;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private int clampAlpha(int alpha, int form) {
        return Math.max(25, Math.min(90, alpha));
    }

    private void teardown() {
        closeCamera();
        if (tv != null && tv.getParent() != null) {
            try { wm.removeView(tv); } catch (Throwable ignored) {}
        }
        tv = null;
        surfaceTexture = null;
    }

    private void closeCamera() {
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) {}
            session = null;
        }
        if (camera != null) {
            try { camera.close(); } catch (Throwable ignored) {}
            camera = null;
        }
    }

    private void openCamera() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(tv.getWidth(), tv.getHeight());
            Surface s = new Surface(st);
            String id = backCameraId();
            if (id == null) {
                SafeWalkInit.diag("no back camera");
                return;
            }
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    camera = d;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(s);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        d.createCaptureSession(Collections.singletonList(s),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession cs) {
                                        session = cs;
                                        try { cs.setRepeatingRequest(b.build(), null, main); }
                                        catch (Throwable ignored) {}
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession cs) {
                                        SafeWalkInit.diag("capture session failed");
                                    }
                                }, main);
                    } catch (Throwable t) {
                        SafeWalkInit.diag("start preview: " + t);
                    }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); camera = null; }
            }, main);
        } catch (Throwable t) {
            SafeWalkInit.diag("open camera: " + t);
        }
    }

    private String backCameraId() {
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
