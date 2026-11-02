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
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager

/**
 * 行走模式悬浮窗。
 *
 * 由 Hook 端（SystemUI 进程）持有，展示方式完全由 [show] 的三个参数驱动：
 * 形态、不透明度、小窗停靠角。参数变化时只更新布局，切换形态才重建视图。
 *
 * 注意：这个类虽然编译进本 APK，但实际**只运行在 SystemUI 进程**。
 * 相机权限用的是系统身份（uid 1000）自己的权限，而本应用的 manifest
 * 里刻意没有声明 CAMERA —— 所以下面 openCamera 会关掉 Lint 的 MissingPermission，
 * 那是「本应用没声明」的误报，真正的权限判断就写在方法里。
 *
 * @param onFailure 画面出不来时的回调（无相机权限 / 相机没有画面）。
 *                  以前这些情况只写一行日志，用户看到的是「点预览没反应」，
 *                  再往下就是「这功能是假的」。
 * @param onStage   预览链路每走一步就回调一次（窗口挂载 / 相机打开 / 收到画面…）。
 *                  Hook 端把它转发回 App 显示在页面上——黑屏时不用再猜卡在哪。
 */
class RearCameraOverlay(
    private val ctx: Context,
    private val onFailure: ((String) -> Unit)? = null,
    private val onStage: ((String) -> Unit)? = null,
    /** 仅测试用：注入一个可记录的 WindowManager，验证窗口参数是否合法 */
    wmOverride: WindowManager? = null
) {

    companion object {
        private const val TAG = "SafeWalk-Cam"
        const val MODE_FULLSCREEN = "fullscreen"
        const val MODE_SMALLWINDOW = "smallwindow"

        const val CORNER_BR = "bottom_right"
        const val CORNER_BL = "bottom_left"
        const val CORNER_TR = "top_right"
        const val CORNER_TL = "top_left"

        /** 常见相机应用包名前缀，用于前台避让判断 */
        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.huawei.camera",
            "com.miui.camera",
            "com.xiaomi.camera",
            "com.oplus.camera",
            "com.vivo.camera",
            "com.samsung.android.camera",
            "com.oneplus.camera",
            "com.google.android.GoogleCamera"
        )
    }

    /** 当前生效的展示参数 */
    private data class Style(val mode: String, val opacity: Int, val corner: String)

    private val wm = wmOverride
        ?: (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    private val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    /** 预览用 Surface，必须显式 release，否则反复开关会耗尽 buffer */
    private var previewSurface: Surface? = null
    private var tv: TextureView? = null
    private var isShowing = false
    private var style = Style(MODE_FULLSCREEN, 50, CORNER_BR)

    /** 最近一帧画面的时间戳，0 表示本次显示还没出过画面 */
    private var lastFrameTs = 0L
    private var frameWatchdog: Runnable? = null
    private var failureReported = false
    /** 视图尺寸为 0 时的重试次数（Surface 回调常常早于布局） */
    private var layoutRetry = 0

    /** 统一走 SafeWalkLSPHook 的日志出口（logcat + SystemUI 私有目录） */
    private fun diag(msg: String) {
        try {
            SafeWalkLSPHook.diag("[cam] $msg")
        } catch (t: Throwable) {
            // 单元测试环境里没有 Xposed 的类（compileOnly），
            // 这里不能因为打日志把预览逻辑带崩，退回 logcat 就好
            Log.i(TAG, msg)
        }
    }

    /** 预览链路分步回执：窗口挂没挂上、相机开没开、有没有收到画面，一步一报 */
    private var firstFrameReported = false
    private fun stage(msg: String) {
        diag(msg)
        try { onStage?.invoke(msg) } catch (_: Throwable) {}
    }

    val showing: Boolean get() = isShowing

    /**
     * 显示悬浮窗。
     * @param mode 全屏 / 小窗
     * @param opacity 不透明度 10~100，越小越透；实际渲染上限 99%（见 apply）
     * @param corner 小窗停靠角（全屏模式忽略）
     */
    fun show(mode: String = MODE_FULLSCREEN, opacity: Int = 50, corner: String = CORNER_BR) {
        val target = Style(
            if (mode == MODE_SMALLWINDOW) MODE_SMALLWINDOW else MODE_FULLSCREEN,
            opacity.coerceIn(10, 100),
            corner
        )
        // 除了参数未变，还要确认视图真的还挂在 WindowManager 上；
        // 被系统回收（切用户/内存压力）后 parent 会变 null，此时必须重建
        if (isShowing && target == style && tv?.parent != null) {
            // 视图还在，但相机未必还在：Surface 被系统重建过、上次打开失败、
            // 或者相机被别的应用抢走，都会留下「窗口在、画面全黑」的状态。
            // 原实现直接 return，于是这个黑窗口再也不会重连。
            if (camera == null) openCamera()
            return
        }

        val modeChanged = isShowing && target.mode != style.mode
        // 视图被系统摘掉但对象还在：这是最容易被忽略的一种。
        // 直接 addView 回去，TextureView 的 SurfaceTexture 已经失效，
        // onSurfaceTextureAvailable 也不会再回调 —— 结果是窗口在、画面全黑。
        val viewDetached = tv != null && tv?.parent == null
        style = target
        isShowing = true
        failureReported = false
        layoutRetry = 0
        firstFrameReported = false

        if (modeChanged || viewDetached) {
            // 形态切换必须重建视图：尺寸、圆角、Surface 都绑在 View 上
            releaseView()
        }
        apply()
    }

    /** 隐藏悬浮窗 */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        cancelWatchdog()
        teardown()
        Log.i(TAG, "隐藏悬浮窗")
    }

    private fun releaseView() {
        closeCamera()
        tv?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        tv = null
        surfaceTexture = null
    }

    private fun apply() {
        if (tv == null) tv = buildTextureView()

        tv?.outlineProvider = roundOutline()
        tv?.invalidateOutline()
        // 透明度直接作用于视图本身（第一版实测可用的做法，不引入变量）。
        //
        // 上限刻意压在 0.99，永远不给 1.0：
        // 真机实测「全屏 + alpha=1.0」画面出不来，alpha<1 正常。
        // 全屏铺满且像素 alpha 全是 255 时，系统会把这个 layer 判成不透明，
        // 与窗口声明的 TRANSLUCENT 冲突，合成路径选错 → 黑屏；
        // alpha<1 时明确需要 GPU 混合，走的是正常路径。
        // 99% 与 100% 肉眼无差，但能稳定出画面。
        val renderAlpha = minOf(style.opacity / 100f, 0.99f)
        tv?.alpha = renderAlpha

        val lp = buildParams()
        try {
            if (tv?.parent == null) {
                wm.addView(tv, lp)
                stage("窗口已挂载: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha")
            } else {
                wm.updateViewLayout(tv, lp)
                stage("窗口已更新: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha")
            }
            armFrameWatchdog()
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 显示悬浮窗失败", t)
            stage("❌ 窗口挂载失败: ${t.message}")
            isShowing = false
            // 没挂上去的 View 必须丢掉：留着它，下一轮 show 会发现
            // 「tv 非空但 parent 为空」又去重建，每 2 秒抖一次，白折腾
            tv = null
            surfaceTexture = null
        }
    }

    private fun buildTextureView(): TextureView {
        return TextureView(ctx.applicationContext).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    // apply 作用域内 surfaceTexture 会解析到 TextureView 的只读属性，必须显式限定
                    this@RearCameraOverlay.surfaceTexture = st
                    stage("画面缓冲就绪 ${w}x${h}")
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    // 有帧进来就说明预览真的通了，记时间戳给看门狗
                    lastFrameTs = System.currentTimeMillis()
                    failureReported = false
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        stage("✅ 已收到画面")
                    }
                }
            }
            // 永远声明非不透明：这条路径实测能出画面，且与 0.99 的 alpha 自洽
            isOpaque = false
            isFocusable = false
            isClickable = false
            clipToOutline = true
        }
    }

    private fun roundOutline(): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (style.mode == MODE_SMALLWINDOW) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
            } else {
                outline.setRect(0, 0, view.width, view.height)
            }
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        // 窗口格式固定用 TRANSLUCENT，不随不透明度切换。
        // 曾试过「100% 时换 OPAQUE」，真机仍然黑屏（系统对不透明全屏悬浮窗
        // 另有一套拦截/合成逻辑），而 TRANSLUCENT + alpha<1 是实测唯一稳的组合。
        return if (style.mode == MODE_FULLSCREEN) {
            /*
             * 全屏用真实屏幕像素尺寸，而不是 MATCH_PARENT(-1)。
             *
             * 相机搬到 App 进程后，MATCH_PARENT 的 TYPE_APPLICATION_OVERLAY
             * 在部分 ROM 上不会把 TextureView 布局到非零尺寸 ——
             * 窗口挂上去了，但视图宽高是 0，openCamera 拿到 0×0 直接放弃，
             * 5 秒看门狗再把窗口收掉，表现就是「全屏黑屏、小窗正常」。
             * 用确定的像素尺寸，TextureView 一定能布局出正数尺寸。
             *
             * 尺寸取窗口度量的整屏（含状态栏/导航栏），不用 resources.displayMetrics：
             * 后者是「应用可用区域」，全屏悬浮窗会比屏幕小一圈，画面被拉伸、
             * 边缘留白，看起来像没铺满。
             */
            val (screenW, screenH) = screenSize()
            WindowManager.LayoutParams(
                screenW,
                screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        // TextureView 必须跑在硬件加速的窗口里。
                        // 从 Service 直接 addView 的窗口默认**不会**被标记为硬件加速
                        // （那条路径只看这个 flag），表现就是相机开得起来、
                        // 会话也配置成功，但一帧都上不了屏 —— 纯黑。
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        // 允许铺到状态栏/导航栏下面，否则「全屏」会被系统栏挤小一圈
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
        } else {
            val (vGrav, hGrav) = when (style.corner) {
                CORNER_BL -> Gravity.BOTTOM to Gravity.START
                CORNER_TR -> Gravity.TOP to Gravity.END
                CORNER_TL -> Gravity.TOP to Gravity.START
                else -> Gravity.BOTTOM to Gravity.END
            }
            WindowManager.LayoutParams(
                dp(150), dp(260),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        // 同上：TextureView 没有硬件加速就是一整块黑
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = vGrav or hGrav
                // 与第一版一致：四角统一 12dp 边距
                x = dp(12)
                y = dp(12)
            }
        }
    }

    private fun dp(v: Int): Int = Math.round(v * ctx.resources.displayMetrics.density)

    /**
     * 整屏像素尺寸。
     *
     * 优先用 WindowMetrics（API 30+），它给的是窗口系统认可的完整边界；
     * 低版本用 getRealMetrics（含系统栏）。两条路都拿不到正数时退回
     * displayMetrics —— 宁可小一圈，也不能给 LayoutParams 传 0，
     * 0 会让 TextureView 根本不布局，相机直接放弃。
     */
    private fun screenSize(): Pair<Int, Int> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    return dm.widthPixels to dm.heightPixels
                }
            }
        } catch (_: Throwable) {
        }
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 前台是否相机类应用。
     * 命中时 Hook 端会暂时收起悬浮窗，避免挡住取景框。
     */
    fun isCameraAppInForeground(): Boolean {
        return try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getInstance").invoke(null)
            val info = atmClass.getMethod("getFocusedRootTaskInfo").invoke(atm) ?: return false
            val topActivity = info.javaClass.getField("topActivity").get(info) ?: return false
            val pkg = topActivity.javaClass.getField("packageName").get(topActivity) as? String
                ?: return false

            if (CAMERA_PACKAGES.contains(pkg)) return true
            // 部分 ROM 用厂商自有相机包名，退化为包名关键字匹配
            pkg.contains("camera", ignoreCase = true) && !pkg.contains("safewalk", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun teardown() {
        closeCamera()
        tv?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        tv = null
        surfaceTexture = null
    }

    private fun closeCamera() {
        session?.let { try { it.close() } catch (_: Throwable) {} }
        session = null
        camera?.let { try { it.close() } catch (_: Throwable) {} }
        camera = null
        previewSurface?.let { try { it.release() } catch (_: Throwable) {} }
        previewSurface = null
        surfaceTexture = null
    }

    private fun hasCameraPermission(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun cancelWatchdog() {
        frameWatchdog?.let { mainHandler.removeCallbacks(it) }
        frameWatchdog = null
    }

    /**
     * 画面看门狗。
     *
     * 「窗口加进去了」不等于「能看到画面」：缺相机权限、相机被占用、Surface 失效，
     * 都会得到一个 50% 透明度的黑框。用户分不清「黑屏」和「没反应」，
     * 所以这里给 5 秒宽限：一帧都没有就收起窗口并明确报错。
     */
    private fun armFrameWatchdog() {
        cancelWatchdog()
        val start = System.currentTimeMillis()
        val r = Runnable {
            if (!isShowing) return@Runnable
            if (lastFrameTs >= start) return@Runnable
            // 报错前先把现场参数写进日志：黑屏到底是窗口、相机还是合成的问题，
            // 一次就能分辨（cam/session 都非空却没帧 = 合成侧；为空 = 相机没开起来）
            diag("❌ 5 秒无画面 | mode=${style.mode} opacity=${style.opacity} " +
                    "cam=${camera != null} session=${session != null} " +
                    "surface=${surfaceTexture != null} view=${tv?.width}x${tv?.height}")
            reportFailure(if (hasCameraPermission()) "相机没有返回画面" else "应用没有相机权限")
            hide()
        }
        frameWatchdog = r
        mainHandler.postDelayed(r, 5_000L)
    }

    private fun reportFailure(reason: String) {
        if (failureReported) return
        failureReported = true
        diag("❌ 预览失败: $reason")
        stage("❌ $reason（5 秒内没有收到画面）")
        try { onFailure?.invoke(reason) } catch (_: Throwable) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            // 已经有相机会话就不要重复开：onSurfaceTextureAvailable 在某些 ROM 上
            // 会因为一次布局回调触发两次，第二次会把 camera 引用覆盖掉，
            // 被覆盖的那个再也没人 close，摄像头指示灯就一直亮着
            if (camera != null) return

            // 权限检查必须内联，lint 的 MissingPermission 只认同方法内的显式判断
            if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                diag("❌ 无相机权限，跳过预览")
                // 相机开不了就不要再留一个黑框挂在屏幕上
                stage("❌ 应用没有相机权限")
                reportFailure("应用没有相机权限")
                mainHandler.post { hide() }
                return
            }
            val view = tv ?: return
            val st = view.surfaceTexture ?: return
            if (view.width <= 0 || view.height <= 0) {
                diag("❌ 视图尺寸为 0，跳过本次开相机")
                // Surface 回调比布局早的 ROM 上会先拿到 0×0。
                // 只 post 一次经常还是 0（post 跑在同一帧的下一轮消息里，
                // 布局可能还没完成），这里退避式重试几次，总时长控制在 0.6 秒内，
                // 远小于看门狗的 5 秒。
                if (layoutRetry < 3) {
                    layoutRetry++
                    view.postDelayed({ openCamera() }, 200L)
                }
                return
            }
            layoutRetry = 0
            st.setDefaultBufferSize(view.width, view.height)
            val surface = Surface(st)
            previewSurface = surface
            val id = backCameraId()
            if (id == null) {
                Log.e(TAG, "无后置摄像头")
                stage("❌ 找不到后置摄像头")
                // 提前 return 也要把刚建的 Surface 放掉，否则每调一次少一个 buffer
                try { surface.release() } catch (_: Throwable) {}
                previewSurface = null
                return
            }
            Log.i(TAG, "打开后置摄像头: $id")
            stage("正在打开相机 id=$id")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    // 打开是异步的：如果这期间用户已经收起悬浮窗，
                    // 直接持有相机会导致摄像头常亮（耗电 + 隐私指示灯不灭）
                    if (!isShowing || tv == null) {
                        try { d.close() } catch (_: Throwable) {}
                        diag("⚠️ 相机打开时视图已销毁，立即释放")
                        stage("⚠️ 相机打开时窗口已关闭，立即释放")
                        return
                    }
                    stage("相机已打开")
                    camera = d
                    try {
                        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                if (!isShowing) {
                                    try { cs.close() } catch (_: Throwable) {}
                                    return
                                }
                                session = cs
                                stage("预览流已启动")
                                try {
                                    cs.setRepeatingRequest(builder.build(), null, mainHandler)
                                } catch (_: Throwable) {}
                            }

                            override fun onConfigureFailed(cs: CameraCaptureSession) {
                                Log.e(TAG, "会话配置失败")
                                stage("❌ 相机会话配置失败")
                            }
                        }, mainHandler)
                    } catch (t: Throwable) {
                        Log.e(TAG, "启动预览失败", t)
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    try { d.close() } catch (_: Throwable) {}
                    camera = null
                    session = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    try { d.close() } catch (_: Throwable) {}
                    camera = null
                    session = null
                    Log.e(TAG, "相机错误: $error")
                    diag("❌ 相机错误: $error")
                }
            }, mainHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "打开相机失败", t)
            // openCamera 抛异常时 Surface 已经建好了，不释放就永远挂在那里
            try { previewSurface?.release() } catch (_: Throwable) {}
            previewSurface = null
        }
    }

    private fun backCameraId(): String? {
        try {
            for (id in cm.cameraIdList) {
                val cc = cm.getCameraCharacteristics(id)
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            return null
        } catch (_: Throwable) {
            return null
        }
    }
}
