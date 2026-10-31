package io.github.wumisc.safewalk;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import java.util.Collections;

/* loaded from: classes3.dex */
public final class CameraOverlay {
    private static CameraOverlay inst;
    private CameraDevice camera;
    private CameraManager cm;
    private Context ctx;
    private int lastAlpha;
    private int lastCorner;
    private CameraCaptureSession session;
    private boolean shown;
    private SurfaceTexture surfaceTexture;
    private TextureView tv;
    private WindowManager wm;
    private Handler main = new Handler(Looper.getMainLooper());
    private int lastForm = -1;

    private CameraOverlay(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService("window");
        this.cm = (CameraManager) this.ctx.getSystemService("camera");
    }

    public static void show(Context c, int form, int alpha, int corner) {
        if (inst == null) {
            inst = new CameraOverlay(c);
        }
        if (inst.shown && inst.lastForm == form && inst.lastAlpha == alpha && inst.lastCorner == corner) {
            return;
        }
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
        if (this.tv == null) {
            this.tv = new TextureView(this.ctx);
            this.tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() { // from class: io.github.wumisc.safewalk.CameraOverlay.1
                @Override // android.view.TextureView.SurfaceTextureListener
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    CameraOverlay.this.surfaceTexture = st;
                    CameraOverlay.this.openCamera();
                }

                @Override // android.view.TextureView.SurfaceTextureListener
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override // android.view.TextureView.SurfaceTextureListener
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    CameraOverlay.this.closeCamera();
                    return true;
                }

                @Override // android.view.TextureView.SurfaceTextureListener
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
            this.tv.setOpaque(false);
            this.tv.setFocusable(false);
            this.tv.setClickable(false);
            this.tv.setOutlineProvider(new ViewOutlineProvider() { // from class: io.github.wumisc.safewalk.CameraOverlay.2
                @Override // android.view.ViewOutlineProvider
                public void getOutline(View view, Outline outline) {
                    if (CameraOverlay.this.lastForm == 0) {
                        int r = CameraOverlay.this.dp(24);
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                    } else {
                        outline.setRect(0, 0, view.getWidth(), view.getHeight());
                    }
                }
            });
            this.tv.setClipToOutline(true);
        }
        this.tv.setAlpha(clampAlpha(this.lastAlpha, this.lastForm) / 100.0f);
        this.tv.invalidateOutline();
        WindowManager.LayoutParams lp = buildParams(this.lastForm, this.lastCorner);
        try {
            if (this.tv.getParent() == null) {
                this.wm.addView(this.tv, lp);
            } else {
                this.wm.updateViewLayout(this.tv, lp);
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("overlay add: " + t);
        }
    }

    private WindowManager.LayoutParams buildParams(int form, int corner) {
        int gravity;
        if (form == 1) {
            return new WindowManager.LayoutParams(-1, -1, 2038, 56, -3);
        }
        int w = dp(150);
        int h = dp(260);
        switch (corner) {
            case 1:
                gravity = 8388659;
                break;
            case 2:
            default:
                gravity = 8388661;
                break;
            case 3:
                gravity = 8388691;
                break;
            case 4:
                gravity = 8388693;
                break;
        }
        int margin = dp(12);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, 2038, 56, -3);
        lp.gravity = gravity;
        lp.x = margin;
        lp.y = margin;
        return lp;
    }

    public int dp(int v) {
        return Math.round(v * this.ctx.getResources().getDisplayMetrics().density);
    }

    private int clampAlpha(int alpha, int form) {
        return Math.max(25, Math.min(90, alpha));
    }

    private void teardown() {
        closeCamera();
        if (this.tv != null && this.tv.getParent() != null) {
            try {
                this.wm.removeView(this.tv);
            } catch (Throwable th) {
            }
        }
        this.tv = null;
        this.surfaceTexture = null;
    }

    public void closeCamera() {
        if (this.session != null) {
            try {
                this.session.close();
            } catch (Throwable th) {
            }
            this.session = null;
        }
        if (this.camera != null) {
            try {
                this.camera.close();
            } catch (Throwable th2) {
            }
            this.camera = null;
        }
    }

    public void openCamera() {
        try {
            SurfaceTexture st = this.tv.getSurfaceTexture();
            if (st == null) {
                return;
            }
            st.setDefaultBufferSize(this.tv.getWidth(), this.tv.getHeight());
            final Surface s = new Surface(st);
            String id = backCameraId();
            if (id == null) {
                SafeWalkInit.diag("no back camera");
            } else {
                this.cm.openCamera(id, new CameraDevice.StateCallback() { // from class: io.github.wumisc.safewalk.CameraOverlay.3
                    @Override // android.hardware.camera2.CameraDevice.StateCallback
                    public void onOpened(CameraDevice d) {
                        CameraOverlay.this.camera = d;
                        try {
                            final CaptureRequest.Builder b = d.createCaptureRequest(1);
                            b.addTarget(s);
                            b.set(CaptureRequest.CONTROL_MODE, 1);
                            d.createCaptureSession(Collections.singletonList(s), new CameraCaptureSession.StateCallback() { // from class: io.github.wumisc.safewalk.CameraOverlay.3.1
                                @Override // android.hardware.camera2.CameraCaptureSession.StateCallback
                                public void onConfigured(CameraCaptureSession cs) {
                                    CameraOverlay.this.session = cs;
                                    try {
                                        cs.setRepeatingRequest(b.build(), null, CameraOverlay.this.main);
                                    } catch (Throwable th) {
                                    }
                                }

                                @Override // android.hardware.camera2.CameraCaptureSession.StateCallback
                                public void onConfigureFailed(CameraCaptureSession cs) {
                                    SafeWalkInit.diag("capture session failed");
                                }
                            }, CameraOverlay.this.main);
                        } catch (Throwable t) {
                            SafeWalkInit.diag("start preview: " + t);
                        }
                    }

                    @Override // android.hardware.camera2.CameraDevice.StateCallback
                    public void onDisconnected(CameraDevice d) {
                        d.close();
                        CameraOverlay.this.camera = null;
                    }

                    @Override // android.hardware.camera2.CameraDevice.StateCallback
                    public void onError(CameraDevice d, int e) {
                        d.close();
                        CameraOverlay.this.camera = null;
                    }
                }, this.main);
            }
        } catch (Throwable t) {
            SafeWalkInit.diag("open camera: " + t);
        }
    }

    private String backCameraId() {
        try {
            for (String id : this.cm.getCameraIdList()) {
                CameraCharacteristics cc = this.cm.getCameraCharacteristics(id);
                Integer facing = (Integer) cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing.intValue() == 1) {
                    return id;
                }
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }
}
