package io.github.wumisc.safewalk

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 后置看路悬浮窗：走路时用后置摄像头实时显示前方路况。
 *
 * 两种形态：
 *  - fullscreen：全屏半透明叠加，下层应用照常使用
 *  - smallwindow：右下角小窗（约1/4屏宽）
 *
 * 特性：
 *  - 触摸完全穿透：只显示、不挡操作
 *  - 不高于状态栏：顶部留状态栏高度的边距
 *  - 自动模式：走起来自动开、停下自动收
 *  - 相机避让：前台进入相机类app时自动收起
 */
class RearCameraOverlay(private val ctx: Context) {

    companion object {
        private const val TAG = "SafeWalk-Cam"
        const val MODE_FULLSCREEN = "fullscreen"
        const val MODE_SMALLWINDOW = "smallwindow"
        private const val RESUME_DELAY_MS = 90_000L  // 避让后冷却90秒再恢复
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cameraId: String? = null
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var surfaceView: SurfaceView? = null
    private var overlayLayout: FrameLayout? = null
    private var isShowing = false
    private var currentMode = MODE_FULLSCREEN

    // 相机避让相关
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCameraBlocked = false  // 是否被相机类APP抢占
    private var shouldResumeAfterBlock = false  // 被抢占前是否在显示（用于恢复）

    /** 找后置摄像头（背面、非前置） */
    private fun findRearCamera(): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return cm.cameraIdList.firstOrNull { id ->
            val facing = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /** 打开摄像头并开始预览 */
    private fun openCamera() {
        try {
            if (cameraId == null) {
                cameraId = findRearCamera()
                if (cameraId == null) {
                    Log.w(TAG, "无后置摄像头")
                    return
                }
            }
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.openCamera(cameraId!!, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                    // 相机被抢（相机类APP打开）→ 自动避让隐藏
                    if (isShowing && !isCameraBlocked) {
                        Log.i(TAG, "相机被抢占，自动避让隐藏")
                        isCameraBlocked = true
                        shouldResumeAfterBlock = true
                        hide()
                        // 90秒后尝试恢复
                        mainHandler.postDelayed({
                            if (shouldResumeAfterBlock && !isCameraBlocked) {
                                Log.i(TAG, "避让结束，尝试恢复显示")
                                show(currentMode)
                            }
                            shouldResumeAfterBlock = false
                        }, RESUME_DELAY_MS)
                    }
                }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                    Log.e(TAG, "相机错误: $error")
                }
            }, null)
        } catch (t: Throwable) {
            Log.e(TAG, "打开相机失败", t)
        }
    }

    /** 开始预览 */
    private fun startPreview() {
        try {
            val camera = cameraDevice ?: return
            val sv = surfaceView ?: return
            val surface = sv.holder.surface

            val captureRequest = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
            captureRequest.addTarget(surface)
            captureRequest.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            camera.createCaptureSession(listOf(sv.holder.surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(captureRequest.build(), null, null)
                    }
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        Log.e(TAG, "会话配置失败")
                    }
                }, null)
        } catch (t: Throwable) {
            Log.e(TAG, "启动预览失败", t)
        }
    }

    /** 关闭摄像头 */
    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Throwable) {}
    }

    /** 显示悬浮窗
     * @param mode MODE_FULLSCREEN 全屏半透明 / MODE_SMALLWINDOW 角落小窗
     */
    fun show(mode: String = MODE_FULLSCREEN) {
        Log.i(TAG, "show() 被调用, mode=$mode, isShowing=$isShowing")
        if (isShowing) {
            // 已显示，切模式
            if (mode != currentMode) {
                hide()
                show(mode)
            }
            return
        }
        currentMode = mode
        try {
            // 状态栏高度
            val statusBarHeight = getStatusBarHeight()

            // 容器布局
            val container = FrameLayout(ctx)
            surfaceView = SurfaceView(ctx).apply {
                holder.setFormat(PixelFormat.TRANSLUCENT)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { openCamera() }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) { closeCamera() }
                })
            }
            container.addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // 窗口参数
            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE

                format = PixelFormat.TRANSLUCENT

                // 触摸穿透 + 不获取焦点
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                // 不高于状态栏：顶部偏移状态栏高度
                y = statusBarHeight
                gravity = Gravity.TOP or Gravity.START

                when (mode) {
                    MODE_FULLSCREEN -> {
                        width = WindowManager.LayoutParams.MATCH_PARENT
                        height = WindowManager.LayoutParams.MATCH_PARENT - statusBarHeight
                        // 全屏半透明：加一层半透明黑蒙版
                        container.background = android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.argb(128, 0, 0, 0))
                    }
                    MODE_SMALLWINDOW -> {
                        // 右下角小窗：约 1/3 屏宽，2/5 屏高
                        val dm = ctx.resources.displayMetrics
                        width = (dm.widthPixels * 0.33f).toInt()
                        height = (dm.heightPixels * 0.4f).toInt()
                        gravity = Gravity.BOTTOM or Gravity.END
                        y = 0 // 小窗贴底部，不偏移
                        // 小窗圆角
                        container.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.argb(200, 0, 0, 0))
                            cornerRadius = 24f * ctx.resources.displayMetrics.density
                        }
                    }
                }
            }

            Log.i(TAG, "准备 addView, type=${params.type}")
            wm.addView(container, params)
            overlayLayout = container
            isShowing = true
            Log.i(TAG, "✅ 悬浮窗显示成功: $mode")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 显示悬浮窗失败", t)
        }
    }

    /** 隐藏悬浮窗 */
    fun hide() {
        if (!isShowing) return
        try {
            overlayLayout?.let { wm.removeView(it) }
            overlayLayout = null
            surfaceView = null
            closeCamera()
            isShowing = false
            Log.i(TAG, "隐藏悬浮窗")
        } catch (_: Throwable) {}
    }

    fun isShowing() = isShowing

    /**
     * 解除相机阻塞状态（外部检测到用户切回非相机APP时调用）
     */
    fun unblockCamera() {
        isCameraBlocked = false
        Log.i(TAG, "解除相机阻塞")
    }

    /** 获取状态栏高度 */
    private fun getStatusBarHeight(): Int {
        val res = ctx.resources
        val resourceId = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) res.getDimensionPixelSize(resourceId) else 24
    }
}
