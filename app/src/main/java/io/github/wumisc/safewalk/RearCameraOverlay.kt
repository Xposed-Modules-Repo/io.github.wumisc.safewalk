package io.github.wumisc.safewalk

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager

/**
 * 后置看路悬浮窗 - 和最好版CameraOverlay一致，用TextureView
 */
class RearCameraOverlay(private val ctx: Context) {

    companion object {
        private const val TAG = "SafeWalk-Cam"
        const val MODE_FULLSCREEN = "fullscreen"
        const val MODE_SMALLWINDOW = "smallwindow"
    }

    private val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var tv: TextureView? = null
    private var isShowing = false
    private var currentMode = MODE_FULLSCREEN

    /** 显示悬浮窗 */
    fun show(mode: String = MODE_FULLSCREEN) {
        Log.i(TAG, "show() 被调用, mode=$mode, isShowing=$isShowing")
        if (isShowing && mode == currentMode) return

        isShowing = true
        currentMode = mode
        apply()
    }

    /** 隐藏悬浮窗 */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        teardown()
        Log.i(TAG, "隐藏悬浮窗")
    }

    private fun apply() {
        if (tv == null) {
            tv = TextureView(ctx.applicationContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        this@RearCameraOverlay.surfaceTexture = st
                        this@RearCameraOverlay.openCamera()
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        this@RearCameraOverlay.closeCamera()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                isOpaque = false
                isFocusable = false
                isClickable = false
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        if (currentMode == MODE_SMALLWINDOW) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                        } else {
                            outline.setRect(0, 0, view.width, view.height)
                        }
                    }
                }
                clipToOutline = true
            }
        }

        // 切换模式时重新设置圆角outline
        tv?.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (currentMode == MODE_SMALLWINDOW) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                } else {
                    outline.setRect(0, 0, view.width, view.height)
                }
            }
        }
        tv?.invalidateOutline()

        val lp = buildParams()
        try {
            Log.i(TAG, "准备 addView, type=${lp.type}")
            if (tv?.parent == null) {
                wm.addView(tv, lp)
                Log.i(TAG, "✅ 悬浮窗显示成功: $currentMode")
            } else {
                wm.updateViewLayout(tv, lp)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 显示悬浮窗失败", t)
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val statusBarH = getStatusBarHeight()
        return if (currentMode == MODE_FULLSCREEN) {
            // 全屏 - 顶部预留状态栏安全区
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT - statusBarH,
                2038, // TYPE_APPLICATION_OVERLAY
                56,   // FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                -3    // PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                y = statusBarH // 从状态栏下方开始
            }
        } else {
            // 右下角小窗
            val w = dp(150)
            val h = dp(260)
            WindowManager.LayoutParams(
                w, h,
                2038, // TYPE_APPLICATION_OVERLAY
                56,   // FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                -3    // PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                x = dp(12)
                y = statusBarH + dp(12) // 避开状态栏，用真实状态栏高度
            }
        }
    }

    private fun dp(v: Int): Int {
        return Math.round(v * ctx.resources.displayMetrics.density)
    }

    /** 获取系统真实状态栏高度 */
    private fun getStatusBarHeight(): Int {
        return try {
            val resourceId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) ctx.resources.getDimensionPixelSize(resourceId)
            else dp(24) // 默认24dp
        } catch (_: Throwable) {
            dp(24)
        }
    }

    /** 相机避让：检查前台是否是相机类APP，是则自动隐藏 */
    fun checkCameraAvoidance() {
        try {
            // 用反射调用ActivityTaskManager，兼容低版本
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getInstance = atmClass.getMethod("getInstance")
            val atm = getInstance.invoke(null)
            val getFocusedRootTaskInfo = atmClass.getMethod("getFocusedRootTaskInfo")
            val info = getFocusedRootTaskInfo.invoke(atm)
            val topActivityField = info?.javaClass?.getField("topActivity")
            val topActivity = topActivityField?.get(info)
            val packageNameField = topActivity?.javaClass?.getField("packageName")
            val top = packageNameField?.get(topActivity) as? String ?: return

            val cameraApps = setOf(
                "com.android.camera",
                "com.huawei.camera",
                "com.miui.camera",
                "com.xiaomi.camera",
                "com.oplus.camera",
                "com.vivo.camera",
                "com.samsung.android.camera",
                "com.oneplus.camera"
            )
            if (cameraApps.contains(top)) {
                Log.i(TAG, "相机避让：前台是相机APP，自动隐藏")
                hide()
            }
        } catch (_: Throwable) {}
    }

    private fun teardown() {
        closeCamera()
        tv?.let {
            if (it.parent != null) {
                try {
                    wm.removeView(it)
                } catch (_: Throwable) {}
            }
        }
        tv = null
        surfaceTexture = null
    }

    private fun closeCamera() {
        session?.let {
            try { it.close() } catch (_: Throwable) {}
        }
        session = null
        camera?.let {
            try { it.close() } catch (_: Throwable) {}
        }
        camera = null
    }

    private fun openCamera() {
        try {
            val st = tv?.surfaceTexture ?: return
            st.setDefaultBufferSize(tv!!.width, tv!!.height)
            val surface = Surface(st)
            val id = backCameraId()
            if (id == null) {
                Log.e(TAG, "无后置摄像头")
                return
            }
            Log.i(TAG, "打开后置摄像头: $id")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    camera = d
                    try {
                        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                session = cs
                                try {
                                    cs.setRepeatingRequest(builder.build(), null, mainHandler)
                                } catch (_: Throwable) {}
                            }

                            override fun onConfigureFailed(cs: CameraCaptureSession) {
                                Log.e(TAG, "会话配置失败")
                            }
                        }, mainHandler)
                    } catch (t: Throwable) {
                        Log.e(TAG, "启动预览失败", t)
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    camera = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    camera = null
                    Log.e(TAG, "相机错误: $error")
                }
            }, mainHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "打开相机失败", t)
        }
    }

    private fun backCameraId(): String? {
        try {
            for (id in cm.cameraIdList) {
                val cc = cm.getCameraCharacteristics(id)
                val facing = cc.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            return null
        } catch (_: Throwable) {
            return null
        }
    }
}
